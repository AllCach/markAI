package dev.markai.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** A bounded immutable-by-replacement view received only for the local owner. */
@Environment(EnvType.CLIENT)
public final class ClientCompanionState {
    private JsonObject state = new JsonObject();
    private String notice = "";
    private String noticeKind = "status";
    private long noticeUntil;
    private long updatedAt;

    public void update(String json) {
        try {
            var parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Invalid state");
            state = parsed.getAsJsonObject();
            updatedAt = System.nanoTime();
        } catch (RuntimeException error) { notice("error", "Не удалось прочитать состояние компаньона."); }
    }
    public void clear() { state = new JsonObject(); notice = ""; noticeUntil = 0; updatedAt = 0; }
    public void notice(String kind, String text) {
        noticeKind = kind; notice = text; noticeUntil = System.nanoTime() + 12_000_000_000L;
    }
    public boolean received() { return updatedAt != 0; }
    public boolean stale() { return updatedAt != 0 && System.nanoTime() - updatedAt > 5_000_000_000L; }
    public String notice() { return System.nanoTime() < noticeUntil ? notice : string("error", ""); }
    public boolean errorNotice() { return (System.nanoTime() < noticeUntil && noticeKind.equals("error")) || !string("error", "").isBlank(); }
    public JsonObject data() { return state; }
    public String string(String key, String fallback) { return string(state, key, fallback); }
    public double number(String key, double fallback) { return number(state, key, fallback); }
    public boolean bool(String key, boolean fallback) { return bool(state, key, fallback); }
    public JsonObject settings() { return object(state, "settings"); }
    public JsonObject traits() { return object(state, "traits"); }
    public JsonObject emotions() { return object(state, "emotions"); }
    public JsonArray messages() { return array(state, "messages"); }
    public JsonArray memories() { return array(state, "memories"); }
    public static JsonObject object(JsonObject source, String key) {
        var value = source.get(key); return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    public static JsonArray array(JsonObject source, String key) {
        var value = source.get(key); return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
    public static String string(JsonObject source, String key, String fallback) {
        try { return source.has(key) && !source.get(key).isJsonNull() ? source.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    public static double number(JsonObject source, String key, double fallback) {
        try { double value = source.has(key) ? source.get(key).getAsDouble() : fallback; return Double.isFinite(value) ? value : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    public static boolean bool(JsonObject source, String key, boolean fallback) {
        try { return source.has(key) ? source.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
}
