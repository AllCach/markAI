package dev.markai.ai;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Repairs a repeated manual reply once, without changing the user's question or saved memory. */
public final class RepetitionGuardLlmProvider implements LlmProvider {
    private static final Pattern REPEAT_REQUEST = Pattern.compile(
        "(?:^| )(?:повтори|повторите|повторяй|повторить|скажи еще раз|скажи снова|repeat|say that again|say it again)(?: |$)");
    private static final String REPAIR_INSTRUCTION =
        "Предыдущий вариант повторил уже отправленный ответ. Дай новый ответ именно на последнее сообщение игрока, "
        + "учитывая историю. Не пересказывай отвергнутый ответ и не придумывай событий.";
    private final LlmProvider delegate;
    private final long timeoutNanos;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(8);
    private final AtomicBoolean closed = new AtomicBoolean();

    public RepetitionGuardLlmProvider(LlmProvider delegate, Duration timeout) {
        if (delegate == null || timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Invalid dialogue repair configuration");
        this.delegate = delegate;
        timeoutNanos = timeout.toNanos();
        scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "markai-dialogue-repair");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
    }

    @Override public CompletableFuture<OllamaClient.Reply> ask(String contextJson, String question,
                                                              List<OllamaClient.ChatMessage> history) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("AI provider closed"));
        JsonObject context;
        List<OllamaClient.ChatMessage> snapshot;
        try {
            if (contextJson == null || contextJson.length() > 8192)
                throw new IllegalArgumentException("Invalid context size");
            var value = OllamaClient.strictJson(contextJson);
            if (!value.isJsonObject()) throw new IllegalArgumentException("Context must be a JSON object");
            context = value.getAsJsonObject();
            OllamaClient.checkedText(question, 512, false, "playerMessage");
            if (history == null || history.size() > 24)
                throw new IllegalArgumentException("History is limited to 24 messages");
            int characters = 0;
            for (var previous : history) {
                if (previous == null || !("user".equals(previous.role()) || "assistant".equals(previous.role())))
                    throw new IllegalArgumentException("History role must be user or assistant");
                OllamaClient.checkedText(previous.content(), 1024, true, "history content");
                characters += previous.content().length();
            }
            if (characters > 12000) throw new IllegalArgumentException("Conversation context exceeds its character budget");
            snapshot = List.copyOf(history);
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
        if (!capacity.tryAcquire())
            return CompletableFuture.failedFuture(new IllegalStateException("AI dialogue queue is full"));
        var result = new CompletableFuture<OllamaClient.Reply>();
        var active = new AtomicReference<CompletableFuture<OllamaClient.Reply>>();
        var deadline = new AtomicReference<java.util.concurrent.Future<?>>();
        pending.add(result);
        result.whenComplete((reply, failure) -> {
            pending.remove(result);
            capacity.release();
            var timer = deadline.get();
            if (timer != null) timer.cancel(false);
            if (failure != null) {
                var request = active.get();
                if (request != null) request.cancel(true);
            }
        });
        try {
            var timer = scheduler.schedule(() -> result.completeExceptionally(
                new TimeoutException("AI dialogue deadline exceeded")), timeoutNanos, TimeUnit.NANOSECONDS);
            deadline.set(timer);
            if (result.isDone()) timer.cancel(false);
            boolean ambient = context.has("conversation_mode")
                && context.get("conversation_mode").isJsonPrimitive()
                && "ambient".equals(context.get("conversation_mode").getAsString());
            attempt(contextJson, question, snapshot, ambient || explicitlyRequestsRepetition(question), false, result, active);
        } catch (RuntimeException error) { result.completeExceptionally(error); }
        if (closed.get()) result.cancel(true);
        return result;
    }

    private void attempt(String context, String question, List<OllamaClient.ChatMessage> history,
                         boolean allowRepetition, boolean repairing, CompletableFuture<OllamaClient.Reply> result,
                         AtomicReference<CompletableFuture<OllamaClient.Reply>> active) {
        if (result.isDone() || closed.get()) { result.cancel(true); return; }
        CompletableFuture<OllamaClient.Reply> request;
        try { request = Objects.requireNonNull(delegate.ask(context, question, history), "Missing provider request"); }
        catch (RuntimeException error) { result.completeExceptionally(error); return; }
        active.set(request);
        if (result.isDone()) { request.cancel(true); return; }
        request.whenComplete((reply, failure) -> {
            if (result.isDone()) return;
            if (failure != null) {
                while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                result.completeExceptionally(failure);
                return;
            }
            if (reply == null) { result.completeExceptionally(new IllegalArgumentException("Missing AI reply")); return; }
            // Coordinates and other engine-grounded answers legitimately stay identical while facts stay unchanged.
            String grounded = GroundedFacts.apply(new OllamaClient.Reply(reply.action(), ""), context, question).say();
            if (allowRepetition || (!grounded.isEmpty() && grounded.equals(reply.say())) || !repeatsHistory(reply.say(), history)) {
                result.complete(reply); return;
            }
            if (repairing) { result.completeExceptionally(new RepeatedReplyException()); return; }
            try {
                JsonObject repaired = OllamaClient.strictJson(context).getAsJsonObject();
                var correction = new JsonObject();
                correction.addProperty("rejected_reply", reply.say());
                correction.addProperty("instruction", REPAIR_INSTRUCTION);
                repaired.add("dialogue_repair", correction);
                String repairedContext = repaired.toString();
                if (repairedContext.length() > 8192) throw new RepeatedReplyException();
                attempt(repairedContext, question, history, false, true, result, active);
            } catch (RuntimeException error) { result.completeExceptionally(error); }
        });
    }

    static boolean repeatsHistory(String candidate, List<OllamaClient.ChatMessage> history) {
        if (candidate == null) return false;
        String normalized = normalize(candidate);
        boolean substantialReply = normalized.length() >= 32 && normalized.split(" ").length >= 6;
        Set<String> candidateSentences = java.util.Arrays.stream(candidate.split("[.!?…]+"))
            .map(RepetitionGuardLlmProvider::normalize)
            .filter(sentence -> sentence.length() >= 32 && sentence.split(" ").length >= 5)
            .collect(java.util.stream.Collectors.toSet());
        if (!substantialReply && candidateSentences.isEmpty()) return false;
        int examined = 0;
        for (int i = history.size() - 1; i >= 0 && examined < 6; i--) {
            var previous = history.get(i);
            if (!previous.role().equals("assistant")) continue;
            examined++;
            String text = previous.content();
            if (text.stripLeading().startsWith("{")) {
                try {
                    var value = OllamaClient.strictJson(text);
                    if (value.isJsonObject() && value.getAsJsonObject().has("say")
                            && value.getAsJsonObject().get("say").isJsonPrimitive())
                        text = value.getAsJsonObject().get("say").getAsString();
                } catch (RuntimeException ignored) { /* Old plain-text records remain usable. */ }
            }
            if (substantialReply && normalized.equals(normalize(text))) return true;
            if (java.util.Arrays.stream(text.split("[.!?…]+"))
                    .map(RepetitionGuardLlmProvider::normalize).anyMatch(candidateSentences::contains)) return true;
        }
        return false;
    }

    private static boolean explicitlyRequestsRepetition(String question) {
        String text = normalize(question);
        if (text.matches(".*(?:не повтор|не надо повтор|do not repeat|dont repeat|don t repeat).*")) return false;
        return REPEAT_REQUEST.matcher(text).find();
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е')
            .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.forEach(future -> future.cancel(true));
        scheduler.shutdownNow();
        delegate.close();
    }

    /** The server reports a failed request instead of delivering a known repeated answer. */
    public static final class RepeatedReplyException extends IllegalStateException {
        public RepeatedReplyException() { super("AI repeated a recent reply after one repair attempt"); }
    }
}
