package dev.markai.actions;

import java.util.Locale;
import java.util.regex.Pattern;

/** Exact owner requests for server-selected wolf jobs; ordinary conversation never starts a job. */
public final class WolfOrderIntent {
    private static final Pattern PREFIX = Pattern.compile(
        "^(?:(?:walk|волк|марк|mark)(?:\\s*[,!:]\\s*|\\s+)"
            + "|(?:пожалуйста|ну|давай|теперь|а теперь|please)(?:\\s*[,!:]\\s*|\\s+))");
    private static final String LOOT_RU = "(?:мясо|ресурсы|предметы|добычу|вещи|все)";
    private static final String LOOT_EN = "(?:drops|items|resources|meat|loot)";
    private static final Pattern COLLECT = Pattern.compile(
        "(?:собирай|собери|подбери|подбирай)(?: " + LOOT_RU + "(?:(?:\\s*,\\s*|\\s+и\\s+)" + LOOT_RU + ")*)?"
            + "(?: (?:рядом|вокруг|поблизости))?"
            + "|(?:collect|pick up)(?: " + LOOT_EN + "(?: and " + LOOT_EN + ")*)?(?: nearby)?"
            + "|collect all(?: nearby)?(?: drops| items)?");

    private WolfOrderIntent() {}

    /** Returns the allowlisted command name, or null if the message is not an explicit order. */
    public static String parse(String message) {
        if (message == null || message.isBlank() || message.length() > 512
                || message.codePoints().anyMatch(Character::isISOControl)) return null;
        String text = message.toLowerCase(Locale.ROOT).replace('ё', 'е').strip()
            .replaceAll("\\s+", " ").replaceAll("[.!]+$", "").strip();
        for (int i = 0; i < 4; i++) {
            var prefix = PREFIX.matcher(text);
            if (!prefix.find()) break;
            text = text.substring(prefix.end()).strip();
        }
        text = text.replaceFirst("(?:,?\\s+|\\s*,\\s*)(?:пожалуйста|please)$", "").strip();
        return switch (text) {
            case "атакуй", "атакуй цель", "фас", "в атаку", "нападай", "attack", "attack target" -> "attack";
            case "стоп", "прекрати", "остановись", "отмена", "отмени", "stop", "cancel", "stop task",
                "не атакуй", "не нападай", "не собирай", "прекрати атаку", "прекрати сбор", "stop attacking", "stop collecting" -> "stop";
            case "приручи", "приручи волка", "приручить", "приручить волка", "приручение", "tame", "tame wolf" -> "tame";
            default -> COLLECT.matcher(text).matches() ? "collect" : null;
        };
    }
}
