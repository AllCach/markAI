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
    WOLF("minecraft:wolf", true, true, true),
    CHICKEN("minecraft:chicken", false, false, true);

    private final String entityTypeId;
    private final boolean combat;
    private final boolean inventory;
    private final boolean observation;

    CompanionSpecies(String entityTypeId, boolean combat, boolean inventory, boolean observation) {
        this.entityTypeId = entityTypeId;
        this.combat = combat;
        this.inventory = inventory;
        this.observation = observation;
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
