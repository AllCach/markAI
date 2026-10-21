package dev.markai.core;

import java.util.Map;

/** A saved location contains no live level or entity reference. */
public record HomeLocation(String dimension, double x, double y, double z) {
    public HomeLocation {
        if (dimension == null || dimension.isBlank() || dimension.length() > 128
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 32_768)
            throw new IllegalArgumentException("Invalid home location");
    }

    public double distanceTo(double otherX, double otherY, double otherZ) {
        return Math.sqrt(Math.pow(x - otherX, 2) + Math.pow(y - otherY, 2) + Math.pow(z - otherZ, 2));
    }

    public Map<String, Object> context() {
        return Map.of("dimension", dimension, "x", x, "y", y, "z", z);
    }
}
