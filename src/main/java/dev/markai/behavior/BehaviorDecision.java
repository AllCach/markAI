package dev.markai.behavior;

/** Selection is intent; only the game-thread executor can report actual completion. */
public record BehaviorDecision(Activity activity, String reason, boolean changed, long holdUntil, int priority) {}
