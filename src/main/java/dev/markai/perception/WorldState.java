package dev.markai.perception;

import java.util.List;

public record WorldState(PlayerSnapshot player, EnvironmentSnapshot environment,
                         List<EntityObservation> visible_entities, long observation_nanos) {
    public List<EntityObservation> hostiles() {
        return visible_entities.stream().filter(EntityObservation::hostile).toList();
    }
}
