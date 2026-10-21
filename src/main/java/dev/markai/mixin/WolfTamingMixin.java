package dev.markai.mixin;

import dev.markai.taming.WolfTamingHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Wolf.class)
public abstract class WolfTamingMixin {
    @Inject(method = "tryToTame", at = @At("RETURN"))
    private void markai$afterBoneTaming(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer owner) WolfTamingHooks.tamed(owner, (Wolf) (Object) this);
    }
}
