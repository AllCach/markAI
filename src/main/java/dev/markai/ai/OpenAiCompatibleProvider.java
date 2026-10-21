package dev.markai.ai;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Nonstreaming Chat Completions transport for explicitly configured local or remote services. */
public final class OpenAiCompatibleProvider implements LlmProvider {
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final double temperature;
    private final int maxOutputTokens;
    private final boolean structuredOutputs;
    private final ThreadPoolExecutor executor;
    private final HttpClient http;
    private final Semaphore capacity = new Semaphore(8);
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OpenAiCompatibleProvider(String baseUrl, String model, String apiKey, Duration timeout,
                                   double temperature, int maxOutputTokens,
                                   boolean remoteAIEnabled, boolean structuredOutputs) {
        endpoint = endpoint(baseUrl, remoteAIEnabled);
        this.model = OllamaClient.checkedText(model, 128, false, "model");
        this.apiKey = apiKey == null ? "" : OllamaClient.checkedText(apiKey, 4096, true, "API key");
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(5)) > 0
                || !Double.isFinite(temperature) || temperature < 0 || temperature > 2
                || maxOutputTokens < 64 || maxOutputTokens > 4096)
            throw new IllegalArgumentException("Invalid provider limits");
        this.timeout = timeout;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.structuredOutputs = structuredOutputs;
        executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), task -> {
            Thread thread = new Thread(task, "markai-compatible-http"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        http = HttpClient.newBuilder().executor(executor).connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
            }).build();
    }

    @Override public CompletableFuture<OllamaClient.Reply> ask(String contextJson, String playerMessage,
                                                             List<OllamaClient.ChatMessage> history) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("AI provider closed"));
        if (!capacity.tryAcquire()) return CompletableFuture.failedFuture(new IllegalStateException("AI request queue is full"));
        final CompletableFuture<HttpResponse<byte[]>> wire;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("messages", OllamaClient.dialogueMessages(contextJson, playerMessage, history));
            body.addProperty("stream", false);
            body.addProperty("temperature", temperature);
            body.addProperty("max_tokens", maxOutputTokens);
            JsonObject responseFormat = new JsonObject();
            if (structuredOutputs) {
                responseFormat.addProperty("type", "json_schema");
                JsonObject schema = new JsonObject();
                schema.addProperty("name", "companion_reply");
                schema.addProperty("strict", true);
                schema.add("schema", OllamaClient.replySchema(OllamaClient.requestedAction(playerMessage)));
                responseFormat.add("json_schema", schema);
            } else responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json");
            if (!apiKey.isEmpty()) request.header("Authorization", "Bearer " + apiKey);
            wire = http.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                info -> new OllamaClient.BoundedBodySubscriber());
        } catch (RuntimeException failure) {
            capacity.release();
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<OllamaClient.Reply> result = wire.thenApply(response -> {
            if (response.statusCode() != 200) throw new ProviderHttpException(response.statusCode());
            var envelope = OllamaClient.strictJson(OllamaClient.decodeUtf8(response.body()));
            if (!envelope.isJsonObject()) throw new IllegalArgumentException("Invalid AI response envelope");
            var choices = envelope.getAsJsonObject().get("choices");
            if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().size() != 1
                    || !choices.getAsJsonArray().get(0).isJsonObject())
                throw new IllegalArgumentException("Expected one AI response choice");
            JsonObject choice = choices.getAsJsonArray().get(0).getAsJsonObject();
            if (!"stop".equals(OllamaClient.stringField(choice, "finish_reason")))
                throw new IllegalArgumentException("AI response is incomplete or refused");
            var message = choice.get("message");
            if (message == null || !message.isJsonObject()) throw new IllegalArgumentException("Missing AI response message");
            var fields = message.getAsJsonObject();
            if (!"assistant".equals(OllamaClient.stringField(fields, "role")))
                throw new IllegalArgumentException("Invalid AI response role");
            if (fields.has("refusal") && !fields.get("refusal").isJsonNull())
                throw new IllegalArgumentException("AI provider refused the request");
            return GroundedFacts.apply(OllamaClient.parseReply(OllamaClient.stringField(fields, "content")), contextJson, playerMessage);
        }).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        pending.add(result);
        result.whenComplete((reply, failure) -> {
            pending.remove(result); capacity.release();
            if (failure != null) wire.cancel(true);
        });
        if (closed.get()) result.cancel(true);
        return result;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.forEach(future -> future.cancel(true));
        http.shutdownNow(); executor.shutdownNow();
    }

    static URI endpoint(String baseUrl, boolean remoteAIEnabled) {
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException("Invalid AI endpoint");
            if (host.equalsIgnoreCase("localhost")) host = "127.0.0.1";
            boolean local = host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("AI endpoint must use HTTP or HTTPS");
            if (!local && (!remoteAIEnabled || !"https".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException("Remote AI requires explicit enablement and HTTPS");
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.isEmpty()) path = "/v1";
            if (!path.endsWith("/chat/completions")) path += "/chat/completions";
            return new URI(uri.getScheme(), null, host, uri.getPort(), path, null, null);
        } catch (java.net.URISyntaxException | NullPointerException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid AI endpoint");
        }
    }

    /** Contains only the status code; response bodies may contain echoed credentials or private text. */
    static final class ProviderHttpException extends IllegalArgumentException {
        final int status;
        ProviderHttpException(int status) { super("AI provider HTTP status " + status); this.status = status; }
    }
}
