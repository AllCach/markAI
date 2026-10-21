package dev.markai.network;

/** Deterministic packet-boundary checks; no running client or server required. */
public final class NetworkRulesTest {
    private static int checks;
    public static void main(String[] args) {
        for (String action : new String[]{"follow", "stay", "come", "rest", "wander", "home", "sethome", "inventory",
                "spawn", "summon", "dismiss", "investigate", "memory_prev", "memory_next", "cancel", "tame", "attack", "collect", "stop"}) {
            check(NetworkRules.action(action), "Known action " + action);
        }
        for (String action : new String[]{"", " follow", "FOLLOW", "op", "kill @e", "follow\nkill @e", "/mark follow", "execute run op player"}) {
            check(!NetworkRules.action(action), "Reject arbitrary command " + action);
        }
        check(!NetworkRules.action(null), "Null action");
        check(NetworkRules.chat("Привет, Mark! 你好 🐺"), "Natural multilingual text");
        check(NetworkRules.chat("a".repeat(512)), "Maximum message size");
        for (String message : new String[]{"", "   ", "a".repeat(513), "hi\nthere", "hi\u0000there", "\tbad"}) {
            check(!NetworkRules.chat(message), "Invalid message");
        }
        check(!NetworkRules.chat(null), "Null message");
        check(NetworkRules.memoryId("f5095bb5-b54c-4c67-bc62-ab7222f5b33a"), "Canonical memory UUID");
        for (String id : new String[]{"", "1-2-3-4-5", "F5095BB5-B54C-4C67-BC62-AB7222F5B33A", "../../world/markai.json", "f5095bb5-b54c-4c67-bc62-ab7222f5b33z"}) {
            check(!NetworkRules.memoryId(id), "Reject malformed memory id");
        }
        for (String key : new String[]{"hud", "defense", "autochat"}) {
            check(NetworkRules.setting(key, "true") && NetworkRules.setting(key, "false"), "Boolean setting " + key);
            check(!NetworkRules.setting(key, "yes"), "Reject nonboolean " + key);
        }
        for (String value : new String[]{"2", "4.5", "16"}) check(NetworkRules.setting("follow_distance", value), "Valid follow distance");
        for (String value : new String[]{"1.9", "16.1", "NaN", "Infinity", "-Infinity", "nine", ""}) check(!NetworkRules.setting("follow_distance", value), "Invalid follow distance");
        for (String key : new String[]{"loyalty", "curiosity", "caution", "playfulness", "sociability", "independence", "confidence", "trust"}) {
            check(NetworkRules.setting("trait_" + key, "0") && NetworkRules.setting("trait_" + key, "100"), "Trait bounds " + key);
            check(!NetworkRules.setting("trait_" + key, "-1") && !NetworkRules.setting("trait_" + key, "101"), "Reject trait out of bounds " + key);
        }
        check(NetworkRules.setting("name", "Марк 🐺"), "Companion name");
        check(!NetworkRules.setting("name", " ") && !NetworkRules.setting("name", "a".repeat(33)) && !NetworkRules.setting("name", "bad\nname"), "Name bounds");
        check(!NetworkRules.setting("ollamaUrl", "https://example.com") && !NetworkRules.setting("apiKey", "secret"), "Client must not edit endpoint or credentials");
        check(!NetworkRules.setting(null, "true") && !NetworkRules.setting("hud", null), "Null settings");
        System.out.println("NetworkRulesTest: " + checks + " checks passed");
    }
    private static void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
}
