package com.yas.commonlibrary.logging;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * The retry number of the outbound call running on this thread: 0 for the first attempt, n for the n-th retry.
 * The number comes from Resilience4j itself ({@code @Retry}): its retry event is published on the calling thread
 * right before the next attempt, and the end events reset it. Two different calls to the same URL in one request
 * therefore stay 0; only real retries count.
 */
public final class OutboundRetryCounter implements SmartInitializingSingleton {

    private static final ThreadLocal<Integer> ATTEMPT = new ThreadLocal<>();

    private final ObjectProvider<RetryRegistry> registryProvider;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();

    public OutboundRetryCounter(ObjectProvider<RetryRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    /** Retry number for the call about to be sent on this thread. */
    static int current() {
        Integer attempt = ATTEMPT.get();
        return attempt == null ? 0 : attempt;
    }

    /** Safety net at the end of an inbound request (thread pools reuse threads). */
    public static void clear() {
        ATTEMPT.remove();
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            RetryRegistry registry = registryProvider.getIfAvailable();
            if (registry == null) {
                return;
            }
            registry.getEventPublisher().onEntryAdded(event -> subscribe(event.getAddedEntry()));
            for (Retry retry : registry.getAllRetries()) {
                subscribe(retry);
            }
        } catch (Throwable ignored) {
            // logging is optional
        }
    }

    private void subscribe(Retry retry) {
        try {
            if (subscribed.add(retry.getName())) {
                retry.getEventPublisher()
                    .onRetry(event -> ATTEMPT.set(event.getNumberOfRetryAttempts()))
                    .onSuccess(event -> ATTEMPT.remove())
                    .onError(event -> ATTEMPT.remove())
                    .onIgnoredError(event -> ATTEMPT.remove());
            }
        } catch (Throwable ignored) {
            // logging is optional
        }
    }
}
