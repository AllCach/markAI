# MarkAI 0.11.1 - internal draft build, not released

> This file is a build-package notice for `dev-draft`, not a public beta release. No GitHub pre-release has been created. The current source contains unverified progression and pathing changes; do not distribute this build or install it over the only copy of a world until the current build and game tests pass.

MarkAI is a Fabric mod for Minecraft Java 26.3 with multiple species-aware companions. The source version in `gradle.properties` is 0.11.1; that version string does not mean this draft has passed release acceptance.

## Companion interaction

- **Wolf / Walk:** tame a wild wolf with bones under normal Minecraft rules. It is registered after successful vanilla taming. Existing custom names and existing saved identities are preserved.
- **Chicken / Mark:** crouch (Shift), hold seeds and right-click an existing wild chicken. MarkAI adopts that same entity. Ordinary seed feeding without Shift remains vanilla behavior. There is no command or GUI button to spawn a companion chicken.
- **Commands:** `/mark` is the canonical command. `/walk` remains a backward-compatible alias for existing scripts and player muscle memory.
- **Species boundary:** wolf-only combat, inventory, collection and trick training are not available to the chicken.

## Current draft limitations

The companion progression model has levels 1-20, XP and talent points, but chicken talent effects and the final balance pass are unfinished. The item collection path has a regression test for a resource on a surface one block higher, but that test has not been run since the latest code changes.

## Validation required before release

Use JDK 25 and the current Gradle wrapper:

```powershell
.\gradlew.bat clean check build --console=plain
.\gradlew.bat runGameTest --console=plain
.\gradlew.bat runClientGameTest --console=plain
.\gradlew.bat productionJarSmokeTest verifyBetaDist --console=plain
```

Also check the actual client, natural taming of both species, default names regardless of the order of taming, elevated item collection, saves/reloads and death/respawn on a copy of a world. Treat any test that did not run on the current HEAD as unverified.
