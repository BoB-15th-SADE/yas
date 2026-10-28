package com.yas.commonlibrary.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Copies a valid incoming {@code X-Request-ID} into the MDC key {@code request_id}.
 * A request id is never generated here; the gateway owns it. Also runs for the error dispatch,
 * so log lines written while the error response is built keep the request id.
 */
public class RequestIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            OutboundRetryCounter.clear();
            String requestId = request.getHeader(HEADER);
            if (requestId != null && HttpLogSupport.REQUEST_ID_PATTERN.matcher(requestId).matches()) {
                MDC.put(HttpLogSupport.MDC_REQUEST_ID, requestId);
            } else {
                MDC.remove(HttpLogSupport.MDC_REQUEST_ID);
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(HttpLogSupport.MDC_REQUEST_ID);
            OutboundRetryCounter.clear();
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
