package dev.markai.inventory;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;

/** One row of storage belonging to a living, nearby companion. */
public final class DogInventory extends SimpleContainer {
    private final Wolf wolf;

    public DogInventory(Wolf wolf) {
        super(9);
        this.wolf = wolf;
    }

    @Override public boolean stillValid(Player player) {
        var owner = wolf.getOwnerReference();
        return wolf.isAlive() && !wolf.isRemoved() && wolf.isTame()
            && ((CompanionInventoryHolder) wolf).markai$isCompanion()
            && player.isAlive() && !player.isSpectator() && player.level() == wolf.level()
            && owner != null && owner.getUUID().equals(player.getUUID())
            && player.distanceToSqr(wolf) <= 64.0;
    }
}
