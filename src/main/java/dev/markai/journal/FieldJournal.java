package dev.markai.journal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Per-owner field research and personal notes. The game/server layer owns persistence;
 * this class contains bounded, deterministic rules so they can be tested without Minecraft.
 */
public final class FieldJournal {
    public static final int MAX_SPECIES = 256;
    public static final int MAX_UNIQUE_ENTITIES_PER_SPECIES = 64;
    public static final int MAX_BIOMES_PER_SPECIES = 16;
    public static final int MAX_NOTES_PER_SPECIES = 32;
    public static final int MAX_NOTE_LENGTH = 512;
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long nextNoteId = 1;

    public enum ResearchStage { UNKNOWN, OBSERVED, STUDIED, DOCUMENTED }

    public enum Topic { OVERVIEW, BEHAVIOR, HABITAT, THREATS, TAMING }

    public record Knowledge(String translationKey, int observationsRequired) { }

    public record Note(long id, String text, long createdAt) { }

    public static final class Entry {
        public String speciesId;
        public long firstObservedAt;
        public long lastObservedAt;
        public int observationCount;
        public List<String> observedEntityIds = new ArrayList<>();
        public List<String> biomes = new ArrayList<>();
        public List<Note> notes = new ArrayList<>();

        public Entry() { }

        private Entry(String speciesId, long gameTime) {
            this.speciesId = speciesId;
            firstObservedAt = Math.max(0, gameTime);
            lastObservedAt = Math.max(0, gameTime);
        }

        public ResearchStage stage() {
            if (observationCount >= 8) return ResearchStage.DOCUMENTED;
            if (observationCount >= 3) return ResearchStage.STUDIED;
            if (observationCount >= 1) return ResearchStage.OBSERVED;
            return ResearchStage.UNKNOWN;
        }
    }

    /** Returns true only when this is a newly documented physical entity. */
    public boolean observe(String speciesId, UUID entityUuid, String biomeId, long gameTime) {
        if (!validId(speciesId) || entityUuid == null) return false;
        Entry entry = entries.get(speciesId);
        if (entry == null) {
            if (entries.size() >= MAX_SPECIES) return false;
            entry = new Entry(speciesId, gameTime);
            entries.put(speciesId, entry);
        }

        String entityId = entityUuid.toString();
        boolean newlyObserved = !entry.observedEntityIds.contains(entityId);
        if (newlyObserved) {
            if (entry.observedEntityIds.size() >= MAX_UNIQUE_ENTITIES_PER_SPECIES) return false;
            entry.observedEntityIds.add(entityId);
            entry.observationCount = entry.observedEntityIds.size();
            entry.lastObservedAt = Math.max(entry.lastObservedAt, Math.max(0, gameTime));
        }
        if (validId(biomeId) && !entry.biomes.contains(biomeId)
                && entry.biomes.size() < MAX_BIOMES_PER_SPECIES) {
            entry.biomes.add(biomeId);
        }
        return newlyObserved;
    }

    public boolean addNote(String speciesId, String text, long gameTime) {
        if (!validId(speciesId) || text == null) return false;
        String normalized = text.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_NOTE_LENGTH) return false;
        Entry entry = entries.get(speciesId);
        if (entry == null || entry.notes.size() >= MAX_NOTES_PER_SPECIES) return false;
        entry.notes.add(new Note(nextNoteId++, normalized, Math.max(0, gameTime)));
        return true;
    }

    public boolean removeNote(String speciesId, long noteId) {
        Entry entry = entries.get(speciesId);
        return entry != null && entry.notes.removeIf(note -> note.id() == noteId);
    }

    public Entry entry(String speciesId) {
        Entry entry = entries.get(speciesId);
        return entry == null ? null : copy(entry);
    }

    public List<Entry> entries() {
        return entries.values().stream().map(FieldJournal::copy).toList();
    }

    public int speciesCount() { return entries.size(); }

    public Knowledge knowledge(String speciesId, Topic topic) {
        Entry entry = entries.get(speciesId);
        if (entry == null || topic == null || entry.observationCount < threshold(topic)) return null;
        String base = "markai.journal." + speciesId.replace(':', '.') + ".";
        String suffix = switch (topic) {
            case OVERVIEW -> "overview";
            case BEHAVIOR -> "behavior";
            case HABITAT -> "habitat";
            case THREATS -> "threats";
            case TAMING -> "taming";
        };
        String key = base + suffix;
        if (!KNOWLEDGE_KEYS.contains(key)) return null;
        return new Knowledge(key, threshold(topic));
    }

    private static int threshold(Topic topic) {
        return switch (topic) {
            case OVERVIEW -> 1;
            case BEHAVIOR -> 3;
            case HABITAT, THREATS -> 5;
            case TAMING -> 8;
        };
    }

    private static final Set<String> KNOWLEDGE_KEYS = Set.of(
        "markai.journal.minecraft.wolf.overview",
        "markai.journal.minecraft.wolf.behavior",
        "markai.journal.minecraft.wolf.habitat",
        "markai.journal.minecraft.wolf.threats",
        "markai.journal.minecraft.wolf.taming",
        "markai.journal.minecraft.chicken.overview",
        "markai.journal.minecraft.chicken.behavior",
        "markai.journal.minecraft.chicken.habitat",
        "markai.journal.minecraft.chicken.threats",
        "markai.journal.minecraft.chicken.taming",
        "markai.journal.minecraft.zombie.overview",
        "markai.journal.minecraft.zombie.behavior",
        "markai.journal.minecraft.zombie.habitat",
        "markai.journal.minecraft.zombie.threats",
        "markai.journal.minecraft.zombie.taming"
    );

    private static boolean validId(String value) {
        return value != null && value.length() <= 128 && RESOURCE_ID.matcher(value).matches();
    }

    private static Entry copy(Entry source) {
        Entry target = new Entry();
        target.speciesId = source.speciesId;
        target.firstObservedAt = source.firstObservedAt;
        target.lastObservedAt = source.lastObservedAt;
        target.observationCount = source.observationCount;
        target.observedEntityIds = new ArrayList<>(source.observedEntityIds);
        target.biomes = new ArrayList<>(source.biomes);
        target.notes = new ArrayList<>(source.notes);
        return target;
    }
}
