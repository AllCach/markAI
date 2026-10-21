package dev.markai.ai;

import com.google.gson.JsonParser;
import java.util.Locale;

/** Short acknowledgements stay short; numerical answers use the authoritative observation. */
final class GroundedFacts {
    private GroundedFacts() {}
    static OllamaClient.Reply apply(OllamaClient.Reply reply, String observation, String question) {
        String text = question.toLowerCase(Locale.ROOT).replace('ё', 'е').strip();
        String acknowledgement = text.replaceAll("[.!]+$", "").strip();
        if (java.util.Set.of("хорошо", "ладно", "понял", "поняла", "ок", "окей", "ясно", "договорились").contains(acknowledgement))
            return new OllamaClient.Reply(reply.action(), "Договорились.");
        if (java.util.Set.of("спасибо", "благодарю").contains(acknowledgement))
            return new OllamaClient.Reply(reply.action(), "Пожалуйста.");
        boolean ownPosition = text.matches(".*(?:где ты|твои координаты|свои координаты|у тебя координаты|координаты прямо сейчас|координаты x[, ]+y).*" );
        if (!ownPosition) return reply;
        try {
            var position = JsonParser.parseString(observation).getAsJsonObject().getAsJsonObject("companion").getAsJsonObject("position");
            int x = position.get("x").getAsInt(), y = position.get("y").getAsInt(), z = position.get("z").getAsInt();
            return new OllamaClient.Reply(reply.action(), "Я сейчас здесь: X=" + x + ", Y=" + y + ", Z=" + z + ".");
        } catch (RuntimeException missingObservation) { return reply; }
    }
}
