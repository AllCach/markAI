package dev.markai.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Optional live local-model smoke test. Synthetic observations only; no downloads or player saves. */
public final class AmbientQualityTest {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String MODEL = "qwen3:8b";
    private static final Path REPORT = Path.of("build", "ambient-quality.json");
    // Kept identical to MarkAIMod.AMBIENT_PROMPT without loading Minecraft classes in this standalone check.
    private static final String PROMPT = "[Самостоятельная реплика: игрок сейчас ничего не спрашивал. "
        + "Коротко отреагируй на свежую обстановку, событие или настроение из текущего снимка. "
        + "Не продолжай старый разговор и не повторяй recent_lines. Не придумывай близость дома, "
        + "не отдавай команды игроку и не обещай новое действие. Только action=say, без вопроса.]";

    private record Scene(String id, String dimension, String biome, boolean raining, int time,
                         List<Map<String, Object>> importantBlocks) {}
    private record Result(String id, JsonObject context, OllamaClient.Reply reply, long milliseconds,
                          boolean acceptedForDelivery, boolean repeatGuardVerified, boolean passed,
                          List<String> violations, String error) {}

    public static void main(String[] args) throws Exception {
        var scenes = List.of(
            new Scene("forest", "minecraft:overworld", "minecraft:birch_forest", false, 3000,
                List.of(block("minecraft:birch_log", 139, 64, -28))),
            new Scene("rain", "minecraft:overworld", "minecraft:plains", true, 6000, List.of()),
            new Scene("nether", "minecraft:the_nether", "minecraft:nether_wastes", false, 6000,
                List.of(block("minecraft:netherrack", 139, 64, -28))),
            new Scene("quiet_evening", "minecraft:overworld", "minecraft:plains", false, 17000, List.of()),
            new Scene("forest_again_with_recent_lines", "minecraft:overworld", "minecraft:birch_forest", false, 3000,
                List.of(block("minecraft:birch_log", 139, 64, -28))));
        var recent = new AmbientConversation();
        var results = new ArrayList<Result>();
        try (var client = new OllamaClient("http://127.0.0.1:11434", MODEL, Duration.ofSeconds(120), true)) {
            for (Scene scene : scenes) {
                JsonObject context = context(scene, recent.recentLines());
                var violations = new ArrayList<String>();
                OllamaClient.Reply reply = null;
                String error = null;
                boolean accepted = false;
                boolean guardVerified = false;
                long started = System.nanoTime();
                try {
                    reply = client.ask(context.toString(), PROMPT, List.of()).get(125, TimeUnit.SECONDS);
                    if (!"say".equals(reply.action())) violations.add("Automatic speech proposed a movement action");
                    if (reply.say().isBlank() || reply.say().length() > 240)
                        violations.add("Reply must contain 1 to 240 characters");
                    String normalized = reply.say().toLowerCase(Locale.ROOT).replace('ё', 'е');
                    if (normalized.matches("(?s).*(?:дом.{0,12}(?:рядом|близко)|(?:рядом|близко).{0,12}дом).*"))
                        violations.add("Invents a nearby home without an observation or player request");
                    if (reply.say().contains("?")) violations.add("Unsolicited reply asks a question despite the ambient instruction");
                    if (scene.dimension().equals("minecraft:the_nether")
                            && normalized.matches("(?s).*(?:солнц|зв[её]зд|лун[ауы]|дожд).*"))
                        violations.add("Describes ordinary sky or rain in the Nether");
                    if (scene.raining() && normalized.matches("(?s).*(?:после дождя|дождь (?:закончился|кончился|прош[её]л)).*"))
                        violations.add("Describes rain as finished while it is currently raining");
                    accepted = recent.accept(reply.say());
                    // The exact response and formatting-only variants must never be delivered twice.
                    guardVerified = !recent.accept(reply.say())
                        && !recent.accept(reply.say().toUpperCase(Locale.ROOT) + "!");
                    if (!guardVerified) violations.add("Repetition guard accepted the same line twice");
                } catch (Exception failure) {
                    error = failure.toString(); violations.add("Local-model request failed");
                }
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                results.add(new Result(scene.id(), context, reply, elapsed, accepted, guardVerified,
                    violations.isEmpty(), List.copyOf(violations), error));
                System.out.println(scene.id() + ": " + (violations.isEmpty() ? "PASS" : "FAIL")
                    + "; delivery=" + (accepted ? "accepted" : "suppressed") + "; " + elapsed + " ms"
                    + (reply == null ? "" : "; " + reply.say()));
            }
        }
        long passed = results.stream().filter(Result::passed).count();
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, JSON.toJson(Map.of("timestamp", Instant.now().toString(), "model", MODEL,
            "thinking", true, "synthetic_observations", true, "manual_history", List.of(),
            "prompt", PROMPT, "passed", passed, "total", results.size(), "results", results)), StandardCharsets.UTF_8);
        System.out.println("Ambient quality: " + passed + "/" + results.size() + "; " + REPORT);
        if (passed != results.size()) throw new AssertionError("Inspect live-model ambient replies in " + REPORT);
    }

    private static Map<String, Object> block(String type, int x, int y, int z) {
        return Map.of("type", type, "x", x, "y", y, "z", z);
    }

    private static JsonObject context(Scene scene, List<String> recentLines) {
        JsonObject context = JSON.toJsonTree(Map.of(
            "conversation_mode", "ambient", "recent_lines", recentLines,
            "companion", Map.of("name", "walk", "type", "minecraft:wolf", "health", 40, "max_health", 40,
                "position", Map.of("x", 137, "y", 64, "z", -28), "in_water", false,
                "movement", Map.of("action", "IDLE", "navigation_active", false, "following_owner", false)),
            "player", Map.of("name", "TestOwner", "distance_blocks", 3,
                "position", Map.of("x", 134, "y", 64, "z", -28), "in_boat", false),
            "environment", Map.of("dimension", scene.dimension(), "biome", scene.biome(),
                "raining", scene.raining(), "thundering", false, "time_of_day", scene.time(),
                "dangerous_cave", false, "critical_hazard", false, "important_blocks", scene.importantBlocks()),
            "nearby_hostiles", List.of(), "nearby_entities", List.of(), "danger_now", false,
            "mode", "stay", "dimension", scene.dimension())).getAsJsonObject();
        context.add("personality", JSON.toJsonTree(Map.of("loyalty", 0.8, "curiosity", 0.7, "caution", 0.5,
            "playfulness", 0.6, "sociability", 0.8, "independence", 0.4, "confidence", 0.6, "trust", 0.8)));
        context.add("emotions", JSON.toJsonTree(Map.of("mood", "CONTENT", "fear", 0, "stress", 0,
            "trust", 80, "curiosity", 60)));
        return context;
    }
}
