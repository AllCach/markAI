package dev.markai.network;

/** Deterministic packet-boundary checks; no running client or server required. */
public final class NetworkRulesTest {
    private static int checks;
    public static void main(String[] args) {
        for (String action : new String[]{"follow", "stay", "come", "rest", "wander", "home", "sethome", "inventory",
                "spawn", "summon", "dismiss", "investigate", "memory_prev", "memory_next", "cancel", "tame", "attack", "collect", "stop",
                "talent_guardian", "talent_hunter", "talent_tracker", "talent_nimble", "talent_lookout", "talent_evasive"}) {
            check(NetworkRules.action(action), "Known action " + action);
        }
        for (String action : new String[]{"attack", "collect", "stop", "come", "stay", "follow", "rest", "wander", "home", "sethome", "investigate"}) {
            check(NetworkRules.groupAction(action) && NetworkRules.action("all:" + action), "Allowed group action: " + action);
        }
        for (String action : new String[]{"spawn", "spawn_chicken", "respawn", "tame", "inventory", "status", "forget", "cancel",
                "summon", "dismiss", "memory", "deleteMemory", "train", "", "ATTACK", "../attack", "attack\nkill @e"}) {
            check(!NetworkRules.groupAction(action) && !NetworkRules.action("all:" + action), "Rejected group action: " + action);
        }
        check(!NetworkRules.action("all:all:attack") && !NetworkRules.action("all:") && !NetworkRules.groupAction(null),
            "Nested and empty group requests rejected");
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
        check(NetworkRules.companionId("f5095bb5-b54c-4c67-bc62-ab7222f5b33a"), "Canonical companion UUID");
        check(!NetworkRules.companionId(null), "Null companion UUID");
        for (String id : new String[]{"", "1-2-3-4-5", "F5095BB5-B54C-4C67-BC62-AB7222F5B33A", "../../world/markai.json", "f5095bb5-b54c-4c67-bc62-ab7222f5b33z"}) {
            check(!NetworkRules.memoryId(id), "Reject malformed memory id");
            check(!NetworkRules.companionId(id), "Reject malformed companion id");
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
        for (String key : new String[]{"hud","defense","autochat"}) check(NetworkRules.setting("all:"+key,"true"),"Allowed bulk preference "+key);
        check(NetworkRules.setting("all:follow_distance","16"),"Bulk distance");
        check(!NetworkRules.setting("all:follow_distance","NaN"),"Invalid bulk distance");
        for (String key : new String[]{"all:name","all:trait_trust","all:all:hud","all:apiKey"}) check(!NetworkRules.setting(key,"true"),"Personal or injected bulk preference rejected");
        check(NetworkRules.setting("group_enabled","false")&&NetworkRules.setting("mutual_replies","true"),"Group boolean preferences");
        check(!NetworkRules.setting("mutual_replies","100"),"Reply budget cannot be supplied by a client");
        check(NetworkRules.action("group_cancel")&&!NetworkRules.action("all:group_cancel"),"Group conversation has its own owner-scoped cancellation");
        System.out.println("NetworkRulesTest: " + checks + " checks passed");
    }
    private static void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
}
