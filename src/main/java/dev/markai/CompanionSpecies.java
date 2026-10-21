package dev.markai;

import java.util.Arrays;
import java.util.Optional;

/**
 * Canonical species metadata and capability boundary for persistent companions.
 *
 * Species capabilities are explicit: Chicken has its own minimal follow runtime,
 * but deliberately does not inherit wolf combat, inventory or trick behavior.
 */
public enum CompanionSpecies {
    WOLF("minecraft:wolf", true, true, true, true),
    CHICKEN("minecraft:chicken", true, false, false, false);

    private final String id;
    private final boolean runtimeSupported;
    private final boolean combat;
    private final boolean inventory;
    private final boolean tricks;

    CompanionSpecies(String id, boolean runtimeSupported, boolean combat, boolean inventory, boolean tricks) {
        this.id = id;
        this.runtimeSupported = runtimeSupported;
        this.combat = combat;
        this.inventory = inventory;
        this.tricks = tricks;
    }

    public String id() {
        return id;
    }

    public boolean runtimeSupported() {
        return runtimeSupported;
    }

    public boolean canCombat() {
        return combat;
    }

    public boolean hasInventory() {
        return inventory;
    }

    public boolean canUseWolfTricks() {
        return tricks;
    }

    public static Optional<CompanionSpecies> fromId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(species -> species.id.equals(id)).findFirst();
    }
}
