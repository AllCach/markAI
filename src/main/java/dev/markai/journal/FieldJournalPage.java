package dev.markai.journal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small, owner-independent page projection. The server supplies only the requesting owner's journal. */
public final class FieldJournalPage {
    public static final int SPECIES_PAGE_SIZE = 6;
    public static final int NOTE_PAGE_SIZE = 2;
    public static final int BIOME_PAGE_SIZE = 4;

    private FieldJournalPage() { }

    public static Map<String, Object> snapshot(FieldJournal journal, String requestedSpeciesId,
            int requestedSpeciesOffset, int requestedNoteOffset, int requestedBiomeOffset) {
        List<String> ids = journal == null ? List.of() : journal.speciesIds();
        int speciesOffset = pageStart(requestedSpeciesOffset, ids.size(), SPECIES_PAGE_SIZE);
        List<Map<String, Object>> species = new ArrayList<>();
        for (int i = speciesOffset; i < Math.min(ids.size(), speciesOffset + SPECIES_PAGE_SIZE); i++) {
            FieldJournal.Entry entry = journal.entry(ids.get(i));
            species.add(Map.of("species_id", entry.speciesId, "observations", entry.observationCount,
                "stage", entry.stage().name()));
        }

        String selectedId = requestedSpeciesId == null ? "" : requestedSpeciesId;
        FieldJournal.Entry selected = journal == null ? null : journal.entry(selectedId);
        if (selected == null && !species.isEmpty()) {
            selectedId = ids.get(speciesOffset);
            selected = journal.entry(selectedId);
        }
        int noteTotal = selected == null ? 0 : selected.notes.size();
        int biomeTotal = selected == null ? 0 : selected.biomes.size();
        int noteOffset = pageStart(requestedNoteOffset, noteTotal, NOTE_PAGE_SIZE);
        int biomeOffset = pageStart(requestedBiomeOffset, biomeTotal, BIOME_PAGE_SIZE);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested_species_id", requestedSpeciesId == null ? "" : requestedSpeciesId);
        result.put("requested_species_offset", requestedSpeciesOffset);
        result.put("requested_note_offset", requestedNoteOffset);
        result.put("requested_biome_offset", requestedBiomeOffset);
        result.put("species", species);
        result.put("species_total", ids.size());
        result.put("species_offset", speciesOffset);
        result.put("selected_species_id", selected == null ? "" : selectedId);
        result.put("notes_total", noteTotal);
        result.put("notes_offset", noteOffset);
        result.put("biomes_total", biomeTotal);
        result.put("biomes_offset", biomeOffset);
        if (selected != null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("species_id", selected.speciesId);
            detail.put("observations", selected.observationCount);
            detail.put("stage", selected.stage().name());
            detail.put("first_observed_at", selected.firstObservedAt);
            detail.put("last_observed_at", selected.lastObservedAt);
            detail.put("biomes", selected.biomes.subList(biomeOffset,
                Math.min(biomeTotal, biomeOffset + BIOME_PAGE_SIZE)));
            detail.put("notes", selected.notes.subList(noteOffset,
                Math.min(noteTotal, noteOffset + NOTE_PAGE_SIZE)).stream().map(note ->
                    Map.of("id", note.id(), "text", note.text(), "created_at", note.createdAt())).toList());
            Map<String, String> knowledge = new LinkedHashMap<>();
            for (FieldJournal.Topic topic : FieldJournal.Topic.values()) {
                FieldJournal.Knowledge unlocked = journal.knowledge(selectedId, topic);
                if (unlocked != null) knowledge.put(topic.name().toLowerCase(Locale.ROOT), unlocked.translationKey());
            }
            detail.put("knowledge", knowledge);
            result.put("entry", detail);
        }
        return result;
    }

    private static int pageStart(int requested, int total, int size) {
        if (total == 0) return 0;
        return Math.min(Math.max(0, requested / size * size), (total - 1) / size * size);
    }
}
