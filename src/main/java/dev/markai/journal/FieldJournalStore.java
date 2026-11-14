package dev.markai.journal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Versioned, size-bounded, atomic world persistence for owner-scoped Field Journals. */
public final class FieldJournalStore {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_OWNERS = 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Map<UUID, FieldJournal> read(Path path) throws IOException {
        if (!Files.exists(path)) return new LinkedHashMap<>();
        long size = Files.size(path);
        if (size < 2 || size > MAX_BYTES) throw new IOException("Field journal file size is invalid");
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            throw new IOException("Field journal file is malformed", failure);
        }
        if (!parsed.isJsonObject()) throw new IOException("Field journal root must be an object");
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("schemaVersion") || !root.get("schemaVersion").isJsonPrimitive()
                || !root.get("schemaVersion").getAsJsonPrimitive().isNumber()
                || root.get("schemaVersion").getAsInt() != SCHEMA_VERSION)
            throw new IOException("Unsupported field journal schema version");
        if (!root.has("owners") || !root.get("owners").isJsonObject())
            throw new IOException("Field journal owner map is missing");
        JsonObject owners = root.getAsJsonObject("owners");
        if (owners.size() > MAX_OWNERS) throw new IOException("Too many field journal owners");
        Map<UUID, FieldJournal> result = new LinkedHashMap<>();
        for (var item : owners.entrySet()) {
            UUID owner;
            try {
                owner = UUID.fromString(item.getKey());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid field journal owner UUID", invalid);
            }
            if (!item.getValue().isJsonObject()) throw new IOException("Invalid field journal owner record");
            FieldJournal journal;
            try {
                journal = JSON.fromJson(item.getValue(), FieldJournal.class);
            } catch (RuntimeException failure) {
                throw new IOException("Could not decode field journal", failure);
            }
            if (journal == null) throw new IOException("Empty field journal owner record");
            journal.normalize();
            result.put(owner, journal);
        }
        return result;
    }

    public void write(Path path, Map<UUID, FieldJournal> journals) throws IOException {
        if (journals == null || journals.size() > MAX_OWNERS)
            throw new IOException("Invalid field journal owner map");
        JsonObject owners = new JsonObject();
        for (var item : journals.entrySet()) {
            if (item.getKey() == null || item.getValue() == null)
                throw new IOException("Field journal owner and data are required");
            FieldJournal journal = item.getValue();
            journal.normalize();
            owners.add(item.getKey().toString(), JSON.toJsonTree(journal, FieldJournal.class));
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.add("owners", owners);
        byte[] output = (JSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8);
        if (output.length > MAX_BYTES) throw new IOException("Field journal exceeds byte limit");

        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = parent.resolve("." + absolute.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
