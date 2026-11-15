package dev.markai.companion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.markai.persistence.StrictJson;
import dev.markai.persistence.AtomicFileReplace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Versioned companion persistence with bounded, strict input and an exact legacy migration backup. */
public final class CompanionStore<T> {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RECORDS = 4096;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Class<T> recordType;

    public record Snapshot<T>(List<T> companions, Map<UUID, UUID> selected, Map<UUID, GroupChatState> groups, boolean legacy) {
        public Snapshot(List<T> companions, Map<UUID, UUID> selected, boolean legacy) { this(companions, selected, Map.of(), legacy); }
        public Snapshot {
            if (companions == null || selected == null || companions.size() > MAX_RECORDS)
                throw new IllegalArgumentException("Invalid companion snapshot");
            companions = List.copyOf(companions);
            var copy = new LinkedHashMap<UUID, UUID>();
            for (var entry : selected.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null)
                    throw new IllegalArgumentException("Selection UUID is required");
                copy.put(entry.getKey(), entry.getValue());
            }
            if (copy.size() > MAX_RECORDS) throw new IllegalArgumentException("Too many selections");
            selected = Collections.unmodifiableMap(copy);
            if (groups == null || groups.size() > MAX_RECORDS) throw new IllegalArgumentException("Invalid owner groups");
            groups = Map.copyOf(groups);
        }
    }

    public CompanionStore(Class<T> recordType) {
        this.recordType = java.util.Objects.requireNonNull(recordType, "Companion class");
    }

    public Snapshot<T> read(Path path) throws IOException {
        if (!Files.exists(path)) return new Snapshot<>(List.of(), Map.of(), false);
        return decode(readBounded(path));
    }

    public void write(Path path, Collection<T> companions, Map<UUID, UUID> selected) throws IOException {
        write(path, new Snapshot<>(new ArrayList<>(companions), selected, false));
    }
    public void write(Path path, Collection<T> companions, Map<UUID, UUID> selected, Map<UUID, GroupChatState> groups) throws IOException {
        write(path, new Snapshot<>(new ArrayList<>(companions), selected, groups, false));
    }

    public void write(Path path, Snapshot<T> snapshot) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray records = new JsonArray();
        for (T companion : snapshot.companions()) records.add(JSON.toJsonTree(companion, recordType));
        root.add("companions", records);
        JsonObject selected = new JsonObject();
        for (var entry : snapshot.selected().entrySet()) selected.addProperty(entry.getKey().toString(), entry.getValue().toString());
        root.add("selected", selected);
        JsonObject groups = new JsonObject();
        for (var entry : snapshot.groups().entrySet()) groups.add(entry.getKey().toString(), JSON.toJsonTree(entry.getValue()));
        root.add("groups", groups);
        byte[] output = (JSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8);
        if (output.length > MAX_BYTES) throw new IOException("Companion store exceeds byte limit");

        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        byte[] existing = Files.exists(absolute) ? readBounded(absolute) : null;
        boolean legacy = existing != null && decode(existing).legacy();
        Path temporary = parent.resolve("." + absolute.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(output);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (legacy) {
                Path backup = parent.resolve(absolute.getFileName() + ".pre-0.9-" + UUID.randomUUID() + ".bak");
                Files.write(backup, existing, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            moveWithRetry(temporary, absolute, (source, destination, options) ->
                Files.move(source, destination, options));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path destination, CopyOption... options) throws IOException;
    }

    /**
     * Windows can temporarily deny replacing markai.json while another process scans it.
     * Retry only sharing/permission errors, never destroy the existing save as a fallback.
     * Other failures (including malformed existing data) still propagate to the caller.
     */
    static void moveWithRetry(Path temporary, Path target, MoveOperation operation) throws IOException {
        AtomicFileReplace.replace(temporary, target, operation::move);
    }

    private Snapshot<T> decode(byte[] bytes) throws IOException {
        try {
            JsonElement root = StrictJson.parse(bytes);
            boolean legacy = root.isJsonArray();
            JsonArray records;
            Map<UUID, UUID> selections = new LinkedHashMap<>();
            Map<UUID, GroupChatState> groups = new LinkedHashMap<>();
            if (legacy) records = root.getAsJsonArray();
            else {
                if (!root.isJsonObject()) throw new IOException("Companion store must be an object or legacy array");
                JsonObject object = root.getAsJsonObject();
                JsonElement version = object.get("schemaVersion");
                if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || !version.getAsString().equals(Integer.toString(SCHEMA_VERSION)))
                    throw new IOException("Unsupported companion schema version");
                if (object.get("companions") == null || !object.get("companions").isJsonArray()
                    || object.get("selected") == null || !object.get("selected").isJsonObject())
                    throw new IOException("Missing companion store fields");
                records = object.getAsJsonArray("companions");
                for (var entry : object.getAsJsonObject("selected").entrySet()) {
                    if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                        throw new IOException("Selection must contain UUID strings");
                    UUID owner = parseUuid(entry.getKey());
                    UUID id = parseUuid(entry.getValue().getAsString());
                    if (selections.putIfAbsent(owner, id) != null) throw new IOException("Duplicate selection owner");
                }
                if (object.has("groups")) {
                    if (!object.get("groups").isJsonObject()) throw new IOException("Invalid owner groups");
                    for (var entry : object.getAsJsonObject("groups").entrySet()) {
                        if (!entry.getValue().isJsonObject()) throw new IOException("Invalid group state");
                        GroupChatState state = JSON.fromJson(entry.getValue(), GroupChatState.class);
                        if (state == null || groups.putIfAbsent(parseUuid(entry.getKey()), state) != null)
                            throw new IOException("Invalid group owner");
                    }
                }
            }
            if (records.size() > MAX_RECORDS || selections.size() > MAX_RECORDS)
                throw new IOException("Companion store exceeds record limit");
            List<T> companions = new ArrayList<>(records.size());
            for (JsonElement record : records) {
                if (!record.isJsonObject()) throw new IOException("Companion record must be an object");
                T companion = JSON.fromJson(record, recordType);
                if (companion == null) throw new IOException("Null companion record");
                companions.add(companion);
            }
            return new Snapshot<>(companions, selections, groups, legacy);
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("Invalid companion store JSON", error);
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) throw new IOException("Companion store exceeds byte limit");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Companion store exceeds byte limit");
            return bytes;
        }
    }

    private static UUID parseUuid(String value) throws IOException {
        UUID result = UUID.fromString(value);
        if (!result.toString().equalsIgnoreCase(value)) throw new IOException("Invalid UUID representation");
        return result;
    }

}
