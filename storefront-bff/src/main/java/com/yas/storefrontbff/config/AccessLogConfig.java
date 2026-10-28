package com.yas.storefrontbff.config;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.http.server.logging.AccessLogArgProvider;

/**
 * Writes one JSON line per inbound request to the {@code yas.http.access} logger using the
 * Reactor Netty access log. Field names follow the operations log standard (section 2).
 * No headers or bodies are written.
 */
@Configuration
public class AccessLogConfig {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("yas.http.access");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> accessLogWebServerFactoryCustomizer(
        @Value("${spring.application.name:unknown}") String service) {
        String host = hostName();
        return factory -> factory.addServerCustomizers(
            httpServer -> httpServer.accessLog(true, args -> write(args, service, host)));
    }

    private static AccessLog write(AccessLogArgProvider args, String service, String host) {
        try {
            ACCESS_LOG.info(toJson(args, service, host));
        } catch (Throwable ignored) {
            // logging must never fail the request
        }
        return null;
    }

    static String toJson(AccessLogArgProvider args, String service, String host) {
        ZonedDateTime start = args.accessDateTime();
        String uri = text(args.uri());
        int query = uri.indexOf('?');
        String path = query >= 0 ? uri.substring(0, query) : uri;
        Long status = null;
        CharSequence statusText = args.status();
        if (statusText != null) {
            try {
                status = Long.parseLong(statusText.toString().trim());
            } catch (NumberFormatException ignored) {
                status = null;
            }
        }
        String requestId = text(args.requestHeader("X-Request-ID"));
        if (!REQUEST_ID.matcher(requestId).matches()) {
            requestId = "";
        }

        StringBuilder sb = new StringBuilder(384);
        sb.append('{');
        field(sb, "timestamp", TIMESTAMP_FORMAT.format(start != null ? start : ZonedDateTime.now()));
        sb.append(',');
        field(sb, "service", service);
        sb.append(',');
        field(sb, "host", host);
        sb.append(',');
        field(sb, "trace_id", traceId(text(args.requestHeader("traceparent"))));
        sb.append(',');
        field(sb, "span_id", "");
        sb.append(',');
        field(sb, "request_id", requestId);
        sb.append(',');
        field(sb, "client_ip", clientIp(args));
        sb.append(',');
        field(sb, "method", text(args.method()));
        sb.append(',');
        field(sb, "route", "");
        sb.append(',');
        field(sb, "path", path);
        sb.append(",\"status\":").append(status == null ? "null" : status.toString());
        sb.append(",\"duration_ms\":").append(args.duration());
        sb.append(',');
        field(sb, "direction", "INBOUND");
        sb.append(',');
        field(sb, "peer", "gateway");
        sb.append(',');
        field(sb, "result", "ok");
        sb.append('}');
        return sb.toString();
    }

    /** Trace id from a W3C traceparent header ({@code version-traceid-parentid-flags}). */
    static String traceId(String traceparent) {
        String[] parts = traceparent.trim().split("-");
        if (parts.length >= 4 && TRACE_ID.matcher(parts[1]).matches() && !parts[1].equals("0".repeat(32))) {
            return parts[1];
        }
        return "";
    }

    private static String clientIp(AccessLogArgProvider args) {
        String forwarded = text(args.requestHeader("X-Forwarded-For"));
        if (!forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        var connection = args.connectionInformation();
        SocketAddress remote = connection == null ? null : connection.connectionRemoteAddress();
        if (remote instanceof InetSocketAddress inet) {
            return inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
        }
        return remote == null ? "" : remote.toString();
    }

    private static String hostName() {
        String host = System.getenv("YAS_HOST");
        return host == null || host.isBlank() ? "unknown" : host;
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static void field(StringBuilder sb, String name, String value) {
        appendString(sb, name);
        sb.append(':');
        appendString(sb, value == null ? "" : value);
    }

    private static void appendString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
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
