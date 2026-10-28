package com.yas.commonlibrary.logging;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Request id propagation into the MDC and outbound HTTP logging for every service that uses
 * the common library. Registered through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so it applies whether or not the service component-scans {@code com.yas.commonlibrary}.
 */
@AutoConfiguration
public class HttpLoggingAutoConfiguration {

    static String serviceName(Environment environment) {
        return environment.getProperty("spring.application.name", "unknown");
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean(name = "outboundHttpLoggingRestClientCustomizer")
    public RestClientCustomizer outboundHttpLoggingRestClientCustomizer(Environment environment) {
        OutboundHttpLoggingInterceptor interceptor = new OutboundHttpLoggingInterceptor(serviceName(environment));
        return builder -> builder.requestInterceptor(interceptor);
    }

    /** Retry number of outbound calls, from Resilience4j retry events. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.github.resilience4j.retry.RetryRegistry")
    public OutboundRetryCounter outboundRetryCounter(ObjectProvider<RetryRegistry> registryProvider) {
        return new OutboundRetryCounter(registryProvider);
    }

    /** Which @CircuitBreaker method was rejected (route/peer of circuit_open lines). */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = {"io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker",
        "org.aspectj.lang.ProceedingJoinPoint"})
    public CircuitBreakerCallSite circuitBreakerCallSite() {
        return new CircuitBreakerCallSite();
    }

    /** Resilience4j is a dependency of every module (root pom); the registry bean is looked up lazily. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry")
    public CircuitBreakerOutboundLogger circuitBreakerOutboundLogger(
        ObjectProvider<CircuitBreakerRegistry> registryProvider, Environment environment) {
        return new CircuitBreakerOutboundLogger(registryProvider, serviceName(environment));
    }
}
