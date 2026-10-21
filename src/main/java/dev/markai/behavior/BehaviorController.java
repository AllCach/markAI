package dev.markai.behavior;

import dev.markai.actions.CompanionAction;
import dev.markai.core.LivingState;
import dev.markai.personality.PersonalityEngine;
import java.util.Map;

/** Deterministic priorities, bounded autonomous activity and transition hysteresis. */
public final class BehaviorController {
    private Activity current = Activity.IDLE;
    private String reason = "initial";
    private long activitySince;
    private long holdUntil;
    private int priority;
    private long autonomousUntil;
    private long nextAutonomyAt;
    private CompanionAction lastCommand;
    private boolean lastManualStay;

    public Activity current() { normalize(); return current; }
    public long activitySince() { return activitySince; }
    public String reason() { return reason; }

    public BehaviorDecision update(BehaviorInput input, LivingState state) {
        if (input == null || state == null) throw new IllegalArgumentException("Behavior requires a snapshot and state");
        normalize();
        long now = input.gameTime();
        if (holdUntil > now + 1200 || activitySince > now) holdUntil = now;
        activitySince = Math.min(activitySince, now);
        if (autonomousUntil > now + 1200) autonomousUntil = 0;
        if (nextAutonomyAt > now + 1200) nextAutonomyAt = now;
        CompanionAction requested = input.commandActive() ? input.command() : null;
        boolean explicitChange = requested != lastCommand || input.manualStay() != lastManualStay;
        lastCommand = requested;
        lastManualStay = input.manualStay();
        Candidate candidate = select(input, state);
        if (!explicitChange && candidate.activity != current && candidate.priority < priority
                && now < holdUntil && canKeepCurrent(input, state)) {
            return new BehaviorDecision(current, reason, false, holdUntil, priority);
        }
        boolean changed = candidate.activity != current;
        if (changed) {
            current = candidate.activity;
            activitySince = now;
            holdUntil = now + minimumDuration(current);
        }
        if (candidate.priority == 20 && (changed || priority != 20)) {
            autonomousUntil = now + (current == Activity.PLAY ? 80 : 60);
            nextAutonomyAt = now + (current == Activity.INVESTIGATE ? 240 : 300);
        }
        reason = candidate.reason;
        priority = candidate.priority;
        return new BehaviorDecision(current, reason, changed, holdUntil, priority);
    }

    private Candidate select(BehaviorInput in, LivingState state) {
        boolean command = in.commandActive();
        boolean staying = in.manualStay() || command && in.command() == CompanionAction.STAY;
        double healthRatio = in.health() / in.maxHealth();
        double criticalHealth = PersonalityEngine.retreatHealthRatio(state.personality, state.settings);
        double dangerDistance = PersonalityEngine.dangerDistance(state.personality, state.settings);
        boolean immediateAttack = in.wolfUnderAttack()
            || in.ownerUnderAttack() && in.hostileVisible() && in.hostileDistance() < 2.5;
        if (in.criticalHazard() || healthRatio < criticalHealth && immediateAttack)
            return candidate(Activity.RETREAT, "critical_hazard", 100);
        if (!in.ownerAvailable())
            return candidate(staying ? Activity.STAY : Activity.IDLE, "owner_unavailable", 95);
        if (staying) return candidate(Activity.STAY, "owner_ordered_stay", 90);
        boolean threat = in.hostileVisible() && in.hostileDistance() <= dangerDistance;
        if (threat && (healthRatio < criticalHealth || state.emotions.fear > 65
                || current == Activity.RETREAT && state.emotions.fear > 40))
            return candidate(Activity.RETREAT, "danger_exceeds_confidence", 85);
        if (state.settings.defense && in.validEnemyTarget()
                && (in.ownerUnderAttack() || in.wolfUnderAttack())
                && healthRatio >= criticalHealth && in.hostileDistance() <= 16)
            return candidate(Activity.DEFEND, "defending_against_confirmed_enemy", 80);
        if (command) {
            Candidate explicit = explicitCommand(in, state);
            if (explicit != null) return explicit;
        }
        if (!in.ownerVisible() && in.ownerDistance() > state.settings.followDistance * 1.5
                || in.ownerDistance() > 24)
            return candidate(Activity.SEEK_OWNER, "owner_out_of_sight", 60);
        double followStop = Math.max(1.5, state.settings.followDistance * 0.6);
        if (in.ownerDistance() > state.settings.followDistance
                || current == Activity.FOLLOW && in.ownerDistance() > followStop)
            return candidate(Activity.FOLLOW, "owner_is_farther_than_follow_distance", 50);
        if (threat) return candidate(Activity.ALERT, "observed_nearby_hostile", 45);
        double restAt = PersonalityEngine.restEnergy(state.personality);
        if (state.emotions.energy < restAt
                || current == Activity.REST && state.emotions.energy < restAt + 18)
            return candidate(Activity.REST, "recovering_energy", 40);
        if (in.gameTime() < autonomousUntil && priority == 20 && canKeepCurrent(in, state))
            return candidate(current, reason, 20);
        if (in.gameTime() >= nextAutonomyAt) {
            if (in.interestingTargetAvailable() && PersonalityEngine.wantsToInvestigate(state.personality, state.emotions))
                return candidate(Activity.INVESTIGATE, "curious_about_visible_target", 20);
            if (in.ownerDistance() < 4 && PersonalityEngine.wantsToPlay(state.personality, state.emotions))
                return candidate(Activity.PLAY, "comfortable_and_playful", 20);
            if (state.personality.independence > 0.65 && state.emotions.energy > 60
                    && state.emotions.alertness < 35 && in.ownerDistance() < 4)
                return candidate(Activity.WANDER, "quiet_nearby_exploration", 20);
        }
        return candidate(Activity.IDLE, "comfortable_near_owner", 0);
    }

    private Candidate explicitCommand(BehaviorInput in, LivingState state) {
        return switch (in.command()) {
            case STAY -> candidate(Activity.STAY, "owner_ordered_stay", 90);
            case FOLLOW -> candidate(in.ownerDistance() > Math.max(1.5, state.settings.followDistance * 0.6)
                ? Activity.FOLLOW : Activity.IDLE, "owner_ordered_follow", 70);
            case COME -> candidate(in.ownerDistance() > 1.8 ? Activity.SEEK_OWNER : Activity.IDLE,
                in.ownerDistance() > 1.8 ? "owner_called_companion" : "owner_reached", 70);
            case REST -> candidate(Activity.REST, "owner_ordered_rest", 70);
            case WANDER -> candidate(Activity.WANDER, "owner_ordered_wander", 70);
            case RETURN_HOME -> candidate(state.home == null || !in.homeSameDimension() ? Activity.IDLE
                : in.homeDistance() <= 2 ? Activity.IDLE : Activity.RETURN_HOME,
                state.home == null ? "home_unknown" : !in.homeSameDimension() ? "home_in_other_dimension"
                    : in.homeDistance() <= 2 ? "home_reached" : "owner_ordered_return_home", 70);
            case INVESTIGATE -> candidate(in.interestingTargetAvailable() ? Activity.INVESTIGATE : Activity.ALERT,
                in.interestingTargetAvailable() ? "owner_ordered_investigate" : "no_observed_target", 70);
            case PLAY -> candidate(state.emotions.energy > 30 ? Activity.PLAY : Activity.REST,
                state.emotions.energy > 30 ? "owner_invited_play" : "too_tired_to_play", 70);
            case LOOK_AT_PLAYER -> candidate(Activity.ALERT, "owner_requested_attention", 70);
            case RETREAT -> candidate(in.hostileVisible() ? Activity.RETREAT : Activity.IDLE,
                in.hostileVisible() ? "owner_ordered_retreat" : "no_observed_threat", 70);
            case IDLE -> candidate(Activity.IDLE, "owner_requested_pause", 70);
            case CANCEL -> null;
        };
    }

    private boolean canKeepCurrent(BehaviorInput in, LivingState state) {
        if (in.criticalHazard()) return current == Activity.RETREAT;
        if (in.manualStay() || !in.ownerAvailable()) return current == Activity.STAY;
        return switch (current) {
            case FOLLOW -> in.ownerDistance() > Math.max(1.5, state.settings.followDistance * 0.6);
            case STAY -> in.commandActive() && in.command() == CompanionAction.STAY;
            case DEFEND -> state.settings.defense && in.validEnemyTarget()
                && (in.ownerUnderAttack() || in.wolfUnderAttack());
            case RETREAT -> in.hostileVisible() || in.criticalHazard();
            case SEEK_OWNER -> in.ownerDistance() > 1.8;
            case RETURN_HOME -> in.commandActive() && in.command() == CompanionAction.RETURN_HOME
                && state.home != null && in.homeSameDimension() && in.homeDistance() > 2;
            case REST -> !in.hostileVisible() && in.ownerDistance() <= state.settings.followDistance + 1;
            case INVESTIGATE -> in.interestingTargetAvailable() && !in.hostileVisible()
                && state.emotions.energy > 25 && (priority != 20 || in.gameTime() < autonomousUntil);
            case PLAY, WANDER -> !in.hostileVisible() && state.emotions.energy > 25
                && in.ownerDistance() < state.settings.followDistance
                && (priority != 20 || in.gameTime() < autonomousUntil);
            case ALERT -> in.hostileVisible() || in.commandActive();
            case IDLE -> true;
        };
    }

    public void cancel(long time) {
        current = Activity.IDLE; reason = "cancelled"; activitySince = Math.max(0, time);
        holdUntil = activitySince; priority = 0; autonomousUntil = 0;
        nextAutonomyAt = activitySince + 100; lastCommand = null;
    }

    public void normalize() {
        if (current == null) current = Activity.IDLE;
        if (reason == null || reason.length() > 120) reason = "restored";
        activitySince = Math.max(0, activitySince); holdUntil = Math.max(0, holdUntil);
        priority = Math.max(0, Math.min(100, priority));
        autonomousUntil = Math.max(0, autonomousUntil); nextAutonomyAt = Math.max(0, nextAutonomyAt);
    }

    public Map<String, Object> context() {
        normalize();
        return Map.of("activity", current.name(), "reason", reason, "since", activitySince, "hold_until", holdUntil);
    }

    private static Candidate candidate(Activity activity, String reason, int priority) {
        return new Candidate(activity, reason, priority);
    }
    private record Candidate(Activity activity, String reason, int priority) {}
    private static long minimumDuration(Activity activity) {
        return switch (activity) {
            case REST -> 100;
            case INVESTIGATE, PLAY, WANDER -> 60;
            case DEFEND, RETREAT, ALERT -> 40;
            case FOLLOW, SEEK_OWNER, RETURN_HOME -> 20;
            default -> 0;
        };
    }
}
