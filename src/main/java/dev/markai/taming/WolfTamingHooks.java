package dev.markai.taming;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import java.util.function.BiConsumer;

/** Server hook after the normal bone interaction has actually established ownership. */
public final class WolfTamingHooks {
    private static BiConsumer<ServerPlayer, Wolf> adopted = (owner, wolf) -> { };
    private WolfTamingHooks() { }
    public static void register(BiConsumer<ServerPlayer, Wolf> callback) { adopted = callback; }
    public static void tamed(ServerPlayer owner, Wolf wolf) {
        if (!wolf.level().isClientSide() && wolf.isAlive() && wolf.isTame() && wolf.isOwnedBy(owner))
            adopted.accept(owner, wolf);
    }
}
