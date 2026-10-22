package dev.markai.companion;

public final class CompanionSpeciesTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(CompanionSpecies.fromEntityTypeId("minecraft:wolf").orElseThrow() == CompanionSpecies.WOLF,
            "Existing wolf species resolves");
        check(CompanionSpecies.fromEntityTypeId("minecraft:chicken").orElseThrow() == CompanionSpecies.CHICKEN,
            "Mark's chicken species resolves");
        check(CompanionSpecies.fromEntityTypeId(" MINECRAFT:CHICKEN ").orElseThrow() == CompanionSpecies.CHICKEN,
            "Entity IDs normalize whitespace and case");
        check(CompanionSpecies.fromEntityTypeId(null).isEmpty(), "Null entity ID is rejected safely");
        check(CompanionSpecies.fromEntityTypeId(" ").isEmpty(), "Blank entity ID is rejected safely");
        check(CompanionSpecies.fromEntityTypeId("minecraft:creeper").isEmpty(),
            "Unknown species is not silently accepted");
        check(CompanionSpecies.WOLF.supportsCombat() && CompanionSpecies.WOLF.supportsInventory(),
            "Wolf retains combat and inventory capabilities");
        check(!CompanionSpecies.CHICKEN.supportsCombat() && !CompanionSpecies.CHICKEN.supportsInventory(),
            "Chicken does not inherit wolf combat or inventory assumptions");
        check(CompanionSpecies.CHICKEN.supportsObservation(),
            "Chicken can participate in future world observation features");
        System.out.println("Companion species contract: " + checks + " checks passed");
    }
}
