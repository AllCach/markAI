package dev.markai.voice;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Backend-independent cancellation contract. No microphone or cloud service is enabled implicitly. */
public final class VoiceSessionManager implements AutoCloseable {
    private final SpeechRecognizer recognizer;
    private final SpeechSynthesizer synthesizer;
    private CompletableFuture<?> pending;
    private boolean closed;
    public VoiceSessionManager(SpeechRecognizer recognizer, SpeechSynthesizer synthesizer) {
        this.recognizer = recognizer; this.synthesizer = synthesizer;
    }
    public boolean inputAvailable() { return !closed && recognizer != null; }
    public boolean outputAvailable() { return !closed && synthesizer != null; }
    public synchronized CompletableFuture<String> listen() {
        cancel();
        if (!inputAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("Speech input backend is unavailable"));
        var result = recognizer.recognize(); pending = result; return result;
    }
    public synchronized CompletableFuture<Void> speak(UUID companion, String text) {
        cancel();
        if (!outputAvailable() || companion == null || text == null || text.isBlank() || text.length() > 240)
            return CompletableFuture.failedFuture(new IllegalStateException("Speech output backend or input is unavailable"));
        var result = synthesizer.speak(companion, text); pending = result; return result;
    }
    public synchronized void cancel() { if (pending != null) { pending.cancel(true); pending = null; } }
    @Override public synchronized void close() {
        if (closed) return; closed = true; cancel();
        try { if (recognizer != null) recognizer.close(); }
        finally { if (synthesizer != null) synthesizer.close(); }
    }
}
