# Field Journal - implementation contract

## Player-facing goal

The Field Journal is an owner-scoped in-game researcher notebook and bestiary. It combines discoveries about living creatures with player-written notes. It is separate from a companion's dialogue memories and separate from companion XP.

## Research rules

- A discovery is keyed by the vanilla resource ID and physical entity UUID.
- Seeing the same entity again does not advance research, even after reload or across a long time gap.
- New biome information may be recorded when an already-known entity is seen in another biome, but this does not grant another sighting.
- Research stages are progressive: 1 unique entity unlocks the overview, 3 unlock behavior, 5 unlock habitat and threats, and 8 unlock taming / interaction notes.
- The initial curated catalog covers wolf, chicken and zombie. Unknown species may be counted, but the journal must not fabricate species-specific facts.
- Player notes are private to the owner and bounded to 32 notes per species, 512 characters per note.
- The model bounds the journal to 256 species, 64 tracked entity UUIDs and 16 biomes per species.

## Architecture boundaries

- FieldJournal contains deterministic rules only. Minecraft entity scanning, owner permissions, world save/load, localization and UI live in the integration layer.
- The journal never asks the language model to invent factual bestiary entries. Curated facts are surfaced by translation keys.
- Journal research does not mutate companion progression or companion memory.
- Journal persistence is a separate versioned, size-bounded world file with atomic replacement. Journal write failures do not disable companion commands or overwrite the existing file.
- UI mutations travel through server-validated, owner-scoped networking. Clients cannot author discoveries.

## Current implementation

The current branch includes periodic nearby-mob observations, unique-entity research stages, biome tracking, a versioned owner-scoped save file, English/Russian curated facts for wolf/chicken/zombie, a Journal UI tab, and validated personal-note add/delete actions. Deterministic tests cover progression thresholds, duplicate sightings, bounds, persistence round-trips, malformed files and network input validation.

Known UI boundary: each snapshot currently includes at most 16 species and four notes per species to keep the network payload bounded. The model stores more than the UI currently exposes; pagination is a follow-up requirement before considering the journal fully complete for 1.0. In-game client/server behavior and save migration still require manual GameTest coverage.
