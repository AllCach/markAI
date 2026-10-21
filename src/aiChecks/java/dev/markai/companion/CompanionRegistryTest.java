package dev.markai.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CompanionRegistryTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, Class<? extends Exception> expected, String message) throws Exception {
        checks++;
        try { action.run(); } catch (Exception error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(message + ": wrong exception", error);
        }
        throw new AssertionError(message + ": accepted invalid input");
    }

    public static final class Saved {
        UUID id, owner, entity;
        String name;
        JsonObject memory, training;
        JsonArray inventory;
        Saved(UUID owner, String name) {
            this.id = UUID.randomUUID(); this.owner = owner; this.entity = UUID.randomUUID(); this.name = name;
            memory = JsonParser.parseString("{\"reports\":[{\"text\":\"Берёзовый дом, синяя крыша\",\"tick\":125}],\"history\":[{\"role\":\"user\",\"content\":\"бро\"}],\"emotion\":{\"trust\":0.75}}").getAsJsonObject();
            training = JsonParser.parseString("{\"experience\":17,\"lessons\":{\"BARK\":3,\"FETCH\":1}}").getAsJsonObject();
            inventory = JsonParser.parseString("[{\"Slot\":0,\"id\":\"minecraft:beef\",\"count\":4,\"components\":{\"minecraft:custom_name\":\"Обед\"}}]").getAsJsonArray();
        }
    }

    private static CompanionRegistry<Saved> registry() { return new CompanionRegistry<>(c -> c.id, c -> c.owner, c -> c.entity); }

    public static void main(String[] args) throws Exception {
        registryIdentities();
        persistence();
        System.out.println("Companion registry/store: " + checks + " checks passed");
    }

    private static void registryIdentities() throws Exception {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        Saved walk = new Saved(alice, "Walk"), mark = new Saved(alice, "Марк"), other = new Saved(bob, "Рыжий");
        var registry = registry();
        registry.add(walk); registry.add(mark); registry.add(other);
        check(registry.values().size() == 3 && registry.owned(alice).equals(List.of(walk, mark)), "Same-owner companions retain separate stable identities and order");
        check(registry.selected(alice) == walk && registry.selected(bob) == other, "Each owner starts with their own first companion");
        check(registry.get(mark.id) == mark && registry.byEntity(mark.entity) == mark, "ID and entity lookups use independent indexes");
        check(!registry.select(bob, walk.id) && registry.selected(bob) == other, "Owner cannot select another player's companion");
        check(registry.select(alice, mark.id) && registry.selected(alice) == mark && registry.selected(bob) == other, "Changing one selection leaves another owner unchanged");
        check(!registry.select(alice, UUID.randomUUID()), "Missing companion cannot be selected");
        var oldValues = registry.values();
        var oldOwned = registry.owned(alice);
        var oldSelected = registry.selections();
        rejects(() -> oldValues.clear(), UnsupportedOperationException.class, "Values are immutable");
        rejects(() -> oldOwned.clear(), UnsupportedOperationException.class, "Owner list is immutable");
        rejects(() -> oldSelected.clear(), UnsupportedOperationException.class, "Selection snapshot is immutable");

        UUID oldEntity = mark.entity;
        mark.entity = UUID.randomUUID();
        registry.add(mark);
        check(registry.byEntity(oldEntity) == null && registry.byEntity(mark.entity) == mark, "Respawn rebinds even when a companion instance is mutated in place");
        check(registry.selected(alice) == mark && registry.owned(alice).equals(List.of(walk, mark)), "Respawn preserves selection and ordering");
        Saved replacement = new Saved(alice, "Марк новый"); replacement.id = mark.id;
        registry.add(replacement);
        check(registry.get(mark.id) == replacement && registry.byEntity(mark.entity) == null, "Replacing a record cleans its old entity index");
        check(oldValues.size() == 3 && oldOwned.equals(List.of(walk, mark)), "Earlier collection snapshots do not change after upsert");

        Saved stolenId = new Saved(bob, "Чужой"); stolenId.id = walk.id;
        rejects(() -> registry.add(stolenId), IllegalArgumentException.class, "Stable companion ID cannot change owner");
        Saved duplicateEntity = new Saved(bob, "Копия"); duplicateEntity.entity = walk.entity;
        rejects(() -> registry.add(duplicateEntity), IllegalArgumentException.class, "Two companions cannot reference one entity");
        check(registry.get(walk.id) == walk && registry.byEntity(walk.entity) == walk && registry.owned(bob).size() == 1, "Rejected additions leave all indexes intact");
        for (int i = 2; i < CompanionRegistry.MAX_COMPANIONS_PER_OWNER; i++) registry.add(new Saved(alice, "Волк " + i));
        rejects(() -> registry.add(new Saved(alice, "Девятый")), IllegalArgumentException.class, "Per-owner companion bound enforced");
        registry.add(replacement);
        check(registry.owned(alice).size() == 8, "Existing identity can update at the owner limit");
        registry.add(new Saved(bob, "Второй"));
        check(registry.owned(bob).size() == 2, "A full owner does not limit another owner");

        registry.restoreSelections(Map.of(alice, replacement.id, bob, other.id));
        var selections = registry.selections();
        Map<UUID, UUID> invalid = new LinkedHashMap<>();
        invalid.put(alice, walk.id); invalid.put(bob, walk.id);
        rejects(() -> registry.restoreSelections(invalid), IllegalArgumentException.class, "Whole restore rejects foreign selection");
        check(registry.selections().equals(selections), "Invalid restore is atomic and does not partially change selections");
        registry.restoreSelections(Map.of());
        check(registry.selected(alice) == walk && registry.selected(bob) == other, "Missing persisted choices default to each owner's first companion");
        Saved missingId = new Saved(alice, "Нет ID"); missingId.id = null;
        Saved missingOwner = new Saved(alice, "Нет хозяина"); missingOwner.owner = null;
        Saved missingEntity = new Saved(alice, "Нет сущности"); missingEntity.entity = null;
        rejects(() -> registry.add(missingId), IllegalArgumentException.class, "Null companion identity rejected");
        rejects(() -> registry.add(missingOwner), IllegalArgumentException.class, "Null owner rejected");
        rejects(() -> registry.add(missingEntity), IllegalArgumentException.class, "Null entity rejected");
        rejects(() -> registry.get(null), IllegalArgumentException.class, "Null ID lookup rejected");
        rejects(() -> registry.select(alice, null), IllegalArgumentException.class, "Null selection ID rejected");
        check(registry.contains(walk.id) && !registry.contains(UUID.randomUUID()), "Contains checks companion identity");
        check(registry.owned(UUID.randomUUID()).isEmpty() && registry.selected(UUID.randomUUID()) == null, "Unknown owner has no companion");
        registry.clear();
        check(registry.values().isEmpty() && registry.selections().isEmpty() && registry.byEntity(walk.entity) == null && registry.owned(alice).isEmpty(), "Clear removes every index and selection");
    }

    private static void persistence() throws Exception {
        Path dir = Files.createTempDirectory("markai-registry-");
        try {
            Path path = dir.resolve("companions.json");
            var store = new CompanionStore<>(Saved.class);
            check(store.read(path).companions().isEmpty() && !store.read(path).legacy(), "Missing file is an empty current snapshot");
            UUID owner = UUID.randomUUID();
            Saved walk = new Saved(owner, "Walk"), mark = new Saved(owner, "Марк");
            var gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
            byte[] legacy = (" \n" + gson.toJson(List.of(walk, mark)) + "\r\n ").getBytes(StandardCharsets.UTF_8);
            Files.write(path, legacy);
            var old = store.read(path);
            check(old.legacy() && old.companions().size() == 2 && old.selected().isEmpty(), "Legacy array imports every record without fabricated selection");
            check(old.companions().get(0).memory.equals(walk.memory) && old.companions().get(1).inventory.equals(mark.inventory), "Legacy import retains nested memory and component inventory data");
            rejects(() -> old.companions().clear(), UnsupportedOperationException.class, "Loaded records list is immutable");
            store.write(path, old.companions(), Map.of(owner, mark.id));
            var current = store.read(path);
            check(!current.legacy() && current.selected().get(owner).equals(mark.id) && current.companions().size() == 2, "Versioned roundtrip retains same-owner records and selected ID");
            check(current.companions().get(0).memory.equals(walk.memory) && current.companions().get(0).training.equals(walk.training)
                && current.companions().get(1).inventory.equals(mark.inventory) && current.companions().get(1).name.equals("Марк"), "New format retains memories, skills, inventory components and Unicode names");
            var loaded = registry();
            for (Saved saved : current.companions()) loaded.add(saved);
            loaded.restoreSelections(current.selected());
            check(loaded.selected(owner).id.equals(mark.id) && loaded.owned(owner).size() == 2, "Persisted selection restores against the identity registry");
            List<Path> backups;
            try (var files = Files.list(dir)) { backups = files.filter(p -> p.getFileName().toString().contains(".pre-0.9-")).toList(); }
            check(backups.size() == 1 && java.util.Arrays.equals(Files.readAllBytes(backups.getFirst()), legacy), "First migration preserves exact legacy bytes in a separate backup");
            store.write(path, current);
            try (var files = Files.list(dir)) {
                check(files.filter(p -> p.getFileName().toString().contains(".pre-0.9-")).count() == 1, "Writing the new schema does not create repeated migration backups");
            }
            try (var files = Files.list(dir)) { check(files.noneMatch(p -> p.toString().endsWith(".tmp")), "Atomic writes leave no temporary sibling"); }
            byte[] committed = Files.readAllBytes(path);
            Saved huge = new Saved(owner, "Очень длинная память"); huge.memory.addProperty("large", "x".repeat(CompanionStore.MAX_BYTES));
            rejects(() -> store.write(path, List.of(huge), Map.of()), IOException.class, "Oversized write rejected before replacing the committed file");
            check(java.util.Arrays.equals(Files.readAllBytes(path), committed), "Failed write leaves the existing save byte-for-byte unchanged");

            // Simulate Windows sharing violations deterministically, regardless of the host OS.
            Path moveSource = dir.resolve("transient.tmp"), moveTarget = dir.resolve("transient.json");
            Files.writeString(moveSource, "new state");
            Files.writeString(moveTarget, "old state");
            var tries = new java.util.concurrent.atomic.AtomicInteger();
            CompanionStore.moveWithRetry(moveSource, moveTarget, (source, destination, options) -> {
                if (tries.incrementAndGet() <= 2) throw new java.nio.file.AccessDeniedException(destination.toString());
                Files.move(source, destination, options);
            });
            check(tries.get() == 3 && Files.readString(moveTarget).equals("new state")
                && !Files.exists(moveSource), "Transient sharing violations retry and eventually commit the new state");

            Files.writeString(moveSource, "unsaved state");
            tries.set(0);
            rejects(() -> CompanionStore.moveWithRetry(moveSource, moveTarget, (source, destination, options) -> {
                tries.incrementAndGet();
                throw new java.nio.file.AccessDeniedException(destination.toString());
            }), java.nio.file.AccessDeniedException.class, "Persistent sharing violations must not be ignored");
            check(tries.get() == 6 && Files.readString(moveTarget).equals("new state")
                && Files.readString(moveSource).equals("unsaved state"),
                "Exhausted retries preserve the committed save and the uncommitted source");

            rejects(() -> current.selected().clear(), UnsupportedOperationException.class, "Loaded selection map is immutable");

            Path bad = dir.resolve("bad.json");
            for (String malformed : List.of("[{}] trailing", "[/*comment*/{}]", "[{'id':'x'}]", "[NaN]", "[{} ,]", "{\"schemaVersion\":1,\"schemaVersion\":1,\"companions\":[],\"selected\":{}}", "{\"schemaVersion\":2,\"companions\":[],\"selected\":{}}", "{\"schemaVersion\":\"1\",\"companions\":[],\"selected\":{}}", "{\"schemaVersion\":1,\"companions\":[],\"selected\":{\"bad\":\"uuid\"}}", "[null]", "null")) {
                Files.writeString(bad, malformed);
                rejects(() -> store.read(bad), IOException.class, "Malformed or unsupported save rejected: " + malformed);
            }
            Files.write(bad, new byte[] {(byte) 0xC3, 0x28});
            rejects(() -> store.read(bad), IOException.class, "Malformed UTF-8 rejected");
            Files.writeString(bad, "[".repeat(130) + "0" + "]".repeat(130));
            rejects(() -> store.read(bad), IOException.class, "Excessive nesting rejected");
            Files.writeString(bad, "[" + "{},".repeat(CompanionStore.MAX_RECORDS) + "{}]");
            rejects(() -> store.read(bad), IOException.class, "Record count bounded before decoding DTOs");
            Files.write(bad, new byte[CompanionStore.MAX_BYTES + 1]);
            rejects(() -> store.read(bad), IOException.class, "File byte count bounded before parsing");
            Path corrupt = dir.resolve("corrupt-existing.json"); Files.writeString(corrupt, "BROKEN");
            rejects(() -> store.write(corrupt, current), IOException.class, "Malformed existing save is not silently overwritten");
            check(Files.readString(corrupt).equals("BROKEN"), "Rejected migration preserves original malformed save");
            rejects(() -> new CompanionStore.Snapshot<>(new ArrayList<>(java.util.Collections.nCopies(CompanionStore.MAX_RECORDS + 1, walk)), Map.of(), false), IllegalArgumentException.class, "Writes also enforce record count");
        } finally {
            // Only this test-created directory is removed; no player world or installation is involved.
            try (var files = Files.walk(dir)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
