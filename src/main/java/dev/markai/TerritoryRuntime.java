package dev.markai;

import dev.markai.actions.CompanionSafety;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;

/** Real movement and observation for bounded owner-issued territorial jobs. */
final class TerritoryRuntime {
    boolean tickWolf(MarkAIMod.Companion companion, Wolf wolf, ServerPlayer owner) {
        TerritoryTask task = companion.territory;
        if (task == null) return false;
        if (!task.valid()) { companion.territory = null; return false; }
        ServerLevel level = (ServerLevel) wolf.level();
        if (!task.dimension.equals(level.dimension().identifier().toString())) {
            wolf.getNavigation().stop(); companion.action = "TERRITORY_UNLOADED"; return true;
        }
        if (CompanionSafety.critical(wolf)) {
            companion.territory = null;
            owner.sendSystemMessage(Component.literal(companion.living.name + ": задание прервано из-за опасности."));
            return false;
        }
        if (wolf.getTarget() != null && wolf.getTarget().isAlive()) return false;
        if (task.mode == TerritoryTask.Mode.LOOKOUT) { companion.territory = null; return false; }

        long now = level.getGameTime();
        if (task.mode == TerritoryTask.Mode.GUARD) {
            Monster threat = level.getEntitiesOfClass(Monster.class, wolf.getBoundingBox().inflate(12),
                mob -> mob.isAlive() && mob.position().distanceToSqr(task.anchor()) <= task.radius * task.radius
                    && LivingCompanionRuntime.validTarget(companion, mob) && wolf.hasLineOfSight(mob))
                .stream().min(Comparator.comparingDouble(wolf::distanceToSqr)).orElse(null);
            if (threat != null) {
                wolf.setOrderedToSit(false);
                wolf.setTarget(threat);
                companion.action = "GUARD";
                return true;
            }
            companion.action = "GUARD";
            if (wolf.position().distanceToSqr(task.anchor()) <= 9) {
                wolf.getNavigation().stop(); wolf.setOrderedToSit(true); return true;
            }
            wolf.setOrderedToSit(false);
            move(wolf, level, task, task.anchor(), owner, companion, now);
            return true;
        }

        int offset = Math.min(task.radius - 4, task.mode == TerritoryTask.Mode.PATROL ? 12 : 24);
        Vec3 target = switch (task.waypoint % 4) {
            case 0 -> task.anchor().add(offset, 0, 0);
            case 1 -> task.anchor().add(0, 0, offset);
            case 2 -> task.anchor().add(-offset, 0, 0);
            default -> task.anchor().add(0, 0, -offset);
        };
        companion.action = task.mode.name();
        wolf.setOrderedToSit(false);
        if (wolf.position().distanceToSqr(target) <= 6.25) {
            wolf.getNavigation().stop();
            if (task.mode == TerritoryTask.Mode.EXPLORE) {
                String biome = level.getBiome(wolf.blockPosition()).unwrapKey()
                    .map(key -> key.identifier().toString()).orElse("");
                companion.progression.discoverBiome(biome);
                if (++task.waypoint >= 4) {
                    companion.territory = null;
                    companion.action = "EXPLORE_COMPLETE";
                    owner.sendSystemMessage(Component.literal(companion.living.name + " исследовал четыре доступные точки территории."));
                }
            } else task.waypoint = (task.waypoint + 1) % 4;
            return true;
        }
        move(wolf, level, task, target, owner, companion, now);
        return true;
    }

    boolean tickChicken(MarkAIMod.Companion companion, Chicken chicken, ServerPlayer owner,
                        ChickenCompanionRuntime chickenRuntime) {
        TerritoryTask task = companion.territory;
        if (task == null) return false;
        if (!task.valid() || task.mode != TerritoryTask.Mode.LOOKOUT) {
            companion.territory = null; return false;
        }
        ServerLevel level = (ServerLevel) chicken.level();
        if (!task.dimension.equals(level.dimension().identifier().toString())) {
            chickenRuntime.stop(chicken); companion.action = "TERRITORY_UNLOADED"; return true;
        }
        long now = level.getGameTime();
        Monster threat = level.getEntitiesOfClass(Monster.class, chicken.getBoundingBox().inflate(12),
            mob -> mob.isAlive() && mob.position().distanceToSqr(task.anchor()) <= task.radius * task.radius)
            .stream().min(Comparator.comparingDouble(chicken::distanceToSqr)).orElse(null);
        if (threat != null && chicken.hasLineOfSight(threat)) {
            chickenRuntime.evade(chicken, 1);
            if (now >= task.nextAlertAt) {
                owner.sendSystemMessage(Component.literal(companion.living.name + " заметила угрозу у охраняемого места!"));
                task.nextAlertAt = now + 200;
            }
            companion.action = "LOOKOUT";
            return true;
        }
        if (chicken.position().distanceToSqr(task.anchor()) > 16 && now >= task.nextMoveAt) {
            task.nextMoveAt = now + 20;
            if (level.hasChunkAt(net.minecraft.core.BlockPos.containing(task.anchor())))
                chicken.getNavigation().moveTo(task.x, task.y, task.z, 1.0);
        } else if (chicken.position().distanceToSqr(task.anchor()) <= 16) chickenRuntime.stop(chicken);
        companion.action = "LOOKOUT";
        return true;
    }

    private void move(Wolf wolf, ServerLevel level, TerritoryTask task, Vec3 target, ServerPlayer owner,
                      MarkAIMod.Companion companion, long now) {
        if (now < task.nextMoveAt) return;
        task.nextMoveAt = now + 20;
        if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(target))) {
            wolf.getNavigation().stop(); companion.action = "TERRITORY_UNLOADED"; return;
        }
        if (!LivingCompanionRuntime.safePathTarget(level, target)
                || !wolf.getNavigation().moveTo(target.x, target.y, target.z, 1.0)) {
            if (++task.failedPaths >= 3) {
                companion.territory = null;
                wolf.getNavigation().stop();
                owner.sendSystemMessage(Component.literal(companion.living.name + ": не могу найти безопасный путь по территории."));
            }
        } else task.failedPaths = 0;
    }
}
