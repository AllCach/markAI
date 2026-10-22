package dev.markai;

import dev.markai.actions.ActionResult;
import dev.markai.actions.CompanionCombatRules;
import dev.markai.actions.CompanionSafety;
import dev.markai.inventory.CompanionInventoryHolder;
import dev.markai.inventory.DogInventory;
import dev.markai.mixin.ItemEntityAccessor;
import dev.markai.mixin.MobAccessor;
import dev.markai.network.CompanionNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Bounded server-thread jobs. Native navigation and melee perform every movement and bite. */
final class WolfWorkRuntime {
    private final BiConsumer<MarkAIMod.Companion, Long> completionReward;

    WolfWorkRuntime() { this((companion, gameTime) -> { }); }

    WolfWorkRuntime(BiConsumer<MarkAIMod.Companion, Long> completionReward) {
        this.completionReward = java.util.Objects.requireNonNull(completionReward);
    }
    private enum Kind { ATTACK, COLLECT }
    private static final class Job {
        Kind kind;
        final ServerLevel level;
        Vec3 origin;
        double radius;
        long deadline, nextScan, nextPath, progressUntil, initialEmptyUntil;
        double lastDistance = Double.POSITIVE_INFINITY;
        UUID target;
        final Set<UUID> unreachable = new HashSet<>();
        int collected, failedPaths;
        boolean afterAttack, reportResult = true;
        MarkAIMod.Companion companion;
        Wolf wolf;
        Job(Kind kind, Wolf wolf, long duration) {
            this.kind = kind; level = (ServerLevel) wolf.level(); origin = wolf.position(); radius = 16;
            deadline = level.getGameTime() + duration; progressUntil = level.getGameTime() + 160;
            initialEmptyUntil = level.getGameTime() + 40;
        }
    }
    private final Map<UUID, Job> jobs = new HashMap<>();

    void configure(MarkAIMod.Companion companion, Wolf wolf) {
        var goals = ((MobAccessor) wolf).markai$goals();
        goals.removeAllGoals(goal -> goal instanceof CollectionGoal);
        goals.addGoal(1, new CollectionGoal(companion, wolf));
    }

    void unconfigure(Wolf wolf) {
        ((MobAccessor) wolf).markai$goals().removeAllGoals(goal -> goal instanceof CollectionGoal);
    }

    ActionResult attack(MarkAIMod.Companion companion, Wolf wolf, ServerPlayer owner, LivingEntity target) {
        return attack(companion, wolf, owner, target, true);
    }

    ActionResult attack(MarkAIMod.Companion companion, Wolf wolf, ServerPlayer owner, LivingEntity target, boolean reportResult) {
        if (!validOwner(companion, wolf, owner) || !CompanionCombatRules.commandTargetAllowed(wolf, owner, target))
            return failed(companion, wolf, "invalid_attack_target", "Посмотри на видимого врага или дикое животное не дальше 16 блоков. Игроков и питомцев я не трогаю.");
        if (unsafe(wolf, true)) return failed(companion, wolf, "unsafe_work", "Сейчас мне нужно выбраться из опасности и восстановиться.");
        cancel(companion, wolf);
        Job job = new Job(Kind.ATTACK, wolf, 600); job.target = target.getUUID(); job.reportResult = reportResult;
        begin(companion, wolf, job); wolf.setTarget(target);
        return companion.living.lastAction = ActionResult.accepted("Атакую выбранную цель. Потом подберу её добычу рядом.", job.level.getGameTime());
    }

    ActionResult collect(MarkAIMod.Companion companion, Wolf wolf, ServerPlayer owner) {
        return collect(companion, wolf, owner, true);
    }

    ActionResult collect(MarkAIMod.Companion companion, Wolf wolf, ServerPlayer owner, boolean reportResult) {
        if (!validOwner(companion, wolf, owner)) return failed(companion, wolf, "invalid_owner", "Для сбора подойди к своему волку на 16 блоков.");
        if (unsafe(wolf, wolf.getTarget() != null)) return failed(companion, wolf, "unsafe_work", "Сейчас мне нужно выбраться из опасности и восстановиться.");
        if (activeThreat(wolf, owner) != null) return failed(companion, wolf, "collect_interrupted", "Сейчас идёт бой. Соберу предметы, когда станет безопасно.");
        cancel(companion, wolf);
        Job job = new Job(Kind.COLLECT, wolf, 1200); job.reportResult = reportResult; begin(companion, wolf, job);
        return companion.living.lastAction = ActionResult.accepted("Собираю лежащие предметы в радиусе 16 блоков в свой инвентарь.", job.level.getGameTime());
    }

    private void begin(MarkAIMod.Companion companion, Wolf wolf, Job job) {
        job.companion = companion; job.wolf = wolf;
        jobs.put(wolf.getUUID(), job); companion.workActive = true; companion.requestedAction = null;
        companion.dismissed = false; companion.automaticSit = false; companion.expectedSit = false; companion.sitObserved = true;
        companion.living.behavior.cancel(job.level.getGameTime());
        companion.action = job.kind.name(); wolf.setOrderedToSit(false); wolf.setIsInterested(false);
        wolf.getNavigation().stop(); wolf.setTarget(null);
    }

    /** Returns true while a work job owns movement; false lets ordinary safety/behavior run now. */
    boolean tick(MarkAIMod.Companion companion, Wolf wolf, ServerPlayer owner) {
        Job job = jobs.get(wolf.getUUID());
        if (job == null) { companion.workActive = false; return false; }
        if (!validOwner(companion, wolf, owner) || wolf.level() != job.level || companion.dismissed || wolf.isOrderedToSit()) {
            finish(companion, wolf, ActionResult.cancelled("Задание остановлено.", job.level.getGameTime())); return false;
        }
        if (unsafe(wolf, job.kind == Kind.ATTACK || wolf.getTarget() != null)) {
            finish(companion, wolf, ActionResult.failed("unsafe_work", "Прерываю задание: мне нужно отступить из опасности.", job.level.getGameTime())); return false;
        }
        LivingEntity threat = job.kind == Kind.COLLECT ? activeThreat(wolf, owner) : null;
        if (threat != null) {
            finish(companion, wolf, new ActionResult(ActionResult.Status.CANCELLED, "collect_interrupted",
                "Прерываю сбор: рядом начался бой.", job.level.getGameTime()));
            // Clearing the work job must not erase the native defensive target that interrupted it.
            if (CompanionCombatRules.canTarget(wolf, threat)) wolf.setTarget(threat);
            return false;
        }
        if (job.level.getGameTime() >= job.deadline) {
            finish(companion, wolf, job.collected > 0
                ? ActionResult.succeeded("Подобрал предметов: " + job.collected + ". Время сбора закончилось.", job.level.getGameTime())
                : ActionResult.failed("work_timeout", "Не удалось закончить задание вовремя.", job.level.getGameTime())); return false;
        }
        companion.action = job.kind.name();
        if (job.kind == Kind.ATTACK) {
            Entity target = job.level.getEntity(job.target);
            if (target instanceof LivingEntity victim && !victim.isAlive()) {
                job.kind = Kind.COLLECT; job.origin = victim.position(); job.radius = 4; job.afterAttack = true;
                job.target = null; job.nextScan = job.level.getGameTime() + 5; job.deadline = job.level.getGameTime() + 240;
                job.initialEmptyUntil = job.level.getGameTime() + 40;
                job.progressUntil = job.level.getGameTime() + 160; wolf.setTarget(null); wolf.getNavigation().stop();
            } else if (!(target instanceof LivingEntity victim) || !mayAttack(companion, wolf, victim)) {
                finish(companion, wolf, ActionResult.failed("target_lost", "Цель ушла слишком далеко или больше недоступна.", job.level.getGameTime())); return false;
            } else {
                wolf.setTarget(victim);
                double distance = wolf.distanceToSqr(victim);
                if (distance < job.lastDistance - .25) { job.lastDistance = distance; job.progressUntil = job.level.getGameTime() + 160; }
                // A target within bite reach may legitimately take longer than navigation to defeat.
                if (distance > 4 && job.level.getGameTime() >= job.progressUntil) {
                    finish(companion, wolf, ActionResult.failed("attack_unreachable", "Не могу добраться до выбранной цели.", job.level.getGameTime())); return false;
                }
            }
        } else collectTick(companion, wolf, owner, job);
        return jobs.get(wolf.getUUID()) == job;
    }

    boolean mayAttack(MarkAIMod.Companion companion, Wolf wolf, LivingEntity target) {
        Job job = jobs.get(wolf.getUUID());
        return companion.workActive && job != null && job.kind == Kind.ATTACK && target != null && target.isAlive()
            && !target.isRemoved() && target.level() == job.level && wolf.level() == job.level
            && job.level.getGameTime() < job.deadline && target.getUUID().equals(job.target)
            && wolf.distanceToSqr(target) <= (double) CompanionCombatRules.combatRadius() * CompanionCombatRules.combatRadius()
            && wolf.position().distanceToSqr(job.origin) <= (double) CompanionCombatRules.combatRadius() * CompanionCombatRules.combatRadius()
            && job.level.hasChunkAt(target.blockPosition()) && !unsafe(wolf, true);
    }

    void cancel(MarkAIMod.Companion companion, Wolf wolf) {
        remove(companion.entity); companion.workActive = false;
        if (wolf != null) { wolf.getNavigation().stop(); wolf.setTarget(null); }
    }
    void remove(UUID entity) {
        Job job = jobs.remove(entity);
        if (job == null) return;
        job.companion.workActive = false;
        job.wolf.getNavigation().stop(); job.wolf.setTarget(null);
    }
    void clear() { for (UUID entity : List.copyOf(jobs.keySet())) remove(entity); }

    private void collectTick(MarkAIMod.Companion companion, Wolf wolf, ServerPlayer owner, Job job) {
        long now = job.level.getGameTime();
        if (wolf.position().distanceToSqr(job.origin) > (job.radius + 2) * (job.radius + 2)) {
            finish(companion, wolf, ActionResult.failed("outside_collection_area", "Остановился у границы участка сбора.", now)); return;
        }
        ItemEntity item = job.target != null && job.level.getEntity(job.target) instanceof ItemEntity found ? found : null;
        if (item != null && !eligible(item, wolf, owner, job)) { job.target = null; item = null; wolf.getNavigation().stop(); }
        if (item == null && now >= job.nextScan) {
            job.nextScan = now + 20;
            List<ItemEntity> candidates = new ArrayList<>(32);
            job.level.getEntities(EntityTypeTest.forClass(ItemEntity.class), new AABB(job.origin, job.origin).inflate(job.radius),
                candidate -> eligible(candidate, wolf, owner, job), candidates, 32);
            candidates.sort(Comparator.comparingDouble(wolf::distanceToSqr));
            DogInventory inventory = inventory(wolf);
            item = candidates.stream().filter(candidate -> inventory.canAddItem(candidate.getItem())).findFirst().orElse(null);
            if (item == null) {
                // Vanilla Q-drops remain unpickable for 40 ticks; do not finish before that initial grace period.
                if (candidates.isEmpty() && now < job.initialEmptyUntil) return;
                String text = !candidates.isEmpty() ? "Инвентарь заполнен. Остальные предметы оставил на земле."
                    : job.collected > 0 ? "Сбор закончен. Подобрал предметов: " + job.collected + "."
                    : job.afterAttack ? "Цель повержена. Доступной добычи рядом нет."
                    : job.unreachable.isEmpty() ? "В этом радиусе нет доступных лежащих предметов."
                    : "Доступные предметы собраны; к остальным не могу безопасно подойти.";
                ActionResult result = !candidates.isEmpty() && job.collected == 0 && !job.afterAttack
                    ? ActionResult.failed("inventory_full", text, now) : ActionResult.succeeded(text, now);
                finish(companion, wolf, result); return;
            }
            job.target = item.getUUID(); job.failedPaths = 0; job.lastDistance = Double.POSITIVE_INFINITY;
            job.progressUntil = now + 160; job.nextPath = 0;
        }
        if (item == null) return;
        double distance = wolf.distanceToSqr(item);
        if (distance <= 2.25 && wolf.hasLineOfSight(item)) {
            ItemStack before = item.getItem();
            ItemStack remainder = inventory(wolf).addItem(before.copy());
            int accepted = before.getCount() - remainder.getCount();
            if (accepted > 0) {
                job.collected += accepted;
                wolf.take(item, accepted);
                if (remainder.isEmpty()) item.discard(); else item.setItem(remainder);
            }
            job.target = null; job.nextScan = now + 1; wolf.getNavigation().stop();
            return;
        }
        if (distance < job.lastDistance - .25) { job.lastDistance = distance; job.progressUntil = now + 160; }
        if (now >= job.progressUntil || job.failedPaths >= 3) skip(wolf, job, item);
    }

    private static boolean eligible(ItemEntity item, Wolf wolf, ServerPlayer owner, Job job) {
        if (!item.isAlive() || item.isRemoved() || item.isPassenger() || item.hasPickUpDelay() || item.getItem().isEmpty()
                || item.level() != job.level || job.unreachable.contains(item.getUUID())
                || item.position().distanceToSqr(job.origin) > job.radius * job.radius
                || !job.level.hasChunkAt(item.blockPosition()) || !wolf.hasLineOfSight(item)) return false;
        UUID reservation = ((ItemEntityAccessor) item).markai$getPickupTarget();
        if (reservation != null && !reservation.equals(owner.getUUID())) return false;
        Entity thrower = item.getOwner();
        return thrower == null || thrower.getUUID().equals(owner.getUUID());
    }

    private static DogInventory inventory(Wolf wolf) { return ((CompanionInventoryHolder) wolf).markai$inventory(); }
    private static boolean validOwner(MarkAIMod.Companion companion, Wolf wolf, ServerPlayer owner) {
        return wolf != null && owner != null && wolf.isAlive() && !wolf.isRemoved() && owner.isAlive() && !owner.isSpectator()
            && wolf.isTame() && wolf.level() instanceof ServerLevel && owner.level() == wolf.level()
            && companion.owner.equals(owner.getUUID()) && companion.entity.equals(wolf.getUUID())
            && wolf.getOwnerReference() != null && wolf.getOwnerReference().getUUID().equals(owner.getUUID())
            && owner.distanceToSqr(wolf) <= 256;
    }
    private static boolean unsafe(Wolf wolf, boolean combat) {
        return CompanionSafety.critical(wolf) || combat && wolf.getHealth() < wolf.getMaxHealth() * .3F;
    }
    private static LivingEntity activeThreat(Wolf wolf, ServerPlayer owner) {
        LivingEntity current = wolf.getTarget();
        if (current instanceof Enemy && current.isAlive() && current.level() == wolf.level()) return current;
        LivingEntity source = recentEnemyAttack(wolf);
        if (source != null) return source;
        source = owner != null && wolf.hasLineOfSight(owner) ? recentEnemyAttack(owner) : null;
        return source != null && wolf.hasLineOfSight(source) ? source : null;
    }
    private static LivingEntity recentEnemyAttack(LivingEntity victim) {
        LivingEntity source = victim.getLastHurtByMob();
        int elapsed = victim.tickCount - victim.getLastHurtByMobTimestamp();
        return source instanceof Enemy && source.isAlive() && !source.isRemoved() && source.level() == victim.level()
            && elapsed >= 0 && elapsed <= 100 ? source : null;
    }
    private static ActionResult failed(MarkAIMod.Companion companion, Wolf wolf, String code, String text) {
        return companion.living.lastAction = ActionResult.failed(code, text, wolf.level().getGameTime());
    }
    private void finish(MarkAIMod.Companion companion, Wolf wolf, ActionResult result) {
        Job job = jobs.get(wolf.getUUID());
        boolean reportResult = job == null || job.reportResult;
        cancel(companion, wolf); companion.living.lastAction = result;
        if (job != null && result.success()) completionReward.accept(companion, job.level.getGameTime());
        ServerPlayer owner = ((ServerLevel) wolf.level()).getServer().getPlayerList().getPlayer(companion.owner);
        if (owner != null && reportResult) {
            owner.sendSystemMessage(Component.literal("<" + companion.living.name + "> " + result.message()));
            CompanionNetwork.notice(owner, result.success() ? "info" : "error", result.message());
        }
    }
    private static void skip(Wolf wolf, Job job, ItemEntity item) {
        // At most 32 rejected paths are retained; the complete job is also time bounded.
        if (job.unreachable.size() < 32) job.unreachable.add(item.getUUID());
        else job.deadline = job.level.getGameTime();
        job.target = null; job.nextScan = job.level.getGameTime() + 20; wolf.getNavigation().stop();
    }

    private final class CollectionGoal extends Goal {
        private final MarkAIMod.Companion companion;
        private final Wolf wolf;
        CollectionGoal(MarkAIMod.Companion companion, Wolf wolf) {
            this.companion = companion; this.wolf = wolf; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override public boolean canUse() {
            Job job = jobs.get(wolf.getUUID());
            if (!companion.workActive || job == null || job.kind != Kind.COLLECT || wolf.isOrderedToSit() || unsafe(wolf, false)) return false;
            ServerPlayer owner = job.level.getServer().getPlayerList().getPlayer(companion.owner);
            return activeThreat(wolf, owner) == null;
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void stop() { wolf.getNavigation().stop(); }
        @Override public void tick() {
            Job job = jobs.get(wolf.getUUID());
            if (job == null || job.target == null || job.level.getGameTime() < job.nextPath) return;
            if (!(job.level.getEntity(job.target) instanceof ItemEntity item)) return;
            ServerPlayer owner = job.level.getServer().getPlayerList().getPlayer(companion.owner);
            if (owner == null || !eligible(item, wolf, owner, job)) return;
            wolf.getLookControl().setLookAt(item, 30, 30); job.nextPath = job.level.getGameTime() + 20;
            Vec3 destination = item.position();
            if (!CompanionSafety.safeNavigationTarget(job.level, destination)) {
                BlockPos block = item.blockPosition();
                destination = new Vec3(item.getX(), block.getY(), item.getZ());
                if (!CompanionSafety.safeNavigationTarget(job.level, destination)) {
                    destination = destination.add(0, .5, 0);
                    if (!CompanionSafety.safeNavigationTarget(job.level, destination)) { skip(wolf, job, item); return; }
                }
            }
            if (!wolf.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.1)) job.failedPaths++;
            else job.failedPaths = 0;
        }
    }
}
