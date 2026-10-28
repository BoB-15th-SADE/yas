package com.yas.commonlibrary.logging;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Writes a {@code circuit_open} line to {@code yas.http.outbound} whenever a circuit breaker
 * rejects a call. Subscribes to every breaker in the registry, including breakers created later.
 */
public class CircuitBreakerOutboundLogger implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(HttpLogSupport.OUTBOUND_LOGGER);

    private final ObjectProvider<CircuitBreakerRegistry> registryProvider;
    private final String service;
    private final String host;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();

    public CircuitBreakerOutboundLogger(ObjectProvider<CircuitBreakerRegistry> registryProvider, String service) {
        this.registryProvider = registryProvider;
        this.service = service;
        this.host = HttpLogSupport.host();
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            CircuitBreakerRegistry registry = registryProvider.getIfAvailable();
            if (registry == null) {
                return;
            }
            registry.getEventPublisher().onEntryAdded(event -> subscribe(event.getAddedEntry()));
            for (CircuitBreaker circuitBreaker : registry.getAllCircuitBreakers()) {
                subscribe(circuitBreaker);
            }
        } catch (Throwable ignored) {
            // logging is optional
        }
    }

    private void subscribe(CircuitBreaker circuitBreaker) {
        try {
            String name = circuitBreaker.getName();
            if (subscribed.add(name)) {
                circuitBreaker.getEventPublisher().onCallNotPermitted(event -> logCircuitOpen(name));
            }
        } catch (Throwable ignored) {
            // logging is optional
        }
    }

    private void logCircuitOpen(String name) {
        try {
            String[] trace = HttpLogSupport.currentTrace();
            String line = new HttpLogSupport.JsonLine()
                .string("timestamp", HttpLogSupport.timestamp(ZonedDateTime.now()))
                .string("service", service)
                .string("host", host)
                .string("trace_id", trace[0])
                .string("span_id", trace[1])
                .string("request_id", HttpLogSupport.requestId())
                .string("client_ip", "")
                .string("method", "")
                .string("route", "")
                .string("path", "")
                .number("status", null)
                .number("duration_ms", 0L)
                .string("direction", "OUTBOUND")
                .string("peer", name)
                .string("result", "circuit_open")
                .number("retry", 0L)
                .object("req_headers", Map.of())
                .object("resp_headers", Map.of())
                .string("req_body", null)
                .string("resp_body", null)
                .string("req_content_type", "")
                .string("resp_content_type", "")
                .number("req_bytes", null)
                .number("resp_bytes", null)
                .bool("truncated", false)
                .build();
            LOG.info(line);
        } catch (Throwable ignored) {
            // logging must never fail the request
        }
    }
}
