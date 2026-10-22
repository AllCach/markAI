# Companion species boundary — 0.11.1

This document describes the species boundary in `dev-draft` (project source version 0.11.1). The latest refactor and item-navigation regression have not been built or validated in-game yet.

## Current implementation

- Persistent companion records include logical identity, owner UUID, physical entity UUID and species ID.
- `CompanionRegistry<Companion>` indexes logical identity, owner, physical entity and selection; `CompanionStore<Companion>` persists the world-level `markai.json`.
- Missing/blank species on older records defaults to `minecraft:wolf`; unknown species are rejected during load validation.
- `CompanionSpecies` defines explicit capabilities for Wolf and Chicken.
- The existing Wolf path retains its combat, inventory, training and trick behavior.
- Chicken/Mark has a separate runtime: it adopts the existing chicken through Shift + right-click with seeds, supports the chicken-specific movement commands and species-aware status, selected-companion handling, chat context and ambient dialogue. No player command or GUI button spawns a chicken companion.
- Chicken does not inherit Wolf combat, inventory or wolf tricks. The personal UI must hide wolf-only controls for Chicken, and server actions must not create a Wolf when `spawn` is invoked with a live Chicken selected.
- Ambient Chicken dialogue uses a separate context and does not replay player chat history.
- Ordinary wild chickens are not registered automatically. Registration is triggered only by the in-world Shift + right-click interaction with seeds; the same chicken entity is adopted, never replaced by a spawned companion.

## 0.11.1 UI and controls review

- Species IDs use canonical values such as `minecraft:chicken`; client screens must compare canonical IDs rather than shortened display labels.
- The Chicken profile offers only Chicken-supported commands, while the Wolf profile keeps Wolf-specific actions.
- With no selected companion, the profile and command tabs show taming instructions instead of actionable commands.
- Companion creation belongs to in-world taming, not UI buttons. Wolves register after vanilla bone taming; wild chickens register when the owner sneaks and right-clicks with an item in `#minecraft:chicken_food`. Normal chicken feeding without sneak remains vanilla.
- The shared owner panel has a dedicated group-orders tab. `P` sets the selected Wolf home, while `Ctrl+P` sets a home for every compatible loaded Wolf within the group command radius. Unsupported species are reported as skipped.
- Key mappings are registered on the client, localized and configurable under Minecraft Controls. Group-compatible command buttons explain the Ctrl modifier.
- In-game UI verification should cover Wolf and Chicken profiles, mixed group orders, GUI scales 2 and 4, and set-home execution.

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
