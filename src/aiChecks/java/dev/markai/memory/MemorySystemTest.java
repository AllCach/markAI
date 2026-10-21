package dev.markai.memory;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.markai.ai.OllamaClient;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Provenance, migration, extraction and deletion regressions without a language model. */
public final class MemorySystemTest {
    private static final Gson JSON = new Gson();
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        UUID companion = UUID.randomUUID();
        var memory = new CompanionMemory(); memory.bind(companion);
        memory.rememberStatementAsync("Хочу построить дом из березы с синей крышей.", 100).get(2, TimeUnit.SECONDS);
        memory.flushPending();
        var plan = memory.inspect().getFirst();
        check(plan.id() != null && plan.companionId().equals(companion) && plan.gameTime() == 100,
            "Extracted memories retain stable identity and event time");
        check(plan.sourceType() == MemoryEntry.SourceType.PLAYER_STATEMENT
            && plan.reliability() == MemoryEntry.Reliability.REPORTED && plan.category().equals("plan"),
            "Plan is a player report rather than a confirmed building event");
        memory.rememberEvent("defense:zombie", "Марк защитил владельца от зомби.", 200,
            "minecraft:overworld", 10, 64, -20);
        var event = memory.inspect().getFirst();
        check(event.sourceType() == MemoryEntry.SourceType.WORLD_EVENT
            && event.reliability() == MemoryEntry.Reliability.CONFIRMED
            && event.location().z() == -20 && event.dimension().equals("minecraft:overworld"),
            "Observed event retains confirmed source and associated location");
        var observed = JsonParser.parseString(JSON.toJson(memory.context("защитил"))).getAsJsonObject()
            .getAsJsonArray("observed_events").get(0).getAsJsonObject();
        check(observed.has("dimension") && observed.has("location")
            && observed.get("dimension").getAsString().equals("minecraft:overworld")
            && observed.getAsJsonObject("location").get("x").getAsInt() == 10
            && observed.getAsJsonObject("location").get("z").getAsInt() == -20,
            "Retrieved confirmed observations expose their recorded dimension and location");
        memory.rememberEvent("defense:zombie", "Марк защитил владельца от зомби.", 201);
        check(memory.inspect().size() == 2, "Repeated world-event notification is deduplicated");
        memory.rememberStatement("Хочу построить дом из березы с синей крышей.", 300);
        check(memory.inspect().size() == 2, "Repeated player report is deduplicated");
        memory.rememberExchange("Привет", new OllamaClient.Reply("say", "Я нашел алмазы и убил дракона."), 400);
        check(memory.inspect().stream().noneMatch(e -> e.content().contains("дракона")),
            "Assistant inventions are never promoted to durable game memories");
        for (int i = 0; i < 10; i++) memory.rememberStatement("Запомни номер " + i, 500 + i);
        String context = JSON.toJson(memory.context("Какой дом из березы я задумал?"));
        check(context.contains("синей крышей") && context.length() <= 3000,
            "Relevant old plan outranks newer unrelated reports within context budget");
        var loaded = JSON.fromJson(JSON.toJson(memory), CompanionMemory.class); loaded.normalize(); loaded.bind(companion);
        check(loaded.inspect().stream().anyMatch(e -> e.id().equals(event.id()) && e.location().x() == 10),
            "Memory IDs, source and location survive serialization");
        UUID planId = loaded.inspect().stream().filter(e -> e.category().equals("plan")).findFirst().orElseThrow().id();
        check(loaded.delete(planId) && !loaded.delete(planId), "Inspection-selected memory can be deleted once");
        loaded = JSON.fromJson(JSON.toJson(loaded), CompanionMemory.class); loaded.normalize();
        check(loaded.inspect().stream().noneMatch(e -> e.category().equals("plan")), "Deletion persists and recovery does not resurrect a forgotten record");
        loaded.bind(UUID.randomUUID());
        check(loaded.inspect().isEmpty() && loaded.history().isEmpty(), "A new companion identity does not inherit another wolf's memories");

        var legacy = JSON.fromJson("""
            {"conversation":[],"reports":[{"kind":"preference","quote":"Я люблю березовые леса.","game_time":7}],
             "events":[{"key":"won:zombie","text":"Я победил зомби.","game_time":8}]}
            """, CompanionMemory.class);
        legacy.normalize(); legacy.bind(companion);
        check(legacy.inspect().size() == 2 && legacy.inspect().stream().allMatch(e -> e.companionId().equals(companion)),
            "Legacy quotes/events migrate to full episodic records bound to the saved companion");
        check(JSON.toJson(legacy.context("лес")).contains("Я люблю березовые леса."), "Migrated preference remains retrievable");
        var ids = legacy.inspect().stream().map(MemoryEntry::id).toList();
        legacy.normalize();
        check(legacy.inspect().stream().map(MemoryEntry::id).toList().equals(ids), "Repeated normalization preserves migrated memory IDs");

        var compacted = new CompanionMemory(); compacted.bind(companion);
        compacted.rememberExchange("Я обещаю вернуться домой к вечеру.", new OllamaClient.Reply("say", "Подожду."), 1);
        for (int i = 0; i < 14; i++) compacted.rememberExchange("Привет " + i, new OllamaClient.Reply("say", "Привет."), 2 + i);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (compacted.inspect().stream().noneMatch(e -> e.sourceType() == MemoryEntry.SourceType.CONVERSATION_SUMMARY)
                && System.nanoTime() < deadline) Thread.sleep(5);
        check(compacted.history().size() <= 24 && compacted.inspect().stream().anyMatch(e -> e.category().equals("promise")),
            "Promise survives recent-conversation window eviction");
        check(compacted.inspect().stream().anyMatch(e -> e.sourceType() == MemoryEntry.SourceType.CONVERSATION_SUMMARY
            && e.content().contains("Я обещаю") && e.reliability() != MemoryEntry.Reliability.CONFIRMED),
            "Older conversation is extractively compacted without upgrading words to a world observation");
        var oldPromise = compacted.inspect().stream().filter(e -> e.category().equals("promise")).findFirst().orElseThrow();
        check(compacted.delete(oldPromise.id()) && !JSON.toJson(compacted.context("обещаю")).contains(oldPromise.content()),
            "Deleting a player statement also removes its extractive conversation summaries");
        var truncatedSave = JSON.toJsonTree(compacted).getAsJsonObject();
        truncatedSave.getAsJsonArray("entries").add(JSON.toJsonTree(oldPromise));
        truncatedSave.getAsJsonArray("entries").add(JSON.toJsonTree(new MemoryEntry(UUID.randomUUID(), companion, 20,
            "conversation", "Игрок говорил: «" + oldPromise.content().substring(0, 20), "dialogue:compaction",
            MemoryEntry.SourceType.CONVERSATION_SUMMARY, MemoryEntry.Reliability.REPORTED, 45, null, null)));
        // This models an older save whose summary ended halfway through the source quotation.
        truncatedSave.getAsJsonArray("deletedContents").asList().clear();
        var truncated = JSON.fromJson(truncatedSave, CompanionMemory.class); truncated.normalize();
        check(truncated.delete(oldPromise.id()) && truncated.inspect().stream().noneMatch(e -> e.sourceType() == MemoryEntry.SourceType.CONVERSATION_SUMMARY),
            "Deleting a source quote also forgets a truncated quotation in an older saved summary");

        var recent = new CompanionMemory(); recent.bind(companion);
        String forgotten = "Запомни код моего дома: семь сосен.";
        recent.rememberExchange(forgotten, new OllamaClient.Reply("say", forgotten), 10);
        recent.rememberStatementAsync(forgotten, 10).get(2, TimeUnit.SECONDS); recent.flushPending();
        UUID recentId = recent.inspect().getFirst().id();
        check(recent.delete(recentId) && recent.history().stream().noneMatch(m -> m.content().contains(forgotten)),
            "Deleting a player statement removes its recent exchange rather than disclosing it as history");
        recent.rememberStatement(forgotten.toUpperCase(Locale.ROOT), 11);
        check(recent.inspect().stream().anyMatch(e -> e.content().equals(forgotten.toUpperCase(Locale.ROOT))),
            "A newly stated fact can be intentionally remembered again after deletion");
        deletionSurvivesPendingCompaction(companion);

        var racedSave = new CompanionMemory(); racedSave.bind(companion);
        racedSave.rememberExchange("Хочу построить мост.", new OllamaClient.Reply("say", "Хорошая идея."), 42);
        var recovered = JSON.fromJson(JSON.toJson(racedSave), CompanionMemory.class);
        check(JSON.toJson(recovered.context("мост")).contains("Хочу построить мост.")
            && recovered.inspect().stream().anyMatch(e -> e.content().equals("Хочу построить мост.") && e.gameTime() == 42),
            "A save racing extraction auto-recovers exact quote/time before the first context request");
        recovered.rememberStatementAsync("Запомни то, что нужно забыть.", 44);
        recovered.clear(); Thread.sleep(30); recovered.flushPending();
        check(recovered.inspect().isEmpty() && recovered.history().isEmpty(), "Forget invalidates late asynchronous extraction results");

        var malformed = JsonParser.parseString(JSON.toJson(memory)).getAsJsonObject();
        malformed.getAsJsonArray("entries").add(JsonParser.parseString("{\"id\":null,\"content\":\"bad\"}"));
        malformed.getAsJsonArray("entries").add(JsonParser.parseString("{\"id\":\"not-a-uuid\",\"content\":\"bad\"}"));
        var repaired = JSON.fromJson(malformed, CompanionMemory.class); repaired.normalize();
        check(repaired.inspect().stream().allMatch(e -> e.id() != null && e.sourceType() != null), "Invalid loaded entries are discarded without corrupting valid memories");
        CompanionMemory.shutdownWorkers();
        var reopened = new CompanionMemory();
        reopened.rememberStatementAsync("Я люблю дождь.", 1).get(2, TimeUnit.SECONDS); reopened.flushPending();
        check(reopened.inspect().size() == 1, "Memory workers restart after another world is opened");
        CompanionMemory.shutdownWorkers();
        System.out.println("Memory system: " + checks + " checks passed");
    }

    private static void deletionSurvivesPendingCompaction(UUID companion) throws Exception {
        // Hold the worker so deletion deterministically races queued extraction and compaction.
        var executorMethod = CompanionMemory.class.getDeclaredMethod("executor"); executorMethod.setAccessible(true);
        var executor = (ThreadPoolExecutor) executorMethod.invoke(null);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocker = executor.submit(() -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        check(started.await(2, TimeUnit.SECONDS), "Memory worker paused before queuing a deletion race");
        var memory = new CompanionMemory(); memory.bind(companion);
        String forgotten = "Я обещаю построить тайный мост.";
        try {
            memory.rememberStatement(forgotten, 1);
            UUID id = memory.inspect().getFirst().id();
            memory.rememberExchange(forgotten.toUpperCase(Locale.ROOT), new OllamaClient.Reply("say", "Договорились."), 1);
            for (int i = 0; i < 14; i++) memory.rememberExchange("Привет " + i, new OllamaClient.Reply("say", "Привет."), i + 2);
            memory.bind(companion); memory.normalize();
            check(memory.inspect().size() == 1 && memory.inspect().getFirst().id().equals(id),
                "Repeated identity binding does not synchronously re-extract queued journal records or replace memory IDs");
            check(memory.delete(id), "A statement can be deleted while its extraction and summary are queued");
            var recovered = JSON.fromJson(JSON.toJson(memory), CompanionMemory.class); recovered.normalize();
            check(!JSON.toJson(recovered.context("тайный мост")).toLowerCase(Locale.ROOT).contains(forgotten.toLowerCase(Locale.ROOT)),
                "A save during deletion cannot recover a forgotten quote from its extraction journal");
        } finally { release.countDown(); }
        blocker.get(2, TimeUnit.SECONDS);
        executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        check(memory.inspect().stream().noneMatch(e -> e.content().toLowerCase(Locale.ROOT).contains(forgotten.toLowerCase(Locale.ROOT)))
            && !JSON.toJson(memory.context("тайный мост")).toLowerCase(Locale.ROOT).contains(forgotten.toLowerCase(Locale.ROOT)),
            "Late asynchronous extraction and compaction cannot resurrect a deleted statement");
    }
}
