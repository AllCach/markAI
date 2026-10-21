package dev.markai.actions;

import java.util.Locale;
import java.util.Optional;

/** Finite commands that the server can validate and execute. */
public enum CompanionAction {
    FOLLOW, STAY, COME, REST, WANDER, RETURN_HOME, INVESTIGATE, PLAY,
    LOOK_AT_PLAYER, RETREAT, IDLE, CANCEL;

    public static Optional<CompanionAction> fromWire(String text) {
        if (text == null) return Optional.empty();
        String normalized = text.strip().toUpperCase(Locale.ROOT);
        if (normalized.equals("FLEE")) normalized = "RETREAT";
        try { return Optional.of(valueOf(normalized)); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }
}
