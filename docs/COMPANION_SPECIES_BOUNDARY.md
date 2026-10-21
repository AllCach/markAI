# Companion species boundary — 0.10.4

This document records the integration boundary before adding Chicken/Mark. It is not a claim that chicken companions are implemented.

## Current call path

- Persistent record: `MarkAIMod.Companion` stores logical ID, owner UUID, physical entity UUID, species string, dimension/chunk, living state, memory and dog training.
- Registry: `CompanionRegistry<Companion>` indexes logical identity, owner, physical entity and selected companion.
- Persistence: `CompanionStore<Companion>` reads/writes the world-level `markai.json`.
- Server startup: `MarkAIMod` loads the store and currently accepts only `minecraft:wolf`.
- Spawn: command path directly constructs `EntityTypes.WOLF`.
- Resolution: `find(Companion)` returns `Wolf`; a Chicken entity with the saved UUID is converted to Wolf.
- Main loop: `tick(Companion)` calls Wolf-only configuration, observation, behavior, dialogue and work logic.
- Wolf-only capabilities: `LivingCompanionRuntime`, `WolfWorkRuntime`, inventory mixin/holder, training and combat rules.

## Required separation

1. Keep the existing Wolf execution path intact while extracting species dispatch.
2. Resolve a saved record by species and entity type; never convert a valid Chicken/Mark entity into a Wolf.
3. Give Chicken/Mark its own runtime. It may share owner/identity, memory, observation and dialogue services, but must not call wolf inventory, dog training, wolf combat, wolf sound or wolf-only goals.
4. Keep ordinary wild chickens unchanged. Only entities explicitly registered as MarkAI companions enter the companion runtime.
5. On unsupported or malformed persisted species, fail closed as current storage does: do not silently spawn a replacement and risk duplicates.
6. Keep old records without a species field mapped to `minecraft:wolf` for backwards compatibility.
7. Add tests for both persistence and runtime dispatch before allowing a non-wolf species through startup validation.

## Implementation order

- Slice A: introduce a small species/runtime dispatch abstraction and test it without changing the existing Wolf path.
- Slice B: route existing Wolf setup/tick/find through that abstraction; run build and existing registry/GameTests.
- Slice C: add Chicken/Mark entity creation, health=4, owner binding, safe follow/retreat and persistence.
- Slice D: verify coexistence, save/reload, unload/reload, selection isolation and that Wolf inventory/attack/tricks are never called for Chicken.

Do not merge this branch into `dev` or `main` until the full diff is reviewed and checks pass.
