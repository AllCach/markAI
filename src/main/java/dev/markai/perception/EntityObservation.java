package dev.markai.perception;

import java.util.UUID;

/** Immutable observation; safe to pass to a worker without an Entity reference. */
public record EntityObservation(UUID id, String type, String name, double x, double y, double z,
                                double distance_blocks, boolean visible, boolean hostile) {}
