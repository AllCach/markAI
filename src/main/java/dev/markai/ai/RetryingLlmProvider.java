package dev.markai.ai;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded retry policy; cancellation also cancels a current request or a scheduled retry. */
public final class RetryingLlmProvider implements LlmProvider {
    private final LlmProvider delegate;
    private final int retries;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(8);

    public RetryingLlmProvider(LlmProvider delegate, int retries) {
        if (delegate == null || retries < 0 || retries > 3) throw new IllegalArgumentException("Invalid retry policy");
        this.delegate = delegate; this.retries = retries;
        scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "markai-ai-retry"); thread.setDaemon(true); return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
    }

    @Override public CompletableFuture<OllamaClient.Reply> ask(String context, String message,
                                                             List<OllamaClient.ChatMessage> history) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("AI provider closed"));
        if (history == null || history.size() > 24 || history.stream().anyMatch(java.util.Objects::isNull))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid conversation history"));
        if (!capacity.tryAcquire()) return CompletableFuture.failedFuture(new IllegalStateException("AI retry queue is full"));
        var result = new CompletableFuture<OllamaClient.Reply>();
        var active = new AtomicReference<java.util.concurrent.Future<?>>();
        pending.add(result);
        result.whenComplete((reply, error) -> {
            pending.remove(result);
            capacity.release();
            if (error != null) { var current = active.get(); if (current != null) current.cancel(true); }
        });
        attempt(context, message, List.copyOf(history), 0, result, active);
        if (closed.get()) result.cancel(true);
        return result;
    }

    private void attempt(String context, String message, List<OllamaClient.ChatMessage> history, int number,
                         CompletableFuture<OllamaClient.Reply> result,
                         AtomicReference<java.util.concurrent.Future<?>> active) {
        if (result.isDone() || closed.get()) { result.cancel(true); return; }
        CompletableFuture<OllamaClient.Reply> request;
        try { request = delegate.ask(context, message, history); }
        catch (RuntimeException error) { result.completeExceptionally(error); return; }
        active.set(request);
        if (result.isDone()) { request.cancel(true); return; }
        request.whenComplete((reply, failure) -> {
            if (result.isDone()) return;
            if (failure == null) { result.complete(reply); return; }
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (number >= retries || !retryable(cause) || closed.get()) {
                result.completeExceptionally(cause); return;
            }
            try {
                var scheduled = scheduler.schedule(() -> attempt(context, message, history, number + 1, result, active),
                    Math.min(4000, 250L << number), TimeUnit.MILLISECONDS);
                active.set(scheduled);
                if (result.isDone()) scheduled.cancel(true);
            } catch (RuntimeException error) { result.completeExceptionally(error); }
        });
    }

    private static boolean retryable(Throwable error) {
        if (error instanceof IOException || error instanceof TimeoutException) return true;
        if (error instanceof OpenAiCompatibleProvider.ProviderHttpException status)
            return status.status == 429 || status.status >= 500 && status.status <= 599;
        String message = error.getMessage();
        return error instanceof IllegalArgumentException && message != null
            && message.matches("Ollama HTTP status (?:429|5[0-9][0-9])");
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.forEach(future -> future.cancel(true)); scheduler.shutdownNow(); delegate.close();
    }
}
