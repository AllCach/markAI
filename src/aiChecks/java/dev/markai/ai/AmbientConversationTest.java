package dev.markai.ai;

/** Dependency-free regressions for autonomous speech timing and the repeated-home screenshot. */
public final class AmbientConversationTest {
    private static int checks;

    public static void main(String[] args) {
        timing();
        repetition();
        boundedSnapshots();
        System.out.println("Ambient conversation: " + checks + " checks passed");
    }

    private static void timing() {
        var chat = new AmbientConversation();
        check(!chat.begin(0, 0, "forest"), "A zero interval disables automatic requests");
        check(!chat.begin(0, -1, "forest"), "A negative interval cannot enable requests");
        check(chat.begin(0, 1, "forest"), "The first attempt can start at game time zero");
        check(!chat.begin(599, 1, "rain"), "A changed scene still respects the minimum thirty seconds");
        check(chat.begin(600, 1, "rain"), "The minimum cooldown is applied consistently for a changed scene");
        check(!chat.begin(1_200, 1, "rain"), "An unchanged scene does not produce speech every interval");
        check(!chat.begin(6_599, 1, "rain"), "An unchanged scene stays quiet for five minutes");
        check(chat.begin(6_600, 1, "rain"), "An unchanged scene can receive an occasional later comment");
        check(chat.begin(7_200, 1, "taiga"), "A new biome can be discussed after the minimum interval");

        var configured = new AmbientConversation();
        check(configured.begin(100, 120, "home"), "The first attempt also works at a nonzero time");
        check(!configured.begin(2_499, 120, "rain"), "A configured longer interval is preserved");
        check(configured.begin(2_500, 120, "rain"), "A configured longer interval expires at its deadline");

        var manual = new AmbientConversation();
        manual.defer(0, 60);
        check(!manual.begin(1_199, 60, "home"), "Manual speech postpones the first autonomous turn");
        check(manual.begin(1_200, 60, "home"), "An autonomous turn can follow the manual cooldown");
        manual.defer(2_000, 60);
        check(!manual.begin(3_199, 60, "rain"), "A subsequent manual turn also takes priority");
        check(manual.begin(3_200, 60, "rain"), "A new scene is eligible after the owner's cooldown");
        manual.defer(4_000, 60);
        check(!manual.begin(9_999, 60, "rain"), "A manual turn resets the unchanged-scene quiet period");
        check(manual.begin(10_000, 60, "rain"), "The deferred unchanged scene eventually becomes eligible");

        check(manual.accept("Я вижу берёзовый лес."), "Clock test has a recent spoken line");
        check(manual.begin(5, 60, "rain"), "A world-clock rollback cannot retain an obsolete deadline");
        check(!manual.accept("Я ВИЖУ БЕРЕЗОВЫЙ ЛЕС!"), "A world-clock rollback retains the independent repeat guard");
        var huge = new AmbientConversation();
        check(huge.begin(Long.MAX_VALUE - 1, Integer.MAX_VALUE, "forest"), "Large times and intervals remain representable");
        check(!huge.begin(Long.MAX_VALUE - 1, Integer.MAX_VALUE, "rain"), "Cooldown arithmetic cannot overflow into an immediate second attempt");
    }

    private static void repetition() {
        var chat = new AmbientConversation();
        check(!chat.accept(null) && !chat.accept(" \t ") && !chat.accept("..."), "Empty automatic output never reaches chat");
        check(chat.accept("Дом рядом! Иди, я последую за тобой."), "The first real home comment is accepted");
        check(!chat.accept("Дом рядом! Иди, я последую за тобой."), "The exact screenshot repetition is rejected");
        check(!chat.accept("ДОМ рядом… иди — я последую за тобой!!!"), "Case and punctuation changes do not bypass the guard");
        check(!chat.accept("Дом рядом! Иду за тобой."), "A shortened movement paraphrase is also a repeated home comment");
        check(!chat.accept("Дом близко, я готов следовать за тобой."), "Small synonymous changes cannot repeat the same promise");
        check(chat.accept("Начался дождь, шерсть намокает."), "A distinct weather observation can be delivered");
        check(chat.accept("Впереди заснеженная тайга, здесь заметно холоднее."), "A distinct biome observation can be delivered");
        check(chat.accept("Здесь светит солнце."), "Mentioning a different current weather state is allowed");
        check(chat.accept("Я рядом."), "A short distinct acknowledgement is allowed");
        check(chat.accept("Я рядом, слышу дождь."), "Two common words alone do not suppress another observation");
        check(!chat.accept("Я РЯДОМ!"), "Even short exact duplicates are rejected");
        check(chat.accept("Мне нравится этот берёзовый лес."), "A new forest observation is allowed");
        check(!chat.accept("Мне нравится этот березовый лес..."), "The Russian ё spelling variant is normalized");
        check(!chat.accept("x".repeat(1_025)), "A single ambient line has a bounded retained size");
    }

    private static void boundedSnapshots() {
        var chat = new AmbientConversation();
        check(chat.accept("Первая реплика."), "The first bounded-history line is accepted");
        var detached = chat.recentLines();
        for (int i = 0; i < 12; i++) check(chat.accept("Наблюдение " + i), "Different observations remain eligible: " + i);
        check(chat.recentLines().size() == 8, "Only the last eight automatic lines are retained");
        check(detached.size() == 1 && detached.getFirst().equals("Первая реплика."), "A returned snapshot is detached from later writes");
        boolean immutable = false;
        try { detached.add("Изменение"); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "A returned history snapshot is immutable");
        check(chat.accept("Первая реплика."), "Expired history does not prevent a much later repeated phrase forever");
        check(!chat.accept("НАБЛЮДЕНИЕ 11!"), "A still-recent line remains protected after history rotation");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }
}
