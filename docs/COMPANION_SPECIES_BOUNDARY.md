# Companion species boundary — 0.11.0

This document describes the current multi-species implementation carried forward from the 0.10.5 integration branch. The code has been written through GitHub file operations; a local Gradle build and in-game validation have not yet been run.

## Current implementation

- Persistent companion records include logical identity, owner UUID, physical entity UUID and species ID.
- `CompanionRegistry<Companion>` indexes logical identity, owner, physical entity and selection; `CompanionStore<Companion>` persists the world-level `markai.json`.
- Missing/blank species on older records defaults to `minecraft:wolf`; unknown species are rejected during load validation.
- `CompanionSpecies` defines explicit capabilities for Wolf and Chicken.
- The existing Wolf path retains its combat, inventory, training and trick behavior.
- Chicken/Mark has a separate minimal runtime: spawn, follow/stay/summon/cancel/remove, species-aware status, selected-companion handling, chat context and ambient dialogue.
- Chicken does not inherit Wolf combat, inventory or wolf tricks. Wolf-only actions must be rejected for Chicken.
- Ambient Chicken dialogue uses a separate context and does not replay player chat history.
- Ordinary wild chickens are not registered as MarkAI companions unless explicitly spawned/registered through the companion flow.

## 0.11.0 acceptance criteria

1. Run Gradle build and all existing `check` tasks, including `companionSpeciesTest`.
2. Verify old `markai.json` records without a species field still load as Wolf and retain their identity, memory, training and settings.
3. Verify Wolf and Chicken coexist for one owner; selection, status, chat, commands and saved state remain isolated.
4. Verify save/reload, death/respawn, entity unload/reload, dimension changes and owner disconnect/reconnect without duplicates.
5. Verify a Chicken never reaches Wolf-only inventory, combat, training or trick code.
6. Verify async AI replies are discarded when the companion entity or world context is no longer valid.
7. Test multi-player ownership isolation and confirm another player cannot command or remove someone else's companion.
8. Test the actual mod in Minecraft before considering a release.

## Known limitations until verified

- The current Chicken runtime is a minimal companion prototype, not the full Living World behavior design.
- No successful build, automated test run or in-game validation is claimed for this integration yet.
- Do not merge into `dev` or `main` until the acceptance criteria are checked.
