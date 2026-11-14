package dev.markai;

import net.minecraft.world.phys.Vec3;

/** Deterministic bounds for persisted territorial job state. */
public final class TerritoryTaskTest {
    public static void main(String[] args) {
        TerritoryTask task = new TerritoryTask(TerritoryTask.Mode.PATROL,
            "minecraft:overworld", new Vec3(4, 64, -2), 100);
        check(task.valid() && task.radius == 32, "Territory radius is capped at 32 blocks");
        check(task.anchor().equals(new Vec3(4, 64, -2)), "Anchor coordinates survive the job model");
        task.waypoint = 5;
        check(!task.valid(), "Malformed persisted waypoint is rejected");
        task.waypoint = 0;
        task.x = Double.NaN;
        check(!task.valid(), "Non-finite persisted coordinates are rejected");
        System.out.println("TerritoryTaskTest: 4 checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
