package dev.markai;

import com.google.gson.Gson;
import dev.markai.companion.CompanionStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Read-only audit of real world files. Every migration write happens on a temporary copy. */
public final class WorldSaveAudit {
    private static final Gson JSON = new Gson();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass a manifest of world markai.json paths");
        List<String> sources = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        var store = new CompanionStore<>(MarkAIMod.Companion.class);
        int checked = 0, legacy = 0, records = 0;
        for (String source : sources) {
            if (source.isBlank()) continue;
            Path original = Path.of(source);
            byte[] bytes = Files.readAllBytes(original);
            Path directory = Files.createTempDirectory("markai-world-audit-");
            try {
                Path copy = directory.resolve("markai.json");
                Files.write(copy, bytes);
                var before = store.read(copy);
                store.write(copy, before);
                var after = store.read(copy);
                if (before.companions().size() != after.companions().size()
                        || !before.selected().equals(after.selected())
                        || !before.groups().equals(after.groups())
                        || !JSON.toJsonTree(before.companions()).equals(JSON.toJsonTree(after.companions())))
                    throw new AssertionError("A copied world changed companion data during round-trip");
                if (before.legacy()) {
                    try (var backups = Files.list(directory)) {
                        var backup = backups.filter(path -> path.getFileName().toString().contains(".pre-0.9-")).findFirst();
                        if (backup.isEmpty() || !Arrays.equals(bytes, Files.readAllBytes(backup.get())))
                            throw new AssertionError("Legacy migration did not retain exact original bytes");
                    }
                    legacy++;
                }
                if (!Arrays.equals(bytes, Files.readAllBytes(original)))
                    throw new AssertionError("Original world file changed during read-only audit");
                checked++;
                records += before.companions().size();
            } finally {
                try (var files = Files.list(directory)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                }
                Files.deleteIfExists(directory);
            }
        }
        System.out.println("World save copies: " + checked + " files, " + legacy
            + " legacy, " + records + " companion records; originals unchanged");
    }
}
