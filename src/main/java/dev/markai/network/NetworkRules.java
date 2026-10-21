package dev.markai.network;

import java.util.Set;
import java.util.UUID;

/** Pure input rules, shared by packet handlers and deterministic tests. */
public final class NetworkRules {
    private static final Set<String> ACTIONS = Set.of("spawn", "status", "follow", "stay", "come", "rest", "wander",
        "home", "sethome", "investigate", "summon", "dismiss", "inventory", "debug", "brain", "mood", "memory", "memory_prev", "memory_next", "cancel",
        "tame", "attack", "collect", "stop");
    // Ctrl modifies only commands that make sense for multiple living companions.
    private static final Set<String> GROUP_ACTIONS = Set.of(
        "attack", "collect", "stop", "come", "stay", "follow",
        "rest", "wander", "home", "investigate");

    private static final Set<String> TRAITS = Set.of("trait_loyalty", "trait_curiosity", "trait_caution", "trait_playfulness",
        "trait_sociability", "trait_independence", "trait_confidence", "trait_trust");

    private NetworkRules() {}

    public static boolean groupAction(String action) {
        return action != null && GROUP_ACTIONS.contains(action);
    }
    public static boolean action(String action) {
        return action != null && (ACTIONS.contains(action)
            || action.startsWith("all:") && groupAction(action.substring(4)));
    }
    public static boolean chat(String message) {
        return message != null && !message.isBlank() && message.length() <= 512 && message.codePoints().noneMatch(Character::isISOControl);
    }
    public static boolean memoryId(String id) {
        return canonicalId(id);
    }
    public static boolean companionId(String id) {
        return canonicalId(id);
    }
    private static boolean canonicalId(String id) {
        if (id == null || id.length() != 36) return false;
        try { return UUID.fromString(id).toString().equals(id); } catch (IllegalArgumentException ignored) { return false; }
    }
    public static boolean setting(String key, String value) {
        if (key == null || value == null || key.length() > 32 || value.length() > 64) return false;
        if (Set.of("hud", "defense", "autochat").contains(key)) return value.equals("true") || value.equals("false");
        if (key.equals("name")) return !value.isBlank() && value.length() <= 32 && value.codePoints().noneMatch(Character::isISOControl);
        if (key.equals("follow_distance")) return number(value, 2, 16);
        if (TRAITS.contains(key)) return number(value, 0, 100);
        return false;
    }
    private static boolean number(String value, double min, double max) {
        try { double number = Double.parseDouble(value); return Double.isFinite(number) && number >= min && number <= max; }
        catch (NumberFormatException ignored) { return false; }
    }
}
