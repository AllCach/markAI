package dev.markai;

import net.minecraft.world.phys.Vec3;

/** Owner-issued, bounded job state saved with one logical companion. */
public final class TerritoryTask {
    public enum Mode { GUARD, PATROL, EXPLORE, LOOKOUT }

    public Mode mode;
    public String dimension;
    public double x, y, z;
    public int radius = 32;
    public int waypoint;
    public transient long nextMoveAt, nextAlertAt, nextScanAt;
    public transient int failedPaths;

    public TerritoryTask() { }

    public TerritoryTask(Mode mode, String dimension, Vec3 anchor, int radius) {
        this.mode = mode;
        this.dimension = dimension;
        x = anchor.x; y = anchor.y; z = anchor.z;
        this.radius = Math.max(8, Math.min(32, radius));
    }

    public boolean valid() {
        return mode != null && dimension != null && !dimension.isBlank()
            && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && radius >= 8 && radius <= 32 && waypoint >= 0 && waypoint <= 4;
    }

    public Vec3 anchor() { return new Vec3(x, y, z); }
}
