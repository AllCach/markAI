package dev.markai.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.markai.ai.OllamaClient;
import dev.markai.memory.MemoryEntry.Reliability;
import dev.markai.memory.MemoryEntry.SourceType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent, bounded memory. Workers produce immutable results; only callers change saved lists. */
public final class CompanionMemory {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final UUID UNBOUND = new UUID(0, 0);
    private static ThreadPoolExecutor workers;
    // Legacy v0.5/0.6 fields retained for lossless Gson migration.
    private record Report(String kind, String quote, long game_time) {}
    private record Event(String key, String text, long game_time) {}
    private List<Report> reports;
    private List<Event> events;
    private UUID companionId = UNBOUND;
    private List<OllamaClient.ChatMessage> conversation = new ArrayList<>();
    private List<MemoryEntry> entries = new ArrayList<>();
    private List<String> deletedContents = new ArrayList<>();
    private record StatementJournal(String quote, long time) {}
    private List<StatementJournal> extractionJournal = new ArrayList<>();
    private transient ConcurrentLinkedQueue<Extracted> completed = new ConcurrentLinkedQueue<>();
    private transient List<Pending> pending = new ArrayList<>();
    private transient AtomicLong generation = new AtomicLong();
    private transient boolean prepared;
    private record Extracted(long generation, MemoryEntry entry, String quote, long time, List<String> summarizedQuotes) {}
    private record Pending(String quote, long time, long generation, CompletableFuture<Void> task) {}

    public void bind(UUID id) {
        if (id == null) throw new IllegalArgumentException("Companion identity is required");
        if (!prepared) normalize();
        if (id.equals(companionId)) return;
        if (!UNBOUND.equals(companionId) && !id.equals(companionId)) clear();
        companionId = id;
        for (int i = 0; i < entries.size(); i++) {
            MemoryEntry e = entries.get(i);
            if (UNBOUND.equals(e.companionId())) entries.set(i, withIdentity(e, id));
        }
    }

    public void normalize() {
        if (prepared) return;
        if (conversation == null) conversation = new ArrayList<>();
        if (entries == null) entries = new ArrayList<>();
        if (deletedContents == null) deletedContents = new ArrayList<>();
        deletedContents.removeIf(s -> s == null || s.length() != 64);
        while (deletedContents.size() > 160) deletedContents.removeFirst();
        if (extractionJournal == null) extractionJournal = new ArrayList<>();
        if (companionId == null) companionId = UNBOUND;
        if (completed == null) completed = new ConcurrentLinkedQueue<>();
        if (pending == null) pending = new ArrayList<>();
        if (generation == null) generation = new AtomicLong();
        entries.removeIf(e -> !valid(e));
        if (reports != null) {
            for (Report r : reports) if (r != null && validText(r.quote(), 512) && validText(r.kind(), 48))
                add(newEntry(r.game_time(), r.kind(), r.quote(), "legacy:dialogue", SourceType.PLAYER_STATEMENT,
                    Reliability.REPORTED, 60, null, null));
            reports = null;
        }
        if (events != null) {
            for (Event e : events) if (e != null && validText(e.text(), 512) && validText(e.key(), 160))
                add(newEntry(e.game_time(), "adventure", e.text(), "event:" + e.key(), SourceType.WORLD_EVENT,
                    Reliability.CONFIRMED, 70, null, null));
            events = null;
        }
        // Exact-input journal preserves game times if saving raced the asynchronous extractor.
        for (StatementJournal journal : extractionJournal.subList(Math.max(0, extractionJournal.size() - 32), extractionJournal.size()))
            if (journal != null && validText(journal.quote(), 512)) {
                MemoryEntry recovered = extractStatement(journal.quote(), journal.time(), companionId);
                if (recovered != null) add(recovered);
            }
        extractionJournal.clear();
        boolean valid = conversation.size() % 2 == 0;
        for (int i = 0; i < conversation.size() && valid; i++) {
            var message = conversation.get(i);
            valid = message != null && validText(message.content(), 1024)
                && (i % 2 == 0 ? "user" : "assistant").equals(message.role());
        }
        if (!valid) conversation.clear();
        Set<UUID> ids = new HashSet<>();
        entries.removeIf(e -> !ids.add(e.id()) || !UNBOUND.equals(companionId)
            && !UNBOUND.equals(e.companionId()) && !companionId.equals(e.companionId()));
        trim(false, 0);
        prepared = true;
    }

    public List<OllamaClient.ChatMessage> history() { flushPending(); return List.copyOf(conversation); }

    public void rememberExchange(String question, OllamaClient.Reply reply, long time) {
        if (!validText(question, 512) || reply == null) return;
        String answer = JSON.toJson(reply);
        if (answer.length() > 1024) return;
        flushPending();
        conversation.add(new OllamaClient.ChatMessage("user", question));
        conversation.add(new OllamaClient.ChatMessage("assistant", answer));
        rememberStatementAsync(question, time);
        trim(true, time);
    }

    /** Explicit commands/imports can synchronously add a known player statement. */
    public void rememberStatement(String text, long time) {
        if (!validText(text, 512)) return;
        flushPending();
        allowRemembering(text);
        MemoryEntry extracted = extractStatement(text, time, companionId);
        if (extracted != null) add(extracted);
        trim(false, time);
    }

    /** Local deterministic extraction runs off the Minecraft thread. */
    public CompletableFuture<Void> rememberStatementAsync(String text, long time) {
        if (!validText(text, 512)) return CompletableFuture.completedFuture(null);
        flushPending();
        allowRemembering(text);
        long version = generation.get(); UUID identity = companionId;
        CompletableFuture<Void> task = new CompletableFuture<>();
        Pending work = new Pending(text, time, version, task);
        if (pending.size() >= 32) return CompletableFuture.failedFuture(new IllegalStateException("Memory extraction queue is full"));
        pending.add(work);
        extractionJournal.add(new StatementJournal(text, time));
        while (extractionJournal.size() > 32) extractionJournal.removeFirst();
        try {
            executor().execute(() -> {
                try {
                    MemoryEntry result = extractStatement(text, time, identity);
                    if (generation.get() == version) completed.add(new Extracted(version, result, text, time, List.of()));
                    task.complete(null);
                } catch (RuntimeException failure) { task.completeExceptionally(failure); }
            });
        } catch (RuntimeException rejected) {
            pending.remove(work); task.completeExceptionally(new IllegalStateException("Memory extraction unavailable"));
        }
        return task;
    }

    /** Commit finished worker results without waiting. Invoke before a world save and periodically. */
    public void flushPending() {
        if (!prepared) normalize();
        if (completed == null || generation == null) return;
        for (Extracted item; (item = completed.poll()) != null;) if (item.generation() == generation.get()) {
            if (item.entry() != null && item.summarizedQuotes().stream().noneMatch(this::wasDeleted))
                add(item.entry());
            if (item.quote() != null) {
                String quote = item.quote(); long time = item.time();
                extractionJournal.removeIf(j -> j.quote().equals(quote) && j.time() == time);
            }
        }
        if (pending != null) pending.removeIf(p -> p.task().isDone());
        boundEntries();
    }

    public void rememberEvent(String key, String text, long time) { rememberEvent(key, text, time, null, null); }

    public void rememberEvent(String key, String text, long time, String dimension, int x, int y, int z) {
        rememberEvent(key, text, time, dimension, new MemoryEntry.Location(x, y, z));
    }

    private void rememberEvent(String key, String text, long time, String dimension, MemoryEntry.Location location) {
        if (!validText(key, 160) || text == null || text.isBlank()) return;
        flushPending();
        if (text.length() > 512) text = text.substring(0, 512);
        if (!validText(text, 512)) return;
        allowRemembering(text);
        String source = "event:" + key.toLowerCase(Locale.ROOT);
        for (int i = entries.size() - 1; i >= 0; i--) {
            MemoryEntry previous = entries.get(i);
            if (previous.sourceType() == SourceType.WORLD_EVENT && previous.source().equals(source)
                    && previous.content().equals(text) && time >= previous.gameTime() && time - previous.gameTime() < 1200) return;
        }
        String categoryKey = key.toLowerCase(Locale.ROOT);
        String category = categoryKey.contains("danger") || categoryKey.contains("hurt") || categoryKey.contains("damage") || categoryKey.contains("critical") ? "danger"
            : categoryKey.contains("biome") || categoryKey.contains("discover") || categoryKey.contains("diamonds") ? "discovery"
            : categoryKey.contains("affection") || categoryKey.contains("reunited") || categoryKey.contains("healed") ? "relationship" : "adventure";
        add(newEntry(time, category, text, source, SourceType.WORLD_EVENT, Reliability.CONFIRMED,
            category.equals("danger") ? 85 : 70, dimension, location));
        boundEntries();
    }

    public Map<String, Object> context(String question) {
        flushPending();
        var chosenReports = new ArrayList<Map<String, Object>>();
        var chosenEvents = new ArrayList<Map<String, Object>>();
        for (MemoryEntry entry : select(entries, question)) {
            if (entry.sourceType() == SourceType.PLAYER_STATEMENT && chosenReports.size() < 6)
                chosenReports.add(Map.of("id", entry.id().toString(), "kind", entry.category(),
                    "quote", entry.content(), "game_time", entry.gameTime(), "reliability", "player_report"));
            else if (entry.sourceType() == SourceType.WORLD_EVENT && chosenEvents.size() < 4) {
                var observation = new LinkedHashMap<String, Object>();
                observation.put("id", entry.id().toString()); observation.put("key", entry.source().replaceFirst("^event:", ""));
                observation.put("text", entry.content()); observation.put("game_time", entry.gameTime());
                observation.put("reliability", "confirmed_observation");
                if (entry.dimension() != null) observation.put("dimension", entry.dimension());
                if (entry.location() != null) observation.put("location", entry.location());
                chosenEvents.add(observation);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("player_reports", chosenReports); result.put("observed_events", chosenEvents);
        var summaries = select(entries, question).stream().filter(e -> e.sourceType() == SourceType.CONVERSATION_SUMMARY)
            .limit(1).map(e -> Map.of("text", e.content(), "source", "quoted_player_conversation_summary",
                "game_time", e.gameTime())).toList();
        if (!summaries.isEmpty()) result.put("conversation_summaries", new ArrayList<>(summaries));
        while (JSON.toJson(result).length() > 3000) {
            if (!chosenEvents.isEmpty()) chosenEvents.removeLast();
            else if (!chosenReports.isEmpty()) chosenReports.removeLast();
            else { result.remove("conversation_summaries"); break; }
        }
        return result;
    }

    public List<MemoryEntry> inspect() {
        flushPending();
        return entries.stream().sorted(Comparator.comparingLong(MemoryEntry::gameTime).reversed()).toList();
    }

    public boolean delete(UUID id) {
        if (id == null) return false;
        flushPending();
        MemoryEntry target = entries.stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
        if (target == null) return false;
        deletedContents.add(digest(target.content().toLowerCase(Locale.ROOT)));
        while (deletedContents.size() > 160) deletedContents.removeFirst();
        if (target.sourceType() == SourceType.PLAYER_STATEMENT) {
            String forgotten = target.content().toLowerCase(Locale.ROOT);
            entries.removeIf(entry -> entry.sourceType() == SourceType.CONVERSATION_SUMMARY
                && summaryMentions(entry.content(), forgotten));
            // Forget the exchange as a pair: both the player's words and the answer may repeat it.
            for (int i = conversation.size() - 2; i >= 0; i -= 2) {
                if (conversation.get(i).content().toLowerCase(Locale.ROOT).contains(forgotten)
                        || assistantMentions(conversation.get(i + 1), forgotten)) {
                    conversation.remove(i + 1); conversation.remove(i);
                }
            }
            extractionJournal.removeIf(journal -> journal.quote().equalsIgnoreCase(target.content()));
        }
        return entries.removeIf(entry -> entry.id().equals(id));
    }

    private static boolean assistantMentions(OllamaClient.ChatMessage message, String forgotten) {
        try { return OllamaClient.parseReply(message.content()).say().toLowerCase(Locale.ROOT).contains(forgotten); }
        catch (IllegalArgumentException invalidLegacyReply) { return message.content().toLowerCase(Locale.ROOT).contains(forgotten); }
    }

    private static boolean summaryMentions(String summary, String forgotten) {
        String text = summary.toLowerCase(Locale.ROOT);
        if (text.contains(forgotten)) return true;
        // Earlier saves could cut their last quotation at the summary's 900-character bound.
        String prefix = "игрок говорил: «";
        int start = text.lastIndexOf(prefix);
        if (start < 0 || text.endsWith("»")) return false;
        String fragment = text.substring(start + prefix.length());
        return !fragment.isEmpty() && forgotten.startsWith(fragment);
    }

    public void clear() {
        if (!prepared) normalize();
        if (generation == null) generation = new AtomicLong();
        generation.incrementAndGet();
        if (completed != null) completed.clear();
        if (pending != null) { pending.forEach(p -> p.task().cancel(false)); pending.clear(); }
        conversation.clear(); entries.clear(); deletedContents.clear(); extractionJournal.clear(); reports = null; events = null;
    }

    private void trim(boolean compact, long time) {
        var oldPlayerQuotes = new ArrayList<String>();
        while (conversation.size() > 24 || conversation.stream().mapToInt(m -> m.content().length()).sum() > 12000) {
            oldPlayerQuotes.add(conversation.removeFirst().content()); conversation.removeFirst();
        }
        if (compact && !oldPlayerQuotes.isEmpty()) compactAsync(oldPlayerQuotes, time);
        boundEntries();
    }

    private void compactAsync(List<String> quotes, long time) {
        List<String> immutable = List.copyOf(quotes);
        long version = generation.get(); UUID identity = companionId;
        try {
            executor().execute(() -> {
                // Extractive summary: only exact player words, never an assistant's invented event.
                var summarized = new ArrayList<String>();
                var summary = new StringBuilder();
                for (String quote : immutable) {
                    if (extractStatement(quote, time, identity) == null) continue;
                    String fragment = "Игрок говорил: «" + quote + "»";
                    if (summary.length() + (summary.isEmpty() ? 0 : 1) + fragment.length() > 900) break;
                    if (!summary.isEmpty()) summary.append(' ');
                    summary.append(fragment); summarized.add(quote);
                    if (summarized.size() == 3) break;
                }
                if (!summary.isEmpty() && generation.get() == version)
                    completed.add(new Extracted(version, new MemoryEntry(UUID.randomUUID(), identity, time,
                        "conversation", summary.toString(), "dialogue:compaction",
                        SourceType.CONVERSATION_SUMMARY, Reliability.REPORTED, 45, null, null), null, time, List.copyOf(summarized)));
            });
        } catch (RuntimeException rejected) { /* Recent history and exact durable reports remain bounded. */ }
    }

    private void add(MemoryEntry entry) {
        if (!valid(entry)) return;
        if (deletedContents != null && wasDeleted(entry.content())) return;
        if (!UNBOUND.equals(companionId) && !companionId.equals(entry.companionId())) entry = withIdentity(entry, companionId);
        MemoryEntry incoming = entry;
        if (entry.sourceType() == SourceType.PLAYER_STATEMENT) entries.removeIf(e -> e.sourceType() == SourceType.PLAYER_STATEMENT
            && e.content().equalsIgnoreCase(incoming.content()));
        if (entries.stream().noneMatch(e -> e.id().equals(incoming.id()))) entries.add(incoming);
    }

    private void boundEntries() {
        if (entries == null) return;
        boundCategory(SourceType.PLAYER_STATEMENT, 48);
        boundCategory(SourceType.WORLD_EVENT, 64);
        boundCategory(SourceType.CONVERSATION_SUMMARY, 24);
        while (entries.size() > 160) entries.removeFirst();
    }

    private void boundCategory(SourceType type, int limit) {
        long count = entries.stream().filter(e -> e.sourceType() == type).count();
        while (count > limit) {
            MemoryEntry leastImportant = entries.stream().filter(e -> e.sourceType() == type)
                .min(Comparator.comparingInt(MemoryEntry::importance).thenComparingLong(MemoryEntry::gameTime)).orElseThrow();
            entries.remove(leastImportant); count--;
        }
    }

    private static List<MemoryEntry> select(List<MemoryEntry> entries, String question) {
        Set<String> terms = new HashSet<>(Arrays.asList((question == null ? "" : question)
            .toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")));
        terms.removeIf(t -> t.length() < 3 || Set.of("какой", "какая", "помнишь", "меня", "тебя", "мне", "что", "как").contains(t));
        return entries.stream().sorted(Comparator.<MemoryEntry>comparingInt(e -> {
            String text = e.content().toLowerCase(Locale.ROOT).replace('ё', 'е');
            return (int) terms.stream().filter(text::contains).count();
        }).reversed().thenComparing(Comparator.comparingInt(MemoryEntry::importance).reversed())
            .thenComparing(Comparator.comparingLong(MemoryEntry::gameTime).reversed())).toList();
    }

    private MemoryEntry newEntry(long time, String category, String text, String source,
                                 SourceType sourceType, Reliability reliability, int importance,
                                 String dimension, MemoryEntry.Location location) {
        return new MemoryEntry(UUID.randomUUID(), companionId, Math.max(0, time), category, text, source,
            sourceType, reliability, importance, dimension, location);
    }

    private static MemoryEntry extractStatement(String text, long time, UUID identity) {
        String lower = text.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
        String kind;
        if (lower.matches("^(?:я )?(?:хочу|планирую|собираюсь)(?=\\s|[,!:.]|$).*")) kind = "plan";
        else if (lower.matches("^(?:я люблю|мне нравится|я предпочитаю|мое любимое|мой любимый)(?=\\s|[,!:.]|$).*")) kind = "preference";
        else if (lower.matches("^(?:я )?(?:обещаю|обязуюсь)(?=\\s|[,!:.]|$).*")) kind = "promise";
        else if (lower.matches("^(?:запомни|меня зовут|зови меня|мой |моя |мое |у меня ).*")) kind = "fact";
        else return null;
        return new MemoryEntry(UUID.randomUUID(), identity, Math.max(0, time), kind, text, "dialogue:player",
            SourceType.PLAYER_STATEMENT, Reliability.REPORTED, kind.equals("promise") ? 85 : 60, null, null);
    }

    private static MemoryEntry withIdentity(MemoryEntry e, UUID identity) {
        return new MemoryEntry(e.id(), identity, e.gameTime(), e.category(), e.content(), e.source(),
            e.sourceType(), e.reliability(), e.importance(), e.dimension(), e.location());
    }

    private static boolean valid(MemoryEntry e) {
        return e != null && e.id() != null && e.companionId() != null && e.gameTime() >= 0
            && validText(e.category(), 48) && validText(e.content(), 900) && validText(e.source(), 200)
            && e.sourceType() != null && e.reliability() != null && e.importance() >= 0 && e.importance() <= 100
            && (e.dimension() == null || e.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            && (e.sourceType() != SourceType.WORLD_EVENT || e.reliability() == Reliability.CONFIRMED);
    }

    private static boolean validText(String text, int limit) {
        return text != null && !text.isBlank() && text.length() <= limit && text.codePoints()
            .noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == 0x00a7);
    }

    private static String digest(String content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private boolean wasDeleted(String text) {
        // Keep accepting the original case-sensitive hashes stored by earlier versions.
        return deletedContents.contains(digest(text)) || deletedContents.contains(digest(text.toLowerCase(Locale.ROOT)));
    }

    private void allowRemembering(String text) {
        deletedContents.remove(digest(text));
        deletedContents.remove(digest(text.toLowerCase(Locale.ROOT)));
    }

    private static synchronized ThreadPoolExecutor executor() {
        if (workers == null || workers.isShutdown()) workers = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "markai-memory-extraction"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        return workers;
    }

    public static synchronized void shutdownWorkers() {
        if (workers != null) { workers.shutdown(); workers = null; }
    }
}
