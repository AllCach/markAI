package dev.markai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.chicken.Chicken;

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
}
