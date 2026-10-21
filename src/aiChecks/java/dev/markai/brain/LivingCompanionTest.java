package dev.markai.brain;

import com.google.gson.Gson;
import dev.markai.actions.ActionResult;
import dev.markai.actions.CompanionAction;
import dev.markai.behavior.Activity;
import dev.markai.behavior.BehaviorInput;
import dev.markai.core.HomeLocation;
import dev.markai.core.LivingState;
import dev.markai.emotions.CompanionMood;
import dev.markai.events.EventHistory;
import dev.markai.events.WorldEvent;
import dev.markai.events.WorldEvent.Type;
import dev.markai.personality.PersonalityTraits;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure deterministic domain checks, including persistence and interrupted behavior. */
public final class LivingCompanionTest {
    private static int checks;
    private static final Gson JSON = new Gson();

    public static void main(String[] args) {
        persistenceChecks();
        emotionalChecks();
        eventChecks();
        behaviorChecks();
        System.out.println("LivingCompanion: " + checks + " checks passed");
    }

    private static void persistenceChecks() {
        check("stable initial identity variation", () -> {
            UUID identity = UUID.fromString("b90eb39e-a103-4074-bd9c-055269f302e3");
            var one = PersonalityTraits.forIdentity(identity);
            var two = PersonalityTraits.forIdentity(identity);
            require(JSON.toJson(one).equals(JSON.toJson(two)), "Same identity changed traits");
            require(!JSON.toJson(one).equals(JSON.toJson(PersonalityTraits.forIdentity(UUID.randomUUID()))), "No individual variation");
        });
        check("persistent personality emotions settings and home", () -> {
            var original = new LivingState();
            original.personality.caution = 0.91;
            original.personality.confidence = 0.19;
            original.emotions.energy = 31;
            original.settings.defense = false;
            original.settings.followDistance = 7;
            original.home = new HomeLocation("minecraft:overworld", 15, 64, -20);
            original.onEvent(WorldEvent.of(Type.OWNER_ATTACKED_WOLF, 200, "Observed owner hit"));
            double trust = original.emotions.trust;
            var loaded = JSON.fromJson(JSON.toJson(original), LivingState.class);
            loaded.normalize();
            require(loaded.name.equals("Марк") && loaded.personality.caution == 0.91, "Identity/personality lost");
            require(loaded.personality.confidence == 0.19 && loaded.emotions.trust == trust, "Relationship changed on load");
            require(loaded.emotions.energy == 31 && !loaded.settings.defense && loaded.settings.followDistance == 7,
                "Energy/settings lost");
            require(loaded.home.z() == -20 && loaded.events.size() == 1, "Home/events lost");
        });
        check("old missing fields and null fields normalize safely", () -> {
            var state = JSON.fromJson("{\"name\":null,\"personality\":null,\"emotions\":null,\"settings\":null,"
                + "\"behavior\":null,\"events\":null,\"lastAction\":null}", LivingState.class);
            state.normalize();
            require(state.name.equals("Марк") && state.activity() == Activity.IDLE, "Defaults not restored");
            require(state.context().size() == 8, "Snapshot missing subsystems");
        });
        check("malformed numeric values are bounded", () -> {
            var state = new LivingState();
            state.personality.curiosity = Double.NaN;
            state.personality.caution = 12;
            state.settings.followDistance = Double.POSITIVE_INFINITY;
            state.settings.cautiousness = -3;
            state.emotions.fear = Double.NaN;
            state.emotions.energy = -20;
            state.normalize();
            require(state.personality.curiosity == 0.72 && state.personality.caution == 1, "Traits invalid");
            require(state.settings.followDistance == 4 && state.settings.cautiousness == 0, "Settings invalid");
            require(state.emotions.fear == 8 && state.emotions.energy == 0, "Emotions invalid");
        });
        check("snapshot is immutable and detached", () -> {
            var state = new LivingState();
            var context = state.context();
            @SuppressWarnings("unchecked") var emotions = (Map<String, Object>) context.get("emotions");
            state.emotions.fear = 90;
            require(((Number) emotions.get("fear")).doubleValue() == 8, "Snapshot retained live mutable emotions");
            try { context.put("name", "Other"); throw new AssertionError("Mutable context"); }
            catch (UnsupportedOperationException expected) { }
        });
        check("accepted action is not falsely completed", () -> {
            require(!ActionResult.accepted("Path accepted", 200).success(), "Accepted path claimed completion");
            require(ActionResult.succeeded("Arrived", 300).success(), "Completion missing");
            require(ActionResult.failed("no_path", "Blocked", 200).status() == ActionResult.Status.FAILED, "Failure not typed");
            require(CompanionAction.fromWire("flee").orElseThrow() == CompanionAction.RETREAT, "Legacy flee alias missing");
            require(CompanionAction.fromWire("/kill @a").isEmpty(), "Arbitrary command accepted");
        });
        check("home coordinates validate and measure distance", () -> {
            var home = new HomeLocation("minecraft:overworld", 0, 64, 0);
            require(home.distanceTo(3, 64, 4) == 5, "Distance wrong");
            try { new HomeLocation("minecraft:overworld", Double.NaN, 64, 0); throw new AssertionError("NaN home accepted"); }
            catch (IllegalArgumentException expected) { }
        });
    }

    private static void emotionalChecks() {
        check("damage has causal fear alertness and energy effects", () -> {
            var state = new LivingState();
            double fear = state.emotions.fear, alertness = state.emotions.alertness, energy = state.emotions.energy;
            require(state.onEvent(new WorldEvent(Type.WOLF_TOOK_DAMAGE, 100, "Zombie hit", 4)), "Event rejected");
            require(state.emotions.fear > fear && state.emotions.alertness > alertness && state.emotions.energy < energy,
                "Damage did not affect state");
        });
        check("owner harm changes relationship while care repairs it", () -> {
            var state = new LivingState();
            double original = state.emotions.trust;
            state.onEvent(WorldEvent.of(Type.OWNER_ATTACKED_WOLF, 100, "Owner hit"));
            double hurt = state.emotions.trust;
            state.onEvent(WorldEvent.of(Type.OWNER_HEALED_WOLF, 200, "Owner healed"));
            require(hurt < original && state.emotions.trust > hurt, "Relationship not causal");
            require(state.emotions.trust < original, "One snack erased harm entirely");
        });
        check("fear recovers gradually without rerolling traits", () -> {
            var state = new LivingState();
            state.onEvent(WorldEvent.of(Type.WARDEN_DETECTED, 100, "Visible warden"));
            double fear = state.emotions.fear;
            state.emotions.decayTo(1100, false, false, state.personality);
            require(state.emotions.fear < fear && state.emotions.fear > 5, "No gradual recovery");
            require(state.personality.caution == 0.56, "Emotion mutated stable trait");
        });
        check("repeated same-time ticks do not drain stamina", () -> {
            var state = new LivingState();
            state.emotions.decayTo(100, false, true, state.personality);
            double energy = state.emotions.energy;
            for (int i = 0; i < 100; i++) state.emotions.decayTo(100, false, true, state.personality);
            require(state.emotions.energy == energy, "State changed without elapsed time");
        });
        check("rest replenishes stamina and movement consumes it", () -> {
            var resting = new LivingState(); var moving = new LivingState();
            resting.emotions.energy = moving.emotions.energy = 40;
            resting.emotions.decayTo(100, true, false, resting.personality);
            moving.emotions.decayTo(100, false, true, moving.personality);
            resting.emotions.decayTo(300, true, false, resting.personality);
            moving.emotions.decayTo(300, false, true, moving.personality);
            require(resting.emotions.energy > 40 && moving.emotions.energy < 40, "Energy had no gameplay meaning");
        });
        check("clock reset does not apply negative decay", () -> {
            var state = new LivingState();
            state.emotions.decayTo(1000, false, false, state.personality);
            state.emotions.fear = 60;
            state.emotions.decayTo(10, false, false, state.personality);
            require(state.emotions.fear == 60 && state.emotions.lastUpdated == 10, "Backward time changed emotions");
        });
        check("all observed event types remain finite and bounded", () -> {
            var state = new LivingState(); long time = 0;
            for (int i = 0; i < 5; i++) for (Type type : Type.values()) {
                state.onEvent(new WorldEvent(type, time += 201, "Actual observation", 100));
                for (Object value : state.emotions.context().values()) if (value instanceof Number number)
                    require(Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0 && number.doubleValue() <= 100,
                        "Unbounded emotional value");
            }
        });
        check("critical fear and exhaustion affect reported mood", () -> {
            var state = new LivingState();
            state.emotions.fear = 90; state.normalize();
            require(state.emotions.mood == CompanionMood.AFRAID, "Fear mood absent");
            state.emotions.fear = 5; state.emotions.energy = 5; state.normalize();
            require(state.emotions.mood == CompanionMood.TIRED, "Fatigue mood absent");
        });
    }

    private static void eventChecks() {
        check("duplicate events cannot repeatedly inflate emotions", () -> {
            var state = new LivingState();
            state.onEvent(WorldEvent.of(Type.HOSTILE_MOB_DETECTED, 100, "Same zombie"));
            double fear = state.emotions.fear;
            require(!state.onEvent(WorldEvent.of(Type.HOSTILE_MOB_DETECTED, 110, "Same zombie")), "Duplicate accepted");
            require(state.emotions.fear == fear && state.events.size() == 1, "Duplicate inflated state");
        });
        check("event history is bounded and persists", () -> {
            var history = new EventHistory();
            for (int i = 0; i < 100; i++) history.accept(WorldEvent.of(Type.ENTERED_NEW_BIOME, i * 201, "Biome " + i));
            require(history.size() <= EventHistory.LIMIT && history.size() > 0, "History is unbounded or empty");
            var loaded = JSON.fromJson(JSON.toJson(history), EventHistory.class);
            require(loaded.size() == history.size(), "History lost on load");
        });
        check("expired events do not become current observations", () -> {
            var history = new EventHistory();
            history.accept(WorldEvent.of(Type.WOLF_TOOK_DAMAGE, 100, "Old fight"));
            require(history.recent(100 + EventHistory.EXPIRY_TICKS + 1, 4).isEmpty(), "Expired event leaked into context");
            require(history.recent(1, 4).isEmpty(), "Future event leaked after clock reset");
        });
        check("urgent observations outrank nearby routine events", () -> {
            var history = new EventHistory();
            history.accept(WorldEvent.of(Type.ENTERED_NEW_BIOME, 100, "Forest"));
            history.accept(WorldEvent.of(Type.COMPANION_HEALTH_CRITICAL, 120, "4 HP"));
            require(history.recent(120, 1).getFirst().type() == Type.COMPANION_HEALTH_CRITICAL, "Priority ignored");
            require(history.context(120, 4).getFirst().get("source").equals("observed_game_event"), "Provenance absent");
        });
    }

    private static void behaviorChecks() {
        check("all twelve activities are typed", () -> require(Activity.values().length == 12, "Missing required activity"));
        check("manual stay resists ordinary danger and curiosity", () -> {
            var state = new LivingState(); state.emotions.curiosity = 90;
            var in = BehaviorInput.builder(100).stay(true).interest(true)
                .danger(false, true, 3, true, true, false).build();
            require(activity(state, in) == Activity.STAY, "Ordinary threat overrode stay");
        });
        check("critical terrain interrupts stay", () -> {
            var state = new LivingState();
            var in = BehaviorInput.builder(100).stay(true).danger(true, false, 99, false, false, false).build();
            require(activity(state, in) == Activity.RETREAT, "Dog stayed in critical terrain");
        });
        check("critical health under attack permits emergency escape", () -> {
            var state = new LivingState();
            var in = BehaviorInput.builder(100).stay(true).health(2, 40)
                .danger(false, true, 2, true, false, true).build();
            require(activity(state, in) == Activity.RETREAT, "Critically wounded dog did not escape");
        });
        check("confirmed hostile attack triggers defense", () -> {
            var state = new LivingState();
            require(activity(state, attacked(100, 40, true)) == Activity.DEFEND, "Owner not defended");
        });
        check("neutral targets are never selected for defense", () -> {
            var state = new LivingState();
            require(activity(state, attacked(100, 40, false)) != Activity.DEFEND, "Neutral target was attacked");
        });
        check("disabled defense remains disabled", () -> {
            var state = new LivingState(); state.settings.defense = false;
            require(activity(state, attacked(100, 40, true)) != Activity.DEFEND, "Defense setting ignored");
        });
        check("saved personality changes retreat versus defense", () -> {
            var cautious = new LivingState(); var bold = new LivingState();
            cautious.personality.caution = 1; cautious.personality.confidence = 0;
            bold.personality.caution = 0; bold.personality.confidence = 1;
            var input = attacked(100, 10, true);
            require(activity(cautious, input) == Activity.RETREAT, "Cautious personality took unnecessary risk");
            require(activity(bold, input) == Activity.DEFEND, "Confident personality behaved identically");
        });
        check("following has start and stop hysteresis", () -> {
            var state = new LivingState();
            require(activity(state, BehaviorInput.builder(100).owner(true, true, 5).build()) == Activity.FOLLOW, "Did not start follow");
            require(activity(state, BehaviorInput.builder(110).owner(true, true, 3).build()) == Activity.FOLLOW, "Oscillated at follow threshold");
            require(activity(state, BehaviorInput.builder(120).owner(true, true, 2).build()) == Activity.IDLE, "Did not stop nearby");
        });
        check("configured follow distance changes real selection", () -> {
            var state = new LivingState(); state.settings.followDistance = 8;
            require(activity(state, BehaviorInput.builder(100).owner(true, true, 6).build()) == Activity.IDLE, "Follow setting ignored");
            require(activity(state, BehaviorInput.builder(200).owner(true, true, 9).build()) == Activity.FOLLOW, "Dog never resumed follow");
        });
        check("lost line of sight selects owner search", () -> {
            var state = new LivingState();
            require(activity(state, BehaviorInput.builder(100).owner(true, false, 9).build()) == Activity.SEEK_OWNER, "No owner search");
        });
        check("fatigue causes rest until useful recovery", () -> {
            var state = new LivingState(); state.emotions.energy = 10;
            require(activity(state, BehaviorInput.builder(100).build()) == Activity.REST, "Exhausted dog did not rest");
            require(activity(state, BehaviorInput.builder(150).build()) == Activity.REST, "Rest immediately oscillated away");
            require(activity(state, BehaviorInput.builder(200).owner(true, true, 10).build()) == Activity.FOLLOW, "Rest blocked owner leaving");
        });
        check("curious dog investigates actual target with cooldown", () -> {
            var state = new LivingState(); state.emotions.curiosity = 90;
            require(activity(state, BehaviorInput.builder(100).interest(true).build()) == Activity.INVESTIGATE, "Visible target ignored");
            require(activity(state, BehaviorInput.builder(170).interest(true).build()) == Activity.IDLE, "Autonomous investigation unbounded");
            require(activity(state, BehaviorInput.builder(200).interest(true).build()) == Activity.IDLE, "Autonomy cooldown missing");
            require(activity(state, BehaviorInput.builder(350).interest(true).build()) == Activity.INVESTIGATE, "Autonomy never resumed");
        });
        check("unobserved objects never trigger investigation", () -> {
            var state = new LivingState(); state.emotions.curiosity = 100;
            require(activity(state, BehaviorInput.builder(100).interest(false).build()) != Activity.INVESTIGATE, "Invented target");
        });
        check("comfortable playfulness changes autonomous activity", () -> {
            var playful = new LivingState(); var reserved = new LivingState();
            playful.emotions.excitement = reserved.emotions.excitement = 80;
            reserved.personality.playfulness = 0.1;
            require(activity(playful, BehaviorInput.builder(100).build()) == Activity.PLAY, "Playful wolf never played");
            require(activity(reserved, BehaviorInput.builder(100).build()) != Activity.PLAY, "Playfulness had no effect");
        });
        check("independent personality explores only while quiet and nearby", () -> {
            var state = new LivingState(); state.personality.independence = 0.9;
            require(activity(state, BehaviorInput.builder(100).build()) == Activity.WANDER, "Independent wolf never explored");
            require(activity(state, BehaviorInput.builder(110).owner(true, true, 8).build()) == Activity.FOLLOW,
                "Independence overrode staying near owner");
        });
        check("danger immediately interrupts autonomous behavior", () -> {
            var state = new LivingState(); state.emotions.curiosity = 90;
            activity(state, BehaviorInput.builder(100).interest(true).build());
            require(activity(state, attacked(110, 40, true)) == Activity.DEFEND, "Curiosity blocked combat");
        });
        check("after combat selection returns to ordinary following", () -> {
            var state = new LivingState(); activity(state, attacked(100, 40, true));
            require(activity(state, BehaviorInput.builder(110).owner(true, true, 9).build()) == Activity.FOLLOW,
                "Old combat persisted without valid enemy");
        });
        check("explicit home action requires saved same-dimension home", () -> {
            var state = new LivingState();
            var input = BehaviorInput.builder(100).command(CompanionAction.RETURN_HOME, 0).home(true, 20).build();
            require(activity(state, input) == Activity.IDLE && state.behavior.reason().equals("home_unknown"), "Invented home");
            state.home = new HomeLocation("minecraft:overworld", 0, 64, 0);
            require(activity(state, input) == Activity.RETURN_HOME, "Known home not selected");
            require(activity(state, BehaviorInput.builder(110).command(CompanionAction.RETURN_HOME, 0).home(false, 20).build()) == Activity.IDLE,
                "Cross-dimension path attempted");
        });
        check("home return never happens without owner instruction", () -> {
            var state = new LivingState(); state.home = new HomeLocation("minecraft:overworld", 0, 64, 0);
            require(activity(state, BehaviorInput.builder(100).home(true, 20).build()) != Activity.RETURN_HOME, "Dog abandoned owner for home");
        });
        check("new stay command cancels current wander immediately", () -> {
            var state = new LivingState();
            require(activity(state, BehaviorInput.builder(100).command(CompanionAction.WANDER, 500).build()) == Activity.WANDER, "Wander command ignored");
            require(activity(state, BehaviorInput.builder(110).command(CompanionAction.STAY, 0).build()) == Activity.STAY, "Wander did not cancel");
        });
        check("expired command does not remain active", () -> {
            var state = new LivingState();
            activity(state, BehaviorInput.builder(100).command(CompanionAction.REST, 120).build());
            require(activity(state, BehaviorInput.builder(130).command(CompanionAction.REST, 120).build()) == Activity.IDLE, "Expired rest continued");
        });
        check("offline owner never causes blind owner seeking", () -> {
            var state = new LivingState();
            require(activity(state, BehaviorInput.builder(100).owner(false, false, 100).build()) == Activity.IDLE, "Tried navigating to unavailable owner");
        });
        check("controller state survives Gson reload and preserves hysteresis", () -> {
            var state = new LivingState();
            activity(state, BehaviorInput.builder(100).owner(true, true, 5).build());
            var loaded = JSON.fromJson(JSON.toJson(state), LivingState.class);
            require(activity(loaded, BehaviorInput.builder(110).owner(true, true, 3).build()) == Activity.FOLLOW, "Reload reset activity hysteresis");
        });
        check("controller recovers from a restored earlier game clock", () -> {
            var state = new LivingState();
            activity(state, BehaviorInput.builder(10000).owner(true, true, 5).build());
            require(activity(state, BehaviorInput.builder(10).owner(true, true, 2).build()) == Activity.IDLE,
                "Future transition guard froze behavior after clock reset");
            require(state.behavior.activitySince() <= 10, "Future activity timestamp survived reset");
        });
        check("no arbitrary model action can mutate living state", () -> {
            var state = new LivingState(); String before = JSON.toJson(state.personality);
            require(CompanionAction.fromWire("set_fear_to_zero").isEmpty(), "Unallowed state mutation became action");
            require(before.equals(JSON.toJson(state.personality)), "Parser mutated personality");
        });
    }

    private static BehaviorInput attacked(long time, double hp, boolean validEnemy) {
        return BehaviorInput.builder(time).health(hp, 40).danger(false, true, 2, validEnemy, true, true).build();
    }
    private static Activity activity(LivingState state, BehaviorInput input) { return MarkBrain.think(input, state).activity(); }
    private static void check(String name, Runnable run) {
        try { run.run(); checks++; }
        catch (Throwable error) { throw new AssertionError(name, error); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
