package dev.markai.companion;

import java.util.Locale;
import java.util.Optional;

/**
 * Declares the vanilla entity type and supported baseline capabilities for each MarkAI companion species.
 *
 * <p>This is deliberately metadata only: species-specific entity behavior must be implemented by a
 * dedicated runtime before that species is accepted by persisted companion state.</p>
 */
public enum CompanionSpecies {
    WOLF("minecraft:wolf", true, true, true, true, true),
    CHICKEN("minecraft:chicken", true, false, false, true, false);

    private final String entityTypeId;
    private final boolean runtimeSupported;
    private final boolean combat;
    private final boolean inventory;
    private final boolean observation;
    private final boolean wolfTricks;

    CompanionSpecies(String entityTypeId, boolean runtimeSupported, boolean combat, boolean inventory,
                     boolean observation, boolean wolfTricks) {
        this.entityTypeId = entityTypeId;
        this.runtimeSupported = runtimeSupported;
        this.combat = combat;
        this.inventory = inventory;
        this.observation = observation;
        this.wolfTricks = wolfTricks;
    }

    public boolean runtimeSupported() {
        return runtimeSupported;
    }

    public boolean supportsWolfTricks() {
        return wolfTricks;
    }

    public String entityTypeId() {
        return entityTypeId;
    }

    public boolean supportsCombat() {
        return combat;
    }

    public boolean supportsInventory() {
        return inventory;
    }

    public boolean supportsObservation() {
        return observation;
    }

    public static Optional<CompanionSpecies> fromEntityTypeId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        for (CompanionSpecies species : values()) {
            if (species.entityTypeId.equals(normalized)) return Optional.of(species);
        }
        return Optional.empty();
    }
}
