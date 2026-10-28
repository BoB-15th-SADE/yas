package com.yas.commonlibrary.logging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Writes one JSON line per outbound HTTP attempt to the {@code yas.http.outbound} logger.
 * The response body is buffered so the caller still receives it in full.
 * Logging never changes the outcome of the call.
 */
public class OutboundHttpLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(HttpLogSupport.OUTBOUND_LOGGER);

    static final Set<String> KNOWN_SERVICES = Set.of(
        "product", "media", "customer", "cart", "rating", "order", "payment", "promotion", "tax",
        "inventory", "location", "sampledata", "storefront-bff", "backoffice-bff");

    private static final byte[] EMPTY = new byte[0];

    private final String service;
    private final String host;

    public OutboundHttpLoggingInterceptor(String service) {
        this.service = service;
        this.host = HttpLogSupport.host();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
        throws IOException {
        ZonedDateTime start = ZonedDateTime.now();
        long startNanos = System.nanoTime();
        int retry = 0;
        try {
            retry = OutboundRetryCounter.next(request.getMethod().name(), String.valueOf(request.getURI()));
        } catch (Throwable ignored) {
            // logging must never fail the request
        }

        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException ex) {
            logFailure(request, body, start, startNanos, retry, ex);
            throw ex;
        }

        byte[] responseBody;
        try {
            InputStream in = response.getBody();
            responseBody = in == null ? EMPTY : in.readAllBytes();
        } catch (IOException | RuntimeException ex) {
            try {
                response.close();
            } catch (Throwable ignored) {
                // already failing
            }
            logFailure(request, body, start, startNanos, retry, ex);
            throw ex;
        }

        ClientHttpResponse buffered = new BufferedClientHttpResponse(response, responseBody);
        logSuccess(request, body, buffered, responseBody, start, startNanos, retry);
        return buffered;
    }

    private void logSuccess(HttpRequest request, byte[] body, ClientHttpResponse response, byte[] responseBody,
                            ZonedDateTime start, long startNanos, int retry) {
        try {
            Long status = null;
            try {
                status = (long) response.getStatusCode().value();
            } catch (Throwable ignored) {
                // keep null
            }
            HttpHeaders responseHeaders = response.getHeaders();
            String line = buildLine(request, body, start, startNanos, retry, status, "ok",
                responseHeaders, responseBody);
            LOG.info(line);
        } catch (Throwable ignored) {
            // logging must never fail the request
        }
    }

    private void logFailure(HttpRequest request, byte[] body, ZonedDateTime start, long startNanos, int retry,
                            Throwable ex) {
        try {
            String result = isTimeout(ex) ? "timeout" : "conn_error";
            String line = buildLine(request, body, start, startNanos, retry, null, result, null, null);
            LOG.info(line);
        } catch (Throwable ignored) {
            // logging must never fail the request
        }
    }

    static boolean isTimeout(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 20) {
            if (current instanceof SocketTimeoutException
                || current instanceof HttpTimeoutException
                || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private String buildLine(HttpRequest request, byte[] requestBody, ZonedDateTime start, long startNanos,
                             int retry, Long status, String result, HttpHeaders responseHeaders,
                             byte[] responseBody) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        URI uri = request.getURI();
        String path = uri == null || uri.getRawPath() == null ? "" : uri.getRawPath();
        String[] trace = HttpLogSupport.currentTrace();

        String requestContentType = contentType(request.getHeaders());
        String responseContentType = responseHeaders == null ? "" : contentType(responseHeaders);

        boolean truncated = false;
        String requestBodyText = null;
        if (requestBody != null && requestBody.length > 0 && HttpLogSupport.isJson(requestContentType)) {
            String[] masked = HttpLogSupport.maskAndTruncate(requestBody);
            requestBodyText = masked[0];
            truncated = Boolean.parseBoolean(masked[1]);
        }
        String responseBodyText = null;
        if (responseBody != null && responseBody.length > 0 && HttpLogSupport.isJson(responseContentType)) {
            String[] masked = HttpLogSupport.maskAndTruncate(responseBody);
            responseBodyText = masked[0];
            truncated = truncated || Boolean.parseBoolean(masked[1]);
        }

        return new HttpLogSupport.JsonLine()
            .string("timestamp", HttpLogSupport.timestamp(start))
            .string("service", service)
            .string("host", host)
            .string("trace_id", trace[0])
            .string("span_id", trace[1])
            .string("request_id", HttpLogSupport.requestId())
            .string("client_ip", "")
            .string("method", request.getMethod().name())
            .string("route", "")
            .string("path", path)
            .number("status", status)
            .number("duration_ms", durationMs)
            .string("direction", "OUTBOUND")
            .string("peer", peer(uri))
            .string("result", result)
            .number("retry", (long) retry)
            .object("req_headers", headers(request.getHeaders()))
            .object("resp_headers", responseHeaders == null ? Map.<String, String>of() : headers(responseHeaders))
            .string("req_body", requestBodyText)
            .string("resp_body", responseBodyText)
            .string("req_content_type", requestContentType)
            .string("resp_content_type", responseContentType)
            .number("req_bytes", requestBody == null ? 0L : (long) requestBody.length)
            .number("resp_bytes", responseBody == null ? null : (long) responseBody.length)
            .bool("truncated", truncated)
            .build();
    }

    static String peer(URI uri) {
        if (uri == null) {
            return "";
        }
        String path = uri.getPath();
        if (path != null) {
            String trimmed = path.startsWith("/") ? path.substring(1) : path;
            int slash = trimmed.indexOf('/');
            String first = slash >= 0 ? trimmed.substring(0, slash) : trimmed;
            if (KNOWN_SERVICES.contains(first)) {
                return first;
            }
        }
        return uri.getHost() == null ? "" : uri.getHost();
    }

    private static String contentType(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        return value == null ? "" : value;
    }

    static Map<String, String> headers(HttpHeaders headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String key = name.toLowerCase(Locale.ROOT);
            String value = HttpLogSupport.MASKED_HEADERS.contains(key)
                ? HttpLogSupport.REDACTED
                : join(values);
            result.merge(key, value, (a, b) -> HttpLogSupport.REDACTED.equals(a) ? a : a + ", " + b);
        });
        return result;
    }

    private static String join(List<String> values) {
        return values == null ? "" : String.join(", ", values);
    }

    /** Response whose body has been read into memory; the caller reads it from the buffer. */
    static final class BufferedClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
