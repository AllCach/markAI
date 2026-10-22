package dev.markai.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent companion progression. Game-thread only; pure rules live here so XP,
 * level thresholds and talent allocation can be tested without starting Minecraft.
 */
public final class CompanionProgression {
    public static final int MAX_LEVEL = 20;
    public static final int MAX_TALENT_RANK = 3;
    public static final int TALENT_POINT_INTERVAL = 4;
    public static final int MAX_DISCOVERED_BIOMES = 64;
    public static final long TRAVEL_MILESTONE_BLOCKS = 96;
    public static final long TASK_XP_COOLDOWN_TICKS = 1_200;
    private static final long TRAVEL_XP = 8;
    private static final long BIOME_XP = 12;
    private static final long MAX_TOTAL_XP = totalXpForLevel(MAX_LEVEL);
    private static final Set<String> WOLF_TALENTS = Set.of("guardian", "hunter", "tracker");
    private static final Set<String> CHICKEN_TALENTS = Set.of("nimble", "lookout", "evasive");

    /** Lifetime XP. It is capped at the amount required for level 20. */
    public long totalXp;
    public int guardianRank;
    public int hunterRank;
    public int trackerRank;
    public int nimbleRank;
    public int lookoutRank;
    public int evasiveRank;
    /** Ordered stable biome IDs. The first biome is a free baseline, later ones grant XP once. */
    public List<String> discoveredBiomes = new ArrayList<>();
    /** Shared cooldown for rewarding a completed non-combat task, persisted to resist restart farming. */
    public boolean hasTaskRewardTimestamp;
    public long lastTaskRewardTick;
    /** Partial route distance is persistent; sample position is transient and never rewards teleports. */
    public double travelDistance;

    private transient boolean hasTravelSample;
    private transient double lastOwnerX;
    private transient double lastOwnerZ;
    private transient String lastDimension;

    public static long xpToAdvance(int currentLevel) {
        if (currentLevel < 1 || currentLevel >= MAX_LEVEL) return 0;
        long n = currentLevel - 1L;
        return 30L + 8L * n + n * n;
    }

    /** Total XP needed to reach a level, where level 1 starts at zero XP. */
    public static long totalXpForLevel(int targetLevel) {
        int bounded = Math.max(1, Math.min(MAX_LEVEL, targetLevel));
        long total = 0;
        for (int level = 1; level < bounded; level++) total += xpToAdvance(level);
        return total;
    }

    public int level() {
        long xp = Math.max(0, Math.min(MAX_TOTAL_XP, totalXp));
        int level = 1;
        while (level < MAX_LEVEL) {
            long cost = xpToAdvance(level);
            if (xp < cost) break;
            xp -= cost;
            level++;
        }
        return level;
    }

    public long xpIntoLevel() {
        return Math.max(0, Math.min(MAX_TOTAL_XP, totalXp) - totalXpForLevel(level()));
    }

    public long xpRequiredForLevel() {
        return xpToAdvance(level());
    }

    public long xpToNextLevel() {
        long required = xpRequiredForLevel();
        return required == 0 ? 0 : Math.max(0, required - xpIntoLevel());
    }

    public int earnedPoints() { return level() / TALENT_POINT_INTERVAL; }

    public int spentPoints() {
        return guardianRank + hunterRank + trackerRank + nimbleRank + lookoutRank + evasiveRank;
    }

    public int unspentPoints() { return Math.max(0, earnedPoints() - spentPoints()); }

    public long addExperience(long amount) {
        if (amount <= 0 || totalXp >= MAX_TOTAL_XP) return 0;
        long before = Math.max(0, Math.min(MAX_TOTAL_XP, totalXp));
        long after = amount >= MAX_TOTAL_XP - before ? MAX_TOTAL_XP : before + amount;
        totalXp = after;
        return after - before;
    }

    /**
     * Awards 8 XP per 96 horizontal blocks walked by the owner while the companion
     * is actively following nearby. A sample over 20 blocks is treated as a
     * teleport/discontinuity and only resets the baseline.
     */
    public long sampleTravel(double x, double z, String dimension) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || dimension == null || dimension.isBlank()) {
            resetTravelSample();
            return 0;
        }
        if (!hasTravelSample || !dimension.equals(lastDimension)) {
            lastOwnerX = x;
            lastOwnerZ = z;
            lastDimension = dimension;
            hasTravelSample = true;
            return 0;
        }
        double distance = Math.hypot(x - lastOwnerX, z - lastOwnerZ);
        lastOwnerX = x;
        lastOwnerZ = z;
        lastDimension = dimension;
        if (!Double.isFinite(distance) || distance > 20.0) return 0;

        travelDistance = Double.isFinite(travelDistance) ? Math.max(0.0, Math.min(TRAVEL_MILESTONE_BLOCKS, travelDistance)) : 0.0;
        travelDistance += distance;
        long gained = 0;
        while (travelDistance >= TRAVEL_MILESTONE_BLOCKS) {
            travelDistance -= TRAVEL_MILESTONE_BLOCKS;
            gained += addExperience(TRAVEL_XP);
        }
        return gained;
    }

    /** Resets the movement baseline, not the earned partial journey. */
    public void resetTravelSample() {
        hasTravelSample = false;
        lastDimension = null;
    }

    /** Records a biome once. The first observed biome sets the baseline without XP. */
    public long discoverBiome(String biomeId) {
        if (biomeId == null || biomeId.isBlank() || biomeId.length() > 160
                || biomeId.codePoints().anyMatch(Character::isISOControl)) return 0;
        if (discoveredBiomes == null) discoveredBiomes = new ArrayList<>();
        if (discoveredBiomes.contains(biomeId) || discoveredBiomes.size() >= MAX_DISCOVERED_BIOMES) return 0;
        boolean first = discoveredBiomes.isEmpty();
        discoveredBiomes.add(biomeId);
        return first ? 0 : addExperience(BIOME_XP);
    }

    /** Task XP has a shared 60-second (1200 game tick) cooldown across tricks. */
    public long rewardCompletedTask(long gameTime, long amount) {
        if (amount <= 0 || totalXp >= MAX_TOTAL_XP) return 0;
        if (hasTaskRewardTimestamp && gameTime >= lastTaskRewardTick
                && gameTime - lastTaskRewardTick < TASK_XP_COOLDOWN_TICKS) return 0;
        hasTaskRewardTimestamp = true;
        lastTaskRewardTick = gameTime;
        return addExperience(amount);
    }

    public boolean spendTalent(String species, String talent) {
        if (talent == null || !supportsTalent(species, talent) || unspentPoints() <= 0) return false;
        int current = rank(talent);
        int nextRank = current + 1;
        if (current >= MAX_TALENT_RANK || level() < nextRank * TALENT_POINT_INTERVAL) return false;
        switch (talent) {
            case "guardian" -> guardianRank = nextRank;
            case "hunter" -> hunterRank = nextRank;
            case "tracker" -> trackerRank = nextRank;
            case "nimble" -> nimbleRank = nextRank;
            case "lookout" -> lookoutRank = nextRank;
            case "evasive" -> evasiveRank = nextRank;
            default -> { return false; }
        }
        return true;
    }

    public int rank(String talent) {
        if (talent == null) return 0;
        return switch (talent) {
            case "guardian" -> guardianRank;
            case "hunter" -> hunterRank;
            case "tracker" -> trackerRank;
            case "nimble" -> nimbleRank;
            case "lookout" -> lookoutRank;
            case "evasive" -> evasiveRank;
            default -> 0;
        };
    }

    public boolean supportsTalent(String species, String talent) {
        return "minecraft:wolf".equals(species) ? WOLF_TALENTS.contains(talent)
            : "minecraft:chicken".equals(species) && CHICKEN_TALENTS.contains(talent);
    }

    public Map<String, Integer> talentSnapshot(String species) {
        if ("minecraft:wolf".equals(species))
            return Map.of("guardian", guardianRank, "hunter", hunterRank, "tracker", trackerRank);
        if ("minecraft:chicken".equals(species))
            return Map.of("nimble", nimbleRank, "lookout", lookoutRank, "evasive", evasiveRank);
        return Map.of();
    }

    public int attributeSignature() { return guardianRank * 31 + hunterRank; }

    /** Defensive normalization for old saves, edited JSON and partially missing fields. */
    public void normalize(String species) {
        totalXp = Math.max(0, Math.min(MAX_TOTAL_XP, totalXp));
        if (!Double.isFinite(travelDistance)) travelDistance = 0;
        travelDistance = Math.max(0.0, Math.min(TRAVEL_MILESTONE_BLOCKS - 0.0001, travelDistance));
        guardianRank = clampRank(guardianRank);
        hunterRank = clampRank(hunterRank);
        trackerRank = clampRank(trackerRank);
        nimbleRank = clampRank(nimbleRank);
        lookoutRank = clampRank(lookoutRank);
        evasiveRank = clampRank(evasiveRank);

        int levelRankCap = Math.min(MAX_TALENT_RANK, level() / TALENT_POINT_INTERVAL);
        guardianRank = Math.min(guardianRank, levelRankCap);
        hunterRank = Math.min(hunterRank, levelRankCap);
        trackerRank = Math.min(trackerRank, levelRankCap);
        nimbleRank = Math.min(nimbleRank, levelRankCap);
        lookoutRank = Math.min(lookoutRank, levelRankCap);
        evasiveRank = Math.min(evasiveRank, levelRankCap);

        if ("minecraft:wolf".equals(species)) {
            nimbleRank = lookoutRank = evasiveRank = 0;
        } else if ("minecraft:chicken".equals(species)) {
            guardianRank = hunterRank = trackerRank = 0;
        } else {
            guardianRank = hunterRank = trackerRank = nimbleRank = lookoutRank = evasiveRank = 0;
        }

        while (spentPoints() > earnedPoints()) {
            if ("minecraft:wolf".equals(species)) {
                if (trackerRank > 0) trackerRank--;
                else if (hunterRank > 0) hunterRank--;
                else if (guardianRank > 0) guardianRank--;
                else break;
            } else if ("minecraft:chicken".equals(species)) {
                if (evasiveRank > 0) evasiveRank--;
                else if (lookoutRank > 0) lookoutRank--;
                else if (nimbleRank > 0) nimbleRank--;
                else break;
            } else break;
        }

        if (discoveredBiomes == null) discoveredBiomes = new ArrayList<>();
        List<String> safeBiomes = new ArrayList<>();
        for (String biome : discoveredBiomes) {
            if (biome == null || biome.isBlank() || biome.length() > 160
                    || biome.codePoints().anyMatch(Character::isISOControl) || safeBiomes.contains(biome)) continue;
            safeBiomes.add(biome);
            if (safeBiomes.size() == MAX_DISCOVERED_BIOMES) break;
        }
        discoveredBiomes = safeBiomes;
        if (!hasTaskRewardTimestamp) lastTaskRewardTick = 0;
    }

    private static int clampRank(int rank) { return Math.max(0, Math.min(MAX_TALENT_RANK, rank)); }
}
