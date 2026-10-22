package dev.markai.training;

import java.util.*;

/** Wolf-only trick training is earned through lessons and completed tricks, never by a model's claims. */
public final class WolfTraining {
    public enum Trick {
        BARK("голос", 1), COME("ко мне", 1), FETCH("принеси", 2);
        public final String title;
        public final int requiredLevel;
        Trick(String title, int requiredLevel) { this.title = title; this.requiredLevel = requiredLevel; }
        public static Trick parse(String text) {
            if (text == null) return null;
            return switch (text.toLowerCase(Locale.ROOT).strip().replaceAll("[!.?]+$", "")) {
                case "голос", "лай", "погавкай", "bark" -> BARK;
                case "ко мне", "сюда", "come" -> COME;
                case "принеси", "принеси предмет", "принеси предметы", "fetch" -> FETCH;
                default -> null;
            };
        }
    }

    private int experience;
    private Map<String, Integer> lessons = new HashMap<>();
    private long nextPracticeReward;

    public void normalize() {
        experience = Math.clamp(experience, 0, 135);
        if (lessons == null) lessons = new HashMap<>();
        lessons.entrySet().removeIf(e -> Arrays.stream(Trick.values()).noneMatch(t -> t.name().equals(e.getKey())) || e.getValue() == null);
        lessons.replaceAll((key, value) -> Math.clamp(value, 0, 3));
    }
    public int level() { return 1 + experience / 15; }
    public int experience() { return experience; }
    public int progress(Trick trick) { return lessons.getOrDefault(trick.name(), 0); }
    public boolean knows(Trick trick) { return progress(trick) >= 3; }
    public boolean canTrain(Trick trick) { return level() >= trick.requiredLevel && !knows(trick); }
    public boolean train(Trick trick) {
        if (!canTrain(trick)) return false;
        lessons.put(trick.name(), progress(trick) + 1);
        experience = Math.min(135, experience + 5);
        return true;
    }
    public void completed(long time) {
        if (time < nextPracticeReward) return;
        experience = Math.min(135, experience + 2);
        nextPracticeReward = time + 200;
    }
    public int fetchRange() { return Math.min(28, 8 + level() * 2); }
    public Map<String, Object> context() {
        var skills = new LinkedHashMap<String, Object>();
        for (Trick trick : Trick.values()) skills.put(trick.title, Map.of("lessons", progress(trick), "learned", knows(trick), "required_level", trick.requiredLevel));
        return Map.of("level", level(), "experience", experience, "next_level_at", Math.min(135, level() * 15), "tricks", skills);
    }
    public String status() {
        var parts = new ArrayList<String>();
        for (Trick t : Trick.values()) parts.add(t.title + ": " + (knows(t) ? "освоен" : progress(t) + "/3"));
        return "Уровень " + level() + ", опыт " + experience + "/" + Math.min(135, level() * 15) + ". " + String.join("; ", parts);
    }
}
