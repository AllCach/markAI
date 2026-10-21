package dev.markai.personality;

import dev.markai.core.CompanionSettings;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Eight stable traits, each in [0,1]. No random work happens while loading a save. */
public final class PersonalityTraits {
    public double loyalty = 0.92;
    public double curiosity = 0.72;
    public double caution = 0.56;
    public double playfulness = 0.70;
    public double sociability = 0.63;
    public double independence = 0.46;
    public double confidence = 0.66;
    public double trust = 0.85;

    /** Optional initial variation is deterministic for the companion identity. */
    public static PersonalityTraits forIdentity(UUID identity) {
        PersonalityTraits traits = new PersonalityTraits();
        if (identity == null) return traits;
        Random random = new Random(identity.getMostSignificantBits() ^ identity.getLeastSignificantBits());
        traits.loyalty += variation(random);
        traits.curiosity += variation(random);
        traits.caution += variation(random);
        traits.playfulness += variation(random);
        traits.sociability += variation(random);
        traits.independence += variation(random);
        traits.confidence += variation(random);
        traits.trust += variation(random);
        traits.normalize();
        return traits;
    }

    public void normalize() {
        loyalty = normalized(loyalty, 0.92); curiosity = normalized(curiosity, 0.72);
        caution = normalized(caution, 0.56); playfulness = normalized(playfulness, 0.70);
        sociability = normalized(sociability, 0.63); independence = normalized(independence, 0.46);
        confidence = normalized(confidence, 0.66); trust = normalized(trust, 0.85);
    }

    public Map<String, Object> context() {
        normalize();
        return Map.of("loyalty", loyalty, "curiosity", curiosity, "caution", caution,
            "playfulness", playfulness, "sociability", sociability, "independence", independence,
            "confidence", confidence, "trust", trust);
    }

    private static double variation(Random random) { return (random.nextDouble() - 0.5) * 0.16; }
    private static double normalized(double value, double fallback) {
        return CompanionSettings.finiteClamp(value, 0, 1, fallback);
    }
}
