package dev.markai.voice;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Optional client backend; playback follows an existing entity and observes native sound volume/distance. */
public interface SpeechSynthesizer extends AutoCloseable {
    CompletableFuture<Void> speak(UUID entityId, String text);
    @Override void close();
}
