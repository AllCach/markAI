package dev.markai.companion;

import java.util.ArrayList;

/** Pure progression rules; no Minecraft server or AI provider required. */
public final class CompanionProgressionTest {
    private static int checks;

    public static void main(String[] args) {
        thresholdsAndCap();
        pointsAndTalents();
        speciesIsolationAndNormalization();
        travelAndBiomeRewards();
        taskRewardCooldown();
        System.out.println("CompanionProgressionTest: " + checks + " checks passed");
    }

    private static void thresholdsAndCap() {
        CompanionProgression p = new CompanionProgression();
        check(p.level() == 1 && p.xpIntoLevel() == 0 && p.xpToNextLevel() == 30, "New companion starts at level one");
        p.addExperience(CompanionProgression.totalXpForLevel(4) - 1);
        check(p.level() == 3, "XP immediately below level four stays at level three");
        p.addExperience(1);
        check(p.level() == 4 && p.unspentPoints() == 1, "Level four grants exactly one talent point");
        p.addExperience(Long.MAX_VALUE);
        check(p.level() == CompanionProgression.MAX_LEVEL, "Overflow-sized XP stops at the level cap");
        check(p.totalXp == CompanionProgression.totalXpForLevel(CompanionProgression.MAX_LEVEL)
            && p.xpToNextLevel() == 0 && p.unspentPoints() == 5, "Level cap is stable and grants five total points");
        check(p.addExperience(1) == 0, "XP cannot grow after level cap");
    }

    private static void pointsAndTalents() {
        CompanionProgression p = new CompanionProgression();
        check(!p.spendTalent("minecraft:wolf", "guardian"), "Cannot spend a point before earning one");
        p.addExperience(CompanionProgression.totalXpForLevel(4));
        check(p.spendTalent("minecraft:wolf", "guardian"), "Wolf can buy guardian rank one at level four");
        check(p.guardianRank == 1 && p.unspentPoints() == 0, "Purchase consumes exactly one point");
        check(!p.spendTalent("minecraft:wolf", "guardian"), "Rank two requires another earned point");
        p.addExperience(CompanionProgression.totalXpForLevel(8) - p.totalXp);
        check(p.spendTalent("minecraft:wolf", "guardian"), "Rank two unlocks at level eight");
        p.addExperience(CompanionProgression.totalXpForLevel(12) - p.totalXp);
        check(p.spendTalent("minecraft:wolf", "guardian"), "Rank three unlocks at level twelve");
        check(!p.spendTalent("minecraft:wolf", "guardian"), "Talent rank is capped at three");
    }

    private static void speciesIsolationAndNormalization() {
        CompanionProgression p = new CompanionProgression();
        p.totalXp = CompanionProgression.totalXpForLevel(20);
        p.guardianRank = 3;
        p.hunterRank = 2;
        p.trackerRank = 0;
        p.nimbleRank = 3;
        p.lookoutRank = 3;
        p.evasiveRank = 3;
        p.discoveredBiomes = new ArrayList<>();
        p.normalize("minecraft:chicken");
        check(p.guardianRank == 0 && p.hunterRank == 0 && p.trackerRank == 0, "Chicken cannot retain wolf talents");
        check(p.spentPoints() <= p.earnedPoints(), "Normalization respects the point budget");
        check(p.nimbleRank + p.lookoutRank + p.evasiveRank <= 5, "Only five points can be spent at max level");
        check(!p.supportsTalent("minecraft:chicken", "hunter")
            && !p.supportsTalent("minecraft:wolf", "lookout"), "Species-only talents are enforced by the model");
        check(p.talentSnapshot("minecraft:wolf").size() == 3
            && p.talentSnapshot("minecraft:chicken").size() == 3, "Snapshots expose only each species' three talents");
        p.normalize("minecraft:wolf");
        check(p.nimbleRank == 0 && p.lookoutRank == 0 && p.evasiveRank == 0, "Wolf cannot retain chicken talents");
    }

    private static void travelAndBiomeRewards() {
        CompanionProgression p = new CompanionProgression();
        check(p.sampleTravel(0, 0, "minecraft:overworld") == 0, "First route sample establishes a baseline");
        check(p.sampleTravel(100, 0, "minecraft:overworld") == 0, "Teleport-sized jump grants no route XP");
        check(p.sampleTravel(116, 0, "minecraft:overworld") == 0, "Travel accumulates in small verified samples");
        check(p.sampleTravel(132, 0, "minecraft:overworld") == 0, "Route remains below the milestone");
        check(p.sampleTravel(148, 0, "minecraft:overworld") == 0, "Route continues to accumulate");
        check(p.sampleTravel(164, 0, "minecraft:overworld") == 0, "Route remains below the milestone");
        check(p.sampleTravel(180, 0, "minecraft:overworld") == 0, "80 route blocks do not grant XP");
        check(p.sampleTravel(196, 0, "minecraft:overworld") == 8, "96 real route blocks grant eight XP");
        check(p.sampleTravel(212, 0, "minecraft:overworld") == 0, "Travel rewards do not repeat before the next milestone");
        p.resetTravelSample();
        check(p.sampleTravel(0, 0, "minecraft:the_nether") == 0, "Changing dimensions resets the travel baseline");
        check(p.discoverBiome("minecraft:plains") == 0, "First biome establishes baseline without XP");
        check(p.discoverBiome("minecraft:plains") == 0, "Revisiting a biome does not reward XP");
        check(p.discoverBiome("minecraft:forest") == 12, "A new biome grants twelve XP once");
        check(p.discoverBiome("minecraft:forest") == 0, "The same biome cannot be farmed for XP");
        for (int i = 0; i < 100; i++) p.discoverBiome("test:biome_" + i);
        check(p.discoveredBiomes.size() == CompanionProgression.MAX_DISCOVERED_BIOMES,
            "Biome history has a fixed storage bound");
    }

    private static void taskRewardCooldown() {
        CompanionProgression p = new CompanionProgression();
        check(p.rewardCompletedTask(100, 10) == 10, "Completed task grants XP");
        check(p.rewardCompletedTask(200, 10) == 0, "Task rewards share a anti-farm cooldown");
        check(p.rewardCompletedTask(1_300, 10) == 10, "Task XP becomes available after cooldown");
        check(p.rewardCompletedTask(50, 10) == 10, "A new world-time epoch does not lock progression forever");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
