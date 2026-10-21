package dev.markai.ai;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative movement commands: ordinary conversation keeps the current movement mode. */
public final class DialogueIntent {
    private DialogueIntent() {}

    private record Rule(String action, Pattern pattern) {}
    private static final Pattern PREFIX = Pattern.compile(
        "^(?:(?:марк|mark|walk(?!\\s+around\\b)|волк)(?:\\s*[,!:]\\s*|\\s+)|(?:пожалуйста|ну|давай|please|а теперь|теперь|а сейчас|сейчас)(?:\\s*[,!:]\\s*|\\s+))");
    private static final List<Rule> RULES = List.of(
        rule("follow", "(?:ты )?можешь(?:,? пожалуйста,?)? (?:подойти ко мне|подойти сюда|идти за мной|пойти за мной|следовать за мной)"),
        rule("stay", "(?:ты )?можешь(?:,? пожалуйста,?)? (?:остановиться|остаться (?:здесь|тут|на месте)|не (?:идти|следовать) за мной)"),
        rule("stay", "не (?:иди|ходи|следуй|беги) за мной|не (?:подходи|иди|беги) (?:ко мне|сюда)|не двигайся"
            + "|(?:don't|dont|do not) follow me|stop following me"),
        rule("idle", "замри|сделай паузу|отдохни|ничего не делай|(?:постой|подожди) (?:немного|секунду|минутку)"
            + "|pause|take a break"),
        rule("stay", "стой|остановись|стоп|оставайся (?:здесь|тут|на месте)|(?:жди|подожди)(?: меня| здесь| тут)?"
            + "|(?:иди|ложись) спать|спать[.!?]*$|(?:go to )?sleep[.!?]*$"
            + "|stop(?: (?:here|moving))?[.!?]*$|stay(?: (?:here|there|put))?[.!?]*$|wait(?: (?:here|for me))?[.!?]*$"),
        rule("follow", "(?:иди|идем|пойдем|пошли|беги|следуй) за мной|(?:подойди|подбеги|иди|беги) ко мне"
            + "|(?:иди|подойди|беги) сюда|(?:пойдем|пошли) (?:со мной|вместе)|(?:пойдем|пошли|идем)(?:,? пожалуйста)?[.!?]*$|за мной[.!?]*$"
            + "|сюда(?:,? пожалуйста)?[.!?]*$|follow me|come (?:here|to me)|come with me"),
        rule("wander", "гуляй|погуляй|прогуляйся|походи вокруг|иди (?:гулять|погуляй)"
            + "|wander|walk around|go for a walk"),
        rule("look_at_player", "(?:посмотри|смотри) на меня|повернись ко мне|посмотри мне в глаза|look at me|turn towards me"),
        rule("flee", "убегай|убеги|спасайся|отступай|беги от|run away|flee|retreat")
    );

    /** Returns an allowed action, defaulting to say for indirect, ambiguous or unsupported requests. */
    public static String requestedAction(String message) {
        if (message == null || message.isBlank()) return "say";
        String text = message.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('’', '\'')
            .strip().replaceAll("\\s+", " ");
        for (int count = 0; count < 4; count++) {
            var prefix = PREFIX.matcher(text);
            if (!prefix.find()) break;
            text = text.substring(prefix.end());
        }
        for (Rule rule : RULES) if (rule.pattern().matcher(text).find()) return rule.action();
        return "say";
    }

    private static Rule rule(String action, String expression) {
        return new Rule(action, Pattern.compile("^(?:" + expression + ")(?=$|[\\s,.!?;:])"));
    }
}
