# Field Journal - implementation contract

## Player-facing goal

The Field Journal is an owner-scoped in-game researcher notebook and bestiary. It should combine discoveries about living creatures with player-written notes. It is separate from a companion's dialogue memories and separate from companion XP.

## Research rules

- A discovery is keyed by the vanilla resource ID and the physical entity UUID.
- Seeing the same entity again does not advance research, even after reload or across a long time gap.
- New biome information may be recorded when an already-known entity is seen in another biome, but this does not grant another sighting.
- Research stages are progressive: 1 unique entity unlocks the overview, 3 unlock behavior, 5 unlock habitat and threats, and 8 unlock taming / interaction notes.
- The first curated catalog covers wolf, chicken and zombie. Unknown species may be counted, but the journal must not fabricate species-specific facts.
- Player notes are private to the owner and bounded to 32 notes per species, 512 characters per note.
- The initial model bounds the journal to 256 species, 64 tracked entity UUIDs and 16 biomes per species.

## Architecture boundaries

- FieldJournal contains deterministic rules only. Minecraft entity scanning, owner permissions, world save/load, localization and UI belong to the integration layer.
- The journal must never call the language model to invent factual bestiary entries. Catalog entries are curated and surfaced by translation keys.
- Journal research must not mutate companion progression or companion memory.
- Journal write failure must not interrupt gameplay. Persistence must be bounded, versioned, and use atomic replacement without destroying the previous save on failure.
- UI mutations must travel through server-validated, owner-scoped networking. Clients never author discoveries.

## Current slice

The first slice adds the pure research model and deterministic tests for staged unlocks, duplicate-entity anti-farming, note limits, defensive snapshots and collection bounds. It is not yet a player-visible feature: persistence, world observation hooks, localized catalog text and UI/network integration remain separate follow-up slices.
