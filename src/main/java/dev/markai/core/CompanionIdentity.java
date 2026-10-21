package dev.markai.core;

import java.util.UUID;

/** Logical identity survives an explicitly requested revival with a new physical wolf UUID. */
public record CompanionIdentity(UUID companionId, UUID ownerId, UUID entityId) {
    public CompanionIdentity {
        if (companionId == null || ownerId == null || entityId == null) throw new IllegalArgumentException("Incomplete companion identity");
    }
}
