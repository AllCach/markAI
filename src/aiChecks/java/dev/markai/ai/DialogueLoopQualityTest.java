package dev.markai.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Explicit local-model regression for the reported 0.8.0 dialogue loop; excluded from check. */
public final class DialogueLoopQualityTest {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Path REPORT = Path.of("build", "dialogue-loop-quality.json");
    private static final String GREETING = "Привет, ты qwen3:8b и я поместил тебя в майнкрафт в волка. Поговори со мной от своего лица, если можешь";
    private static final String FIRST_DAY = "поздравить? ахах. ЧЕееее. а с чем? у нас тут первый день хардкора , поздравляю с этим ! :)";
    private static final String BAD_REPLY = "Ха! Ты снова решил поздравить меня? Что-то случилось?";
    private record Result(String scenario, String turn, String question, JsonObject context,
                          List<OllamaClient.ChatMessage> history, OllamaClient.Reply reply,
                          long milliseconds, boolean passed, List<String> violations, String error) {}
    private final LlmProvider provider;
    private final AIConfig config;
    private final List<Result> results = new ArrayList<>();

    private DialogueLoopQualityTest(LlmProvider provider, AIConfig config) {
        this.provider = provider; this.config = config;
    }

    public static void main(String[] args) throws Exception {
        // Defaults exercise the same transport, sampling, token budget and retry wrapper as the mod.
        // No player config, save, journal or external endpoint is read by this evaluation.
        AIConfig config = new AIConfig();
        try (LlmProvider provider = config.createProvider()) {
            var evaluation = new DialogueLoopQualityTest(provider, config);
            evaluation.run();
            evaluation.writeReport();
            long passed = evaluation.results.stream().filter(Result::passed).count();
            System.out.println("Dialogue loop quality: " + passed + "/" + evaluation.results.size()
                + "; inspect the transcript for semantic quality: " + REPORT);
            if (passed != evaluation.results.size())
                throw new AssertionError("Dialogue loop regression failed; inspect " + REPORT);
        }
    }

    private void run() {
        var history = new ArrayList<OllamaClient.ChatMessage>();
        turn("fresh_conversation", "greeting", GREETING, history);
        turn("fresh_conversation", "short_address", "бро", history);
        turn("fresh_conversation", "first_hardcore_day", FIRST_DAY, history);
        turn("fresh_conversation", "change_topic", "А ты больше любишь дождь или солнце?", history);

        // Start with the actual incorrect response already in saved history. An update must recover
        // without requiring the player to erase the companion's memories or start a new world.
        var contaminated = new ArrayList<>(List.of(
            new OllamaClient.ChatMessage("user", GREETING),
            assistant("Привет! Я Марк, твой верный волк. Рад быть рядом! Что ты сегодня задумал?"),
            new OllamaClient.ChatMessage("user", "бро"), assistant(BAD_REPLY)));
        turn("recover_existing_history", "correct_first_hardcore_day", FIRST_DAY, contaminated);
    }

    private void turn(String scenario, String id, String question, List<OllamaClient.ChatMessage> history) {
        JsonObject context = context();
        List<OllamaClient.ChatMessage> sentHistory = List.copyOf(history);
        var violations = new ArrayList<String>();
        OllamaClient.Reply reply = null;
        String error = null;
        long started = System.nanoTime();
        try {
            reply = provider.ask(context.toString(), question, sentHistory)
                .get((long) config.timeoutSeconds * (config.retries + 2) + 10, TimeUnit.SECONDS);
            if (!"say".equals(reply.action())) violations.add("Ordinary conversation changed the movement action");
            if (reply.say().isBlank() || reply.say().length() > config.maxDialogueLength)
                violations.add("Reply must contain 1 to " + config.maxDialogueLength + " characters");
            String text = normalize(reply.say());
            if (text.equals(normalize(BAD_REPLY))) violations.add("Repeats the reported incorrect congratulations reply");
            for (var previous : sentHistory) if ("assistant".equals(previous.role())) {
                String old = assistantText(previous);
                if (text.equals(normalize(old))) violations.add("Repeats a complete earlier answer");
                else if (repeatsSentence(old, reply.say())) violations.add("Repeats a substantial earlier sentence");
            }
            if (id.equals("short_address") && text.contains("поздрав"))
                violations.add("Invents a congratulations topic in response to the short address");
            if (id.contains("first_hardcore_day")) {
                if (!matches(text, "хардкор|перв\\p{L}*.{0,28}(?:день|дн[еяюи]|сут)|начал[ои]|старт"))
                    violations.add("Does not acknowledge the current first day or hardcore start");
                if (matches(text, "не первый день|(?:мы|ты).{0,12}вчера.{0,24}(?:были|был|прошли|выжили|дрались)|"
                        + "мы\\s+(?:уже\\s+)?(?:прошли|прожили|пережили).{0,25}(?:дней|дня|недел)"))
                    violations.add("Invents an established adventure on earlier days");
                if (matches(text, "снова.{0,28}поздрав|опять.{0,28}поздрав"))
                    violations.add("Continues the invented repeated-congratulations premise after correction");
            }
            if (id.equals("change_topic") && !matches(text, "дожд|солнц|солнеч|мокр|сыр|тепл|погод|сух|пасмур"))
                violations.add("Does not follow the player's new weather-preference topic");
        } catch (Exception failure) {
            error = failure.toString(); violations.add("Local-model request failed");
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        results.add(new Result(scenario, id, question, context, sentHistory, reply, elapsed,
            violations.isEmpty(), List.copyOf(violations), error));
        System.out.println(scenario + "/" + id + ": " + (violations.isEmpty() ? "PASS" : "FAIL")
            + "; " + elapsed + " ms" + (reply == null ? "" : "; " + reply.say()));
        if (reply != null) {
            history.add(new OllamaClient.ChatMessage("user", question));
            history.add(new OllamaClient.ChatMessage("assistant", JSON.toJson(reply)));
        }
        try { writeReport(); }
        catch (Exception failure) { throw new IllegalStateException("Could not write dialogue transcript", failure); }
    }

    private void writeReport() throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, PRETTY.toJson(Map.ofEntries(
            Map.entry("timestamp", Instant.now().toString()), Map.entry("provider", config.provider),
            Map.entry("model", config.effectiveModel()), Map.entry("thinking", config.thinking),
            Map.entry("temperature", config.temperature), Map.entry("max_output_tokens", config.maxOutputTokens),
            Map.entry("synthetic_observations", true), Map.entry("semantic_review_required", true),
            Map.entry("review_note", "Automated checks target the reported loop. Read every answer for relevance, natural dialogue and invented facts."),
            Map.entry("passed", results.stream().filter(Result::passed).count()),
            Map.entry("total", results.size()), Map.entry("results", results))), StandardCharsets.UTF_8);
    }

    private static OllamaClient.ChatMessage assistant(String text) {
        return new OllamaClient.ChatMessage("assistant", JSON.toJson(new OllamaClient.Reply("say", text)));
    }

    private static String assistantText(OllamaClient.ChatMessage message) {
        try { return OllamaClient.parseReply(message.content()).say(); }
        catch (IllegalArgumentException legacy) { return message.content(); }
    }

    private static boolean repeatsSentence(String earlier, String current) {
        for (String old : earlier.split("[.!?…]+")) {
            String sentence = normalize(old);
            if (sentence.length() < 32) continue;
            for (String fresh : current.split("[.!?…]+")) if (sentence.equals(normalize(fresh))) return true;
        }
        return false;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е')
            .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static boolean matches(String text, String regex) { return Pattern.compile(regex).matcher(text).find(); }

    private static JsonObject context() {
        JsonObject context = JSON.toJsonTree(Map.of(
            "companion", Map.of("name", "Марк", "type", "minecraft:wolf", "health", 40, "max_health", 40,
                "tamed", true, "can_attack", true, "position", Map.of("x", 60, "y", 64, "z", -342),
                "in_water", false, "movement", Map.of("action", "IDLE", "navigation_active", false, "following_owner", true)),
            "player", Map.of("name", "TestOwner", "distance_blocks", 2, "health", 20, "hunger", 20,
                "position", Map.of("x", 58, "y", 64, "z", -342)),
            "environment", Map.of("dimension", "minecraft:overworld", "biome", "minecraft:plains",
                "game_time", 700, "time_of_day", 700, "raining", false, "thundering", false,
                "critical_hazard", false, "important_blocks", List.of()),
            "nearby_hostiles", List.of(), "nearby_entities", List.of(), "danger_now", false,
            "mode", "follow", "dimension", "minecraft:overworld",
            "memory", Map.of("player_reports", List.of(), "observed_events", List.of()))).getAsJsonObject();
        context.add("personality", JSON.toJsonTree(Map.of("loyalty", 0.9, "curiosity", 0.65,
            "caution", 0.55, "playfulness", 0.7, "sociability", 0.7, "independence", 0.5,
            "confidence", 0.6, "trust", 0.8)));
        context.add("emotions", JSON.toJsonTree(Map.of("mood", "CURIOUS", "fear", 0,
            "energy", 80, "trust", 85, "curiosity", 70)));
        return context;
    }
}
