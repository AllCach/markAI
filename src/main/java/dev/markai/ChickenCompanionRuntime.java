package dev.markai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.monster.Monster;

/**
 * Minimal species-specific runtime for Mark. Deliberately excludes wolf combat,
 * inventory, training and trick behavior.
 */
final class ChickenCompanionRuntime {
    void configure(Chicken chicken, String name) {
        chicken.setCustomName(net.minecraft.network.chat.Component.literal(name));
        chicken.setCustomNameVisible(true);
        chicken.setPersistenceRequired();
    }

    void follow(Chicken chicken, ServerPlayer owner) {
        chicken.getNavigation().moveTo(owner, 1.1D);
    }

    void stop(Chicken chicken) {
        chicken.getNavigation().stop();
    }

    /** Evades nearby hostile mobs without attacking them or changing vanilla chicken taming. */
    boolean evade(Chicken chicken, int rank) {
        if (rank <= 0 || chicken.level().isClientSide()) return false;
        var threats = chicken.level().getEntitiesOfClass(Monster.class,
            chicken.getBoundingBox().inflate(8.0), Entity::isAlive);
        Monster threat = threats.stream()
            .min(java.util.Comparator.comparingDouble(chicken::distanceToSqr)).orElse(null);
        if (threat == null) return false;
        double dx = chicken.getX() - threat.getX();
        double dz = chicken.getZ() - threat.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            dx = chicken.getRandom().nextBoolean() ? 1.0 : -1.0;
            dz = chicken.getRandom().nextBoolean() ? 1.0 : -1.0;
            length = Math.sqrt(dx * dx + dz * dz);
        }
        double distance = 5.0 + rank * 2.0;
        chicken.getNavigation().moveTo(chicken.getX() + dx / length * distance,
            chicken.getY(), chicken.getZ() + dz / length * distance, 1.0 + rank * 0.15);
        return true;
    }
}
