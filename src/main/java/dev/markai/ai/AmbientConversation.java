package dev.markai.ai;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Game-thread-only timing and repetition guard for unsolicited companion speech. */
public final class AmbientConversation {
    public static final int RECENT_LIMIT = 8;
    public static final long UNCHANGED_SCENE_TICKS = 6_000;
    private static final Set<String> FUNCTION_WORDS = Set.of(
        "я", "ты", "мы", "вы", "он", "она", "они", "мне", "меня", "тебя", "тебе", "тобой", "мной",
        "себя", "это", "этот", "эта", "что", "как", "и", "а", "но", "или", "в", "во", "на", "к", "ко",
        "с", "со", "за", "от", "до", "по", "для", "у", "же", "бы", "the", "a", "an", "i", "you", "we",
        "am", "is", "are", "to", "for", "with", "and", "of");
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private long lastObservedTime = -1;
    private long nextEligible;
    private long lastAttempt;
    private boolean started;
    private String lastScene;

    /** Reserves one attempt; repeated observations of the same scene get a longer quiet period. */
    public boolean begin(long now, int intervalSeconds, String scene) {
        now = observeTime(now);
        if (intervalSeconds <= 0 || now < nextEligible) return false;
        String currentScene = scene == null ? "" : scene;
        if (started && currentScene.equals(lastScene) && now - lastAttempt < UNCHANGED_SCENE_TICKS) return false;
        started = true;
        lastScene = currentScene;
        lastAttempt = now;
        nextEligible = after(now, cooldown(intervalSeconds));
        return true;
    }

    /** A manual turn takes priority and gives the owner time to continue their conversation. */
    public void defer(long now, int intervalSeconds) {
        now = observeTime(now);
        nextEligible = Math.max(nextEligible, after(now, cooldown(intervalSeconds)));
        if (started) lastAttempt = now;
    }

    /** Returns a detached immutable snapshot; these lines are not player statements or saved memories. */
    public List<String> recentLines() { return List.copyOf(lines); }

    /** Accepts a new line once, retaining only a small independent window of delivered ambient speech. */
    public boolean accept(String line) {
        if (line == null || line.isBlank() || line.length() > 1_024) return false;
        String normalized = normalize(line);
        if (normalized.isEmpty()) return false;
        Set<String> words = words(normalized);
        for (String previous : lines) {
            String earlier = normalize(previous);
            if (normalized.equals(earlier) || nearRepeat(words, words(earlier))) return false;
        }
        lines.addLast(line.strip());
        while (lines.size() > RECENT_LIMIT) lines.removeFirst();
        return true;
    }

    private long observeTime(long time) {
        long now = Math.max(0, time);
        if (lastObservedTime >= 0 && now < lastObservedTime) {
            // A changed world clock must not leave the companion muted until an old world's deadline.
            nextEligible = 0;
            lastAttempt = 0;
            lastScene = null;
            started = false;
        }
        lastObservedTime = now;
        return now;
    }

    private static long cooldown(int seconds) { return Math.max(30L, seconds) * 20L; }
    private static long after(long now, long ticks) { return now > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : now + ticks; }

    private static String normalize(String line) {
        return Normalizer.normalize(line, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё', 'е')
            .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static Set<String> words(String normalized) {
        Set<String> result = new HashSet<>();
        for (String word : normalized.split(" +")) {
            if (FUNCTION_WORDS.contains(word) || word.isEmpty()) continue;
            // Short movement acknowledgements differ in inflection but repeat the same promise.
            result.add(switch (word) {
                case "иди", "иду", "идем", "идти", "пойду", "пойдем", "пошли", "пойти", "следую", "последую",
                    "следуем", "следовать", "последовать" -> "следовать";
                case "дом", "дома", "домой" -> "дом";
                case "рядом", "близко", "неподалеку" -> "рядом";
                default -> word;
            });
        }
        return result;
    }

    private static boolean nearRepeat(Set<String> words, Set<String> previous) {
        int smaller = Math.min(words.size(), previous.size());
        // A shared "дождь" or "я рядом" is not enough to suppress a different observation.
        if (smaller < 3) return false;
        int common = 0;
        for (String word : words) if (previous.contains(word)) common++;
        int union = words.size() + previous.size() - common;
        return common >= 3 && (common * 5 >= smaller * 4 || common * 5 >= union * 3);
    }
}
