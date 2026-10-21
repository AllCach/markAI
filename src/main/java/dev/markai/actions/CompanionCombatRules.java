package dev.markai.actions;

import dev.markai.inventory.CompanionInventoryHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import java.util.function.BiPredicate;

/** Shared target and damage gate. No conversation or model output can bypass these checks. */
public final class CompanionCombatRules {
    private static volatile BiPredicate<Wolf, LivingEntity> permission = (wolf, target) -> false;
    private static volatile BiPredicate<Wolf, LivingEntity> explicitPermission = (wolf, target) -> false;

    private CompanionCombatRules() {}

    /** The server installs a lookup of the tracked UUID and current defensive setting. */
    public static void register(BiPredicate<Wolf, LivingEntity> rule) {
        permission = rule == null ? (wolf, target) -> false : rule;
    }

    /** Permission is scoped to one live owner-issued job and its exact target UUID. */
    public static void registerExplicit(BiPredicate<Wolf, LivingEntity> rule) {
        explicitPermission = rule == null ? (wolf, target) -> false : rule;
    }

    public static boolean isCompanion(Mob mob) {
        return mob instanceof Wolf wolf && wolf instanceof CompanionInventoryHolder holder && holder.markai$isCompanion();
    }

    public static boolean canTarget(Mob attacker, LivingEntity target) {
        if (!isCompanion(attacker) || target == null) return true;
        Wolf wolf = (Wolf) attacker;
        if (!(wolf.level() instanceof ServerLevel level)) return true;
        if (!protectedTargetAllowed(wolf, target) || target.level() != level || wolf.distanceToSqr(target) > 256
                || !level.hasChunkAt(target.blockPosition())) return false;
        try {
            if ((target instanceof Enemy || target instanceof Animal) && explicitPermission.test(wolf, target)) return true;
        } catch (RuntimeException ignored) { return false; }
        if (!(target instanceof Enemy)) return false;
        if (target instanceof NeutralMob neutral) {
            LivingEntity owner = wolf.getOwner();
            boolean attacking = target instanceof Mob enemy && (enemy.getTarget() == wolf || owner != null && enemy.getTarget() == owner);
            if (!attacking && !neutral.isAngryAt(wolf, level) && (owner == null || !neutral.isAngryAt(owner, level))
                    && !recentlyHurtBy(wolf, target) && !recentlyHurtBy(owner, target)) return false;
        }
        try { return permission.test(wolf, target); }
        catch (RuntimeException ignored) { return false; }
    }

    /** Initial command acquisition requires an observable target, never a model-provided entity ID. */
    public static boolean commandTargetAllowed(Wolf wolf, ServerPlayer owner, LivingEntity target) {
        return wolf != null && owner != null && target != null && wolf.isAlive() && !wolf.isRemoved()
            && wolf.isTame() && owner.isAlive() && !owner.isSpectator()
            && wolf.getOwnerReference() != null && wolf.getOwnerReference().getUUID().equals(owner.getUUID())
            && wolf.level() instanceof ServerLevel level && owner.level() == level && target.level() == level
            && (target instanceof Enemy || target instanceof Animal) && protectedTargetAllowed(wolf, target)
            && wolf.distanceToSqr(target) <= 256 && owner.distanceToSqr(target) <= 256
            && owner.distanceToSqr(wolf) <= 256 && level.hasChunkAt(target.blockPosition())
            && owner.hasLineOfSight(target) && wolf.hasLineOfSight(target);
    }

    private static boolean protectedTargetAllowed(Wolf wolf, LivingEntity target) {
        if (target == null || !target.isAlive() || target.isRemoved() || target == wolf
                || target instanceof Player || target instanceof AbstractVillager || target instanceof ArmorStand
                || wolf.isAlliedTo(target)) return false;
        if (wolf.getOwnerReference() != null && wolf.getOwnerReference().getUUID().equals(target.getUUID())) return false;
        return !(target instanceof TamableAnimal pet && pet.isTame())
            && !(target instanceof OwnableEntity owned && owned.getOwnerReference() != null);
    }

    public static boolean canAttack(Mob attacker, Entity target) {
        if (!isCompanion(attacker)) return true;
        return target instanceof LivingEntity living && canTarget(attacker, living);
    }

    private static boolean recentlyHurtBy(LivingEntity victim, LivingEntity source) {
        return victim != null && victim.getLastHurtByMob() == source
            && victim.tickCount >= victim.getLastHurtByMobTimestamp()
            && victim.tickCount - victim.getLastHurtByMobTimestamp() <= 200;
    }
}
