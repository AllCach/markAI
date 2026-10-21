package dev.markai.behavior;

import dev.markai.actions.CompanionAction;

/** A detached observation. validEnemyTarget must be established by the server's target validator. */
public record BehaviorInput(long gameTime, boolean ownerAvailable, boolean ownerVisible,
        double ownerDistance, double health, double maxHealth, boolean criticalHazard,
        boolean hostileVisible, double hostileDistance, boolean validEnemyTarget,
        boolean ownerUnderAttack, boolean wolfUnderAttack, boolean manualStay,
        CompanionAction command, long commandUntil, boolean interestingTargetAvailable,
        boolean homeSameDimension, double homeDistance) {

    public BehaviorInput {
        gameTime = Math.max(0, gameTime);
        ownerDistance = distance(ownerDistance);
        hostileDistance = distance(hostileDistance);
        homeDistance = distance(homeDistance);
        maxHealth = Double.isFinite(maxHealth) && maxHealth > 0 ? maxHealth : 40;
        health = Double.isFinite(health) ? Math.max(0, Math.min(maxHealth, health)) : 0;
    }

    public boolean commandActive() {
        return command != null && command != CompanionAction.CANCEL
            && (commandUntil == 0 || commandUntil >= gameTime);
    }

    public static Builder builder(long time) { return new Builder(time); }

    public static final class Builder {
        private final long time;
        private boolean ownerAvailable = true, ownerVisible = true;
        private double ownerDistance = 2, health = 40, maxHealth = 40;
        private boolean criticalHazard, hostileVisible, validEnemy, ownerHit, wolfHit, stay, interest, homeSame;
        private double hostileDistance = Double.POSITIVE_INFINITY, homeDistance = Double.POSITIVE_INFINITY;
        private CompanionAction command;
        private long until;

        private Builder(long time) { this.time = time; }
        public Builder owner(boolean available, boolean visible, double distance) {
            ownerAvailable = available; ownerVisible = visible; ownerDistance = distance; return this;
        }
        public Builder health(double current, double max) { health = current; maxHealth = max; return this; }
        public Builder danger(boolean critical, boolean visible, double distance, boolean validEnemyTarget,
                boolean ownerUnderAttack, boolean wolfUnderAttack) {
            criticalHazard = critical; hostileVisible = visible; hostileDistance = distance;
            validEnemy = validEnemyTarget; ownerHit = ownerUnderAttack; wolfHit = wolfUnderAttack; return this;
        }
        public Builder stay(boolean value) { stay = value; return this; }
        public Builder command(CompanionAction action, long expires) { command = action; until = expires; return this; }
        public Builder interest(boolean value) { interest = value; return this; }
        public Builder home(boolean sameDimension, double distance) { homeSame = sameDimension; homeDistance = distance; return this; }
        public BehaviorInput build() {
            return new BehaviorInput(time, ownerAvailable, ownerVisible, ownerDistance, health, maxHealth,
                criticalHazard, hostileVisible, hostileDistance, validEnemy, ownerHit, wolfHit,
                stay, command, until, interest, homeSame, homeDistance);
        }
    }

    private static double distance(double value) {
        return Double.isNaN(value) || value < 0 ? Double.POSITIVE_INFINITY : value;
    }
}
