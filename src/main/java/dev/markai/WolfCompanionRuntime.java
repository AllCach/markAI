package dev.markai;

import dev.markai.actions.*;
import dev.markai.behavior.*;
import dev.markai.brain.MarkBrain;
import dev.markai.core.HomeLocation;
import dev.markai.events.WorldEvent;
import dev.markai.perception.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Game-thread adapter between immutable perception, the deterministic brain and native wolf goals. */
final class WolfCompanionRuntime {
    private final java.util.function.BooleanSupplier memoryEnabled;
    WolfCompanionRuntime() { this(() -> true); }
    WolfCompanionRuntime(java.util.function.BooleanSupplier memoryEnabled) { this.memoryEnabled = memoryEnabled; }
    final WolfWorldObserver observer = new WolfWorldObserver();
    private final Map<UUID, Observation> previous = new HashMap<>();
    private final Map<UUID, Goal> behaviorGoals = new HashMap<>();
    private static final class Observation {
        String biome, dimension;
        boolean sleeping, cave, home, combat;
        long nextSound, nextGreeting, ownerHitUntil, wolfHitUntil, nextAmbient;
        float health = -1;
        int diamonds = -1;
        long navigationAttempt, navigationDeadline;
        int failedPaths;
        double lastNavigationDistance = Double.POSITIVE_INFINITY;
        long nextTeleport;
        long goalTicks, lastGoalTick;
        WorldState world;
        final dev.markai.events.EventHistory temporaryEvents = new dev.markai.events.EventHistory();
    }

    void clear() { previous.clear(); behaviorGoals.clear(); observer.clear(); }
    void remove(UUID id) { previous.remove(id); behaviorGoals.remove(id); observer.remove(id); }
    void unconfigure(MarkAIMod.Companion c, Wolf wolf) {
        Goal goal = behaviorGoals.remove(c.entity);
        if (goal != null) ((dev.markai.mixin.MobAccessor) wolf).markai$goals().removeGoal(goal);
    }
    Map<String, Object> debug(MarkAIMod.Companion c, Wolf wolf) {
        Observation observed = previous.get(c.entity);
        var result = new LinkedHashMap<String, Object>();
        result.put("goal_ticks", observed == null ? 0 : observed.goalTicks);
        result.put("last_goal_time", observed == null ? 0 : observed.lastGoalTick);
        result.put("navigation_attempt_at", observed == null ? 0 : observed.navigationAttempt);
        result.put("navigation_deadline", observed == null ? 0 : observed.navigationDeadline);
        result.put("failed_paths", observed == null ? 0 : observed.failedPaths);
        if (wolf != null) {
            result.put("wolf_ticks", wolf.tickCount); result.put("sitting_pose", wolf.isInSittingPose());
            result.put("ordered_to_sit", wolf.isOrderedToSit()); result.put("navigation_done", wolf.getNavigation().isDone());
            result.put("entity_chunk_ticking", ((ServerLevel)wolf.level()).areEntitiesActuallyLoadedAndTicking(wolf.chunkPosition()));
        }
        return result;
    }
    WorldState world(MarkAIMod.Companion c, Wolf wolf, ServerPlayer owner) {
        return observer.observe((ServerLevel) wolf.level(), wolf, owner);
    }
    void unavailable(MarkAIMod.Companion c, Wolf wolf) {
        c.memory.flushPending();
        boolean critical = CompanionSafety.critical(wolf);
        var decision = MarkBrain.think(BehaviorInput.builder(wolf.level().getGameTime()).owner(false, false, 1000)
            .health(wolf.getHealth(), wolf.getMaxHealth()).danger(critical, false, 1000, false, false, false)
            .stay(!c.following || c.dismissed).build(), c.living);
        c.action = decision.activity().name();
        wolf.setTarget(null); wolf.setOrderedToSit(decision.activity() != Activity.RETREAT);
        c.expectedSit = wolf.isOrderedToSit(); c.sitObserved = true;
        previous.computeIfAbsent(c.entity, id -> new Observation());
        if (!critical) wolf.getNavigation().stop();
    }
    void event(MarkAIMod.Companion c, Wolf wolf, WorldEvent.Type type, String detail, double magnitude) {
        long time = wolf.level().getGameTime();
        WorldEvent observed = new WorldEvent(type, time, detail, magnitude);
        boolean retain = memoryEnabled.getAsBoolean();
        if (retain) { if (!c.living.onEvent(observed)) return; }
        else {
            Observation runtime = previous.computeIfAbsent(c.entity, id -> new Observation());
            if (!runtime.temporaryEvents.accept(observed)) return;
            c.living.normalize(); c.living.emotions.react(observed, c.living.personality);
        }
        if (retain) c.memory.rememberEvent(type.name(), detail, time, wolf.level().dimension().identifier().toString(),
            wolf.blockPosition().getX(), wolf.blockPosition().getY(), wolf.blockPosition().getZ());
    }
    void damage(MarkAIMod.Companion c, Wolf wolf, LivingEntity hurt, Entity attacker, double amount) {
        if (hurt.level() != wolf.level()) return;
        Observation old = previous.computeIfAbsent(c.entity, id -> new Observation());
        if (hurt.getUUID().equals(c.entity)) {
            old.wolfHitUntil = wolf.level().getGameTime() + 100;
            event(c, wolf, attacker != null && attacker.getUUID().equals(c.owner)
                ? WorldEvent.Type.OWNER_ATTACKED_WOLF : WorldEvent.Type.WOLF_TOOK_DAMAGE,
                "Я получил ранение; сейчас " + (int) wolf.getHealth() + " HP.", amount);
            if (wolf.getHealth() < wolf.getMaxHealth() * .25)
                event(c, wolf, WorldEvent.Type.COMPANION_HEALTH_CRITICAL, "Я тяжело ранен.", 20);
        } else if (hurt.getUUID().equals(c.owner) && wolf.distanceTo(hurt) <= 16 && wolf.hasLineOfSight(hurt)) {
            old.ownerHitUntil = wolf.level().getGameTime() + 100;
            event(c, wolf, WorldEvent.Type.PLAYER_TOOK_DAMAGE, "Хозяин получил урон" +
                (attacker != null && wolf.hasLineOfSight(attacker) ? " от " + attacker.getType().getDescription().getString() : "") + ".", amount);
        }
    }

    void tick(MarkAIMod.Companion c, Wolf wolf, ServerPlayer owner) {
        c.memory.flushPending();
        long now = wolf.level().getGameTime();
        Observation old = previous.computeIfAbsent(c.entity, id -> new Observation());
        WorldState view = world(c, wolf, owner); old.world = view;
        Activity previousActivity = c.living.activity();
        c.living.emotions.decayTo(now, c.living.activity() == Activity.REST, !wolf.getNavigation().isDone(), c.living.personality);
        String biome = view.environment().biome(), dimension = view.environment().dimension();
        if (!Objects.equals(old.biome, biome)) {
            event(c, wolf, WorldEvent.Type.ENTERED_NEW_BIOME, "Я был в биоме " + biome + ".", 1);
            old.biome = biome;
        }
        if (!Objects.equals(old.dimension, dimension)) {
            if (dimension.endsWith("the_nether")) event(c, wolf, WorldEvent.Type.ENTERED_NETHER, "Мы вошли в Незер.", 1);
            if (dimension.endsWith("the_end")) event(c, wolf, WorldEvent.Type.ENTERED_END, "Мы вошли в Энд.", 1);
            old.dimension = dimension;
        }
        if (view.environment().dangerous_cave() && !old.cave)
            event(c, wolf, WorldEvent.Type.ENTERED_DANGEROUS_CAVE, "Здесь темно, а над собой я не вижу открытого неба.", 1);
        old.cave = view.environment().dangerous_cave();
        if (owner.isSleeping() && !old.sleeping) event(c, wolf, WorldEvent.Type.PLAYER_STARTED_SLEEPING, "Хозяин лёг спать.", 1);
        old.sleeping = owner.isSleeping();
        if (wolf.distanceTo(owner) <= 12 && wolf.hasLineOfSight(owner)) {
            int diamonds = 0;
            for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
                var stack = owner.getInventory().getItem(slot);
                if (stack.is(net.minecraft.world.item.Items.DIAMOND)) diamonds += stack.getCount();
            }
            if (old.diamonds >= 0 && diamonds > old.diamonds)
                event(c, wolf, WorldEvent.Type.PLAYER_FOUND_DIAMONDS, "Рядом со мной у хозяина появились алмазы; как именно он их получил, я не знаю.", 1);
            old.diamonds = diamonds;
        }
        if (old.health >= 0 && wolf.getHealth() > old.health)
            event(c, wolf, WorldEvent.Type.WOLF_HEALED, "Мои раны заживают.", wolf.getHealth() - old.health);
        old.health = wolf.getHealth();
        EntityObservation hostile = view.hostiles().stream().findFirst().orElse(null);
        if (hostile != null) event(c, wolf, hostile.type().contains("creeper") ? WorldEvent.Type.CREEPER_APPROACHING
                : hostile.type().contains("warden") ? WorldEvent.Type.WARDEN_DETECTED : WorldEvent.Type.HOSTILE_MOB_DETECTED,
            "Я вижу " + hostile.type() + " в " + Math.round(hostile.distance_blocks()) + " блоках.", 1);
        boolean combat = wolf.getTarget() != null && wolf.getTarget().isAlive();
        if (old.combat && !combat) event(c, wolf, WorldEvent.Type.COMBAT_ENDED, "Бой закончился.", 1);
        old.combat = combat;
        boolean home = c.living.home != null && c.living.home.dimension().equals(dimension)
            && owner.position().distanceTo(homePosition(c)) <= 6;
        if (home && !old.home) event(c, wolf, WorldEvent.Type.PLAYER_RETURNED_HOME, "Хозяин вернулся в отмеченное нами место дома.", 1);
        old.home = home;
        var input = BehaviorInput.builder(now).owner(true, wolf.hasLineOfSight(owner), wolf.distanceTo(owner))
            .health(wolf.getHealth(), wolf.getMaxHealth())
            .danger(view.environment().critical_hazard(), hostile != null, hostile == null ? 1000 : hostile.distance_blocks(),
                wolf.getTarget() instanceof Enemy, old.ownerHitUntil > now || wolf.getTarget() instanceof Enemy, old.wolfHitUntil > now)
            .stay(!c.following || c.dismissed).command(c.requestedAction, c.commandUntil)
            .interest(investigationTarget(view) != null).home(c.living.home != null && c.living.home.dimension().equals(dimension),
                c.living.home == null ? 1000 : wolf.position().distanceTo(homePosition(c))).build();
        BehaviorDecision decision = MarkBrain.think(input, c.living);
        c.action = decision.activity().name();
        boolean sitting = decision.activity() == Activity.STAY || decision.activity() == Activity.REST;
        if (wolf.isOrderedToSit() != sitting) wolf.setOrderedToSit(sitting);
        c.automaticSit = sitting && c.following && !c.dismissed;
        c.expectedSit = wolf.isOrderedToSit(); c.sitObserved = true;
        if (!c.living.settings.defense || decision.activity() == Activity.RETREAT || !validTarget(c, wolf.getTarget())) wolf.setTarget(null);
        if (decision.changed()) {
            old.failedPaths = 0; old.navigationDeadline = 0;
            if (decision.activity() != Activity.DEFEND) wolf.getNavigation().stop();
            if (decision.activity() == Activity.RETREAT && now >= old.nextSound) {
                wolf.playSound(wolf.get(DataComponents.WOLF_SOUND_VARIANT).value().adultSounds().whineSound().value(), .65F, 1F);
                old.nextSound = now + 140;
            }
            if (previousActivity == Activity.REST && c.living.emotions.energy > 60)
                event(c, wolf, WorldEvent.Type.COMPANION_RESTED, "Я отдохнул и набрался сил.", 1);
            if (previousActivity == Activity.RETREAT && !view.environment().critical_hazard() && hostile == null)
                event(c, wolf, WorldEvent.Type.SAFE_LOCATION_REACHED, "Вокруг меня больше нет видимой непосредственной угрозы.", 1);
        }
        if (decision.activity() == Activity.ALERT && now >= old.nextSound) {
            wolf.playSound(wolf.get(DataComponents.WOLF_SOUND_VARIANT).value().adultSounds().growlSound().value(), .5F, 1F);
            old.nextSound = now + 240;
        }
        if (c.requestedAction != null && c.living.lastAction.status() == ActionResult.Status.ACCEPTED) {
            boolean reached = switch (c.requestedAction) {
                case COME -> wolf.distanceTo(owner) <= 2;
                case RETURN_HOME -> c.living.home != null && wolf.position().distanceTo(homePosition(c)) <= 2;
                case FOLLOW -> c.following && !wolf.isOrderedToSit() && (wolf.distanceTo(owner) < c.living.settings.followDistance || wolf.getDeltaMovement().horizontalDistanceSqr() > .001);
                case STAY, REST -> wolf.isInSittingPose();
                case WANDER -> wolf.getDeltaMovement().horizontalDistanceSqr() > .001;
                case PLAY -> decision.activity() == Activity.PLAY && wolf.getDeltaMovement().lengthSqr() > .001;
                case INVESTIGATE -> investigationTarget(view) != null && wolf.position().distanceTo(investigationTarget(view)) <= 2.5;
                case LOOK_AT_PLAYER -> Vec3.directionFromRotation(wolf.getXRot(), wolf.getYHeadRot()).dot(owner.getEyePosition().subtract(wolf.getEyePosition()).normalize()) > .9;
                case IDLE -> decision.activity() == Activity.IDLE && wolf.getNavigation().isDone();
                case RETREAT -> !view.environment().critical_hazard() && (hostile == null || hostile.distance_blocks()
                    > dev.markai.personality.PersonalityEngine.dangerDistance(c.living.personality, c.living.settings) + 2);
                default -> false;
            };
            if (reached) {
                CompanionAction completed = c.requestedAction;
                c.living.lastAction = new ActionResult(ActionResult.Status.SUCCEEDED, completed.name(), completed == CompanionAction.RETURN_HOME ? "Я дошёл до дома." : completed == CompanionAction.COME ? "Я рядом."
                    : completed == CompanionAction.RETREAT ? "Здесь нет непосредственной угрозы." : "Команда выполнена.", now);
                if (completed == CompanionAction.RETURN_HOME) {
                    c.following = false; c.requestedAction = null; wolf.setOrderedToSit(true);
                    c.expectedSit = true; c.automaticSit = false;
                } else if (completed == CompanionAction.COME || completed == CompanionAction.RETREAT) c.requestedAction = null;
            }
            else if (now >= c.commandUntil) fail(c, wolf, "Время команды истекло; добраться не удалось.");
        }
        if (c.requestedAction != null && now >= c.commandUntil) c.requestedAction = null;
        if (decision.activity() == Activity.PLAY && now >= old.nextGreeting && wolf.onGround()) {
            wolf.setDeltaMovement(wolf.getDeltaMovement().add(0, .22, 0));
            old.nextGreeting = now + 120;
            if (now >= old.nextSound) {
                wolf.playSound(wolf.get(DataComponents.WOLF_SOUND_VARIANT).value().adultSounds().ambientSound().value(), .6F, 1.15F);
                old.nextSound = now + 180;
            }
        }
        if ((decision.activity() == Activity.REST || decision.activity() == Activity.IDLE) && c.living.emotions.fear < 30
                && c.living.emotions.energy > 40 && now >= old.nextAmbient) {
            wolf.playSound(wolf.get(DataComponents.WOLF_SOUND_VARIANT).value().adultSounds().pantSound().value(), .25F, 1F);
            old.nextAmbient = now + 600;
        }
        wolf.setIsInterested(!combat && (decision.activity() == Activity.ALERT || decision.activity() == Activity.INVESTIGATE));
    }

    static boolean validTarget(MarkAIMod.Companion c, LivingEntity target) {
        return target == null || c.living.settings.defense && target instanceof Enemy && !target.getUUID().equals(c.owner);
    }
    static Vec3 homePosition(MarkAIMod.Companion c) {
        HomeLocation home = c.living.home; return new Vec3(home.x(), home.y(), home.z());
    }
    private static Vec3 investigationTarget(WorldState view) {
        if (!view.environment().important_blocks().isEmpty()) {
            var block = view.environment().important_blocks().getFirst(); return new Vec3(block.x() + .5, block.y(), block.z() + .5);
        }
        return view.visible_entities().stream().filter(e -> !e.hostile() && !e.type().equals("minecraft:player"))
            .map(e -> new Vec3(e.x(), e.y(), e.z())).findFirst().orElse(null);
    }
    ActionResult request(MarkAIMod.Companion c, Wolf wolf, ServerPlayer owner, CompanionAction action) {
        long now = wolf.level().getGameTime();
        if (action == CompanionAction.CANCEL) {
            c.requestedAction = null; c.living.behavior.cancel(now); wolf.getNavigation().stop();
            return c.living.lastAction = new ActionResult(ActionResult.Status.CANCELLED, "cancel", "Действие отменено.", now);
        }
        ActionResult valid = ActionValidator.validate(owner, wolf, action, c.living.home, null, now);
        if (valid.status() == ActionResult.Status.FAILED) return c.living.lastAction = valid;
        if (action == CompanionAction.INVESTIGATE && investigationTarget(world(c, wolf, owner)) == null)
            return c.living.lastAction = new ActionResult(ActionResult.Status.FAILED, "no_target", "Рядом нет видимой цели для осмотра.", now);
        if (action == CompanionAction.PLAY && c.living.emotions.energy <= 30)
            return c.living.lastAction = new ActionResult(ActionResult.Status.FAILED, "too_tired", "Я устал; мне нужно немного отдохнуть.", now);
        c.requestedAction = action; c.commandUntil = now + (action == CompanionAction.RETURN_HOME ? 1200 : 600);
        Observation old = previous.computeIfAbsent(c.entity, id -> new Observation());
        old.navigationDeadline = now + 200; old.lastNavigationDistance = Double.POSITIVE_INFINITY; old.failedPaths = 0;
        c.dismissed = false;
        if (action == CompanionAction.FOLLOW || action == CompanionAction.COME) c.following = true;
        if (action == CompanionAction.STAY) c.following = false;
        else c.following = true;
        c.automaticSit = false; wolf.setOrderedToSit(!c.following); wolf.getNavigation().stop();
        c.living.lastAction = new ActionResult(ActionResult.Status.ACCEPTED, action.name(), "Команда принята.", now);
        tick(c, wolf, owner);
        return c.living.lastAction;
    }
    private void fail(MarkAIMod.Companion c, Wolf wolf, String message) {
        c.living.lastAction = new ActionResult(ActionResult.Status.FAILED, "navigation_failed", message, wolf.level().getGameTime());
        c.requestedAction = null; c.living.behavior.cancel(wolf.level().getGameTime());
        event(c, wolf, WorldEvent.Type.ACTION_FAILED, message, 1);
        ServerPlayer owner = ((ServerLevel) wolf.level()).getServer().getPlayerList().getPlayer(c.owner);
        if (owner != null) {
            owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("<" + c.living.name + "> " + message));
            dev.markai.network.CompanionNetwork.notice(owner, "error", message);
        }
    }
    Goal goal(MarkAIMod.Companion c, Wolf wolf) {
        Goal behaviorGoal = new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
            @Override public boolean canUse() {
                return !c.workActive && (!c.ownerUnavailable || c.living.activity() == Activity.RETREAT) && c.trick == null && switch (c.living.activity()) {
                    case FOLLOW, SEEK_OWNER, RETURN_HOME, INVESTIGATE, PLAY, WANDER, RETREAT, ALERT, IDLE -> true;
                    default -> false;
                };
            }
            @Override public boolean canContinueToUse() { return canUse(); }
            @Override public void stop() { wolf.getNavigation().stop(); wolf.setIsInterested(false); }
            @Override public void tick() {
                if (!(wolf.level() instanceof ServerLevel level)) return;
                ServerPlayer owner = level.getServer().getPlayerList().getPlayer(c.owner);
                if ((owner == null || owner.level() != level) && c.living.activity() != Activity.RETREAT) return;
                Observation old = previous.get(c.entity); if (old == null) return;
                long now = level.getGameTime();
                old.goalTicks++; old.lastGoalTick = now;
                if (owner != null && c.living.activity() != Activity.RETREAT) wolf.getLookControl().setLookAt(owner, 30, 30);
                if (now < old.navigationAttempt) return;
                old.navigationAttempt = now + 20;
                Vec3 target = null;
                double speed = 1.0;
                switch (c.living.activity()) {
                    case FOLLOW, SEEK_OWNER -> { if (wolf.distanceTo(owner) > Math.max(1.8, c.living.settings.followDistance * .6)) target = owner.position(); }
                    case RETURN_HOME -> target = homePosition(c);
                    case INVESTIGATE -> { target = investigationTarget(old.world); if (target != null) wolf.getLookControl().setLookAt(target.x, target.y, target.z); }
                    case WANDER, PLAY -> { if (wolf.getNavigation().isDone()) target = DefaultRandomPos.getPos(wolf, 4, 2); }
                    case RETREAT -> {
                        wolf.setTarget(null); wolf.setOrderedToSit(false); speed = 1.5;
                        var threat = old.world == null ? null : old.world.hostiles().stream().findFirst().orElse(null);
                        Vec3 danger = threat == null ? wolf.position().add(wolf.getLookAngle()) : new Vec3(threat.x(), threat.y(), threat.z());
                        target = DefaultRandomPos.getPosAway(wolf, 10, 4, danger);
                    }
                    case ALERT, IDLE -> wolf.getNavigation().stop();
                    default -> { }
                }
                if (target != null && target.distanceTo(wolf.position()) > 1.5 && safePathTarget(level, target)) {
                    double distance = target.distanceTo(wolf.position());
                    if (distance < old.lastNavigationDistance - .5) { old.navigationDeadline = now + 200; old.lastNavigationDistance = distance; }
                    if (old.navigationDeadline != 0 && now > old.navigationDeadline && c.requestedAction != null) {
                        fail(c, wolf, "Путь преграждён; я не могу добраться до цели."); return;
                    }
                    boolean path = wolf.getNavigation().moveTo(target.x, target.y, target.z, speed);
                    if (!path && ++old.failedPaths >= 4 && c.requestedAction != null) fail(c, wolf, "Не могу найти безопасный путь.");
                    if (path) old.failedPaths = 0;
                    if ((c.living.activity() == Activity.FOLLOW || c.living.activity() == Activity.SEEK_OWNER)
                            && wolf.distanceTo(owner) > 12 && now >= old.nextTeleport && wolf.shouldTryTeleportToOwner()) {
                        wolf.tryToTeleportToOwner(); old.nextTeleport = now + 200;
                    }
                }
            }
        };
        behaviorGoals.put(c.entity, behaviorGoal);
        return behaviorGoal;
    }
    static boolean safePathTarget(ServerLevel level, Vec3 pos) {
        return CompanionSafety.safeNavigationTarget(level, pos);
    }
}
