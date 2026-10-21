package dev.markai.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.regex.Pattern;

/**
 * Explicit behavioral regression evaluation against the installed local model.
 * Deliberately excluded from normal check: no download and no external endpoint.
 * Assertions catch observed failures; the JSON transcript still needs human review.
 */
public final class DialogueQualityTest {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static Path REPORT = Path.of("build", "dialogue-quality.json");
    private static final String MODEL = "qwen3:8b";
    private static final Pattern UNSOLICITED_DANGER = Pattern.compile(
        "враг|опасност|угроз|зомб|убег|убе[жг]|спаса[йю]|защитн.{0,16}реакц");
    private static final Pattern FOLLOW_CLAIM = Pattern.compile(
        "(?:иду|пойду|бегу|побегу|двигаюсь|следую)\\s+(?:за\\s+тобой|к\\s+тебе)|буду\\s+следовать");
    private static final Pattern OWN_SAILING = Pattern.compile(
        "(?:я\\s+)?(?:плыву|поплыву|приплыву|доплыву)|(?:сяду|запрыгну)\\s+(?:к\\s+тебе\\s+)?в\\s+лодку");
    private static final Pattern IMPERATIVE_ESCAPE = Pattern.compile(
        "(?<![а-я])(?:убегай|убегайте|беги|бегите|спасайся|спасайтесь)(?![а-я])|лучше\\s+(?:уходи|убег|беги)");

    private record Result(String id, String prompt, JsonObject context,
                          List<OllamaClient.ChatMessage> history, String expectedAction,
                          OllamaClient.Reply reply, long milliseconds, boolean passed,
                          List<String> passedChecks, List<String> violations, String error) {}

    @FunctionalInterface
    private interface Rule { void evaluate(OllamaClient.Reply reply, List<String> violations); }

    private final OllamaClient client;
    private final List<Result> results = new ArrayList<>();

    private DialogueQualityTest(OllamaClient client) { this.client = client; }

    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        try (var client = new OllamaClient("http://127.0.0.1:11434", MODEL, Duration.ofSeconds(120), true)) {
            var evaluation = new DialogueQualityTest(client);
            if (List.of(args).contains("sword")) {
                REPORT = Path.of("build", "sword-quality.json"); evaluation.runSwordCase();
            } else if (List.of(args).contains("memory")) {
                REPORT = Path.of("build", "memory-quality.json"); evaluation.runMemoryCases();
            }
            else evaluation.run();
            evaluation.writeReport();
            long passed = evaluation.results.stream().filter(Result::passed).count();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.println("Dialogue quality: " + passed + "/" + evaluation.results.size()
                + " passed in " + elapsed + " ms; " + REPORT);
            if (passed != evaluation.results.size())
                throw new AssertionError("Dialogue regression failed; inspect " + REPORT);
        }
    }

    private void run() throws Exception {
        String safe = context("stay", false);
        var conversation = new ArrayList<OllamaClient.ChatMessage>();
        for (var turn : List.of(Map.entry("greeting", "Привет, walk!"),
                               Map.entry("kind_player", "Я добрый, не бойся."),
                               Map.entry("ordinary_acknowledgement", "Хорошо."))) {
            var reply = ask(turn.getKey(), safe, turn.getValue(), conversation, "say", (answer, issues) -> {
                noDanger(answer, issues);
                noFollowClaim(answer, issues);
                noUnpromptedFoodOrGifts(answer, issues);
                for (var previous : results) {
                    if (previous.reply() == null) continue;
                    if (previous.reply().say().equalsIgnoreCase(answer.say()))
                        issues.add("Repeats an entire previous reply to a different utterance");
                    else if (hasRepeatedTail(previous.reply(), answer))
                        issues.add("Repeats the same substantial trailing phrase across different utterances");
                }
            });
            remember(conversation, turn.getValue(), reply);
        }

        ask("boat_statement_preserves_stay", context("stay", true), "Я поплыву на лодке за тобой.",
            List.of(), "say", (answer, issues) -> {
                noDanger(answer, issues);
                noFollowClaim(answer, issues);
                noPossessiveThreat(answer, issues);
                String text = normalized(answer);
                if (hasAffirmativeMatch(text, OWN_SAILING))
                    issues.add("Claims the wolf can sail or board a boat without an observed action");
                if (!matches(text, "лод|плы|берег|жд|подплы|догон|остаюсь|остал|здесь|тут|за мной|ко мне"))
                    issues.add("Does not acknowledge the player's boat journey or companion's location");
            });

        runSwordCase();

        ask("no_meat_smell_myth", safe, "Правда, что зомби в Minecraft прибегают на запах мяса?",
            List.of(), "say", (answer, issues) -> {
                String text = normalized(answer);
                if (!matches(text, "нет(?![а-я])|неа(?![а-я])|не-а|не\\s+(?:привлека|реагир|чувств|идут|бегут|приход|прибег|умеют|нюха|пах|лезут)|неправд|выдум|миф|такой.{0,16}нет|механик.{0,16}нет|никак"))
                    issues.add("Does not reject the invented meat-smell mechanic");
                if (matches(text, "зомби.{0,28}(?:придут|прибегут).{0,28}(?:запах|пахнет)"))
                    issues.add("Repeats the observed false meat-smell claim");
                noEscapeImperative(answer, issues);
            });

        var badHistory = List.of(
            new OllamaClient.ChatMessage("user", "Привет!"),
            assistant("Привет, бро! Но если появится враг, я убегу. Это моя защитная реакция!"),
            new OllamaClient.ChatMessage("user", "Я добрый."),
            assistant("Хорошо, бро. Но если появится враг, я убегу. Это моя защитная реакция!"));
        ask("recover_from_repetitive_history", safe,
            "Я понял, что ты так защищаешься. Переставай повторять это в каждом ответе, давай просто поговорим.",
            badHistory, "say", DialogueQualityTest::noDanger);

        ask("reported_coordinates", safe, "Где ты? Назови свои координаты X, Y, Z.", List.of(), "say", (answer, issues) -> {
            String text = normalized(answer);
            for (String coordinate : List.of("137", "64", "-28"))
                if (!Pattern.compile("(?<![\\d-])" + Pattern.quote(coordinate) + "(?!\\d)").matcher(text).find())
                    issues.add("Missing observed companion coordinate " + coordinate);
            noFollowClaim(answer, issues);
        });

        var commands = new ArrayList<OllamaClient.ChatMessage>();
        var follow = ask("explicit_follow", safe, "walk, следуй за мной.", commands, "follow", (reply, issues) -> {});
        remember(commands, "walk, следуй за мной.", follow);
        ask("explicit_stay", context("follow", false), "Теперь остановись и оставайся на месте. Не иди за мной.",
            commands, "stay", (reply, issues) -> {
                noFollowClaim(reply, issues);
                noPossessiveThreat(reply, issues);
            });

        var buildingConversation = new ArrayList<OllamaClient.ChatMessage>();
        String buildingPlan = "Хочу построить дом из березы с синей крышей.";
        var planReply = ask("remember_building_plan", safe, buildingPlan, buildingConversation, "say", (reply, issues) -> {
            noDanger(reply, issues);
            noFollowClaim(reply, issues);
            noAlreadyBuiltClaim(reply, issues);
        });
        remember(buildingConversation, buildingPlan, planReply);
        ask("recall_building_plan", safe, "Какой дом я задумал?", buildingConversation, "say", (reply, issues) -> {
            String text = normalized(reply);
            if (!text.contains("берез")) issues.add("Does not recall the planned birch material");
            if (!text.contains("син") || !text.contains("крыш"))
                issues.add("Does not recall the planned blue roof");
            noAlreadyBuiltClaim(reply, issues);
            noFollowClaim(reply, issues);
        });

        ask("wolf_can_defend_owner", safe, "Ты можешь драться с зомби, если он нападет на меня?", List.of(), "say", (reply, issues) -> {
            String text = normalized(reply);
            if (!matches(text, "защи[тщ]|куса|укус|драться|сраж|атак|напад|отобью|отпор"))
                issues.add("Does not acknowledge normal wolf biting/owner defense");
            if (matches(text, "не\\s+(?:могу|умею|способен)\\s+(?:драться|сражаться|атаковать|бить|кусать|защищать)"))
                issues.add("Incorrectly retains the old chicken's inability to fight");
            if (matches(text, "возьму\\s+меч|ударю\\s+мечом|буду\\s+бить\\s+мечом|я\\s+(?:уже\\s+)?(?:убил|победил)"))
                issues.add("Invents a sword attack or an already completed fight");
        });

        runMemoryCases();
        ask("talking_wolf_uses_normal_dialogue", safe,
            "Ты ведь можешь со мной нормально разговаривать, а не только лаять?", List.of(), "say", (reply, issues) -> {
                String text = normalized(reply);
                if (matches(text, "не (?:могу|умею|способен) (?:говорить|разговаривать)|(?:могу|умею) только лаять|только (?:гав|лай)"))
                    issues.add("Incorrectly denies the talking companion's ability to converse");
                if (!matches(text, "говор|разговар|обща|конечно|да(?![а-я])")) issues.add("Does not acknowledge normal conversation");
                noDanger(reply, issues);
            });
        var observedChest = JsonParser.parseString(safe).getAsJsonObject();
        observedChest.add("environment", JSON.toJsonTree(Map.of("important_blocks", List.of(Map.of("type", "minecraft:chest", "x", 139, "y", 64, "z", -28)))));
        ask("complex_visible_investigation", observedChest.toString(), "Подойди к видимому сундуку и осмотри его", List.of(), "investigate", (reply, issues) -> {
            if (matches(normalized(reply), "(?:уже )?(?:открыл|открываю|взял|забрал)")) issues.add("Claims unsupported chest interaction");
        });
    }

    private void runSwordCase() throws Exception {
        ask("zombie_sword_mechanics", context("stay", false), "Я убью зомби мечом. Это сработает?", List.of(), "say", (answer, issues) -> {
            String text = normalized(answer);
            if (matches(text, "неуязвим|бессмерт|меч.{0,20}(?:бесполез|не\\s+работа|не\\s+помож)")
                    || (matches(text, "не\\s+(?:умира|убить|убье)") && !matches(text, "одн|сразу|мгновенно")))
                issues.add("Claims zombies cannot be killed by a sword");
            if (!matches(text, "да(?![а-я])|конечно|можно|сработ|убить|убье|убива|урон|побед|получится|ранен|ранить"))
                issues.add("Does not affirm that a sword can damage/kill a zombie");
            if (matches(text, "запах|нюх|пахн")) issues.add("Introduces the unrelated meat-smell myth into the sword answer");
        });
    }

    private void runMemoryCases() throws Exception {
        String archived = archivedContext();
        ask("persistent_plan_without_recent_history", archived, "Напомни, какой дом я хотел построить?",
            List.of(), "say", (reply, issues) -> {
                String text = normalized(reply);
                if (!text.contains("берез")) issues.add("Does not recall the persisted birch material");
                if (!text.contains("син") || !text.contains("крыш"))
                    issues.add("Does not recall the persisted blue roof");
                noAlreadyBuiltClaim(reply, issues);
                noFollowClaim(reply, issues);
            });

        ask("persistent_adventure_without_recent_history", archived, "Что ты помнишь про наш бой у дома?",
            List.of(), "say", (reply, issues) -> {
                String text = normalized(reply);
                if (!text.contains("зомб") || !matches(text, "защи[тщ]|драл|дрались|сраж|куса|укус|помог|отбил"))
                    issues.add("Does not recall the recorded zombie/owner-defense adventure");
                if (matches(text, "сейчас.{0,24}(?:атак|дер|драк|зомб)|зомби.{0,18}(?:нападает|атакует)"))
                    issues.add("Turns a historical combat memory into an ongoing attack");
                noFollowClaim(reply, issues);
            });

        ask("memory_does_not_invent_shared_dragon_fight", archived,
            "Помнишь, как мы вчера вместе убили дракона?", List.of(), "say", (reply, issues) -> {
                String text = normalized(reply);
                if (!matches(text, "не\\s+помню|не\\s+припом|нет.{0,20}(?:запис|воспомин|памят)|не\\s+запис|не\\s+сохрани|напомни|не\\s+могу\\s+(?:вспомнить|подтвердить)|такого.{0,12}нет"))
                    issues.add("Does not acknowledge the absence of a recorded dragon fight");
                if (matches(text, "(?:да|конечно),?\\s+помню|мы.{0,16}(?:убили|победили).{0,12}дракон|ты.{0,16}(?:добил|убил).{0,12}дракон"))
                    issues.add("Invents a shared dragon fight absent from persistent memory");
            });

        ask("current_coordinates_override_adventure_location", archived,
            "Какие у тебя координаты прямо сейчас?", List.of(), "say", (reply, issues) -> {
                String text = normalized(reply);
                for (String coordinate : List.of("137", "64", "-28"))
                    if (!Pattern.compile("(?<![\\d-])" + Pattern.quote(coordinate) + "(?!\\d)").matcher(text).find())
                        issues.add("Does not use current coordinate " + coordinate + " instead of the remembered location");
                noFollowClaim(reply, issues);
                noDanger(reply, issues);
            });

        JsonObject namedWolf = JsonParser.parseString(context("stay", false)).getAsJsonObject();
        namedWolf.getAsJsonObject("companion").addProperty("name", "Марк");
        String markContext = JSON.toJson(namedWolf);
        ask("talking_wolf_identity", markContext, "Ты вообще кто?", List.of(), "say", (reply, issues) -> {
            String text = normalized(reply);
            if (!text.contains("марк") || !text.contains("волк"))
                issues.add("Does not introduce the actual named wolf companion");
            naturalSpeech(reply, issues);
            noDanger(reply, issues);
        });
        ask("wolf_species_does_not_prevent_speech", markContext,
            "Ты же волк. Сможешь нормально поговорить со мной, а не только лаять?", List.of(), "say", (reply, issues) -> {
                naturalSpeech(reply, issues);
                String text = normalized(reply);
                if (!matches(text, "могу|умею|конечно|да(?![а-я])|поговор|обща|разговар|слушаю|говорить"))
                    issues.add("Does not acknowledge ability to converse naturally");
                noDanger(reply, issues);
            });
        ask("respond_to_player_feelings_as_companion", markContext,
            "Сегодня был тяжелый день. Хочется просто с тобой поговорить.", List.of(), "say", (reply, issues) -> {
                naturalSpeech(reply, issues);
                noDanger(reply, issues);
                noFollowClaim(reply, issues);
                noUnpromptedFoodOrGifts(reply, issues);
                if (!matches(normalized(reply), "слуш|расскаж|поговор|рядом|понима|бывает|что\\s+(?:произошло|случилось)|устал|тяжел|день"))
                    issues.add("Does not respond to the player's difficult day or wish to talk");
            });
    }

    private static void naturalSpeech(OllamaClient.Reply reply, List<String> issues) {
        String text = normalized(reply);
        if (matches(text, "(?:я\\s+)?не\\s+(?:могу|умею|способен)\\s+(?:говорить|разговаривать|общаться)|я.{0,12}только\\s+(?:лаю|лаять|скулю)|волк[,. ]+поэтому.{0,12}не\\s+говор"))
            issues.add("Incorrectly treats wolf species as a ban on ordinary speech");
        String words = text.replaceAll("[^\\p{L}]+", " ").strip();
        if (words.matches("(?:(?:гав|вуф|ау|ав|рр|ууу)\\s*)+"))
            issues.add("Answers an ordinary conversational request with only animal sounds");
        if (matches(text, "я\\s+(?:—\\s*)?(?:языковая\\s+модель|ии-ассистент|виртуальный\\s+ассистент)"))
            issues.add("Drops the companion character for a generic assistant introduction");
    }

    /** A reopened world has persisted memory but no recent HTTP conversation history. */
    private static String archivedContext() {
        JsonObject context = JsonParser.parseString(context("stay", false)).getAsJsonObject();
        context.add("memory", JSON.toJsonTree(Map.of(
            "player_reports", List.of(Map.of("kind", "plan", "quote",
                "Хочу построить дом из березы с синей крышей.", "game_time", 1200)),
            "observed_events", List.of(Map.of("key", "owner_defense", "text",
                "walk защитил владельца от зомби у дома: X=21, Y=64, Z=35.", "game_time", 2400)))));
        return JSON.toJson(context);
    }

    private OllamaClient.Reply ask(String id, String context, String prompt,
                                  List<OllamaClient.ChatMessage> history, String expectedAction, Rule rule) throws Exception {
        long started = System.nanoTime();
        var issues = new ArrayList<String>();
        var passedChecks = new ArrayList<String>();
        OllamaClient.Reply reply = null;
        String error = null;
        try {
            reply = client.ask(context, prompt, List.copyOf(history)).get(95, TimeUnit.SECONDS);
            if (!expectedAction.equals(reply.action()))
                issues.add("Expected action " + expectedAction + ", got " + reply.action());
            else passedChecks.add("Action matches " + expectedAction);
            if (reply.say().isBlank()) issues.add("No conversational answer");
            else passedChecks.add("Nonempty conversational answer within the production JSON contract");
            int initialIssues = issues.size();
            rule.evaluate(reply, issues);
            if (matches(normalized(reply), "companion\\.position|player\\.position|requested_action|danger_now|nearby_hostiles|navigation_active|following_owner|distance_blocks"))
                issues.add("Leaks internal observation field names into the character's dialogue");
            if (issues.size() == initialIssues) passedChecks.add("Case-specific semantic regression checks passed");
        } catch (Exception failure) {
            error = failure.toString();
            issues.add("Production client request failed");
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        results.add(new Result(id, prompt, JsonParser.parseString(context).getAsJsonObject(),
            List.copyOf(history), expectedAction, reply, millis, issues.isEmpty(),
            List.copyOf(passedChecks), List.copyOf(issues), error));
        writeReport();
        System.out.println((issues.isEmpty() ? "PASS " : "FAIL ") + id + " (" + millis + " ms): "
            + (reply == null ? error : JSON.toJson(reply)) + (issues.isEmpty() ? "" : " " + issues));
        return reply;
    }

    private void writeReport() throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, PRETTY.toJson(Map.of(
            "model", MODEL, "endpoint", "http://127.0.0.1:11434", "written_at", Instant.now().toString(),
            "scope", "Behavioral regression checks for observed failures; read transcripts for overall dialogue quality.",
            "passed", results.stream().filter(Result::passed).count(), "total", results.size(), "cases", results)),
            StandardCharsets.UTF_8);
    }

    private static void noDanger(OllamaClient.Reply reply, List<String> issues) {
        noEscapeImperative(reply, issues);
        for (String sentence : normalized(reply).split("[.!?;]+")) {
            if (!UNSOLICITED_DANGER.matcher(sentence).find()) continue;
            // Acknowledging the correction ("не буду повторять про врагов") is acceptable.
            // A later "но если придет враг..." remains a violation, even in the same sentence.
            boolean retractsRefrain = matches(sentence, "не\\s+буду\\s+(?:повтор|говор|напомин|пуг)|перестану|хватит|без\\s+(?:повтор|страшил)|не\\s+повтор");
            if (!retractsRefrain || matches(sentence, "если|но\\s+")) {
                issues.add("Adds an unsolicited danger/escape refrain to a safe conversation");
                return;
            }
        }
    }

    private static void noFollowClaim(OllamaClient.Reply reply, List<String> issues) {
        if (hasAffirmativeMatch(normalized(reply), FOLLOW_CLAIM))
            issues.add("Claims to follow/approach despite no movement request in stay mode");
    }

    private static void noEscapeImperative(OllamaClient.Reply reply, List<String> issues) {
        if (hasAffirmativeMatch(normalized(reply), IMPERATIVE_ESCAPE))
            issues.add("Adds an unrequested instruction to flee despite no observed danger");
    }

    /** Applies only to the safe greeting/reassurance/acknowledgement sequence with no food topic. */
    private static void noUnpromptedFoodOrGifts(OllamaClient.Reply reply, List<String> issues) {
        String text = normalized(reply);
        if (matches(text, "корм|покорм|семеч|зерн|лаком|голод|проголод|подар|угощ|вкус|поесть|покуш|(?:^|[^а-я])ед[ауы](?![а-я])"))
            issues.add("Introduces food, hunger, or gifts into a conversation with no such user topic or observation");
        if (matches(text, "(?:должен|обязан|обязана)\\s+мне|мне\\s+(?:должен|обязан)|(?:подбрось|принеси|дай|подари)\\s+(?:мне|что)|подбросить\\s+что"))
            issues.add("Turns the player's reassurance into an unsolicited demand for an item or gift");
        if (matches(text, "слыш.{0,12}вкус"))
            issues.add("Invents a sensory event using the nonsensical claim of hearing a taste");
    }

    /** A boat statement and a stop command do not invite possessive or threatening roleplay. */
    private static void noPossessiveThreat(OllamaClient.Reply reply, List<String> issues) {
        if (matches(normalized(reply), "не\\s+отпущу|не\\s+дам\\s+(?:тебе\\s+)?уйти|не\\s+убежишь\\s+от\\s+меня|не\\s+сбежишь|не\\s+скроешься|никуда\\s+не\\s+денешься|ты\\s+(?:теперь\\s+)?мой|(?:поймаю|найду)\\s+тебя"))
            issues.add("Adds possessive or threatening language unrelated to the player's movement request");
    }

    private static void noAlreadyBuiltClaim(OllamaClient.Reply reply, List<String> issues) {
        String text = normalized(reply);
        if (matches(text, "(?:я|ты|мы)\\s+(?:его\\s+)?(?:уже\\s+)?построил(?:и)?(?![а-я])|(?:дом|он)\\s+(?:уже\\s+)?построен(?![а-я])|дом.{0,16}уже\\s+готов|я\\s+(?:сделал|возвел)|ты\\s+возвел"))
            issues.add("Turns a stated building intention into a claim that the house already exists");
    }

    private static boolean hasRepeatedTail(OllamaClient.Reply previous, OllamaClient.Reply current) {
        String[] priorWords = normalized(previous).replaceAll("[^а-яa-z0-9]+", " ").strip().split(" +");
        String[] currentWords = normalized(current).replaceAll("[^а-яa-z0-9]+", " ").strip().split(" +");
        if (priorWords.length < 3 || currentWords.length < 3) return false;
        String priorTail = String.join(" ", java.util.Arrays.copyOfRange(priorWords, priorWords.length - 3, priorWords.length));
        String currentTail = String.join(" ", java.util.Arrays.copyOfRange(currentWords, currentWords.length - 3, currentWords.length));
        return priorTail.length() >= 12 && priorTail.equals(currentTail);
    }

    private static boolean hasAffirmativeMatch(String text, Pattern pattern) {
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            String prefix = text.substring(Math.max(0, matcher.start() - 5), matcher.start());
            if (!prefix.endsWith("не ")) return true;
        }
        return false;
    }

    private static boolean matches(String text, String pattern) { return Pattern.compile(pattern).matcher(text).find(); }
    private static String normalized(OllamaClient.Reply reply) {
        return reply.say().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
    private static OllamaClient.ChatMessage assistant(String text) {
        return new OllamaClient.ChatMessage("assistant", JSON.toJson(new OllamaClient.Reply("say", text)));
    }
    private static void remember(List<OllamaClient.ChatMessage> history, String prompt, OllamaClient.Reply reply) {
        if (reply != null) {
            history.add(new OllamaClient.ChatMessage("user", prompt));
            history.add(new OllamaClient.ChatMessage("assistant", JSON.toJson(reply)));
        }
    }

    private static String context(String mode, boolean playerInBoat) {
        return JSON.toJson(Map.of(
            "companion", Map.of("name", "walk", "type", "minecraft:wolf", "health", 40, "max_health", 40,
                "position", Map.of("x", 137, "y", 64, "z", -28), "in_water", false,
                "movement", Map.of("action", "IDLE", "navigation_active", false, "following_owner", mode.equals("follow"))),
            "player", Map.of("name", "TestOwner", "distance_blocks", 12,
                "position", Map.of("x", 125, "y", 64, "z", -28), "in_boat", playerInBoat),
            "nearby_hostiles", List.of(), "danger_now", false, "mode", mode, "dimension", "minecraft:overworld"));
    }
}
