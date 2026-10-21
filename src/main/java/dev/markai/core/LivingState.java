package dev.markai.core;

import dev.markai.actions.ActionResult;
import dev.markai.behavior.Activity;
import dev.markai.behavior.BehaviorController;
import dev.markai.emotions.EmotionalState;
import dev.markai.events.EventHistory;
import dev.markai.events.WorldEvent;
import dev.markai.personality.PersonalityEngine;
import dev.markai.personality.PersonalityTraits;
import java.util.Map;
import java.util.UUID;

/** Persistent per-companion state; mutations happen on the game thread, snapshots contain values only. */
public final class LivingState {
    public String name = "Марк";
    public PersonalityTraits personality = new PersonalityTraits();
    public EmotionalState emotions = new EmotionalState();
    public CompanionSettings settings = new CompanionSettings();
    public HomeLocation home;
    public BehaviorController behavior = new BehaviorController();
    public EventHistory events = new EventHistory();
    public ActionResult lastAction = ActionResult.none();

    public static LivingState forIdentity(UUID companionId) {
        LivingState state = new LivingState();
        state.personality = PersonalityTraits.forIdentity(companionId);
        state.emotions.trust = state.personality.trust * 100;
        return state;
    }

    public void normalize() {
        if (name == null || name.isBlank() || name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl))
            name = "Марк";
        if (personality == null) personality = new PersonalityTraits();
        if (emotions == null) emotions = new EmotionalState();
        if (settings == null) settings = new CompanionSettings();
        if (behavior == null) behavior = new BehaviorController();
        if (events == null) events = new EventHistory();
        if (lastAction == null) lastAction = ActionResult.none();
        personality.normalize(); emotions.normalize(); settings.normalize(); behavior.normalize(); events.normalize();
    }

    public boolean onEvent(WorldEvent event) {
        normalize();
        if (!events.accept(event)) return false;
        emotions.react(event, personality);
        return true;
    }

    public Activity activity() { normalize(); return behavior.current(); }

    /** This detached immutable tree can safely be serialized before an asynchronous request. */
    public Map<String, Object> context() {
        normalize();
        return Map.of("name", name, "personality", PersonalityEngine.context(personality, settings),
            "emotions", emotions.context(), "settings", settings.context(),
            "home", home == null ? "unset" : home.context(), "behavior", behavior.context(),
            "recent_events", events.context(Math.max(0, emotions.lastUpdated), 4),
            "last_action", Map.of("status", lastAction.status().name(), "code", lastAction.code(),
                "message", lastAction.message(), "game_time", lastAction.gameTime()));
    }
}
