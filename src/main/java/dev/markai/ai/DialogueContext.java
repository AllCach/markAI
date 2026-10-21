package dev.markai.ai;

import java.util.List;

/** Detached, immutable input assembled on the game thread, including the memory privacy boundary. */
public record DialogueContext(String observation, List<OllamaClient.ChatMessage> history) {
    public DialogueContext {
        if (observation == null || history == null) throw new IllegalArgumentException("Missing dialogue input");
        history = List.copyOf(history);
    }
}
