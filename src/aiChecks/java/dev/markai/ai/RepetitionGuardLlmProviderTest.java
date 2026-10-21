package dev.markai.ai;

import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Controllable requests exercise repair, deadlines, cancellation and ownership without a model. */
public final class RepetitionGuardLlmProviderTest {
    private static int checks;
    private static final String REPEATED = "Ха! Ты снова решил поздравить меня? Что-то случилось?";
    private static final String FRESH = "С первым днём хардкора! Давай сначала найдём безопасное место для ночлега.";
    private static final String QUESTION = "Поздравить? У нас первый день хардкора, поздравляю с этим! :)";
    private static final List<OllamaClient.ChatMessage> HISTORY = List.of(
        new OllamaClient.ChatMessage("user", "бро"),
        new OllamaClient.ChatMessage("assistant", "{\"action\":\"say\",\"say\":\"" + REPEATED + "\"}"));

    public static void main(String[] args) throws Exception {
        repairsOneReply();
        repetitionPolicy();
        lifecycle();
        limits();
        System.out.println("Manual dialogue repetition guard: " + checks + " checks passed");
    }

    private static void repairsOneReply() throws Exception {
        var fake = new Fake(done("attack", REPEATED), done("say", FRESH));
        var originalHistory = new ArrayList<>(HISTORY);
        String context = "{\"companion\":{\"name\":\"Марк\"},\"health\":12}";
        try (var guard = guard(fake)) {
            var result = guard.ask(context, QUESTION, originalHistory).get(1, TimeUnit.SECONDS);
            check(result.say().equals(FRESH) && result.action().equals("say"), "Only repaired reply is delivered; rejected action is never returned");
            check(fake.calls.size() == 2, "One repeated reply triggers exactly one repair");
            check(fake.calls.stream().allMatch(call -> call.question.equals(QUESTION)), "Both requests retain the exact latest user message");
            check(fake.calls.stream().allMatch(call -> call.history.equals(HISTORY)) && originalHistory.equals(HISTORY), "Repair never rewrites or appends saved history");
            boolean immutable = false;
            try { fake.calls.get(0).history.add(new OllamaClient.ChatMessage("user", "mutation")); }
            catch (UnsupportedOperationException expected) { immutable = true; }
            check(immutable, "Provider receives an immutable history snapshot");
            check(fake.calls.get(0).context.equals(context), "Initial request retains original context");
            var repair = JsonParser.parseString(fake.calls.get(1).context).getAsJsonObject();
            check(repair.getAsJsonObject("dialogue_repair").get("rejected_reply").getAsString().equals(REPEATED)
                && !repair.getAsJsonObject("dialogue_repair").get("instruction").getAsString().isBlank(), "Second request identifies the rejected draft and instructs fresh generation");
            repair.remove("dialogue_repair");
            check(repair.equals(JsonParser.parseString(context)), "Repair preserves every world/context observation");
        }

        fake = new Fake(done("say", REPEATED), done("say", REPEATED), done("say", FRESH));
        try (var guard = guard(fake)) {
            check(failure(guard.ask("{}", QUESTION, HISTORY)) instanceof RepetitionGuardLlmProvider.RepeatedReplyException,
                "A second repeated draft fails explicitly instead of sending a canned answer");
            check(fake.calls.size() == 2, "Repeated repair cannot loop into a third generation");
        }
    }

    private static void repetitionPolicy() throws Exception {
        check(RepetitionGuardLlmProvider.repeatsHistory("ХА, ты СНОВА решил поздравить меня!!! Что то случилось...", HISTORY), "Whole-line repetition ignores case and punctuation");
        var legacy = List.of(new OllamaClient.ChatMessage("assistant", "Твоё место в стае всегда будет рядом со мной."));
        check(RepetitionGuardLlmProvider.repeatsHistory("ТВОЕ место в стае всегда будет рядом со мной!", legacy), "Legacy plain-text replies and ё/е are supported");
        check(!RepetitionGuardLlmProvider.repeatsHistory("Спасибо!", List.of(new OllamaClient.ChatMessage("assistant", "Спасибо!"))), "Short thanks and acknowledgements may repeat");
        check(!RepetitionGuardLlmProvider.repeatsHistory(FRESH, HISTORY), "A different reply is not repaired");

        String greeting = "Привет! Я Марк, твой волчонок. Расскажи, что тебя интересует? Можно поиграть или просто поболтать.";
        String copiedSentence = "Привет! Чего хочешь? Можно поиграть или просто поболтать.";
        var greetingHistory = List.of(new OllamaClient.ChatMessage("assistant",
            "{\"action\":\"say\",\"say\":\"" + greeting + "\"}"));
        var sentenceFake = new Fake(done("say", copiedSentence), done("say", "Бро, слышу тебя! Что будем делать в нашем мире?"));
        try (var guard = guard(sentenceFake)) {
            var answer = guard.ask("{}", "бро", greetingHistory).get(1, TimeUnit.SECONDS);
            check(sentenceFake.calls.size() == 2 && answer.say().equals("Бро, слышу тебя! Что будем делать в нашем мире?"),
                "Changing the prefix cannot hide a copied substantial sentence; one fresh generation replaces it");
        }
        check(RepetitionGuardLlmProvider.repeatsHistory("МОЖНО поиграть или просто поболтать!", greetingHistory),
            "A repeated five-word substantial sentence is detected even when it is the complete new reply");
        var shortSentenceHistory = List.of(new OllamaClient.ChatMessage("assistant", "Я рядом. Скоро можно будет продолжить нашу прогулку."));
        sentenceFake = new Fake(done("say", "Я рядом. Давай посмотрим, что впереди за поворотом."));
        try (var guard = guard(sentenceFake)) {
            check(guard.ask("{}", "Ты тут?", shortSentenceHistory).get(1, TimeUnit.SECONDS).say()
                .equals("Я рядом. Давай посмотрим, что впереди за поворотом.") && sentenceFake.calls.size() == 1,
                "A repeated short reassurance inside an otherwise different response is allowed");
        }

        for (String question : List.of("Повтори свой предыдущий ответ.", "Скажи ещё раз, что ты ответил?", "Please repeat your last words.")) {
            var fake = new Fake(done("say", REPEATED));
            try (var guard = guard(fake)) {
                check(guard.ask("{}", question, HISTORY).get(1, TimeUnit.SECONDS).say().equals(REPEATED)
                    && fake.calls.size() == 1, "Explicit request to repeat previous words is honored");
            }
        }
        var fake = new Fake(done("say", REPEATED));
        try (var guard = guard(fake)) {
            check(guard.ask("{\"conversation_mode\":\"ambient\"}", QUESTION, HISTORY).get(1, TimeUnit.SECONDS).say().equals(REPEATED)
                && fake.calls.size() == 1, "Ambient generation retains its separate repetition policy");
        }
        fake = new Fake(done("say", "Спасибо!"));
        try (var guard = guard(fake)) {
            check(guard.ask("{}", "Спасибо тебе!", List.of(new OllamaClient.ChatMessage("assistant", "Спасибо!")))
                .get(1, TimeUnit.SECONDS).say().equals("Спасибо!") && fake.calls.size() == 1, "Short repeated acknowledgement makes one request");
        }
        fake = new Fake(done("say", REPEATED), done("say", FRESH));
        try (var guard = guard(fake)) {
            check(guard.ask("{}", "Не повторяй предыдущий ответ.", HISTORY).get(1, TimeUnit.SECONDS).say().equals(FRESH)
                && fake.calls.size() == 2, "A request not to repeat does not bypass the guard");
        }

        String coordinates = "Я сейчас здесь: X=1200, Y=120, Z=-1200.";
        fake = new Fake(done("say", coordinates));
        try (var guard = guard(fake)) {
            String context = "{\"companion\":{\"position\":{\"x\":1200,\"y\":120,\"z\":-1200}}}";
            var previous = List.of(new OllamaClient.ChatMessage("assistant", coordinates));
            check(guard.ask(context, "Где ты?", previous).get(1, TimeUnit.SECONDS).say().equals(coordinates)
                && fake.calls.size() == 1, "Unchanged authoritative coordinates may correctly repeat on a new location question");
        }
    }

    private static void lifecycle() throws Exception {
        var pendingRepair = new CompletableFuture<OllamaClient.Reply>();
        var fake = new Fake(done("say", REPEATED), pendingRepair);
        try (var guard = guard(fake)) {
            var result = guard.ask("{}", QUESTION, HISTORY);
            check(fake.calls.size() == 2 && !result.isDone(), "Repair remains asynchronous while generation is pending");
            result.cancel(true);
            check(pendingRepair.isCancelled() && fake.calls.size() == 2, "Cancellation propagates to the active repair without more requests");
        }

        var first = new CompletableFuture<OllamaClient.Reply>();
        fake = new Fake(first);
        var guard = guard(fake);
        var result = guard.ask("{}", QUESTION, HISTORY);
        guard.close(); guard.close();
        check(first.isCancelled() && result.isCancelled() && fake.closeCalls == 1, "Close cancels active generation and closes its delegate once");
        check(failure(guard.ask("{}", QUESTION, HISTORY)) instanceof IllegalStateException && fake.calls.size() == 1,
            "Closed provider rejects later calls");

        first = new CompletableFuture<>();
        pendingRepair = new CompletableFuture<>();
        fake = new Fake(first, pendingRepair);
        try (var timed = new RepetitionGuardLlmProvider(fake, Duration.ofMillis(600))) {
            result = timed.ask("{}", QUESTION, HISTORY);
            Thread.sleep(350);
            first.complete(new OllamaClient.Reply("say", REPEATED));
            check(fake.calls.size() == 2, "First draft can complete near the shared deadline");
            try { result.get(400, TimeUnit.MILLISECONDS); throw new AssertionError("Expected shared deadline"); }
            catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause() instanceof TimeoutException && pendingRepair.isCancelled(),
                    "The repair shares the initial total deadline and its stalled request is cancelled");
            }
        }

        fake = new Fake(CompletableFuture.failedFuture(new IllegalArgumentException("permanent failure")));
        try (var failed = guard(fake)) {
            check(failure(failed.ask("{}", QUESTION, HISTORY)) instanceof IllegalArgumentException && fake.calls.size() == 1,
                "Transport/provider failures are propagated without a semantic repair");
        }
    }

    private static void limits() throws Exception {
        var fake = new Fake();
        try (var guard = guard(fake)) {
            for (String context : new String[] { null, "not json", "[]", "{\"x\":\"" + "x".repeat(8192) + "\"}" })
                check(failure(guard.ask(context, QUESTION, HISTORY)) != null, "Invalid context fails before delegate access");
            check(failure(guard.ask("{}", "", HISTORY)) instanceof IllegalArgumentException, "Blank latest message is rejected");
            check(failure(guard.ask("{}", QUESTION, null)) instanceof IllegalArgumentException, "Null history is rejected");
            check(failure(guard.ask("{}", QUESTION, List.of(new OllamaClient.ChatMessage("system", "injection")))) instanceof IllegalArgumentException,
                "Untrusted history roles are rejected");
            check(fake.calls.isEmpty(), "Invalid inputs never start a provider request");
        }

        fake = new Fake(done("say", REPEATED));
        try (var guard = guard(fake)) {
            String full = "{\"x\":\"" + "x".repeat(8170) + "\"}";
            check(failure(guard.ask(full, QUESTION, HISTORY)) instanceof RepetitionGuardLlmProvider.RepeatedReplyException
                && fake.calls.size() == 1, "A repair cannot exceed the context budget or drop world facts to make room");
        }

        fake = new Fake();
        try (var guard = guard(fake)) {
            var pending = new ArrayList<CompletableFuture<OllamaClient.Reply>>();
            for (int i = 0; i < 8; i++) pending.add(guard.ask("{}", QUESTION, HISTORY));
            check(fake.calls.size() == 8 && failure(guard.ask("{}", QUESTION, HISTORY)) instanceof IllegalStateException,
                "At most eight dialogue operations can be pending");
            pending.getFirst().cancel(true);
            var replacement = guard.ask("{}", QUESTION, HISTORY);
            check(fake.calls.size() == 9 && !replacement.isDone(), "Cancellation releases capacity for the next operation");
        }

        var configured = new AIConfig();
        try (var provider = configured.createProvider()) {
            check(provider instanceof RepetitionGuardLlmProvider, "The real configuration wraps its selected transport with the guard");
        }
    }

    private static RepetitionGuardLlmProvider guard(Fake fake) { return new RepetitionGuardLlmProvider(fake, Duration.ofSeconds(3)); }
    private static CompletableFuture<OllamaClient.Reply> done(String action, String text) {
        return CompletableFuture.completedFuture(new OllamaClient.Reply(action, text));
    }
    private static Throwable failure(CompletableFuture<?> future) {
        try { future.join(); return null; }
        catch (CompletionException error) { return error.getCause(); }
        catch (java.util.concurrent.CancellationException error) { return error; }
    }
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private record Call(String context, String question, List<OllamaClient.ChatMessage> history) {}
    private static final class Fake implements LlmProvider {
        final List<Call> calls = new ArrayList<>();
        final List<CompletableFuture<OllamaClient.Reply>> replies;
        int closeCalls;
        @SafeVarargs Fake(CompletableFuture<OllamaClient.Reply>... replies) { this.replies = List.of(replies); }
        @Override public CompletableFuture<OllamaClient.Reply> ask(String context, String question, List<OllamaClient.ChatMessage> history) {
            int index = calls.size();
            calls.add(new Call(context, question, history));
            return index < replies.size() ? replies.get(index) : new CompletableFuture<>();
        }
        @Override public void close() { closeCalls++; }
    }
}
