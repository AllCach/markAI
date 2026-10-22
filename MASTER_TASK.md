> **Current implementation note - 22 October 2026:** this is the original master task and is retained as project history. The current authoritative plan is [WORK_PLAN.md](WORK_PLAN.md), current architecture is [ARCHITECTURE.md](ARCHITECTURE.md), and current checks are [TESTING.md](TESTING.md). MarkAI is no longer wolf-only: Walk is the default wolf companion, Mark is the default chicken companion; Chicken is adopted by crouching + right-clicking an existing wild chicken with seeds. `/mark` is canonical and `/walk` is a compatibility alias. The current `dev-draft` is unverified and is not a release.

# CODEX MASTER TASK - MARKAI: LIVING COMPANION

## YOUR ROLE

You are the lead Java developer, Minecraft mod engineer, AI systems architect, and QA engineer responsible for building **MarkAI - Living Companion**.

Your task is to implement a complete, functional, extensible Minecraft Java Edition mod that introduces Mark, an intelligent, conversational wolf companion.

**This is a large implementation assignment, not a planning or prototyping exercise.**

Do not stop after creating an architecture, a few sample classes, or a basic chat integration. Implement as much of the actual mod as possible, working through the phases sequentially.

Do not artificially delay completion. Use the available execution time productively to implement, compile, test, debug, and improve the project.

## 0. MANDATORY WORKING RULES

1. Inspect the existing repository before changing anything.
2. Preserve existing working functionality unless replacement is necessary.
3. Detect the Minecraft version, mod loader, mappings, Java version, build tools, and existing architecture.
4. Prefer Fabric if creating a new project. If an established repository uses another loader, preserve it unless migration is explicitly necessary.
5. Verify dependencies and API compatibility rather than guessing.
6. Do not claim compatibility with Minecraft 26.3 unless it can be verified and built against its actual development dependencies.
7. If 26.3 tooling is not available, use the latest compatible, verifiable target supported by the repository and clearly document the limitation.
8. Produce real, compilable Java code.
9. Do not leave primary features as TODO comments or empty methods.
10. Keep Minecraft world access on the appropriate game thread.
11. Run network requests, database operations, and LLM inference asynchronously.
12. Validate all AI-proposed actions before executing them.
13. Maintain clean separation between AI, behavior, perception, memory, UI, and networking.
14. Prefer reusable interfaces and small, testable components.
15. Add tests for important logic.
16. Run Gradle compilation and tests whenever possible.
17. Fix errors discovered during implementation.
18. Do not fabricate successful tests or working integrations.
19. Provide a final report containing implemented functionality, test results, limitations, and remaining work.
20. Continue to the next unfinished phase whenever the preceding phase is working. Do not stop merely because one subsystem has been completed.

The entire repository is your working context. Make actual file changes.

---

# PHASE 1 - PROJECT AUDIT AND FOUNDATION

## 1.1. Audit existing code

Inspect:

- Repository structure.
- Gradle configuration.
- Minecraft and Fabric versions.
- Existing entrypoints.
- Registered entities.
- Existing wolf AI implementation.
- Custom networking code.
- Existing AI API integration.
- Client/server separation.
- Saved data and serialization.
- Existing command handlers.
- Resource assets and translations.

Identify reusable code.

If a MarkBrain class already exists, refactor and extend it rather than blindly duplicating it.

## 1.2. Create a modular architecture

Organize the project into logical packages:

    markai/
      core/
      entity/
      brain/
      behavior/
      perception/
      emotions/
      personality/
      dialogue/
      llm/
      memory/
      actions/
      events/
      commands/
      client/
      client/gui/
      client/render/
      network/
      persistence/
      config/
      debug/

Actual package organization may be adjusted for compatibility with the project.

Create necessary interfaces and implementations.

Do not create useless abstraction layers.

## 1.3. Introduce core domain models

Implement suitable immutable records, enums, and value objects.

Required conceptual models:

- CompanionIdentity
- CompanionProfile
- CompanionState
- WorldState
- PlayerSnapshot
- EnvironmentSnapshot
- EntityObservation
- CompanionMood
- PersonalityTraits
- CompanionDecision
- CompanionAction
- ActionResult
- MemoryEntry
- ConversationMessage
- ConversationSession
- WorldEvent
- DialogueContext
- CompanionSettings

Use stable identifiers for persistent data.

Avoid storing live Minecraft Entity references inside asynchronous AI tasks.

---

# PHASE 2 - MARK THE WOLF

Implement Mark as a persistent in-world wolf companion.

## 2.1. Entity integration

Requirements:

- Named companion Mark.
- Natural wolf appearance.
- Persistent identity.
- Ownership association.
- Owner UUID.
- Wolf health.
- Position and dimension tracking.
- Current activity tracking.
- Current behavior state.
- Saved personality.
- Saved emotional state.
- Reliable persistence across world reloads.

Prefer integrating with vanilla wolf behavior where practical.

If subclassing or replacing vanilla wolf functionality causes unnecessary incompatibilities, choose a safer companion attachment or behavior-controller approach.

Do not break vanilla wolves.

## 2.2. Spawning and ownership

Implement an explicit mechanism to create or designate Mark.

Provide commands or safe in-game interactions for:

- Creating Mark.
- Assigning an owner.
- Finding the active companion.
- Summoning the companion where safe.
- Removing or dismissing a companion.
- Inspecting companion status.

Never silently replace all wolves with intelligent companions.

Prevent accidental duplicate identities.

## 2.3. Lifecycle

Handle:

- World loading.
- Entity loading.
- Chunk loading and unloading.
- Player disconnect.
- Dimension changes.
- Entity death.
- World saving.
- Companion revival or recovery, if explicitly enabled.

Avoid entity duplication or loss of persistent data.

---

# PHASE 3 - PERSONALITY ENGINE

Mark must have a consistent personality.

He is a wolf, not a generic assistant pretending to be an animal.

## 3.1. Core personality

Mark should be:

- Loyal.
- Observant.
- Curious.
- Occasionally stubborn.
- Playful when comfortable.
- Cautious around dangerous creatures.
- Protective toward his owner.
- Capable of expressing disagreement.
- Independent enough not to require constant attention.
- Naturally conversational.

He can speak normally.

He must not repeatedly claim that wolves cannot talk.

He should not constantly mention his paws, fur, tail, or species.

His animal nature should influence his perception and behavior, not restrict his vocabulary.

## 3.2. Personality model

Implement persistent traits:

- Loyalty.
- Curiosity.
- Caution.
- Playfulness.
- Sociability.
- Independence.
- Confidence.
- Trust toward owner.

Do not randomly regenerate traits after restart.

## 3.3. Personality consistency

Create a PersonalityEngine responsible for supplying personality information to:

- Dialogue generation.
- Behavior selection.
- Emotional reactions.
- Autonomous activity planning.

Personality values must produce observable differences in behavior.

A cautious wolf should react differently to a dangerous cave than an adventurous one.

Do not simply generate unused numerical statistics.

---

# PHASE 4 - EMOTIONAL SYSTEM

Implement a lightweight emotional simulation.

## 4.1. Emotional dimensions

Track:

- Mood.
- Fear.
- Curiosity.
- Excitement.
- Trust.
- Fatigue or energy, if useful.
- Alertness.

Choose clear numerical ranges.

Store persistent values only where meaningful.

## 4.2. Emotional triggers

Respond to events:

- Owner receives damage.
- Wolf receives damage.
- Hostile mob approaches.
- Owner heals wolf.
- Owner returns after being absent.
- New biome discovered.
- Dangerous cave entered.
- Wolf rests.
- Owner interacts affectionately.
- Wolf is attacked by owner.
- Safe location reached.
- Long combat ends.

## 4.3. Emotional decay

Implement gradual state recovery.

Fear should not remain permanently high after one attack.

Mood should not change randomly without causes.

Apply time-based adjustments with appropriate rate limiting.

## 4.4. Effect on behavior

Emotion must influence real gameplay.

Examples:

- High fear increases retreat priority.
- High curiosity encourages investigation.
- Low energy increases rest preference.
- High trust changes interaction reactions.
- High alertness reduces playful activities.

## 4.5. Effect on dialogue

Include an appropriate emotional summary in LLM context.

Do not allow the LLM to arbitrarily overwrite emotional state without validation.

---

# PHASE 5 - BEHAVIOR ENGINE

Implement a robust, mostly deterministic behavior engine.

## 5.1. Core activities

Required:

- FOLLOW
- STAY
- WANDER
- REST
- INVESTIGATE
- PLAY
- ALERT
- DEFEND
- RETREAT
- SEEK_OWNER
- RETURN_HOME
- IDLE

Use enums or a typed hierarchy.

## 5.2. Behavior controller

Implement:

- State transitions.
- Priorities.
- Transition guards.
- Activity cancellation.
- Cooldowns.
- Interruption by danger.
- Recovery after interruption.
- Current activity reporting.

Avoid oscillation between two behaviors on consecutive ticks.

## 5.3. Following

Mark must:

- Follow owner at a configurable distance.
- Avoid blocking the player.
- Stop naturally when nearby.
- Resume after temporary obstacles.
- Handle loss of line of sight.
- Avoid excessive path recalculations.
- Avoid repeatedly running into walls.
- Use safe teleportation only as fallback.

## 5.4. Danger avoidance

Mark should attempt to avoid:

- Lava.
- Fire.
- Large drops.
- Dangerous water situations.
- Suffocation.
- The void.
- Other clearly dangerous terrain.

Use Minecraft navigation and collision APIs rather than reinventing every pathfinding mechanism.

## 5.5. Combat and defense

Implement defensive behavior.

Mark may protect his owner from hostile mobs.

Rules:

- Avoid attacking the owner.
- Avoid attacking allied companions.
- Do not start unnecessary fights with neutral mobs.
- Retreat when health is critically low.
- Allow defensive behavior to be disabled.
- Respect validated player commands.

## 5.6. Autonomous actions

When no command is active, Mark may:

- Wander nearby.
- Look around.
- Investigate objects.
- Sniff the ground.
- Rest.
- Observe mobs.
- Return to the owner.
- Choose a nearby safe resting location.

Use bounded, controlled randomness.

Do not use the LLM for every trivial movement decision.

---

# PHASE 6 - WORLD PERCEPTION

Mark should have access to real Minecraft state.

He must not pretend to see things that are not observable.

## 6.1. Player observation

Collect appropriate information:

- Player position.
- Dimension.
- Health.
- Hunger.
- Active effects.
- Held items.
- Armor.
- Movement.
- Distance to Mark.
- Current action when detectable.

## 6.2. Environment observation

Collect:

- Current biome.
- Time of day.
- Weather.
- Light level.
- Nearby visible entities.
- Nearby dangerous mobs.
- Nearby important blocks.
- Nearby hazards.
- Current dimension.
- Known home position.

## 6.3. Visibility rules

Implement perception limits.

Consider:

- Distance.
- Line of sight.
- Loaded chunks.
- Visibility.
- Hearing range.
- Whether an event was actually observed.

Do not grant unlimited knowledge about the world.

## 6.4. Event detection

Implement a typed event system.

Possible events:

    PLAYER_TOOK_DAMAGE
    PLAYER_DIED
    PLAYER_RESPAWNED
    WOLF_TOOK_DAMAGE
    WOLF_HEALED
    HOSTILE_MOB_DETECTED
    CREEPER_APPROACHING
    WARDEN_DETECTED
    ENTERED_NEW_BIOME
    ENTERED_NETHER
    ENTERED_END
    PLAYER_FOUND_DIAMONDS
    PLAYER_STARTED_SLEEPING
    PLAYER_RETURNED_HOME
    PLAYER_LEFT_COMPANION
    COMPANION_REUNITED
    COMPANION_HEALTH_CRITICAL

Use reliable game hooks where available.

Do not assume an event hook exists without verifying the actual mapped API.

## 6.5. Event processing

Implement:

- Deduplication.
- Rate limiting.
- Priority handling.
- Context building.
- Recent event history.
- Event expiry.
- Relevant-event selection.

Events should be capable of triggering behavior without requiring a language model.

---

# PHASE 7 - LLM INTEGRATION

This is a central feature.

Implement real conversational AI integration.

## 7.1. Provider abstraction

Create an LlmProvider interface with appropriate asynchronous methods.

Implement at least one working HTTP-based provider using an OpenAI-compatible API contract.

Allow configuration of:

- Base URL.
- Model identifier.
- Authentication.
- Request timeout.
- Temperature or equivalent supported parameters.
- Maximum output length.
- Retry behavior.

Design compatibility for locally hosted models, including Ollama-compatible setups where supported.

Do not hardcode API credentials.

## 7.2. Asynchronous execution

Requirements:

- Never block the Minecraft render thread.
- Never block the world tick thread waiting for an API response.
- Use a bounded request executor or equivalent.
- Handle cancellations.
- Handle shutdown.
- Prevent stale responses from overwriting current dialogue state.
- Maintain proper response order.
- Handle API errors.
- Handle invalid JSON.
- Handle timeouts.
- Handle empty responses.
- Handle rate limiting.

## 7.3. Character instructions

Build a stable character prompt.

It must specify:

Mark is a talking wolf living in Minecraft.

He is naturally intelligent and capable of ordinary conversation.

He is loyal, curious, observant, slightly stubborn, and has his own opinions.

He does not behave as a generic AI assistant.

He does not constantly remind the player that he is a wolf.

He is permitted to speak naturally, including humor and informal speech.

He should not always agree with the player.

He should not fabricate observations.

He should not fabricate memories.

He should respect available world information.

He should produce concise dialogue by default.

He should avoid repetitive generic statements.

## 7.4. Dialogue context builder

Build context from:

- Stable personality.
- Current emotional state.
- Owner information.
- Current world snapshot.
- Relevant recent events.
- Relevant memories.
- Conversation history.
- Player's current message.

Use explicit token or size budgets.

Do not attach the entire lifetime chat history to every request.

## 7.5. Conversation manager

Implement:

- Message history.
- Conversation sessions.
- Player-to-companion message routing.
- Per-companion context.
- Response queuing.
- Cancellation.
- Message filtering.
- Error handling.
- User-visible response delivery.

## 7.6. Natural responses

Mark should understand and answer:

- Ordinary conversation.
- Questions about the world.
- Questions about previous adventures.
- Jokes.
- Player emotions.
- Plans.
- Gameplay requests.
- Complicated follow-up questions.

He should also be able to say that he does not know something.

---

# PHASE 8 - MEMORY SYSTEM

Implement persistent memory across sessions.

## 8.1. Short-term memory

Store recent dialogue messages and events.

Use a bounded sliding context window.

## 8.2. Long-term memory

Persist important information:

- Owner preferences.
- Important conversations.
- Shared adventures.
- Dangerous experiences.
- Significant locations.
- Promises.
- Important discoveries.
- Relevant relationship history.

## 8.3. Episodic memories

Each memory should include:

- Unique ID.
- Companion ID.
- Timestamp or game time.
- Memory category.
- Content.
- Source event or dialogue reference.
- Importance.
- Associated dimension/location when relevant.
- Confidence or reliability metadata.

## 8.4. Memory extraction

Implement a mechanism that analyzes important interactions and extracts durable memories.

Avoid saving every trivial message as an important memory.

Perform extraction asynchronously.

## 8.5. Memory retrieval

Retrieve memories relevant to the current dialogue.

Start with a practical solution such as metadata filtering, keyword search, and SQLite FTS if appropriate.

Make retrieval extensible for future embeddings.

Do not require a vector database for the initial implementation.

## 8.6. Memory summarization

Compress older conversation history.

Preserve:

- Important facts.
- Unresolved promises.
- Important events.
- Relationship context.

## 8.7. Memory correctness

Prevent hallucinated memories from being accepted as real game events.

Separate:

- Confirmed world events.
- Player-provided statements.
- Inferences.
- AI-generated interpretation.

Provide memory inspection and deletion functionality.

---

# PHASE 9 - NATURAL LANGUAGE COMMANDS

Mark should understand commands spoken conversationally.

Examples:

"Mark, follow me."

"Марк, иди сюда."

"Подожди здесь."

"Не подходи к лаве."

"Пойдём домой."

"Марк, отдохни немного."

## 9.1. Intent parser

Create an IntentParser.

It must recognize supported actions and their arguments.

Prefer deterministic recognition for simple commands where possible.

Use LLM structured outputs for more complex instructions.

## 9.2. Action validation

Never execute arbitrary code produced by the LLM.

Use an explicit allowlist of actions.

Validate:

- Action type.
- Parameters.
- Owner authorization.
- Target existence.
- Target range.
- Dimension.
- Current safety.
- Companion state.

## 9.3. Action executor

Implement safe execution for:

- FOLLOW
- STAY
- COME
- REST
- WANDER
- RETURN_HOME
- INVESTIGATE

Additional actions may be implemented after these work correctly.

## 9.4. Action feedback

Mark should know whether an action succeeded.

Report meaningful failures naturally.

For example:

"I can't reach that spot."

"I don't know where home is yet."

"There's lava between us."

Do not claim an action was completed before the game engine confirms it.

---

# PHASE 10 - GRAPHICAL INTERFACE

Implement a real Minecraft-native GUI.

## 10.1. Companion screen

Display:

- Mark's name.
- Wolf portrait or entity preview.
- Health.
- Current activity.
- Mood.
- Relationship summary.
- AI connection status.

## 10.2. Tabbed interface

Tabs:

1. Profile.
2. Commands.
3. Dialogue.
4. Memories.
5. Settings.

Implement working navigation.

Do not create visually convincing buttons that do nothing.

## 10.3. Dialogue UI

Implement:

- Message history.
- Text input.
- Send button.
- Message scrolling.
- AI response status.
- Error indicators.
- Reply cancellation.
- Appropriate keyboard handling.

## 10.4. Commands UI

Provide interactive buttons:

- Follow.
- Stay.
- Come.
- Rest.
- Wander.
- Return home.

Each button must invoke a real validated action.

## 10.5. Memories UI

Display stored memories.

Support:

- Scrolling.
- Selecting entries.
- Reading details.
- Deleting entries.
- Refreshing the list.

## 10.6. HUD

Implement an optional, lightweight overlay.

Display:

- Companion health.
- Current action.
- Relevant messages.

Allow the HUD to be hidden.

## 10.7. Keybindings

Provide configurable bindings for:

- Open companion screen.
- Open dialogue.
- Open quick-command interface.

Verify interactions with normal Minecraft controls.

---

# PHASE 11 - ANIMATIONS AND EXPRESSIVE BEHAVIOR

Implement a reasonable first set of visual behaviors.

Target animations:

- Looking at the owner.
- Head tilting.
- Resting.
- Alert posture.
- Tail movement.
- Sniffing.
- Excited greeting.
- Frightened reaction.

Use the existing rendering and animation APIs where possible.

If custom skeletal animation requires additional dependencies, verify compatibility before integrating them.

Prioritize working, compatible animations over complex unfinished ones.

Connect animations to actual emotion and behavior states.

Do not trigger unrelated random animations during danger or combat.

---

# PHASE 12 - SOUND AND OPTIONAL VOICE ARCHITECTURE

## 12.1. Wolf sounds

Implement contextual sound reactions:

- Greeting.
- Alert.
- Fear.
- Pain.
- Contentment.
- Playfulness.

Respect existing Minecraft sound settings.

## 12.2. Voice interfaces

Design optional interfaces:

- SpeechRecognizer
- SpeechSynthesizer
- VoiceSessionManager

Implement working microphone/STT/TTS integration only if the target environment and dependencies can be verified.

Do not sacrifice core mod stability for voice integration.

## 12.3. Spatial playback

If implementing voice output, associate the sound with Mark's in-world position.

Handle distance attenuation and cancellation.

---

# PHASE 13 - MULTIPLAYER AND SECURITY

Even if full multiplayer functionality comes later, the initial architecture must respect server authority.

## 13.1. Ownership security

Only authorized players should be able to:

- Issue commands.
- Edit Mark's personality.
- Delete memories.
- Dismiss the companion.
- Change sensitive settings.

## 13.2. Networking

Implement typed packets for:

- Companion state synchronization.
- Player commands.
- Chat requests.
- AI replies.
- UI data requests.
- Memory previews.
- Status updates.

Validate all incoming client data.

## 13.3. Privacy

Do not automatically expose private conversation history to other players.

Store API credentials outside shared world files.

Avoid logging secrets.

Support disabling external AI requests.

---

# PHASE 14 - CONFIGURATION

Implement a usable config system.

Configuration options should include:

- AI provider.
- Model.
- API endpoint.
- Request timeout.
- Max dialogue length.
- Automatic conversation frequency.
- Memory settings.
- HUD visibility.
- Mark movement behavior.
- Follow distance.
- Defensive behavior.
- Debug mode.
- Voice enablement if implemented.

Validate configuration values.

Handle missing or malformed configuration without crashing.

---

# PHASE 15 - DEBUGGING TOOLS

Implement developer diagnostics.

Suggested commands:

    /mark status
    /mark debug
    /mark brain
    /mark mood
    /mark memory
    /mark follow
    /mark stay
    /mark come
    /mark home

Use the actual command API for the selected Minecraft version.

Expose:

- Current behavior state.
- Recent world events.
- Current emotional state.
- AI request status.
- Last action result.
- Memory retrieval results.
- Relevant performance statistics.

Make logs concise and useful.

---

# PHASE 16 - TESTING

Implement automated tests where technically practical.

## 16.1. Brain tests

Test:

- Behavior priority.
- Danger handling.
- State transitions.
- Emotion updates.
- Cooldowns.
- Action selection.

## 16.2. Dialogue tests

Test:

- Context generation.
- Missing API keys.
- Timeout handling.
- Invalid model responses.
- Provider failures.
- Request cancellation.
- Conversation ordering.

Use a fake LlmProvider for deterministic tests.

## 16.3. Memory tests

Test:

- Persistence.
- Retrieval.
- Deduplication.
- Relevant-context selection.
- Serialization.
- Invalid entries.
- Data migration.

## 16.4. Action tests

Test:

- Valid commands.
- Unsupported actions.
- Unauthorized commands.
- Unsafe targets.
- Invalid parameters.
- Failed navigation.

## 16.5. Build verification

Run available Gradle tasks.

Compile client and common sources.

Run automated tests.

Fix errors before reporting completion.

If a Minecraft runtime is available, perform launch and integration smoke tests.

If runtime testing is unavailable, explicitly distinguish compile success from gameplay verification.

---

# PHASE 17 - PERFORMANCE

Optimize for normal Minecraft gameplay.

Requirements:

- No LLM request every tick.
- No excessive world scans.
- Bounded perception radius.
- Cached environment snapshots.
- Event throttling.
- Bounded memory retrieval.
- Limited database writes.
- Proper resource cleanup.
- No unbounded thread creation.
- No blocking network calls on main threads.
- Efficient state synchronization.

Measure costs where practical.

Ensure idle Mark behavior does not cause substantial TPS degradation.

---

# PHASE 18 - DOCUMENTATION

Write:

- README.md
- ARCHITECTURE.md
- DEVELOPMENT.md
- CONFIGURATION.md
- TESTING.md

README should include:

- Supported Minecraft version.
- Mod loader.
- Java requirements.
- How to build.
- How to install.
- How to spawn or designate Mark.
- How to talk to Mark.
- How to configure an LLM provider.
- How to use a local AI server.
- How to open the GUI.
- How to use commands.
- Known limitations.

Use accurate instructions derived from the actual implementation.

Do not describe unfinished features as working.

---

# PHASE 19 - ACCEPTANCE SCENARIOS

The implementation should aim to satisfy the following end-to-end scenarios.

## Scenario A - First meeting

1. Player loads a world.
2. Player creates or designates Mark.
3. Mark appears as a wolf.
4. Mark receives persistent identity.
5. Player opens Mark's UI.
6. Player sends a message.
7. Mark replies in character.
8. Player closes the world.
9. Player reloads the world.
10. Mark retains identity and conversation state.

## Scenario B - Normal conversation

Player: "Привет, Марк."

Mark responds naturally.

Player: "Ты вообще кто?"

Mark identifies himself as Mark, a talking wolf.

He does not say that wolves cannot speak.

Player: "Пойдём исследовать пещеру?"

Mark considers current circumstances and replies appropriately.

## Scenario C - Danger

1. Player enters a dangerous area.
2. Mark detects a nearby hostile mob.
3. Mark changes to an appropriate alert state.
4. Mark may warn the player.
5. If attacked, Mark defends or retreats according to health and context.
6. After the danger passes, Mark returns to normal behavior.

## Scenario D - Commands

1. Player says "Марк, подожди здесь".
2. Intent parser recognizes STAY.
3. ActionValidator approves.
4. Mark stops following.
5. Player says "Теперь ко мне".
6. Mark returns.

## Scenario E - Memory

1. Player and Mark experience an important event.
2. The event is persisted.
3. Player restarts Minecraft.
4. Player refers to that event.
5. Mark retrieves the relevant memory.
6. Mark responds without inventing unrelated details.

## Scenario F - API failure

1. The AI endpoint becomes unavailable.
2. The game continues working.
3. Mark can still move and follow.
4. UI reports the connection problem.
5. The game does not freeze.
6. Future requests may work after connection recovery.

## Scenario G - GUI

1. Player opens Mark's companion screen.
2. The screen shows accurate companion information.
3. Buttons execute actual commands.
4. Chat displays real dialogue.
5. Memories display persisted information.
6. The UI remains functional after closing and reopening.

---

# PHASE 20 - FINAL INTEGRATION PASS

Once the main features have been implemented:

1. Review the entire codebase.
2. Identify unfinished integrations.
3. Remove unused placeholder code.
4. Check server/client class separation.
5. Check async thread safety.
6. Review persistence and serialization.
7. Verify UI actions.
8. Verify command authorization.
9. Review LLM prompt consistency.
10. Check memory correctness.
11. Check behavior priority.
12. Inspect error handling.
13. Run available tests again.
14. Compile the complete mod again.
15. Fix discovered build errors.
16. Review startup and shutdown paths.
17. Update documentation to match reality.
18. Produce a final technical report.

## COMPLETION REPORT FORMAT

Provide:

### Implemented
List working features and the main files responsible.

### Build and test results
List commands actually executed and their results.

### Architecture
Describe how MarkBrain, LLM, memory, behavior, perception, and UI interact.

### Partially implemented
List any incomplete features and explain precisely what remains.

### Known issues
List confirmed problems, not speculative ones presented as facts.

### Next development priorities
Provide specific, actionable next steps.

---

# FINAL INSTRUCTION

Your objective is to deliver the most complete working version of MarkAI possible, not merely demonstrate an architecture.

Prioritize functional integration over excessive abstraction.

Implement real code.

Compile frequently.

Fix bugs.

Test important behavior.

Continue implementing the next incomplete phase.

Do not declare the task finished while major required features remain untouched and there is still a practical opportunity to continue.

**MarkAI should feel like a living wolf companion, not a chatbot attached to a Minecraft entity.**

BEGIN BY INSPECTING THE REPOSITORY AND IMMEDIATELY PROCEED WITH IMPLEMENTATION.
