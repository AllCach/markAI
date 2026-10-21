package dev.markai.ai;

public final class GroundedFactsTest {
    public static void main(String[] args) {
        var generated = new OllamaClient.Reply("say", "Я на X=1, Y=6, Z=3.");
        String snapshot = "{\"companion\":{\"position\":{\"x\":-137,\"y\":64,\"z\":28}}}";
        var corrected = GroundedFacts.apply(generated, snapshot, "Где ты? Назови свои координаты.");
        check(corrected.say().equals("Я сейчас здесь: X=-137, Y=64, Z=28."), "Incorrect generated position must use the authoritative observation");
        check(GroundedFacts.apply(generated, snapshot, "Какие у тебя координаты прямо сейчас?").equals(corrected), "Another natural positional question is grounded");
        check(GroundedFacts.apply(generated, snapshot, "Где находится наш дом?").equals(generated), "Home questions must not receive current wolf coordinates");
        check(GroundedFacts.apply(generated, "{}", "Где ты?").equals(generated), "Missing observations must not fabricate coordinates");
        check(GroundedFacts.apply(generated, "{\"companion\":{\"position\":{\"x\":\"unknown\"}}}", "Где ты?").equals(generated), "Malformed optional observation does not fabricate a position");
        check(GroundedFacts.apply(new OllamaClient.Reply("stay", generated.say()), snapshot, "Где ты?").action().equals("stay"), "Factual correction cannot grant a different action");
        System.out.println("Grounded facts: 6 checks passed");
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
