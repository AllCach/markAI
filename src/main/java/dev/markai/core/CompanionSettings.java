package dev.markai.core;

import java.util.Map;

/** Per-owner preferences persisted with this companion, separate from API credentials. */
public final class CompanionSettings {
    public boolean defense = true;
    public boolean hud = true;
    public boolean autochat = false;
    public double followDistance = 4.0;
    public double cautiousness = 0.5;

    public void normalize() {
        followDistance = finiteClamp(followDistance, 2, 16, 4);
        cautiousness = finiteClamp(cautiousness, 0, 1, 0.5);
    }

    public Map<String, Object> context() {
        normalize();
        return Map.of("defense", defense, "hud", hud, "autochat", autochat,
            "follow_distance", followDistance, "cautiousness", cautiousness);
    }

    public static double finiteClamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
