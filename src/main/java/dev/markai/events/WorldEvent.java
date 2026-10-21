package dev.markai.events;

/** Only game-thread observations and explicit owner interactions create these events. */
public record WorldEvent(Type type, long gameTime, String detail, double magnitude) {
    public enum Type {
        PLAYER_TOOK_DAMAGE, PLAYER_DIED, PLAYER_RESPAWNED, WOLF_TOOK_DAMAGE, WOLF_HEALED,
        OWNER_HEALED_WOLF, HOSTILE_MOB_DETECTED, CREEPER_APPROACHING, WARDEN_DETECTED,
        ENTERED_NEW_BIOME, ENTERED_DANGEROUS_CAVE, ENTERED_NETHER, ENTERED_END,
        PLAYER_FOUND_DIAMONDS, PLAYER_STARTED_SLEEPING, PLAYER_RETURNED_HOME,
        PLAYER_LEFT_COMPANION, COMPANION_REUNITED, OWNER_RETURNED,
        COMPANION_HEALTH_CRITICAL, COMPANION_RESTED, OWNER_AFFECTION, OWNER_ATTACKED_WOLF,
        SAFE_LOCATION_REACHED, COMBAT_ENDED, COMBAT_WON, ACTION_FAILED
    }

    public WorldEvent {
        if (type == null) throw new IllegalArgumentException("Event type is required");
        gameTime = Math.max(0, gameTime);
        if (detail == null) detail = "";
        if (detail.length() > 160) detail = detail.substring(0, 160);
        if (!Double.isFinite(magnitude)) magnitude = 1;
        magnitude = Math.max(0, Math.min(100, magnitude));
    }

    public static WorldEvent of(Type type, long time, String detail) {
        return new WorldEvent(type, time, detail, 1);
    }

    public int priority() {
        return switch (type) {
            case COMPANION_HEALTH_CRITICAL, WARDEN_DETECTED, CREEPER_APPROACHING -> 100;
            case WOLF_TOOK_DAMAGE, PLAYER_TOOK_DAMAGE, OWNER_ATTACKED_WOLF, PLAYER_DIED -> 90;
            case HOSTILE_MOB_DETECTED, ENTERED_DANGEROUS_CAVE -> 70;
            case COMBAT_ENDED, COMBAT_WON, COMPANION_REUNITED, OWNER_RETURNED -> 50;
            default -> 30;
        };
    }
}
