package dev.markai.emotions;

import dev.markai.core.CompanionSettings;
import dev.markai.events.WorldEvent;
import dev.markai.personality.PersonalityTraits;
import java.util.Map;

/** Causal, persistent emotions. All dimensions use [0,100], not random mood rolls. */
public final class EmotionalState {
    public CompanionMood mood = CompanionMood.CALM;
    public double fear = 8;
    public double curiosity = 48;
    public double excitement = 18;
    public double trust = 85;
    public double energy = 85;
    public double alertness = 18;
    public long lastUpdated = -1;

    public void normalize() {
        fear = bounded(fear, 8); curiosity = bounded(curiosity, 48);
        excitement = bounded(excitement, 18); trust = bounded(trust, 85);
        energy = bounded(energy, 85); alertness = bounded(alertness, 18);
        if (lastUpdated < -1) lastUpdated = -1;
        updateMood();
    }

    public void decayTo(long time, boolean resting, boolean moving, PersonalityTraits traits) {
        normalize();
        time = Math.max(0, time);
        if (lastUpdated < 0 || time < lastUpdated) { lastUpdated = time; return; }
        long elapsed = Math.min(2400, time - lastUpdated);
        lastUpdated = time;
        if (elapsed == 0) return;
        fear = approach(fear, 5 + traits.caution * 5, elapsed * 0.015);
        curiosity = approach(curiosity, traits.curiosity * 70, elapsed * 0.007);
        excitement = approach(excitement, 10 + traits.playfulness * 10, elapsed * 0.018);
        alertness = approach(alertness, 8 + traits.caution * 12, elapsed * 0.022);
        energy += elapsed * (resting ? 0.08 : moving ? -0.009 : 0.006);
        normalize();
    }

    public void react(WorldEvent event, PersonalityTraits traits) {
        decayTo(event.gameTime(), false, false, traits);
        double intensity = 1 + Math.min(2, event.magnitude() / 10);
        double sensitivity = 0.55 + traits.caution * 0.65;
        switch (event.type()) {
            case PLAYER_TOOK_DAMAGE -> {
                fear += 8 * sensitivity * intensity; alertness += 25; excitement -= 5;
            }
            case WOLF_TOOK_DAMAGE -> {
                fear += 14 * sensitivity * intensity; alertness += 24; energy -= 3 * intensity;
            }
            case OWNER_ATTACKED_WOLF -> {
                trust -= 20 * intensity; fear += 18 * sensitivity; excitement -= 15; alertness += 15;
            }
            case WOLF_HEALED, OWNER_HEALED_WOLF -> {
                fear -= 8; energy += 4; trust += 3 + traits.loyalty * 2; excitement += 6;
            }
            case HOSTILE_MOB_DETECTED -> { fear += 7 * sensitivity; alertness += 24; }
            case CREEPER_APPROACHING -> { fear += 24 * sensitivity; alertness += 40; }
            case WARDEN_DETECTED -> { fear += 45 * sensitivity; alertness += 55; }
            case COMPANION_HEALTH_CRITICAL -> { fear += 30; alertness += 35; energy -= 8; }
            case ENTERED_DANGEROUS_CAVE -> {
                fear += 18 * sensitivity; curiosity += 8 * traits.curiosity; alertness += 25;
            }
            case ENTERED_NEW_BIOME -> { curiosity += 20 * traits.curiosity + 8; excitement += 8; }
            case ENTERED_NETHER, ENTERED_END -> { fear += 20 * sensitivity; curiosity += 10; alertness += 25; }
            case COMPANION_REUNITED, OWNER_RETURNED -> {
                excitement += 20 + traits.sociability * 10; trust += 3; fear -= 12;
            }
            case OWNER_AFFECTION -> {
                trust += 4; excitement += 12 + traits.playfulness * 8; fear -= 8;
            }
            case SAFE_LOCATION_REACHED, PLAYER_RETURNED_HOME -> { fear -= 20; alertness -= 20; energy += 3; }
            case COMPANION_RESTED, PLAYER_STARTED_SLEEPING -> { energy += 15; fear -= 6; alertness -= 12; }
            case COMBAT_ENDED, COMBAT_WON -> { fear -= 12; alertness -= 12; energy -= 10; excitement += 8; }
            case PLAYER_DIED -> { fear += 25; excitement -= 25; alertness += 25; }
            case PLAYER_RESPAWNED -> { fear -= 8; excitement += 12; }
            case PLAYER_LEFT_COMPANION -> { excitement -= 8; alertness += 5; }
            case PLAYER_FOUND_DIAMONDS -> { curiosity += 15; excitement += 12; }
            case ACTION_FAILED -> { excitement -= 4; alertness += 4; }
        }
        normalize();
    }

    public Map<String, Object> context() {
        normalize();
        return Map.of("mood", mood.name(), "fear", fear, "curiosity", curiosity,
            "excitement", excitement, "trust", trust, "energy", energy, "alertness", alertness);
    }

    private void updateMood() {
        mood = fear >= 60 ? CompanionMood.AFRAID
            : energy <= 20 ? CompanionMood.TIRED
            : alertness >= 60 ? CompanionMood.ALERT
            : trust <= 25 ? CompanionMood.SAD
            : excitement >= 70 ? CompanionMood.EXCITED
            : excitement >= 50 && fear < 25 ? CompanionMood.PLAYFUL
            : curiosity >= 65 ? CompanionMood.CURIOUS
            : trust >= 80 && fear < 20 ? CompanionMood.CONTENT : CompanionMood.CALM;
    }

    private static double bounded(double value, double fallback) {
        return CompanionSettings.finiteClamp(value, 0, 100, fallback);
    }
    private static double approach(double value, double target, double step) {
        return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
    }
}
