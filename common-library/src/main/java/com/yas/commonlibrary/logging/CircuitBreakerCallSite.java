package com.yas.commonlibrary.logging;

import java.util.Locale;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;

/**
 * Remembers which {@code @CircuitBreaker} method is running on this thread, so a {@code circuit_open} line can
 * say what was rejected. All YAS breakers share one name, so the breaker name alone does not tell the target.
 * Runs outside the Resilience4j aspect (highest precedence) and never changes the call.
 */
@Aspect
public class CircuitBreakerCallSite implements Ordered {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    @Around("@annotation(io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker)"
        + " || @within(io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker)")
    public Object remember(ProceedingJoinPoint joinPoint) throws Throwable {
        String previous = CURRENT.get();
        try {
            Signature signature = joinPoint.getSignature();
            CURRENT.set(signature.getDeclaringType().getSimpleName() + "." + signature.getName());
        } catch (Throwable ignored) {
            // logging is optional
        }
        try {
            return joinPoint.proceed();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** "ProductService.getProductById", or "" outside a breaker method. */
    static String current() {
        String value = CURRENT.get();
        return value == null ? "" : value;
    }

    /** Target service guessed from the calling class ("ProductService" -> "product"), else the fallback. */
    static String peer(String callSite, String fallback) {
        int dot = callSite.indexOf('.');
        if (dot > 0) {
            String type = callSite.substring(0, dot);
            if (type.endsWith("Service")) {
                String name = type.substring(0, type.length() - "Service".length()).toLowerCase(Locale.ROOT);
                if (OutboundHttpLoggingInterceptor.KNOWN_SERVICES.contains(name)) {
                    return name;
                }
            }
        }
        return fallback;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
