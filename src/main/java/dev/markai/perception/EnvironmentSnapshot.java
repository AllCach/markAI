package dev.markai.perception;

import java.util.List;

public record EnvironmentSnapshot(String dimension, String biome, long game_time, long time_of_day,
                                  boolean raining, boolean thundering, int light, boolean dangerous_cave,
                                  List<BlockObservation> important_blocks, List<BlockObservation> hazards,
                                  boolean critical_hazard) {
    public record BlockObservation(String type, int x, int y, int z) {}
}
