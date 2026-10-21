package dev.markai.personality;

import dev.markai.core.CompanionSettings;
import dev.markai.emotions.EmotionalState;
import java.util.Map;

/** The same saved traits shape behavior thresholds and dialogue context. */
public final class PersonalityEngine {
    private PersonalityEngine() {}

    public static double retreatHealthRatio(PersonalityTraits traits, CompanionSettings settings) {
        return CompanionSettings.finiteClamp(0.16 + traits.caution * 0.18
            + (1 - traits.confidence) * 0.09 + (settings.cautiousness - 0.5) * 0.12, 0.12, 0.45, 0.28);
    }

    public static double dangerDistance(PersonalityTraits traits, CompanionSettings settings) {
        return 3 + traits.caution * 4 + settings.cautiousness * 2;
    }

    public static double restEnergy(PersonalityTraits traits) {
        return 22 + traits.independence * 12 - traits.loyalty * 4;
    }

    public static boolean wantsToInvestigate(PersonalityTraits traits, EmotionalState emotions) {
        return traits.curiosity >= 0.55 && emotions.curiosity >= 52
            && emotions.energy > 35 && emotions.alertness < 55 && emotions.fear < 45;
    }

    public static boolean wantsToPlay(PersonalityTraits traits, EmotionalState emotions) {
        return traits.playfulness >= 0.60 && emotions.excitement >= 50
            && emotions.energy > 50 && emotions.alertness < 35 && emotions.fear < 25;
    }

    public static Map<String, Object> context(PersonalityTraits traits, CompanionSettings settings) {
        return Map.of("traits", traits.context(), "retreat_health_ratio", retreatHealthRatio(traits, settings),
            "danger_distance", dangerDistance(traits, settings),
            "character", "loyal, observant, curious; has opinions, can be cautious or stubborn");
    }
}
