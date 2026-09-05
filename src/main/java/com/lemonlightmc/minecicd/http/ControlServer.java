package com.lemonlightmc.minecicd.http;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.MineCICDConfig.Control;
import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.http.ControlRequest.ParseException;
import com.lemonlightmc.minecicd.util.Ids;
import com.lemonlightmc.minecicd.util.Threads;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ControlServer {

    public interface Delegate {
        void acceptRequest(String requestId, List<Action> actions, String branch);

        ProgressStream progressStream(String requestId);

        ControlStatus controlStatus();

        void removeRequest(String requestId);

        boolean tryAcquireInFlight(String requestId);

        void releaseInFlight(String requestId);
    }

    private final MineCICD plugin;
    private final String host;
    private final int port;
    private final String path;
    private final String secret;
    private final ControlSecurity security;
    private final Delegate delegate;
    private final SSLContext sslContext;
    private final long maxBodyBytes;
    private final RateLimiter rateLimiter;
    private static final int MAX_HEADER_BYTES = 4096;
    private static final int MAX_EXCHANGES_PER_REQUEST = 4;
    private static final long SSE_IDLE_TIMEOUT_MS = 60_000L;

    private HttpServer server;
    private ExecutorService httpExecutor;
    private ScheduledExecutorService failurePurger;

    public ControlServer(final MineCICD plugin, final Control config,
            final ControlSecurity security, final Delegate delegate, final SSLContext sslContext) {
        this.plugin = plugin;
        this.host = config.host() == null || config.host().isBlank() ? "0.0.0.0" : config.host();
        this.port = config.port();
        this.path = normalizePath(config.path());
        this.secret = config.secret();
        this.security = security;
        this.delegate = delegate;
        this.sslContext = sslContext;
        this.maxBodyBytes = config.maxBodyBytes();
        this.rateLimiter = new RateLimiter(config.rateLimit());
    }

    private static String normalizePath(String p) {
        if (p == null) {
            return "minecicd";
        }
        p = p.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public boolean start() {
        try {
            if (sslContext != null) {
                final HttpsServer https = HttpsServer.create(new InetSocketAddress(host, port), 0);
                https.setHttpsConfigurator(buildConfigurator());
                server = https;
            } else {
                server = HttpServer.create(new InetSocketAddress(host, port), 0);
            }
            server.setExecutor(Threads.dameonThreadPool("minecicd-http", 4));

            // periodically evict expired failure entries so distinct invalid clients
            // cannot grow the rate-limit cache without bound.
            failurePurger = Threads.scheduledThreadExecutor("minecicd-failure-purge");
            failurePurger.scheduleWithFixedDelay(() -> {
                try {
                    rateLimiter.purgeExpired(System.currentTimeMillis());
                } catch (final Exception e) {
                    plugin.getLogger().warning("Failure cache purge error: " + e.getMessage());
                }
            }, 60, 60, TimeUnit.SECONDS);

            // register routes
            server.createContext("/" + path, this::handlePost);
            server.createContext("/" + path + "/stream", this::handleStream);
            server.createContext("/" + path + "/status", this::handleStatus);

            server.start();
            plugin.getLogger().info("Control API listening on " + host + ":" + port + "/" + path
                    + (sslContext != null ? " (HTTPS)" : " (HTTP)"));
            return true;
        } catch (final Exception e) {
            plugin.getLogger().severe("Unable to start Control API: " + e.getMessage());
            return false;
        }
    }

    private HttpsConfigurator buildConfigurator() {
        return new HttpsConfigurator(sslContext) {
            @Override
            public void configure(final HttpsParameters params) {
                try {
                    final SSLParameters sslParams = sslContext.getDefaultSSLParameters();
                    // restrict to TLS v1.2/1.3
                    final List<String> allowed = new ArrayList<>();
                    for (final String p : sslParams.getProtocols()) {
                        if ("TLSv1.2".equals(p) || "TLSv1.3".equals(p)) {
                            allowed.add(p);
                        }
                    }
                    if (!allowed.isEmpty()) {
                        sslParams.setProtocols(allowed.toArray(String[]::new));
                    }
                    params.setSSLParameters(sslParams);
                } catch (final Exception ignored) {
                    super.configure(params);
                }
            }
        };
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        if (failurePurger != null) {
            failurePurger.shutdownNow();
            failurePurger = null;
        }
    }

    private void authenticate(final HttpExchange exchange, final String requestId, final byte[] body) {
        final Headers headers = exchange.getRequestHeaders();
        security.authenticate(
                secret,
                headers.getFirst("X-MineCICD-Timestamp"),
                headers.getFirst("X-MineCICD-Nonce"),
                requestId,
                headers.getFirst("X-MineCICD-Signature"),
                body);
    }

    private byte[] preprocessRequest(final HttpExchange exchange, final String method, final boolean isJSON) {
        // check request method
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return null;
        }
        // rate limiting
        if (rateLimiter.isRateLimited(clientIp(exchange), System.currentTimeMillis())) {
            respond(exchange, 429, "{\"error\":\"Too many requests\"}");
            return null;
        }
        // Check JSON Body Type
        if (isJSON) {
            final String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
                respond(exchange, 415, "{\"error\":\"Content-Type must be application/json\"}");
                return null;
            }
        }
        // check headers size limit
        if (isHeaderTooLarge(exchange)) {
            respond(exchange, 431, "{\"error\":\"Headers too large\"}");
            return null;
        }
        // read body
        final byte[] body = readBody(exchange);
        if (body == null) {
            respond(exchange, 413, "{\"error\":\"Request body too large\"}");
            return null;
        }
        return body;
    }

    private void postprocess(final HttpExchange exchange, final String reqeustId, final byte[] body) {
        // validate request id
        if (!Ids.isValidRequestId(reqeustId)) {
            respond(exchange, 400, "{\"error\":\"Invalid requestId\"}");
            return;
        }
        // authenticate
        try {
            authenticate(exchange, reqeustId, body);
            rateLimiter.recordRequest(clientIp(exchange), System.currentTimeMillis());
        } catch (final ControlSecurity.RejectException e) {
            rateLimiter.recordFailure(clientIp(exchange), System.currentTimeMillis());
            respond(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }
    }

    private void handlePost(final HttpExchange exchange) {
        final long startNano = System.nanoTime();
        try {
            final byte[] body = preprocessRequest(exchange, "POST", true);
            if (body == null) {
                return;
            }

            // parse request from body
            ControlRequest request;
            try {
                request = ControlRequest.parse(new String(body, StandardCharsets.UTF_8));
            } catch (final ParseException e) {
                respond(exchange, 400, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
                return;
            }
            postprocess(exchange, request.requestId(), body);

            // validate actions
            try {
                security.validateActions(request.actions());
            } catch (final ControlSecurity.RejectException e) {
                respond(exchange, 403, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
                return;
            }

            // execute git
            String branch = request.branch();
            final String configured = plugin.config() == null ? null : plugin.config().git().branch();
            if (branch == null || branch.isBlank()) {
                branch = configured;
            }
            if (!branchMatches(branch)) {
                respond(exchange, 403, "{\"error\":\"Branch not allowed\"}");
                return;
            }
            if (!delegate.tryAcquireInFlight(request.requestId())) {
                respond(exchange, 409, "{\"error\":\"Another request is already in flight\"}");
                return;
            }
            delegate.acceptRequest(request.requestId(), request.actions(), branch);
            respond(exchange, 202, "{\"accepted\":true,\"requestId\":\"" + jsonEscape(request.requestId()) + "\"}");

        } catch (final Exception e) {
            plugin.getLogger().warning("Control POST error: " + e.getMessage());
            respond(exchange, 500, "{\"error\":\"Internal error\"}");
        }
        final long elapsed = (System.nanoTime() - startNano) / 1_000_000;
        if (elapsed > 50) {
            plugin.getLogger().info("Control POST handled in " + elapsed + "ms");
        }
    }

    private boolean branchMatches(String requested) {
        final var cfg = plugin.config();
        if (cfg == null) {
            return false;
        }
        // L-03: normalize null to configured branch, then strict allowlist
        if (requested == null) {
            requested = cfg.git().branch();
        }
        if (requested == null) {
            return false;
        }
        if (requested.equals(cfg.git().branch())) {
            return true;
        }
        return cfg.control().branches() != null && cfg.control().branches().contains(requested);
    }

    private void handleStream(final HttpExchange exchange) {
        final byte[] body = preprocessRequest(exchange, "POST", false);
        if (body == null) {
            return;
        }
        final String requestId = query(exchange, "requestId");
        postprocess(exchange, requestId, body);

        // cap exchanges per requestId
        final ProgressStream existing = delegate.progressStream(requestId);
        if (existing != null && existing.exchanges().size() >= MAX_EXCHANGES_PER_REQUEST) {
            respond(exchange, 429, "{\"error\":\"Too many streams\"}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        // removed Access-Control-Allow-Origin: * (SSE is server-to-server)
        try {
            exchange.sendResponseHeaders(200, 0);
        } catch (final IOException e) {
            return;
        }
        ProgressStream stream = delegate.progressStream(requestId);
        if (stream == null) {
            stream = new ProgressStream();
        }
        final ProgressStream activeStream = stream;
        if (!activeStream.add(exchange)) {
            try {
                exchange.close();
            } catch (final Exception ignored) {
            }
            return;
        }
        // waiter with idle timeout and proper exchange close
        final Thread waiter = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                int current = 0;
                while (!activeStream.isClosed()) {
                    if (System.currentTimeMillis() - start > SSE_IDLE_TIMEOUT_MS) {
                        activeStream.close();
                        break;
                    }
                    final int count = delegate.controlStatus().eventCount(requestId);
                    if (count > current) {
                        current = count;
                        start = System.currentTimeMillis();
                    }
                    Thread.sleep(1000);
                }
            } catch (final InterruptedException ignored) {
            } finally {
                try {
                    exchange.close();
                } catch (final Exception ignored) {
                }
            }
        }, "minecicd-stream-" + requestId);
        waiter.setDaemon(true);
        waiter.start();
    }

    private void handleStatus(final HttpExchange exchange) {
        final byte[] body = preprocessRequest(exchange, "GET", false);
        if (body == null) {
            return;
        }

        final String requestId = query(exchange, "requestId");
        postprocess(exchange, requestId, body);

        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        final ControlStatus.Entry entry = delegate.controlStatus().get(requestId);
        if (entry == null) {
            respond(exchange, 404, "{\"error\":\"Unknown requestId\"}");
            return;
        }
        final String payload = "{\"requestId\":\"" + jsonEscape(requestId) + "\",\"status\":\"" + entry.status()
                + "\",\"completed\":" + entry.completedActions() + ",\"total\":" + entry.totalActions()
                + ",\"error\":\"" + jsonEscape(entry.error() == null ? "" : entry.error()) + "\"}";
        respond(exchange, 200, payload);
    }

    private byte[] readBody(final HttpExchange exchange) {
        try {
            final InputStream in = exchange.getRequestBody();
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            final byte[] chunk = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBodyBytes) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (final IOException e) {
            return new byte[0];
        }
    }

    private String query(final HttpExchange exchange, final String key) {
        final String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (final String pair : raw.split("&")) {
            final String[] kv = pair.split("=", 2);
            if (kv.length != 2 || !kv[0].equals(key)) {
                continue;
            }
            try {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            } catch (final Exception e) {
                return kv[1];
            }
        }
        return null;
    }

    private void respond(final HttpExchange exchange, final int status, final String body) {
        if (body == null || body.isEmpty()) {
            exchange.close();
        }
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (payload.length <= 0) {
            exchange.close();
        }
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (final IOException e) {
            try {
                exchange.close();
            } catch (final Exception ignored) {
            }
        }
    }

    private boolean isHeaderTooLarge(final HttpExchange exchange) {
        long total = 0;
        for (final Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            final String name = entry.getKey();
            if (name != null) {
                if (name.length() > MAX_HEADER_BYTES) {
                    return true;
                }
                total += name.length();
                if (total > MAX_HEADER_BYTES * 4) {
                    return true;
                }
            }
            for (final String v : entry.getValue()) {
                if (v == null) {
                    continue;
                }
                if (v.length() > MAX_HEADER_BYTES) {
                    return true;
                }
                total += v.length();
                if (total > MAX_HEADER_BYTES * 4) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String clientIp(final HttpExchange exchange) {
        return exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    private static String jsonEscape(final String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}