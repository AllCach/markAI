package dev.markai.memory;

import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonDeserializer;
import java.lang.reflect.Type;

/** Provenance is retained: a player report or a generated summary is never a confirmed event. */
@com.google.gson.annotations.JsonAdapter(MemoryEntry.Adapter.class)
public record MemoryEntry(UUID id, UUID companionId, long gameTime, String category, String content,
                          String source, SourceType sourceType, Reliability reliability,
                          int importance, String dimension, Location location) {
    public enum SourceType { WORLD_EVENT, PLAYER_STATEMENT, INFERENCE, AI_INTERPRETATION, CONVERSATION_SUMMARY }
    public enum Reliability { CONFIRMED, REPORTED, INFERRED, GENERATED }
    public record Location(int x, int y, int z) {}

    /** One corrupt episodic record must not make the entire companion save unreadable. */
    public static final class Adapter implements JsonSerializer<MemoryEntry>, JsonDeserializer<MemoryEntry> {
        @Override public JsonElement serialize(MemoryEntry entry, Type type, JsonSerializationContext context) {
            var object = new JsonObject();
            object.addProperty("id", entry.id() == null ? null : entry.id().toString());
            object.addProperty("companionId", entry.companionId() == null ? null : entry.companionId().toString());
            object.addProperty("gameTime", entry.gameTime()); object.addProperty("category", entry.category());
            object.addProperty("content", entry.content()); object.addProperty("source", entry.source());
            object.addProperty("sourceType", entry.sourceType() == null ? null : entry.sourceType().name());
            object.addProperty("reliability", entry.reliability() == null ? null : entry.reliability().name());
            object.addProperty("importance", entry.importance()); object.addProperty("dimension", entry.dimension());
            if (entry.location() != null) object.add("location", context.serialize(entry.location()));
            return object;
        }

        @Override public MemoryEntry deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            try {
                JsonObject object = json.getAsJsonObject();
                String id = text(object, "id"), companion = text(object, "companionId");
                String sourceType = text(object, "sourceType"), reliability = text(object, "reliability");
                return new MemoryEntry(id == null ? null : UUID.fromString(id), companion == null ? null : UUID.fromString(companion),
                    object.has("gameTime") ? object.get("gameTime").getAsLong() : -1,
                    text(object, "category"), text(object, "content"), text(object, "source"),
                    sourceType == null ? null : SourceType.valueOf(sourceType),
                    reliability == null ? null : Reliability.valueOf(reliability),
                    object.has("importance") ? object.get("importance").getAsInt() : -1,
                    text(object, "dimension"), object.has("location") && !object.get("location").isJsonNull()
                        ? context.deserialize(object.get("location"), Location.class) : null);
            } catch (RuntimeException corrupt) { return null; }
        }

        private static String text(JsonObject object, String key) {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? null : value.getAsString();
        }
    }
}
