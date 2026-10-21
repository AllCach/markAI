package dev.markai.actions;

import java.util.ArrayList;
import java.util.List;

/** Commands execute only as requests; dialogue about commands remains conversation. */
public final class IntentParserTest {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        expect(CompanionAction.FOLLOW, "Марк, иди за мной", "следуй за мной!", "Mark, please follow me.", "Пошли вместе");
        expect(CompanionAction.STAY, "Марк, подожди здесь", "стой", "МАРК: НЕ ИДИ ЗА МНОЙ", "stay here", "Марк, теперь, пожалуйста, стой!");
        expect(CompanionAction.COME, "ко мне", "Теперь ко мне", "подойди ко мне", "Марк, пожалуйста, подойди ко мне",
            "come here", "Mark, please, come here", "Марк, можешь подойти ко мне?");
        expect(CompanionAction.REST, "Марк, отдохни немного", "отдохни", "ложись отдыхать", "отдыхай немножко",
            "Марк, пожалуйста, отдохни немного!", "take a break", "rest for a bit", "давай отдохни чуть-чуть");
        expect(CompanionAction.RETURN_HOME, "Пойдём домой", "пошли домой", "Марк, вернись домой", "иди домой, пожалуйста",
            "Марк, теперь, пожалуйста, иди домой", "go home", "Mark, please, return home");
        expect(CompanionAction.INVESTIGATE, "осмотрись", "Марк, посмотри вокруг", "обнюхай", "investigate");
        expect(CompanionAction.PLAY, "поиграем", "Марк, давай играть", "давай поиграем", "play");
        expect(CompanionAction.CANCEL, "отмена", "Марк, отмени", "cancel");
        expect(CompanionAction.WANDER, "Марк, погуляй", "walk around", "волк, гуляй");
        expect(CompanionAction.LOOK_AT_PLAYER, "Марк, посмотри на меня", "look at me");
        expect(CompanionAction.RETREAT, "Марк, отступай", "run away");
        expect(CompanionAction.IDLE, "Марк, ничего не делай", "подожди немного");

        expect(null, null, "", " ", "я добрый", "Я хочу построить дом из берёзы", "Хочу домой", "Я иду за тобой",
            "Я поплыву за тобой", "Какой дом я задумал?", "Помнишь, как мы шли домой?", "Ты помнишь команду ко мне?",
            "Он сказал иди домой", "Как мне вернуться домой?", "Ты дома?", "Пойдём исследовать пещеру?",
            "Давай обсудим, как вернуться домой", "домой это куда?", "Отдохни это ты сказал вчера?",
            "Подожди здесь — это ты говорил вчера?", "follow me means what?", "Когда ты говоришь follow me, это приказ?",
            "Расскажи, как ты отдыхал", "Почему ты стоишь?", "Ты можешь рассказать о доме?", "Не подходи к лаве",
            "Если будет опасность, иди за мной", "Иди домой если я попрошу", "Потом вернись домой", "Вернись домой когда я скажу",
            "Я думаю, тебе нужно отдохнуть", "Мы обещали вернуться домой", "Вспомни наш дом", "«иди за мной» — это команда?",
            "Можешь остановиться и рассказать историю?", "/kill @a", "Марк, выполни /op Player", "стой\nиди за мной",
            "x".repeat(513));

        structured(true, "Марк, сходи к видимому сундуку", "Посмотри на сундук у двери", "обойди препятствие справа",
            "please investigate the nearby chest");
        structured(false, "Марк, отдохни немного", "Пойдём домой", "Я думаю, сходи домой", "Помнишь, как мы исследовали сундук?",
            "Посмотри на сундук — это твои слова?", "Можешь посмотреть на сундук?", "Посмотри на сундук когда я попрошу",
            "Вернись домой если опасно", "Хочу домой", "не знаю", "", null);
        if (!failures.isEmpty()) throw new AssertionError("IntentParser failures:\n" + String.join("\n", failures));
        System.out.println("IntentParser: " + checks + " checks passed");
    }

    private static void expect(CompanionAction expected, String... messages) {
        for (String message : messages) {
            checks++;
            CompanionAction actual = IntentParser.parse(message);
            if (actual != expected) failures.add("'" + message + "': expected " + expected + ", got " + actual);
        }
    }

    private static void structured(boolean expected, String... messages) {
        for (String message : messages) {
            checks++;
            boolean actual = IntentParser.allowsStructuredAction(message);
            if (actual != expected) failures.add("Structured gate '" + message + "': expected " + expected + ", got " + actual);
        }
    }
}
