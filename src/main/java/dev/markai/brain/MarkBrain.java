package dev.markai.brain;

import dev.markai.behavior.Activity;
import dev.markai.behavior.BehaviorDecision;
import dev.markai.behavior.BehaviorInput;
import dev.markai.core.LivingState;

/**
 * Minimal decision logic for a configurable Minecraft companion.
 * This class is independent of Minecraft and Fabric APIs.
 */
public final class MarkBrain {

    private MarkBrain() {}

    public record WorldState(
        String playerName,
        double distanceToPlayer,
        double enemyDistance,
        int health
    ) {}

    public record Decision(
        String action,
        String message
    ) {}

    /** Stateful living-companion controller, separate from the original four-field example API. */
    public static BehaviorDecision think(BehaviorInput world, LivingState state) {
        if (world == null || state == null) throw new IllegalArgumentException("World and companion state are required");
        state.normalize();
        Activity previous = state.behavior.current();
        state.emotions.decayTo(world.gameTime(), previous == Activity.REST,
            previous == Activity.FOLLOW || previous == Activity.SEEK_OWNER || previous == Activity.WANDER
                || previous == Activity.RETREAT || previous == Activity.RETURN_HOME || previous == Activity.DEFEND,
            state.personality);
        return state.behavior.update(world, state);
    }

    public static Decision think(WorldState world) {
        if (world == null) {
            throw new IllegalArgumentException("WorldState must not be null");
        }

        String name = world.playerName() == null || world.playerName().isBlank()
            ? "друг"
            : world.playerName();

        // No enemies nearby: supply Double.POSITIVE_INFINITY as enemyDistance.
        if (world.enemyDistance() < 6.0) {
            return new Decision("FLEE", name + ", БЛЯ, СПАСАЙ!");
        }

        if (world.distanceToPlayer() > 4.0) {
            return new Decision("FOLLOW", name + ", подожди меня!");
        }

        return new Decision("IDLE", "Чё делаем, " + name + "?");
    }
}
