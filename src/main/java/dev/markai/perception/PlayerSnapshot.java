package dev.markai.perception;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PlayerSnapshot(UUID id, String name, Map<String, Integer> position, String dimension,
                             float health, int hunger, List<String> effects, List<String> armor,
                             String held_item, String offhand_item, double distance_blocks,
                             boolean moving, boolean sprinting, boolean crouching, boolean sleeping,
                             boolean in_water, boolean in_boat, String vehicle) {}
