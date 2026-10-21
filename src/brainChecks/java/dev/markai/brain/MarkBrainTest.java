package dev.markai.brain;

/** Dependency-free checks; run by Gradle check/build using a dedicated source set. */
public final class MarkBrainTest {
    public static void main(String[] args) {
        expect("FLEE", 100, 5.99); // Threat takes precedence over following.
        expect("FOLLOW", 4.01, 6); // Exact threat boundary is safe.
        expect("IDLE", 4, Double.POSITIVE_INFINITY);
        expect("FOLLOW", 100, Double.POSITIVE_INFINITY);
        var decision = MarkBrain.think(new MarkBrain.WorldState("DifferentPlayer", 10, 100, 4));
        if (!decision.message().contains("DifferentPlayer")) throw new AssertionError("Dynamic player name lost");
        var anonymous = MarkBrain.think(new MarkBrain.WorldState(null, 0, 100, 4));
        if (anonymous.message().contains("null")) throw new AssertionError("Null name leaked");
        try {
            MarkBrain.think(null);
            throw new AssertionError("Null world accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("MarkBrain: 7 checks passed");
    }

    private static void expect(String action, double ownerDistance, double enemyDistance) {
        var decision = MarkBrain.think(new MarkBrain.WorldState("AnyPlayer", ownerDistance, enemyDistance, 4));
        if (!action.equals(decision.action())) throw new AssertionError("Expected " + action + ", got " + decision);
    }
}
