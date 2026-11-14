package dev.markai;

import dev.markai.ai.AIConfig;
import dev.markai.mixin.MobAccessor;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

/** Small, server-side reactions for ordinary animals. No AI session or companion state is shared. */
final class BalancedMobAI {
    private record Alarm(Vec3 danger, long expiresAt) { }

    private final Supplier<AIConfig> config;
    private final Predicate<UUID> companion;
    private final Map<Entity, Boolean> installed = new WeakHashMap<>();
    private final Map<Sheep, Alarm> sheepAlarms = new WeakHashMap<>();

    BalancedMobAI(Supplier<AIConfig> config, Predicate<UUID> companion) {
        this.config = config;
        this.companion = companion;
    }

    void install(Entity entity) {
        if (!(entity instanceof Chicken) && !(entity instanceof Sheep)) return;
        if (installed.putIfAbsent(entity, Boolean.TRUE) != null) return;
        if (entity instanceof Chicken chicken) {
            ((MobAccessor) chicken).markai$goals().addGoal(2, new ChickenFleeGoal(chicken));
        } else if (entity instanceof Sheep sheep) {
            ((MobAccessor) sheep).markai$goals().addGoal(2, new SheepAlarmGoal(sheep));
        }
    }

    void onDamage(LivingEntity victim, Entity attacker) {
        AIConfig settings = config.get();
        if (settings == null || !settings.balancedMobAI || !(attacker instanceof LivingEntity danger)
                || !danger.isAlive() || danger == victim) return;
        if (danger instanceof ServerPlayer player && (player.isCreative() || player.isSpectator())) return;
        int radius = settings.balancedMobRadius;
        if (victim instanceof Sheep sheep && settings.balancedSheep) {
            int alerted = 0;
            for (Sheep neighbor : sheep.level().getEntitiesOfClass(Sheep.class,
                    sheep.getBoundingBox().inflate(radius), candidate -> candidate.isAlive() && candidate != sheep)) {
                sheepAlarms.put(neighbor, new Alarm(danger.position(), sheep.level().getGameTime() + 80));
                if (++alerted == 12) break;
            }
        } else if (victim instanceof Wolf wolf && settings.balancedWildWolves && !wolf.isTame()
                && !(danger instanceof Wolf)) {
            int alerted = 0;
            for (Wolf neighbor : wolf.level().getEntitiesOfClass(Wolf.class,
                    wolf.getBoundingBox().inflate(radius), candidate -> candidate.isAlive() && !candidate.isTame()
                        && candidate != wolf)) {
                if (neighbor.getTarget() != null && neighbor.getTarget() != danger) continue;
                neighbor.setTarget(danger);
                if (++alerted == 6) break;
            }
        }
    }

    void clear() {
        installed.clear();
        sheepAlarms.clear();
    }

    private boolean enabled(boolean speciesEnabled) {
        AIConfig settings = config.get();
        return settings != null && settings.balancedMobAI && speciesEnabled;
    }

    private final class ChickenFleeGoal extends Goal {
        private final Chicken chicken;
        private Vec3 destination;
        private long nextScan;
        private long stopAt;

        ChickenFleeGoal(Chicken chicken) {
            this.chicken = chicken;
            setFlags(java.util.EnumSet.of(Flag.MOVE));
        }

        @Override public boolean canUse() {
            AIConfig settings = config.get();
            if (!enabled(settings != null && settings.balancedChickens) || companion.test(chicken.getUUID())) return false;
            long now = chicken.level().getGameTime();
            if (now < nextScan) return false;
            nextScan = now + 20 + Math.floorMod(chicken.getUUID().hashCode(), 20);
            int radius = settings.balancedMobRadius;
            LivingEntity nearest = null;
            double distance = radius * radius;
            for (Monster monster : chicken.level().getEntitiesOfClass(Monster.class,
                    chicken.getBoundingBox().inflate(radius), Entity::isAlive)) {
                double candidate = chicken.distanceToSqr(monster);
                if (candidate < distance) { nearest = monster; distance = candidate; }
            }
            for (Wolf wolf : chicken.level().getEntitiesOfClass(Wolf.class,
                    chicken.getBoundingBox().inflate(radius), candidate -> candidate.isAlive() && !candidate.isTame())) {
                double candidate = chicken.distanceToSqr(wolf);
                if (candidate < distance) { nearest = wolf; distance = candidate; }
            }
            if (nearest == null) return false;
            destination = DefaultRandomPos.getPosAway(chicken, radius, 4, nearest.position());
            return destination != null;
        }

        @Override public void start() {
            stopAt = chicken.level().getGameTime() + 60;
            chicken.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.15);
        }

        @Override public boolean canContinueToUse() {
            AIConfig settings = config.get();
            return enabled(settings != null && settings.balancedChickens) && !companion.test(chicken.getUUID())
                && chicken.level().getGameTime() < stopAt && !chicken.getNavigation().isDone();
        }
    }

    private final class SheepAlarmGoal extends Goal {
        private final Sheep sheep;
        private Vec3 destination;
        private long stopAt;

        SheepAlarmGoal(Sheep sheep) {
            this.sheep = sheep;
            setFlags(java.util.EnumSet.of(Flag.MOVE));
        }

        @Override public boolean canUse() {
            AIConfig settings = config.get();
            if (!enabled(settings != null && settings.balancedSheep)) return false;
            Alarm alarm = sheepAlarms.get(sheep);
            if (alarm == null || sheep.level().getGameTime() > alarm.expiresAt()) {
                sheepAlarms.remove(sheep);
                return false;
            }
            destination = DefaultRandomPos.getPosAway(sheep, settings.balancedMobRadius, 4, alarm.danger());
            return destination != null;
        }

        @Override public void start() {
            sheepAlarms.remove(sheep);
            stopAt = sheep.level().getGameTime() + 60;
            sheep.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.2);
        }

        @Override public boolean canContinueToUse() {
            AIConfig settings = config.get();
            return enabled(settings != null && settings.balancedSheep)
                && sheep.level().getGameTime() < stopAt && !sheep.getNavigation().isDone();
        }
    }
}
