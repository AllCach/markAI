package dev.markai.journal;

import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Map;

/** Deterministic checks for bounded research progression and player notes. */
public final class FieldJournalTest {
    private static int checks;

    public static void main(String[] args) {
        researchUnlocksGradually();
        duplicateEntitiesCannotFarmResearch();
        notesAreBoundedAndEditable();
        snapshotsCannotMutateJournal();
        inputAndStorageLimitsAreEnforced();
        persistenceRoundTripAndValidation();
        System.out.println("FieldJournalTest: " + checks + " checks passed");
    }

    private static void researchUnlocksGradually() {
        FieldJournal journal = new FieldJournal();
        String wolf = "minecraft:wolf";
        check(journal.knowledge(wolf, FieldJournal.Topic.OVERVIEW) == null, "Unknown species has no unlocked knowledge");
        journal.observe(wolf, UUID.randomUUID(), "minecraft:plains", 10);
        check(journal.entry(wolf).stage() == FieldJournal.ResearchStage.OBSERVED, "First sighting unlocks overview");
        check(journal.knowledge(wolf, FieldJournal.Topic.OVERVIEW) != null, "Overview unlocks at one observation");
        check(journal.knowledge(wolf, FieldJournal.Topic.BEHAVIOR) == null, "Behavior stays hidden after one observation");
        journal.observe(wolf, UUID.randomUUID(), "minecraft:forest", 20);
        check(journal.knowledge(wolf, FieldJournal.Topic.BEHAVIOR) == null, "Behavior stays hidden before three observations");
        journal.observe(wolf, UUID.randomUUID(), "minecraft:taiga", 30);
        check(journal.entry(wolf).stage() == FieldJournal.ResearchStage.STUDIED, "Three sightings mark a species studied");
        check(journal.knowledge(wolf, FieldJournal.Topic.BEHAVIOR) != null, "Behavior unlocks at three observations");
        journal.observe(wolf, UUID.randomUUID(), "minecraft:snowy_taiga", 40);
        journal.observe(wolf, UUID.randomUUID(), "minecraft:grove", 50);
        check(journal.knowledge(wolf, FieldJournal.Topic.HABITAT) != null, "Habitat unlocks at five observations");
        check(journal.knowledge(wolf, FieldJournal.Topic.THREATS) != null, "Threats unlock at five observations");
        for (int i = 0; i < 3; i++) journal.observe(wolf, UUID.randomUUID(), "minecraft:plains", 60 + i);
        check(journal.entry(wolf).stage() == FieldJournal.ResearchStage.DOCUMENTED, "Eight sightings complete field documentation");
        check(journal.knowledge(wolf, FieldJournal.Topic.TAMING) != null, "Taming notes unlock at eight observations");
    }

    private static void duplicateEntitiesCannotFarmResearch() {
        FieldJournal journal = new FieldJournal();
        UUID sameWolf = UUID.randomUUID();
        check(journal.observe("minecraft:wolf", sameWolf, "minecraft:plains", 10), "First unique entity is recorded");
        check(!journal.observe("minecraft:wolf", sameWolf, "minecraft:forest", 999), "Re-observing the same UUID grants no research");
        FieldJournal.Entry entry = journal.entry("minecraft:wolf");
        check(entry.observationCount == 1, "Duplicate UUID does not increase observation count");
        check(entry.biomes.size() == 2, "New biome can be recorded without farming a sighting");
        check(entry.lastObservedAt == 10, "Duplicate UUID does not rewrite last unique observation time");
    }

    private static void notesAreBoundedAndEditable() {
        FieldJournal journal = new FieldJournal();
        journal.observe("minecraft:chicken", UUID.randomUUID(), "minecraft:plains", 1);
        check(journal.addNote("minecraft:chicken", "  Flocks stay near the farm.  ", 2), "Trimmed personal note is saved");
        check("Flocks stay near the farm.".equals(journal.entry("minecraft:chicken").notes.get(0).text()), "Notes are trimmed");
        check(!journal.addNote("minecraft:zombie", "No entry exists yet", 3), "Cannot add notes to an undiscovered species");
        check(!journal.addNote("minecraft:chicken", " ".repeat(4), 3), "Blank note is rejected");
        check(!journal.addNote("minecraft:chicken", "x".repeat(FieldJournal.MAX_NOTE_LENGTH + 1), 3), "Oversized note is rejected");
        long id = journal.entry("minecraft:chicken").notes.get(0).id();
        check(journal.removeNote("minecraft:chicken", id), "Existing note can be removed");
        check(!journal.removeNote("minecraft:chicken", id), "Deleted note cannot be removed twice");
        for (int i = 0; i < FieldJournal.MAX_NOTES_PER_SPECIES; i++)
            check(journal.addNote("minecraft:chicken", "Note " + i, i), "Note within per-species limit is accepted");
        check(!journal.addNote("minecraft:chicken", "One too many", 99), "Per-species note limit is enforced");
    }

    private static void snapshotsCannotMutateJournal() {
        FieldJournal journal = new FieldJournal();
        journal.observe("minecraft:zombie", UUID.randomUUID(), "minecraft:plains", 1);
        FieldJournal.Entry copy = journal.entry("minecraft:zombie");
        copy.observationCount = 64;
        copy.biomes.clear();
        check(journal.entry("minecraft:zombie").observationCount == 1, "Entry accessor returns a defensive copy");
        check(journal.entry("minecraft:zombie").biomes.size() == 1, "Biome list cannot be mutated through a snapshot");
        check(journal.entries().size() == 1, "Entries snapshot contains discovered species");
    }

    private static void inputAndStorageLimitsAreEnforced() {
        FieldJournal journal = new FieldJournal();
        check(!journal.observe("WOLF", UUID.randomUUID(), "minecraft:plains", 1), "Malformed resource ID is rejected");
        check(!journal.observe("minecraft:wolf", null, "minecraft:plains", 1), "Missing entity UUID is rejected");
        for (int i = 0; i < FieldJournal.MAX_UNIQUE_ENTITIES_PER_SPECIES; i++)
            journal.observe("minecraft:wolf", UUID.randomUUID(), "minecraft:plains", i);
        check(!journal.observe("minecraft:wolf", UUID.randomUUID(), "minecraft:plains", 999), "Unique entity tracking is capped");
        check(journal.entry("minecraft:wolf").observationCount == FieldJournal.MAX_UNIQUE_ENTITIES_PER_SPECIES,
            "Research count stays within entity tracking cap");
        for (int i = 0; i < FieldJournal.MAX_SPECIES - 1; i++)
            journal.observe("markai:unknown_" + i, UUID.randomUUID(), "minecraft:plains", i);
        check(journal.speciesCount() == FieldJournal.MAX_SPECIES, "Species collection stays within global cap");
        check(!journal.observe("markai:overflow", UUID.randomUUID(), "minecraft:plains", 999),
            "Species beyond the global cap are rejected");
        check(journal.knowledge("markai:unknown_1", FieldJournal.Topic.OVERVIEW) == null,
            "Unknown species do not expose fabricated facts");
    }

    private static void persistenceRoundTripAndValidation() {
        try {
            Path directory = Files.createTempDirectory("markai-field-journal-");
            Path path = directory.resolve("field-journal.json");
            UUID owner = UUID.randomUUID();
            FieldJournal original = new FieldJournal();
            UUID wolf = UUID.randomUUID();
            original.observe("minecraft:wolf", wolf, "minecraft:taiga", 123);
            original.addNote("minecraft:wolf", "Pack tracks near the ridge", 124);
            FieldJournalStore store = new FieldJournalStore();
            store.write(path, Map.of(owner, original));
            Map<UUID, FieldJournal> loaded = store.read(path);
            check(loaded.size() == 1 && loaded.containsKey(owner), "Owner-scoped journal survives round-trip");
            FieldJournal.Entry entry = loaded.get(owner).entry("minecraft:wolf");
            check(entry.observationCount == 1 && entry.observedEntityIds.contains(wolf.toString()),
                "Unique entity identity survives round-trip");
            check(entry.biomes.contains("minecraft:taiga") && entry.notes.size() == 1,
                "Biome research and personal notes survive round-trip");
            check(loaded.get(owner).addNote("minecraft:wolf", "Second note", 125),
                "Restored note IDs continue without collision");

            Files.writeString(path, "{\"schemaVersion\":99,\"owners\":{}}", StandardCharsets.UTF_8);
            boolean rejected = false;
            try { store.read(path); } catch (IOException expected) { rejected = true; }
            check(rejected, "Unknown schema version is rejected");

            Files.writeString(path, "not json", StandardCharsets.UTF_8);
            rejected = false;
            try { store.read(path); } catch (IOException expected) { rejected = true; }
            check(rejected, "Malformed journal file is rejected");

            Files.write(path, new byte[FieldJournalStore.MAX_BYTES + 1]);
            rejected = false;
            try { store.read(path); } catch (IOException expected) { rejected = true; }
            check(rejected, "Oversized journal file is rejected");
        } catch (Exception error) {
            throw new AssertionError("Persistence checks failed", error);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
