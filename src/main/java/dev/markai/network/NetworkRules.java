package dev.markai.network;

import java.util.Set;
import java.util.UUID;

/** Pure input rules, shared by packet handlers and deterministic tests. */
public final class NetworkRules {
    private static final Set<String> ACTIONS = Set.of("spawn", "status", "follow", "stay", "come", "rest", "wander",
        "home", "sethome", "investigate", "summon", "dismiss", "inventory", "debug", "brain", "mood", "memory", "memory_prev", "memory_next", "cancel",
        "tame", "attack", "collect", "stop", "group_cancel");
    // Ctrl modifies only commands that make sense for multiple living companions.
    private static final Set<String> GROUP_ACTIONS = Set.of(
        "attack", "collect", "stop", "come", "stay", "follow",
        "rest", "wander", "home", "sethome", "investigate");

    private static final Set<String> TALENTS = Set.of("guardian", "hunter", "tracker", "nimble", "lookout", "evasive");

    private static final Set<String> TRAITS = Set.of("trait_loyalty", "trait_curiosity", "trait_caution", "trait_playfulness",
        "trait_sociability", "trait_independence", "trait_confidence", "trait_trust");

    private NetworkRules() {}

    public static boolean talent(String talent) { return talent != null && TALENTS.contains(talent); }

    public static boolean groupAction(String action) {
        return action != null && GROUP_ACTIONS.contains(action);
    }
    public static boolean action(String action) {
        return action != null && (action.startsWith("talent_") && talent(action.substring(7)) || ACTIONS.contains(action)
            || action.startsWith("all:") && groupAction(action.substring(4)));
    }
    public static boolean chat(String message) {
        return message != null && !message.isBlank() && message.length() <= 512 && message.codePoints().noneMatch(Character::isISOControl);
    }
    public static boolean memoryId(String id) {
        return canonicalId(id);
    }
    public static boolean journalSpeciesId(String id) {
        return id != null && id.length() <= 128
            && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }
    /** noteId "0" means add; a positive decimal ID means delete. */
    public static boolean journalNote(String speciesId, String text, String noteId) {
        if (!journalSpeciesId(speciesId) || text == null || noteId == null || noteId.length() > 19) return false;
        if (noteId.equals("0")) return !text.isBlank() && text.length() <= 512
            && text.codePoints().noneMatch(Character::isISOControl);
        try {
            long id = Long.parseLong(noteId);
            return id > 0 && text.isEmpty();
        } catch (NumberFormatException ignored) { return false; }
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
        if (key.startsWith("all:")) return Set.of("hud", "defense", "autochat", "follow_distance").contains(key.substring(4))
            && setting(key.substring(4), value);
        if (Set.of("group_enabled", "mutual_replies").contains(key)) return value.equals("true") || value.equals("false");
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
