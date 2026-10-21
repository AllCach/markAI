package dev.markai.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markai.ai.OllamaClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** File-based checks for pairing, retained diagnostics, rotation and graceful failure. */
public final class ChatJournalTest {
    public static void main(String[] args) throws Exception {
        pairedUtf8Records();
        admittedLimitsAndFallback();
        rotatesThreeFiles();
        unwritableDestinationDoesNotBreakDialogue();
        concurrentPairsStayOrdered();
        System.out.println("Chat journal: 5 checks passed");
    }

    private static void pairedUtf8Records() throws Exception {
        Path directory = Files.createTempDirectory("markai-journal-pair-");
        UUID owner = UUID.randomUUID(), entity = UUID.randomUUID();
        String requestId;
        try (var journal = new ChatJournal(directory)) {
            requestId = journal.begin(owner, entity, "Игрок", "Марк, где ты?",
                "{\"mode\":\"stay\",\"player\":{\"name\":\"Игрок\"}}");
            journal.finish(requestId, "delivered", new OllamaClient.Reply("follow", "Иду!"),
                new OllamaClient.Reply("say", "Стою здесь."), null);
            journal.finish(requestId, "duplicate", null, null, "must not be written");
        }
        List<JsonObject> lines = records(directory);
        check(lines.size() == 2, "A completed request must have exactly two records");
        JsonObject request = lines.get(0), response = lines.get(1);
        check(requestId.equals(request.get("request_id").getAsString())
            && requestId.equals(response.get("request_id").getAsString()), "Request IDs differ");
        check("request".equals(request.get("type").getAsString())
            && "response".equals(response.get("type").getAsString()), "Wrong record order");
        check(owner.toString().equals(request.get("owner_uuid").getAsString())
            && entity.toString().equals(request.get("entity_uuid").getAsString()), "UUIDs were lost");
        check("Игрок".equals(request.get("player_name").getAsString())
            && "Марк, где ты?".equals(request.get("question").getAsString()), "UTF-8 was lost");
        check(request.getAsJsonObject("context").get("mode").getAsString().equals("stay"),
            "Context must be parsed JSON");
        check("follow".equals(response.getAsJsonObject("proposed").get("action").getAsString())
            && "say".equals(response.getAsJsonObject("delivered").get("action").getAsString()),
            "Proposed and delivered replies must remain distinguishable");
        check(response.get("error").isJsonNull(), "Successful response must retain a null error");
        Instant.parse(request.get("timestamp").getAsString());
        Instant.parse(response.get("timestamp").getAsString());
    }

    private static void admittedLimitsAndFallback() throws Exception {
        Path directory = Files.createTempDirectory("markai-journal-limits-");
        String context = "{\"long\":\"" + "я".repeat(8192 - 11) + "\"}";
        String question = "ё".repeat(512), answer = "ю".repeat(240);
        try (var journal = new ChatJournal(directory)) {
            String id = journal.begin(null, null, "Игрок", question, context);
            journal.finish(id, "delivered", new OllamaClient.Reply("say", answer),
                new OllamaClient.Reply("say", answer), null);
            id = journal.begin(null, null, "Игрок", "x".repeat(100_000), "not JSON");
            journal.finish(id, "failed", null, null, "e".repeat(100_000));
        }
        List<JsonObject> lines = records(directory);
        check(lines.size() == 4, "Missing bounded records");
        check(question.equals(lines.get(0).get("question").getAsString()), "Valid question truncated");
        check(context.equals(lines.get(0).get("context").toString()), "Valid context truncated");
        check(answer.equals(lines.get(1).getAsJsonObject("delivered").get("say").getAsString()),
            "Valid reply truncated");
        check(lines.get(2).get("question").getAsString().length() == 512, "Oversize question unbounded");
        check("not JSON".equals(lines.get(2).get("context").getAsString()), "Malformed context was lost");
        check(lines.get(3).get("error").getAsString().length() == 2048, "Oversize error unbounded");
    }

    private static void rotatesThreeFiles() throws Exception {
        Path directory = Files.createTempDirectory("markai-journal-rotation-");
        Path file = directory.resolve("markai-chat.jsonl");
        Files.writeString(file, " ".repeat(5 * 1024 * 1024 - 1), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("markai-chat.jsonl.1"), "previous", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("markai-chat.jsonl.2"), "oldest", StandardCharsets.UTF_8);
        try (var journal = new ChatJournal(directory)) {
            String id = journal.begin(null, null, "Игрок", "Привет", "{}");
            journal.finish(id, "failed", null, null, "test");
        }
        check(records(directory).size() == 2, "Rotation lost the current dialogue");
        check(Files.size(directory.resolve("markai-chat.jsonl.1")) == 5 * 1024 * 1024 - 1,
            "Previous active file was not rotated");
        check("previous".equals(Files.readString(directory.resolve("markai-chat.jsonl.2"))),
            "Second generation was not retained");
        try (var files = Files.list(directory)) {
            check(files.count() == 3, "Rotation must retain only three journal files");
        }
    }

    private static void unwritableDestinationDoesNotBreakDialogue() throws Exception {
        Path fileAsDirectory = Files.createTempFile("markai-journal-unwritable-", ".txt");
        Files.writeString(fileAsDirectory, "unchanged");
        try (var journal = new ChatJournal(fileAsDirectory)) {
            String id = journal.begin(null, null, "Игрок", "Привет", "{}");
            journal.finish(id, "failed", null, null, "unavailable");
        }
        check("unchanged".equals(Files.readString(fileAsDirectory)), "Existing file was altered");
    }

    private static void concurrentPairsStayOrdered() throws Exception {
        Path directory = Files.createTempDirectory("markai-journal-concurrent-");
        try (var journal = new ChatJournal(directory); var callers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 80; i++) futures.add(callers.submit(() -> {
                String id = journal.begin(null, null, "Игрок", "Привет", "{}");
                journal.finish(id, "failed", null, null, "test");
            }));
            for (Future<?> future : futures) future.get();
        }
        List<JsonObject> lines = records(directory);
        check(lines.size() == 160, "Concurrent requests lost a record");
        var requests = new HashSet<String>();
        var responses = new HashSet<String>();
        for (JsonObject record : lines) {
            String id = record.get("request_id").getAsString();
            if (record.get("type").getAsString().equals("request")) check(requests.add(id), "Duplicate request");
            else check(requests.contains(id) && responses.add(id), "Response preceded request or duplicated");
        }
        check(requests.equals(responses), "Unpaired concurrent records");
    }

    private static List<JsonObject> records(Path directory) throws Exception {
        return Files.readAllLines(directory.resolve("markai-chat.jsonl"), StandardCharsets.UTF_8).stream()
            .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
