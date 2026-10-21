package dev.markai.voice;

import java.util.concurrent.CompletableFuture;

/** Optional client backend. Implementations own microphone permissions and release capture on cancellation. */
public interface SpeechRecognizer extends AutoCloseable {
    CompletableFuture<String> recognize();
    @Override void close();
}
