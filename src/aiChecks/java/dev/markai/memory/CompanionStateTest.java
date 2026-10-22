package dev.markai.memory;

import com.google.gson.Gson;
import dev.markai.ai.OllamaClient;
import dev.markai.training.WolfTraining;
import dev.markai.training.WolfTraining.Trick;

public final class CompanionStateTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        Gson json = new Gson();
        var memory = new CompanionMemory();
        memory.rememberExchange("Хочу построить дом из берёзы с синей крышей.", new OllamaClient.Reply("say", "Запомню."), 10);
        memory.rememberEvent("won:zombie", "Я победил зомби.", 20);
        memory = json.fromJson(json.toJson(memory), CompanionMemory.class);
        memory.normalize();
        check(memory.history().size() == 2, "Conversation survives saving and loading");
        String context = json.toJson(memory.context("Какой дом я хотел?"));
        check(context.contains("берёзы") && context.contains("синей крышей") && context.contains("plan"), "Exact plan is remembered as a player report");
        check(context.contains("Я победил зомби"), "Observed adventure survives reload");
        for (int i = 0; i < 12; i++) memory.rememberStatement("Запомни номер " + i, 25 + i);
        check(json.toJson(memory.context("какой дом")).contains("синей крышей"), "Relevant old plan outranks newer unrelated facts");
        memory.rememberEvent("won:zombie", "Я победил зомби.", 21);
        check(json.toJsonTree(memory.context("")).getAsJsonObject().getAsJsonArray("observed_events").size() == 1, "Repeated event deduplicated");
        for (int i = 0; i < 80; i++) memory.rememberExchange("Привет " + i, new OllamaClient.Reply("say", "<".repeat(240)), i + 30);
        check(memory.history().size() <= 24 && memory.history().get(1).content().length() < 1024, "Bounded history preserves HTML-sensitive reply");
        for (int i = 0; i < 80; i++) memory.rememberStatement("Запомни " + i + " " + "я".repeat(480), i + 200);
        check(json.toJson(memory.context("Запомни")).length() <= 3000, "Memory context stays bounded");
        check(!json.toJsonTree(memory.context("")).getAsJsonObject().getAsJsonArray("player_reports")
            .toString().contains("Хочу"), "Old exact reports evicted at bound; compacted conversation may retain a summary");
        memory.clear();
        check(memory.history().isEmpty() && json.toJson(memory.context("")).equals("{\"player_reports\":[],\"observed_events\":[]}")
            || memory.history().isEmpty() && json.toJson(memory.context("")).equals("{\"observed_events\":[],\"player_reports\":[]}"), "Explicit forget clears memories");
        var training = new WolfTraining();
        check(!training.canTrain(Trick.FETCH), "Fetch requires level two");
        check(training.train(Trick.BARK) && training.train(Trick.BARK) && training.train(Trick.BARK), "Three real lessons learn bark");
        check(training.knows(Trick.BARK) && training.level() == 2 && training.experience() == 15, "Lessons grant level and skill");
        check(!training.train(Trick.BARK) && training.experience() == 15, "Learned trick cannot farm lesson XP");
        check(training.canTrain(Trick.FETCH), "Level unlocks fetch training");
        training.completed(1000); training.completed(1001);
        check(training.experience() == 17, "Repeated commands rate-limit practice XP");
        training = json.fromJson(json.toJson(training), WolfTraining.class);
        training.normalize();
        check(training.knows(Trick.BARK) && training.experience() == 17 && training.level() == 2, "Progress survives restart");
        check(Trick.parse("ГОЛОС!") == Trick.BARK && Trick.parse("принеси предмет") == Trick.FETCH && Trick.parse("я приду к тебе") == null, "Only clear trick commands accepted");
        training = json.fromJson("{\"experience\":99999,\"lessons\":{\"BARK\":99,\"HACK\":1}}", WolfTraining.class);
        training.normalize();
        check(training.level() == 10 && training.progress(Trick.BARK) == 3, "Loaded progress normalized");
        System.out.println("Companion memory/training: " + checks + " checks passed");
    }
}
