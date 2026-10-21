package dev.markai.events;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** A bounded recent-event buffer with type cooldowns and duplicate suppression. */
public final class EventHistory {
    public static final int LIMIT = 48;
    public static final long EXPIRY_TICKS = 12_000;
    private List<WorldEvent> events = new ArrayList<>();

    public boolean accept(WorldEvent event) {
        if (event == null) return false;
        normalize();
        for (int i = events.size() - 1; i >= 0; i--) {
            WorldEvent previous = events.get(i);
            if (previous.type() != event.type() || event.gameTime() < previous.gameTime()) continue;
            long elapsed = event.gameTime() - previous.gameTime();
            if (elapsed < cooldown(event.type())
                    || elapsed < 200 && previous.detail().equals(event.detail())) return false;
            break;
        }
        events.removeIf(previous -> event.gameTime() >= previous.gameTime()
            && event.gameTime() - previous.gameTime() > EXPIRY_TICKS);
        events.add(event);
        while (events.size() > LIMIT) events.removeFirst();
        return true;
    }

    public List<WorldEvent> recent(long now, int limit) {
        normalize();
        return events.stream().filter(event -> now >= event.gameTime() && now - event.gameTime() <= EXPIRY_TICKS)
            .sorted(Comparator.comparingInt(WorldEvent::priority).reversed()
                .thenComparing(Comparator.comparingLong(WorldEvent::gameTime).reversed()))
            .limit(Math.max(0, Math.min(LIMIT, limit))).toList();
    }

    public List<Map<String, Object>> context(long now, int limit) {
        return recent(now, limit).stream().map(event -> Map.<String, Object>of(
            "type", event.type().name(), "game_time", event.gameTime(),
            "detail", event.detail(), "source", "observed_game_event")).toList();
    }

    public void normalize() {
        if (events == null) events = new ArrayList<>();
        else if (!(events instanceof ArrayList<?>)) events = new ArrayList<>(events);
        events.removeIf(event -> event == null || event.type() == null);
        while (events.size() > LIMIT) events.removeFirst();
    }

    public void clear() { events = new ArrayList<>(); }
    public void forget(long time, String detail) {
        normalize();
        events.removeIf(event -> event.gameTime() == time && detail != null
            && (detail.equals(event.detail()) || detail.startsWith(event.detail())));
    }
    public int size() { normalize(); return events.size(); }

    private static long cooldown(WorldEvent.Type type) {
        return switch (type) {
            case WOLF_TOOK_DAMAGE, PLAYER_TOOK_DAMAGE, OWNER_ATTACKED_WOLF -> 10;
            case WOLF_HEALED, OWNER_HEALED_WOLF, OWNER_AFFECTION -> 20;
            case HOSTILE_MOB_DETECTED, CREEPER_APPROACHING, WARDEN_DETECTED,
                ENTERED_DANGEROUS_CAVE, COMPANION_RESTED -> 100;
            default -> 40;
        };
    }
}
