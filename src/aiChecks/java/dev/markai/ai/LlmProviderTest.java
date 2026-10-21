package dev.markai.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real loopback HTTP exercises both provider contracts without a model or Internet access. */
public final class LlmProviderTest {
    private static int checks;
    private static final String VALID = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"action\\\":\\\"follow\\\",\\\"say\\\":\\\"Иду к тебе.\\\"}\"}}]}";
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        try (var server = new Fake()) {
            try (var client = server.provider("", Duration.ofSeconds(2))) {
                var reply = client.ask("{\"companion\":{\"name\":\"Марк\"}}", "Марк, сюда!", List.of()).get(3, TimeUnit.SECONDS);
                check(reply.action().equals("follow"), "OpenAI-compatible response is parsed");
                check(server.path.equals("/v1/chat/completions"), "Root URL resolves to chat completions");
                check(server.authorization == null, "Unauthenticated local services work");
                var body = JsonParser.parseString(server.request).getAsJsonObject();
                check(body.get("model").getAsString().equals("local-model") && !body.get("stream").getAsBoolean(), "Model and nonstreaming mode configured");
                check(body.get("max_tokens").getAsInt() == 128 && body.get("temperature").getAsDouble() == 0.25, "Generation limits configured");
                var action = body.getAsJsonObject("response_format").getAsJsonObject("json_schema")
                    .getAsJsonObject("schema").getAsJsonObject("properties").getAsJsonObject("action").getAsJsonArray("enum");
                check(action.size() == 1 && action.get(0).getAsString().equals("follow"), "Schema restricts the requested action");
                String prompt = body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                check(prompt.contains("talking wolf") && prompt.contains("Марк"), "Talking-wolf persona and actual identity reach provider");
            }
            try (var client = server.provider("private-test-token", Duration.ofSeconds(2))) {
                client.ask("{}", "Сюда!", List.of()).get(3, TimeUnit.SECONDS);
                check(server.authorization.equals("Bearer private-test-token") && !server.request.contains("private-test-token"), "Bearer secret is sent only as a header");
            }
            for (String invalid : List.of("{}", "{\"choices\":[]}", VALID.replace("\"stop\"", "\"length\""),
                    VALID.replace("assistant", "user"), VALID.replace("Иду к тебе.", ""),
                    VALID.replace("follow", "execute"), "x".repeat(65537))) {
                server.body = invalid;
                try (var client = server.provider("", Duration.ofSeconds(2))) {
                    check(fails(client.ask("{}", "Сюда!", List.of())), "Malformed/incomplete/oversized output rejected");
                }
            }
            server.body = VALID;
            server.firstStatus = 429; server.status = 200;
            int before = server.requests.get();
            try (var provider = new RetryingLlmProvider(server.provider("", Duration.ofSeconds(2)), 1)) {
                provider.ask("{}", "Сюда!", List.of()).get(4, TimeUnit.SECONDS);
                check(server.requests.get() == before + 2, "Rate-limited request retries once without blocking caller");
            }
            server.firstStatus = 0; server.status = 400;
            before = server.requests.get();
            try (var provider = new RetryingLlmProvider(server.provider("private-test-token", Duration.ofSeconds(2)), 2)) {
                Throwable failure = failure(provider.ask("{}", "Сюда!", List.of()));
                check(server.requests.get() == before + 1, "Permanent HTTP failures are not retried");
                check(!failure.toString().contains("private-test-token") && !failure.toString().contains("PRIVATE_BODY"), "HTTP failures never expose secret headers or response bodies");
            }
            server.status = 503;
            before = server.requests.get();
            try (var provider = new RetryingLlmProvider(server.provider("", Duration.ofSeconds(2)), 3)) {
                var pending = provider.ask("{}", "Сюда!", List.of());
                waitRequests(server, before + 1);
                pending.cancel(true);
                Thread.sleep(450);
                check(server.requests.get() == before + 1, "Cancellation suppresses a scheduled retry");
            }
            server.status = 200; server.stall = true;
            try (var client = server.provider("", Duration.ofMillis(200))) {
                long start = System.nanoTime();
                var future = client.ask("{}", "Сюда!", List.of());
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 150, "HTTP ask returns immediately");
                check(fails(future) && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000, "Deadline includes a stalled response body");
            }
            var client = server.provider("", Duration.ofSeconds(2));
            var pending = client.ask("{}", "Сюда!", List.of());
            client.close();
            check(fails(pending) && fails(client.ask("{}", "Сюда!", List.of())), "Close cancels requests and rejects later calls");
        }
        configChecks();
        System.out.println("LLM providers/config: " + checks + " checks passed");
    }

    private static void configChecks() throws Exception {
        for (String url : List.of("https://api.example.test/v1", "http://example.test/v1", "file:///tmp", "http://user:secret@127.0.0.1:80")) {
            boolean rejected = false;
            try { OpenAiCompatibleProvider.endpoint(url, false); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Remote/unsafe endpoints disabled by default");
        }
        check(OpenAiCompatibleProvider.endpoint("https://api.example.test/v1", true).toString().equals("https://api.example.test/v1/chat/completions"), "Explicit HTTPS remote endpoint accepted without connecting");
        check(OpenAiCompatibleProvider.endpoint("http://localhost:12345/v1/", false).getHost().equals("127.0.0.1"), "Loopback hostname normalized without DNS");
        var config = new AIConfig(); config.provider = "openai-compatible";
        config.baseUrl = "http://127.0.0.1:1/v1"; config.model = "local-model";
        config.authentication = "bearer"; config.apiKeyEnvironment = "MARKAI_TEST_KEY_THAT_IS_NOT_SET_1FC631";
        boolean missing = false;
        try (var ignored = config.createProvider()) {} catch (IllegalArgumentException expected) { missing = true; }
        check(missing, "Missing environment API key fails before an HTTP request");
        var directory = Files.createTempDirectory("markai-config-check-");
        try {
            var path = directory.resolve("markai.json");
            Files.writeString(path, "{\"ollamaModel\":\"qwen3:4b\",\"timeoutSeconds\":60,\"requestCooldownSeconds\":5}");
            var legacy = AIConfig.load(path);
            check(legacy.effectiveModel().equals("qwen3:4b") && legacy.provider.equals("ollama"), "Legacy local Ollama config migrates without losing selected model");
            check(legacy.combatRadius == 32, "Legacy config receives the 32-block combat-radius default");
            Files.writeString(path, "{\"provider\":\"ollama\",\"combatRadius\":40}");
            check(AIConfig.load(path).combatRadius == 40, "Combat radius is loaded from config");
            Files.writeString(path, "{\"provider\":\"ollama\",\"combatRadius\":7}");
            check(AIConfig.load(path).recoveredFromInvalid, "Out-of-range combat radius is rejected and safely recovered");
            String original = "{broken private config";
            Files.writeString(path, original);
            var recovered = AIConfig.load(path);
            check(recovered.recoveredFromInvalid && recovered.provider.equals("ollama") && !recovered.remoteAIEnabled, "Malformed config recovers to safe local defaults");
            try (var files = Files.list(directory)) {
                var backups = files.filter(p -> p.getFileName().toString().contains(".invalid-")).toList();
                check(backups.size() == 2 && backups.stream().anyMatch(p -> {
                    try { return Files.readString(p).equals(original); } catch (Exception ignored) { return false; }
                }), "Malformed original is preserved verbatim in a backup");
            }
            check(!AIConfig.load(path).recoveredFromInvalid, "Recovered config is valid on the next load");
        } finally {
            try (var files = Files.list(directory)) { for (var file : files.toList()) Files.delete(file); }
            Files.delete(directory);
        }
    }

    private static void waitRequests(Fake server, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (server.requests.get() < count && System.nanoTime() < deadline) Thread.sleep(5);
        check(server.requests.get() >= count, "Fake endpoint received request before cancellation");
    }
    private static boolean fails(CompletableFuture<?> future) { return failure(future) != null; }
    private static Throwable failure(CompletableFuture<?> future) {
        try { future.get(3, TimeUnit.SECONDS); return null; }
        catch (Exception expected) { return expected; }
    }

    private static final class Fake implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(4);
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        volatile String request, path, authorization;
        volatile String body = VALID;
        volatile int status = 200, firstStatus;
        volatile boolean stall;
        Fake() throws Exception {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                path = exchange.getRequestURI().getPath(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
                int responseStatus = firstStatus == 0 ? status : firstStatus; firstStatus = 0;
                byte[] content = (responseStatus == 200 ? body : "PRIVATE_BODY private-test-token").getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(responseStatus, stall ? 655 : content.length);
                    if (stall) { exchange.getResponseBody().write(32); exchange.getResponseBody().flush(); release.await(3, TimeUnit.SECONDS); }
                    else exchange.getResponseBody().write(content);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
        }
        OpenAiCompatibleProvider provider(String key, Duration timeout) {
            return new OpenAiCompatibleProvider("http://127.0.0.1:" + server.getAddress().getPort(), "local-model", key, timeout, 0.25, 128, false, true);
        }
        @Override public void close() { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
