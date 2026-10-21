package dev.markai.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Receives immutable snapshots only; implementations never access the Minecraft world. */
public interface LlmProvider extends AutoCloseable {
    CompletableFuture<OllamaClient.Reply> ask(String contextJson, String playerMessage,
        List<OllamaClient.ChatMessage> history);

    @Override void close();
}
