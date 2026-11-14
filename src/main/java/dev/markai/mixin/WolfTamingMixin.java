package dev.markai.mixin;

import dev.markai.taming.CompanionTamingInteractions;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Wolf.class)
public abstract class WolfTamingMixin {
    @Unique private boolean markai$registerTameAttempt;

    @Inject(method = "tryToTame", at = @At("HEAD"))
    private void markai$beforeBoneTaming(Player player, CallbackInfo ci) {
        markai$registerTameAttempt = CompanionTamingInteractions.isShiftBoneTaming(player, (Wolf) (Object) this);
    }

    @Inject(method = "tryToTame", at = @At("RETURN"))
    private void markai$afterBoneTaming(Player player, CallbackInfo ci) {
        if (markai$registerTameAttempt) CompanionTamingInteractions.onVanillaWolfTamed(player, (Wolf) (Object) this);
        markai$registerTameAttempt = false;
    }
}
