package dev.markai.mixin;

import dev.markai.actions.CompanionCombatRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobCombatMixin {
    @Shadow private LivingEntity target;

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void markai$guardTarget(LivingEntity requested, CallbackInfo ci) {
        if (!CompanionCombatRules.canTarget((Mob) (Object) this, requested)) {
            target = null;
            ci.cancel();
        }
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void markai$guardAttack(ServerLevel level, Entity victim, CallbackInfoReturnable<Boolean> cir) {
        if (!CompanionCombatRules.canAttack((Mob) (Object) this, victim)) cir.setReturnValue(false);
    }
}
