package dev.markai.actions;

import dev.markai.ai.DialogueIntent;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deliberately narrow command grammar; questions and memories cannot execute actions. */
public final class IntentParser {
    private static final Pattern PREFIX = Pattern.compile(
        "^(?:(?:марк|mark|walk(?!\\s+around\\b)|волк)(?:\\s*[,!:]\\s*|\\s+)"
            + "|(?:пожалуйста|ну|давай|please|а теперь|теперь|а сейчас|сейчас)(?:\\s*[,!:]\\s*|\\s+))");
    private static final Pattern META = Pattern.compile(
        "(?:^|\\b)(?:помнишь|помню|вспомни|вчера|раньше|когда|если|позже|потом|значит|означает|"
            + "ты сказал|ты говорил|ты обещал|это команда|это твои слова|в прошлый раз|remember|yesterday|"
            + "you said|you told|means?|meaning|if|when|later)(?:\\b|$)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern POLITE_REQUEST = Pattern.compile(
        "(?:ты )?можешь(?:,? пожалуйста,?)? (?:подойти ко мне|подойти сюда|идти за мной|пойти за мной|"
            + "следовать за мной|остановиться|остаться (?:здесь|тут|на месте)|не (?:идти|следовать) за мной)");
    private static final Pattern IMPERATIVE = Pattern.compile(
        "^(?:иди|идем|пойдем|пошли|следуй|подойди|подбеги|беги|вернись|сходи|отдохни|отдыхай|"
            + "оставайся|останься|подожди|жди|осмотрись|осмотри|исследуй|посмотри|обнюхай|гуляй|"
            + "погуляй|прогуляйся|убегай|отступай|играй|поиграй|принеси|обойди|держись|найди|защити|"
            + "прекрати|отмени|follow|come|return|go|rest|wait|stay|look|investigate|play|bring|avoid|defend|"
            + "stop|cancel|retreat)(?=$|\\s)");

    private IntentParser() {}

    public static CompanionAction parse(String message) {
        String text = normalized(message);
        if (text == null || META.matcher(text).find()) return null;
        if (text.contains("?")) {
            if (!text.endsWith("?") || text.indexOf('?') != text.length() - 1
                    || !POLITE_REQUEST.matcher(text.substring(0, text.length() - 1)).matches()) return null;
            text = text.substring(0, text.length() - 1);
        }
        if (text.matches("(?:отдохни|отдыхай|ложись отдыхать|rest|take a break)(?: (?:немного|немножко|чуть-чуть|a little|for a bit))?"))
            return CompanionAction.REST;
        if (text.matches("(?:ты )?можешь(?:,? пожалуйста,?)? (?:подойти ко мне|подойти сюда)"))
            return CompanionAction.COME;
        return switch (text) {
            case "домой", "иди домой", "вернись домой", "пойдем домой", "пошли домой", "идем домой", "вернемся домой",
                "return home", "go home" -> CompanionAction.RETURN_HOME;
            case "ко мне", "иди ко мне", "подойди ко мне", "come here" -> CompanionAction.COME;
            case "осмотрись", "исследуй", "посмотри вокруг", "обнюхай", "investigate" -> CompanionAction.INVESTIGATE;
            case "поиграем", "играй", "играть", "давай играть", "play" -> CompanionAction.PLAY;
            case "отмена", "отмени", "cancel" -> CompanionAction.CANCEL;
            default -> switch (DialogueIntent.requestedAction(text)) {
                case "follow" -> CompanionAction.FOLLOW;
                case "stay" -> CompanionAction.STAY;
                case "wander" -> CompanionAction.WANDER;
                case "flee" -> CompanionAction.RETREAT;
                case "look_at_player" -> CompanionAction.LOOK_AT_PLAYER;
                case "idle" -> CompanionAction.IDLE;
                default -> null;
            };
        };
    }

    /** A provider may propose an allowlisted action only for a fresh imperative that simple parsing did not handle. */
    public static boolean allowsStructuredAction(String message) {
        if (parse(message) != null) return false;
        String text = normalized(message);
        return text != null && !text.contains("?") && !META.matcher(text).find() && IMPERATIVE.matcher(text).find();
    }

    private static String normalized(String message) {
        if (message == null || message.isBlank() || message.length() > 512
                || message.codePoints().anyMatch(Character::isISOControl)) return null;
        String text = message.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('’', '\'')
            .strip().replaceAll("\\s+", " ").replaceAll("[.!]+$", "").strip();
        for (int count = 0; count < 4; count++) {
            var prefix = PREFIX.matcher(text);
            if (!prefix.find()) break;
            text = text.substring(prefix.end()).strip();
        }
        for (int count = 0; count < 2; count++) {
            String shorter = text.replaceFirst("(?:,?\\s+|\\s*,\\s*)(?:пожалуйста|please)$", "").strip();
            if (shorter.equals(text)) break;
            text = shorter;
        }
        return text;
    }
}
