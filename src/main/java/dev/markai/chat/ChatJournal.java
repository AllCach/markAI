package dev.markai.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.markai.ai.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, local dialogue diagnostics; no file I/O runs on the calling thread. */
public final class ChatJournal implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatJournal.class);
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int QUEUE_CAPACITY = 256;
    // Reserving two queue entries per outstanding request keeps its response admissible, too.
    private static final int MAX_PENDING_PAIRS = QUEUE_CAPACITY / 2;

    private final Path logsDirectory;
    private final Path file;
    private final ThreadPoolExecutor writer;
    private final Object lifecycle = new Object();
    private final Map<String, Pending> pending = new HashMap<>();
    private boolean closed;
    private boolean saturationReported;
    private boolean writeFailureReported;

    public ChatJournal(Path logsDirectory) {
        this.logsDirectory = Objects.requireNonNull(logsDirectory, "logsDirectory");
        file = logsDirectory.resolve("markai-chat.jsonl");
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY), task -> {
                Thread thread = new Thread(task, "markai-chat-journal");
                thread.setDaemon(true);
                return thread;
            });
    }

    /** Always returns an ID; unavailable diagnostics never prevent a dialogue request. */
    public String begin(UUID ownerUuid, UUID entityUuid, String playerName, String question, String contextJson) {
        String requestId = UUID.randomUUID().toString();
        try {
            JsonObject record = envelope("request", requestId);
            record.addProperty("owner_uuid", ownerUuid == null ? null : ownerUuid.toString());
            record.addProperty("entity_uuid", entityUuid == null ? null : entityUuid.toString());
            record.addProperty("player_name", bounded(playerName, 64));
            record.addProperty("question", bounded(question, 512));
            record.add("context", context(contextJson));
            synchronized (lifecycle) {
                if (closed) return requestId;
                if (pending.size() >= MAX_PENDING_PAIRS) {
                    if (!saturationReported) {
                        LOGGER.warn("MarkAI chat journal is busy; some dialogue pairs will not be recorded");
                        saturationReported = true;
                    }
                    return requestId;
                }
                pending.put(requestId, new Pending());
                try {
                    writer.execute(() -> append(record));
                    saturationReported = false;
                } catch (RejectedExecutionException error) {
                    pending.remove(requestId);
                    LOGGER.warn("Could not queue a MarkAI chat journal request", error);
                }
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Could not prepare a MarkAI chat journal request", error);
        }
        return requestId;
    }

    /** Records both the model's proposal and what the player actually received. */
    public void finish(String requestId, String status, OllamaClient.Reply proposed,
                       OllamaClient.Reply delivered, String error) {
        if (requestId == null) return;
        try {
            JsonObject record = envelope("response", bounded(requestId, 64));
            record.addProperty("status", bounded(status, 64));
            record.add("proposed", reply(proposed));
            record.add("delivered", reply(delivered));
            record.addProperty("error", bounded(error, 2048));
            synchronized (lifecycle) {
                Pending admission = pending.get(requestId);
                if (closed || admission == null || admission.finished) return;
                admission.finished = true;
                try {
                    writer.execute(() -> {
                        try {
                            append(record);
                        } finally {
                            synchronized (lifecycle) { pending.remove(requestId); }
                        }
                    });
                } catch (RejectedExecutionException failure) {
                    pending.remove(requestId);
                    LOGGER.warn("Could not queue a MarkAI chat journal response", failure);
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not prepare a MarkAI chat journal response", failure);
        }
    }

    /** Flushes queued records for up to two seconds; the writer cannot keep the JVM alive. */
    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            writer.shutdown();
        }
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                writer.shutdownNow();
                LOGGER.warn("MarkAI chat journal did not finish flushing within two seconds");
            }
        } catch (InterruptedException error) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            synchronized (lifecycle) { pending.clear(); }
        }
    }

    private void append(JsonObject record) {
        try {
            byte[] bytes = (JSON.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(logsDirectory);
            if (Files.exists(file) && Files.size(file) + bytes.length > MAX_FILE_BYTES) rotate();
            // Each append closes its handle, so completed tasks are already flushed at shutdown.
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writeFailureReported = false;
        } catch (IOException | RuntimeException failure) {
            if (!writeFailureReported) {
                LOGGER.warn("Could not write MarkAI chat journal; dialogue will continue", failure);
                writeFailureReported = true;
            }
        }
    }

    private void rotate() throws IOException {
        Path previous = file.resolveSibling(file.getFileName() + ".1");
        Path oldest = file.resolveSibling(file.getFileName() + ".2");
        if (Files.exists(previous)) Files.move(previous, oldest, StandardCopyOption.REPLACE_EXISTING);
        Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonObject envelope(String type, String requestId) {
        JsonObject record = new JsonObject();
        record.addProperty("type", type);
        record.addProperty("request_id", requestId);
        record.addProperty("timestamp", Instant.now().toString());
        return record;
    }

    private static JsonElement context(String contextJson) {
        String text = bounded(contextJson, 8192);
        if (text == null) return JsonNull.INSTANCE;
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException ignored) {
            return new JsonPrimitive(text);
        }
    }

    private static JsonElement reply(OllamaClient.Reply reply) {
        if (reply == null) return JsonNull.INSTANCE;
        JsonObject result = new JsonObject();
        result.addProperty("action", bounded(reply.action(), 32));
        result.addProperty("say", bounded(reply.say(), 240));
        return result;
    }

    private static String bounded(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        int end = maxLength;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static final class Pending { private boolean finished; }
}
