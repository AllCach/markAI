package dev.markai.actions;

import dev.markai.core.HomeLocation;
import dev.markai.inventory.CompanionInventoryHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.Vec3;

/** Authoritative game-thread validation; accepting a request never claims navigation succeeded. */
public final class ActionValidator {
    public static final double COMMAND_RANGE = 64;
    public static final double INVESTIGATION_RANGE = 16;

    private ActionValidator() {}

    /** A null investigation entity denotes an observed block selected by the caller's perception layer. */
    public static ActionResult validate(ServerPlayer owner, Wolf wolf, CompanionAction action,
            HomeLocation home, Entity investigationTarget, long gameTime) {
        if (action == null) return failure("unsupported_action", "Не знаю такого действия.", gameTime);
        if (owner == null || wolf == null || !owner.isAlive() || owner.isSpectator())
            return failure("unavailable_owner", "Сейчас нельзя отдать эту команду.", gameTime);
        if (!(wolf instanceof CompanionInventoryHolder holder) || !holder.markai$isCompanion()
                || wolf.getOwnerReference() == null || !wolf.getOwnerReference().getUUID().equals(owner.getUUID()))
            return failure("not_owner", "Этим спутником может управлять только его хозяин.", gameTime);
        if (!wolf.isAlive() || wolf.isRemoved() || !wolf.isTame())
            return failure("unavailable_companion", "Спутник сейчас недоступен.", gameTime);
        if (!(wolf.level() instanceof ServerLevel level) || owner.level() != level)
            return failure("different_dimension", "Мы сейчас в разных измерениях.", gameTime);
        if (level.getEntity(wolf.getUUID()) != wolf)
            return failure("unloaded_companion", "Спутник должен быть загружен рядом с вами.", gameTime);
        if (!Double.isFinite(owner.distanceToSqr(wolf)) || owner.distanceToSqr(wolf) > COMMAND_RANGE * COMMAND_RANGE)
            return failure("too_far", "Я слишком далеко для этой команды.", gameTime);
        if (action != CompanionAction.RETREAT && action != CompanionAction.CANCEL
                && CompanionSafety.critical(wolf))
            return failure("unsafe_current_position", "Сначала мне нужно выбраться из опасного места.", gameTime);
        if (action == CompanionAction.RETURN_HOME) {
            if (home == null) return failure("home_unknown", "Я ещё не знаю, где наш дом.", gameTime);
            if (!level.dimension().identifier().toString().equals(home.dimension()))
                return failure("home_in_other_dimension", "Наш дом в другом измерении.", gameTime);
            if (home.distanceTo(wolf.getX(), wolf.getY(), wolf.getZ()) > COMMAND_RANGE)
                return failure("home_too_far", "Дом слишком далеко: подойдём ближе.", gameTime);
            BlockPos target = BlockPos.containing(home.x(), home.y(), home.z());
            if (!level.hasChunkAt(target))
                return failure("home_unloaded", "Место дома сейчас не загружено.", gameTime);
            if (!isSafeHome(wolf, home))
                return failure("unsafe_home", "Точка дома сейчас небезопасна.", gameTime);
        }
        if (action == CompanionAction.INVESTIGATE && investigationTarget != null) {
            if (!investigationTarget.isAlive() || investigationTarget.isRemoved() || investigationTarget.level() != level
                    || level.getEntity(investigationTarget.getUUID()) != investigationTarget
                    || investigationTarget.isInvisible() || wolf.distanceToSqr(investigationTarget) > INVESTIGATION_RANGE * INVESTIGATION_RANGE
                    || !wolf.hasLineOfSight(investigationTarget))
                return failure("unobserved_target", "Я не вижу доступной цели рядом.", gameTime);
        }
        return ActionResult.accepted("Команда принята.", gameTime);
    }

    /** Tests the destination without moving the wolf or loading chunks. Route success remains the navigator's responsibility. */
    public static boolean isSafeHome(Wolf wolf, HomeLocation home) {
        if (wolf == null || home == null || !(wolf.level() instanceof ServerLevel level)
                || !level.dimension().identifier().toString().equals(home.dimension())) return false;
        BlockPos feet = BlockPos.containing(home.x(), home.y(), home.z());
        if (!level.isInWorldBounds(feet) || !level.hasChunkAt(feet) || !level.hasChunkAt(feet.below())) return false;
        var box = wolf.getBoundingBox().move(home.x() - wolf.getX(), home.y() - wolf.getY(), home.z() - wolf.getZ());
        if (!level.getWorldBorder().isWithinBounds(box) || !level.noCollision(wolf, box)) return false;
        return CompanionSafety.safeNavigationTarget(level, new Vec3(home.x(), home.y(), home.z()));
    }

    private static ActionResult failure(String code, String message, long time) {
        return ActionResult.failed(code, message, time);
    }
}
