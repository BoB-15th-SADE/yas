package com.yas.commonlibrary.logging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Helpers shared by the HTTP log writers: JSON building, masking, truncation and trace lookup.
 * Field names and masking rules follow the operations log standard (section 2).
 */
final class HttpLogSupport {

    static final String OUTBOUND_LOGGER = "yas.http.outbound";
    static final String MDC_REQUEST_ID = "request_id";
    static final String MDC_TRACE_ID = "trace_id";
    static final String MDC_SPAN_ID = "span_id";
    static final int MAX_BODY_BYTES = 65536;
    static final String REDACTED = "[REDACTED]";

    static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    static final Set<String> MASKED_HEADERS = Set.of(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-hub-signature-256", "x-api-key");

    private static final String MASKED_KEYS = "password|newPassword|currentPassword|access_token|accessToken"
        + "|refresh_token|refreshToken|id_token|idToken|client_secret|clientSecret|secret|token"
        + "|cardNumber|card_number|cvv|cvc|securityCode|security_code|expiryDate|expiry|pan";

    private static final Pattern MASK_PATTERN = Pattern.compile(
        "(\"(" + MASKED_KEYS + ")\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|-?[0-9.eE+-]+|true|false|null)",
        Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);

    private HttpLogSupport() {
    }

    static String host() {
        String host = System.getenv("YAS_HOST");
        return host == null || host.isBlank() ? "unknown" : host;
    }

    static String timestamp(ZonedDateTime time) {
        return TIMESTAMP_FORMAT.format(time);
    }

    /**
     * Returns {traceId, spanId} of the current span. With the OpenTelemetry Java agent the API's
     * {@code Span.current()} is bridged to the agent's context (the agent only decorates log events,
     * it does not fill {@link MDC}). The MDC is still read first for setups that fill it.
     * Missing values are empty strings; tracing is optional and never fails logging.
     */
    static String[] currentTrace() {
        String traceId = MDC.get(MDC_TRACE_ID);
        String spanId = MDC.get(MDC_SPAN_ID);
        if (traceId != null && !traceId.isEmpty()) {
            return new String[] {traceId, spanId == null ? "" : spanId};
        }
        try {
            SpanContext context = Span.current().getSpanContext();
            if (context.isValid()) {
                return new String[] {context.getTraceId(), context.getSpanId()};
            }
        } catch (Throwable ignored) {
            // tracing is optional
        }
        return new String[] {"", ""};
    }

    static String requestId() {
        String requestId = MDC.get(MDC_REQUEST_ID);
        return requestId == null ? "" : requestId;
    }

    static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
    }

    static String mask(String json) {
        Matcher matcher = MASK_PATTERN.matcher(json);
        return matcher.replaceAll("$1\"" + Matcher.quoteReplacement(REDACTED) + "\"");
    }

    /**
     * Masks a JSON body and cuts it at {@link #MAX_BODY_BYTES} bytes (on a UTF-8 character boundary).
     * Returns {body, truncatedFlag} where truncatedFlag is "true" or "false".
     */
    static String[] maskAndTruncate(byte[] body) {
        String masked = mask(new String(body, StandardCharsets.UTF_8));
        byte[] maskedBytes = masked.getBytes(StandardCharsets.UTF_8);
        if (maskedBytes.length <= MAX_BODY_BYTES) {
            return new String[] {masked, "false"};
        }
        int end = MAX_BODY_BYTES;
        while (end > 0 && (maskedBytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String[] {new String(maskedBytes, 0, end, StandardCharsets.UTF_8), "true"};
    }

    /** Minimal JSON object writer; keeps the field order it is given. */
    static final class JsonLine {
        private final StringBuilder sb = new StringBuilder(512);
        private boolean first = true;

        JsonLine() {
            sb.append('{');
        }

        private void name(String name) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendString(sb, name);
            sb.append(':');
        }

        JsonLine string(String name, String value) {
            name(name);
            if (value == null) {
                sb.append("null");
            } else {
                appendString(sb, value);
            }
            return this;
        }

        JsonLine number(String name, Long value) {
            name(name);
            sb.append(value == null ? "null" : value.toString());
            return this;
        }

        JsonLine bool(String name, boolean value) {
            name(name);
            sb.append(value);
            return this;
        }

        JsonLine object(String name, Map<String, String> values) {
            name(name);
            sb.append('{');
            boolean firstEntry = true;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!firstEntry) {
                    sb.append(',');
                }
                firstEntry = false;
                appendString(sb, entry.getKey());
                sb.append(':');
                appendString(sb, entry.getValue());
            }
            sb.append('}');
            return this;
        }

        String build() {
            return sb.append('}').toString();
        }
    }

    static void appendString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
