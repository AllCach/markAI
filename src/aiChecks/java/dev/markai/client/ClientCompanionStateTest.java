package dev.markai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Owner-panel state must replace, rather than mix, the selected companion's conversation. */
public final class ClientCompanionStateTest {
    private static final String FIRST = "f5095bb5-b54c-4c67-bc62-ab7222f5b33a";
    private static final String SECOND = "f5095bb5-b54c-4c67-bc62-ab7222f5b33b";
    private static int checks;

    public static void main(String[] args) {
        var state = new ClientCompanionState();
        state.update(snapshot(FIRST, "Walk", "First private conversation").toString());
        check(state.companionId().equals(FIRST), "Use the stable companion identity");
        state.notice("error", "The first companion's failed request");
        state.update(snapshot(FIRST, "Walk renamed", "Same conversation").toString());
        check(state.errorNotice() && state.notice().contains("first companion"), "Refresh preserves this companion's notice");

        state.update(snapshot(SECOND, "Luna", "Second private conversation").toString());
        check(state.companionId().equals(SECOND), "Selection accepts the new server identity");
        check(!state.errorNotice() && state.notice().isBlank(), "Selection clears the previous companion's transient error");
        check(state.string("name", "").equals("Luna"), "The profile follows the selected companion");
        check(state.messages().toString().contains("Second private") && !state.messages().toString().contains("First private"), "Conversations replace each other without mixing");

        var listSnapshot = snapshot(SECOND, "Luna", "Second private conversation");
        var list = new JsonArray();
        list.add("malformed"); list.add(new JsonObject());
        for (int i = 0; i < 12; i++) {
            var entry = new JsonObject();
            entry.addProperty("id", new java.util.UUID(0, i + 1).toString());
            entry.addProperty("name", "Companion " + i);
            list.add(entry); list.add(entry.deepCopy());
        }
        listSnapshot.add("companions", list);
        state.update(listSnapshot.toString());
        check(state.companions().size() == 8, "The selector is bounded to eight valid distinct companions");
        check(state.companions().get(0).getAsJsonObject().get("name").getAsString().equals("Companion 0"), "Malformed and duplicate entries do not hide valid companions");
        state.update("[]");
        check(state.companionId().equals(SECOND), "An invalid snapshot cannot discard the selected state");
        check(state.errorNotice(), "An invalid snapshot reports an error");
        state.clear();
        check(!state.received() && state.companionId().isBlank() && state.messages().isEmpty() && state.companions().isEmpty(), "Disconnect discards every companion's cached data");
        check(state.notice().isBlank() && !state.errorNotice(), "Disconnect discards transient notices");
        System.out.println("ClientCompanionStateTest: " + checks + " checks passed");
    }

    private static JsonObject snapshot(String id, String name, String message) {
        var snapshot = new JsonObject();
        snapshot.addProperty("companion_id", id);
        snapshot.addProperty("entity_uuid", "00000000-0000-0000-0000-000000000099");
        snapshot.addProperty("name", name);
        var messages = new JsonArray();
        var line = new JsonObject(); line.addProperty("role", "assistant"); line.addProperty("text", message);
        messages.add(line); snapshot.add("messages", messages);
        return snapshot;
    }

    private static void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
}
