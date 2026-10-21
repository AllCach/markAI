package dev.markai;

public final class CompanionSpeciesTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(CompanionSpecies.fromId("minecraft:wolf").orElseThrow() == CompanionSpecies.WOLF,
                "wolf id resolves");
        check(CompanionSpecies.WOLF.runtimeSupported(), "wolf runtime remains enabled");
        check(CompanionSpecies.WOLF.canCombat(), "wolf combat remains enabled");
        check(CompanionSpecies.WOLF.hasInventory(), "wolf inventory remains enabled");
        check(CompanionSpecies.WOLF.canUseWolfTricks(), "wolf tricks remain enabled");

        check(CompanionSpecies.fromId("minecraft:chicken").orElseThrow() == CompanionSpecies.CHICKEN,
                "chicken id is recognized as a planned species");
        check(!CompanionSpecies.CHICKEN.runtimeSupported(), "chicken cannot enter runtime before implementation");
        check(!CompanionSpecies.CHICKEN.canCombat(), "chicken does not inherit wolf combat");
        check(!CompanionSpecies.CHICKEN.hasInventory(), "chicken does not inherit wolf inventory");
        check(!CompanionSpecies.CHICKEN.canUseWolfTricks(), "chicken does not inherit wolf tricks");

        check(CompanionSpecies.fromId("minecraft:creeper").isEmpty(), "unknown species is rejected");
        check(CompanionSpecies.fromId(null).isEmpty(), "null species is rejected by parser");
        check(CompanionSpecies.fromId("").isEmpty(), "blank species is rejected by parser");
        System.out.println("CompanionSpeciesTest: " + checks + " checks passed");
    }
}
