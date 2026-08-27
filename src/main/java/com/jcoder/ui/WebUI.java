package com.jcoder.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.agent.Agent;
import com.jcoder.config.ProviderConfig;
import com.jcoder.memory.MemoryService;
import com.jcoder.message.ConversationManager;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionMode;
import com.jcoder.permission.PermissionResponse;
import com.jcoder.session.SessionManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WebUI implements UI {

    public static final int DEFAULT_PORT = 8080;
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final Duration SSE_HEARTBEAT = Duration.ofSeconds(15);
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "font-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "frame-ancestors 'none'",
            "form-action 'self'"
    );

    private final int requestedPort;
    private final ProviderConfig provider;
    private final int connectedMcpServers;
    private final int registeredMcpTools;
    private final List<String> startupWarnings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String csrfToken = createCsrfToken();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile WebRunCoordinator coordinator;
    private volatile Agent agent;
    private volatile ConversationManager conversation;

    private final SessionManager sessionManager;
    private final MemoryService memoryService;

    public WebUI(
            int port,
            ProviderConfig provider,
            int connectedMcpServers,
            int registeredMcpTools,
            List<String> startupWarnings
    ) {
        this(
                port,
                provider,
                connectedMcpServers,
                registeredMcpTools,
                startupWarnings,
                null,
                null
        );
    }

    public WebUI(
            int port,
            ProviderConfig provider,
            int connectedMcpServers,
            int registeredMcpTools,
            List<String> startupWarnings,
            SessionManager sessionManager,
            MemoryService memoryService
    ) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        this.requestedPort = port;
        this.provider = provider;
        this.connectedMcpServers = Math.max(0, connectedMcpServers);
        this.registeredMcpTools = Math.max(0, registeredMcpTools);
        this.startupWarnings = startupWarnings == null
                ? List.of()
                : List.copyOf(startupWarnings);
        this.sessionManager = sessionManager;
        this.memoryService = memoryService;
    }

    public WebUI(ProviderConfig provider) {
        this(DEFAULT_PORT, provider, 0, 0, List.of());
    }

    @Override
    public void run(Agent agent, ConversationManager conversationManager) {
        try {
            URI uri = start(agent, conversationManager);
            System.out.println("[WebUI] MyCoder 已启动: " + uri);
            System.out.println("[WebUI] 使用 Ctrl+C 退出；CLI 模式请使用 --cli");
            openBrowser(uri);
            shutdownLatch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            throw new IllegalStateException("WebUI 启动失败: " + error.getMessage(), error);
        } finally {
            stop();
        }
    }

    public synchronized URI start(
            Agent agent,
            ConversationManager conversationManager
    ) throws IOException {
        if (server != null) {
            throw new IllegalStateException("WebUI is already running");
        }
        this.agent = Objects.requireNonNull(agent, "agent");
        this.conversation = Objects.requireNonNull(conversationManager, "conversationManager");
        this.coordinator = new WebRunCoordinator(
                agent,
                conversationManager,
                mapper,
                sessionManager,
                memoryService
        );

        HttpServer actualServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", requestedPort),
                0
        );
        ExecutorService actualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        actualServer.setExecutor(actualExecutor);
        actualServer.createContext("/", this::route);
        actualServer.start();
        server = actualServer;
        executor = actualExecutor;
        return URI.create("http://127.0.0.1:" + actualServer.getAddress().getPort() + "/");
    }

    public synchronized void stop() {
        HttpServer actualServer = server;
        server = null;
        if (actualServer != null) {
            actualServer.stop(0);
        }
        ExecutorService actualExecutor = executor;
        executor = null;
        if (actualExecutor != null) {
            actualExecutor.shutdownNow();
        }
        shutdownLatch.countDown();
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path == null) {
                sendJson(exchange, 404, Map.of("error", "Not found"));
                return;
            }

            if (!path.startsWith("/api/")) {
                serveStatic(exchange, path);
                return;
            }

            switch (path) {
                case "/api/state" -> serveState(exchange);
                case "/api/conversation" -> serveConversation(exchange);
                case "/api/runs" -> createRun(exchange, false);
                case "/api/runs/retry" -> createRun(exchange, true);
                case "/api/permission" -> resolvePermission(exchange);
                case "/api/permission-mode" -> changePermissionMode(exchange);
                case "/api/conversation/reset" -> resetConversation(exchange);
                case "/api/compact" -> compact(exchange);
                default -> {
                    if (path.startsWith("/api/runs/") && path.endsWith("/events")) {
                        serveEvents(exchange, path);
                    } else {
                        sendJson(exchange, 404, Map.of("error", "Not found"));
                    }
                }
            }
        } catch (IllegalArgumentException error) {
            sendJsonIfPossible(exchange, 400, Map.of("error", safeMessage(error)));
        } catch (IllegalStateException error) {
            sendJsonIfPossible(exchange, 409, Map.of("error", safeMessage(error)));
        } catch (Exception error) {
            sendJsonIfPossible(exchange, 500, Map.of("error", "WebUI 内部错误: " + safeMessage(error)));
        } finally {
            exchange.close();
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        if (!allowReadRequest(exchange)) {
            return;
        }
        String resource;
        String contentType;
        switch (path) {
            case "/", "/index.html" -> {
                resource = "web/index.html";
                contentType = "text/html; charset=utf-8";
            }
            case "/app.css", "/assets/app.css" -> {
                resource = "web/app.css";
                contentType = "text/css; charset=utf-8";
            }
            case "/app.js", "/assets/app.js" -> {
                resource = "web/app.js";
                contentType = "text/javascript; charset=utf-8";
            }
            case "/favicon.ico" -> {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            default -> {
                sendJson(exchange, 404, Map.of("error", "Not found"));
                return;
            }
        }

        try (InputStream input = WebUI.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                sendJson(exchange, 500, Map.of("error", resource + " not found"));
                return;
            }
            byte[] body = input.readAllBytes();
            setSecurityHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private void serveState(HttpExchange exchange) throws IOException {
        if (!allowApiRequest(exchange, "GET", false)) {
            return;
        }
        PermissionChecker checker = agent.getChecker();
        WebRunCoordinator.RunSnapshot run = coordinator.currentSnapshot();

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put(
                "sessionId", sessionManager == null ? null : sessionManager.currentSessionId());
        payload.put("ready", true);
        payload.put("fatal", false);
        payload.put("csrfToken", csrfToken);
        payload.put("permissionMode", checker == null
                ? "UNAVAILABLE" : checker.getMode().name());
        payload.put("workDir", actualWorkDir());
        payload.put("provider", Map.of(
                "name", provider == null ? "" : nullToEmpty(provider.name()),
                "protocol", provider == null ? "" : nullToEmpty(provider.protocol()),
                "model", provider == null ? "" : nullToEmpty(provider.model())
        ));
        payload.put("mcp", Map.of(
                "connectedServers", connectedMcpServers,
                "registeredTools", registeredMcpTools
        ));
        payload.put("errors", startupWarnings);
        payload.put("runState", run.state().name());
        payload.put("activeRunId", run.state().active() ? run.runId() : null);
        payload.put("lastEventId", run.lastEventId());
        payload.put("context", coordinator.lastContext());
        sendJson(exchange, 200, payload);
    }

    private void serveConversation(HttpExchange exchange) throws IOException {
        if (!allowApiRequest(exchange, "GET", false)) {
            return;
        }
        sendJson(exchange, 200, Map.of(
                "items", coordinator.conversationSnapshot()
        ));
    }

    private void createRun(HttpExchange exchange, boolean retry) throws IOException {
        if (!allowApiRequest(exchange, "POST", true)) {
            return;
        }
        WebRunCoordinator.RunSnapshot run;
        if (retry) {
            readJson(exchange);
            run = coordinator.retryLastRun();
        } else {
            Map<String, Object> request = readJson(exchange);
            String message = request.get("message") instanceof String value
                    ? value
                    : "";
            run = coordinator.startUserRun(message);
        }
        sendJson(exchange, 202, runPayload(run));
    }

    private void compact(HttpExchange exchange) throws IOException {
        if (!allowApiRequest(exchange, "POST", true)) {
            return;
        }
        readJson(exchange);
        sendJson(exchange, 202, runPayload(coordinator.startManualCompaction()));
    }

    private Map<String, Object> runPayload(WebRunCoordinator.RunSnapshot run) {
        return Map.of(
                "runId", run.runId(),
                "runState", run.state().name(),
                "lastEventId", run.lastEventId()
        );
    }

    private void resolvePermission(HttpExchange exchange) throws IOException {
        if (!allowApiRequest(exchange, "POST", true)) {
            return;
        }
        Map<String, Object> request = readJson(exchange);
        String requestId = request.get("requestId") instanceof String value ? value : "";
        String decision = request.get("decision") instanceof String value ? value : "";
        PermissionResponse response;
        try {
            response = PermissionResponse.valueOf(decision);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("未知权限决定");
        }
        coordinator.resolvePermission(requestId, response);
        sendJson(exchange, 200, Map.of("resolved", true));
    }

    private void changePermissionMode(HttpExchange exchange) throws IOException {
        if (!allowApiRequest(exchange, "POST", true)) {
            return;
        }
        PermissionChecker checker = agent.getChecker();
        if (checker == null) {
            throw new IllegalStateException("权限检查器未启用");
        }
        Map<String, Object> request = readJson(exchange);
        String requested = request.get("mode") instanceof String value ? value : "";
        PermissionMode mode;
        try {
            mode = PermissionMode.valueOf(requested);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("未知权限模式");
        }
        if (mode == PermissionMode.PLAN) {
            throw new IllegalArgumentException("WebUI 暂不提供未联动的 PLAN 权限模式");
        }
        WebRunCoordinator.RunSnapshot run = coordinator.currentSnapshot();
        if (run.state().active()) {
            throw new IllegalStateException("任务运行期间不能切换权限模式");
        }
        checker.setMode(mode);
        sendJson(exchange, 200, Map.of("permissionMode", mode.name()));
    }

    private void resetConversation(HttpExchange exchange) throws IOException {
        if (!allowApiRequest(exchange, "POST", true)) {
            return;
        }
        readJson(exchange);
        int removedMessages = coordinator.resetConversation();
        sendJson(exchange, 200, Map.of(
                "reset", true,
                "removedMessages", removedMessages
        ));
    }

    private void serveEvents(HttpExchange exchange, String path) throws IOException {
        if (!allowApiRequest(exchange, "GET", false)) {
            return;
        }
        String prefix = "/api/runs/";
        String suffix = "/events";
        String encodedRunId = path.substring(prefix.length(), path.length() - suffix.length());
        String runId = URLDecoder.decode(encodedRunId, StandardCharsets.UTF_8);
        long after = parseEventId(exchange);

        setSecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream output = exchange.getResponseBody()) {
            while (true) {
                List<WebRunCoordinator.WebEvent> events =
                        coordinator.eventsAfter(runId, after);
                for (WebRunCoordinator.WebEvent event : events) {
                    writeSse(output, event);
                    after = event.sequence();
                }

                if (coordinator.isTerminal(runId)
                        && coordinator.eventsAfter(runId, after).isEmpty()) {
                    return;
                }

                boolean changed = coordinator.awaitEvent(runId, after, SSE_HEARTBEAT);
                if (!changed) {
                    output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // Browser refresh or tab close. The Agent keeps running in the coordinator.
        }
    }

    private void writeSse(
            OutputStream output,
            WebRunCoordinator.WebEvent event
    ) throws IOException {
        String frame = "id: " + event.sequence() + "\n"
                + "event: " + event.type() + "\n"
                + "data: " + event.json() + "\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private long parseEventId(HttpExchange exchange) {
        String value = queryParameter(exchange.getRequestURI().getRawQuery(), "after");
        if (value == null || value.isBlank()) {
            value = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        }
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("无效的事件序号");
        }
    }

    private static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return parts.length == 2
                        ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                        : "";
            }
        }
        return null;
    }

    private boolean allowReadRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return false;
        }
        return validateHostAndOrigin(exchange);
    }

    private boolean allowApiRequest(
            HttpExchange exchange,
            String method,
            boolean mutating
    ) throws IOException {
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return false;
        }
        if (!validateHostAndOrigin(exchange)) {
            return false;
        }
        if (mutating) {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null
                    || !contentType.toLowerCase().startsWith("application/json")) {
                sendJson(exchange, 415, Map.of("error", "Content-Type must be application/json"));
                return false;
            }
            String suppliedToken = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
            if (!csrfToken.equals(suppliedToken)) {
                sendJson(exchange, 403, Map.of("error", "Invalid CSRF token"));
                return false;
            }
        }
        return true;
    }

    private boolean validateHostAndOrigin(HttpExchange exchange) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !(host.startsWith("127.0.0.1:")
                || host.startsWith("localhost:")
                || "127.0.0.1".equals(host)
                || "localhost".equals(host))) {
            sendJson(exchange, 403, Map.of("error", "Forbidden host"));
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null
                && !origin.equals("http://" + host)
                && !origin.equals("https://" + host)) {
            sendJson(exchange, 403, Map.of("error", "Forbidden origin"));
            return false;
        }
        return true;
    }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("请求内容过大");
        }
        if (body.length == 0) {
            return Map.of();
        }
        try {
            return mapper.readValue(body, new TypeReference<>() {
            });
        } catch (Exception error) {
            throw new IllegalArgumentException("请求 JSON 无效");
        }
    }

    private void sendJson(
            HttpExchange exchange,
            int status,
            Map<String, ?> payload
    ) throws IOException {
        byte[] body = mapper.writeValueAsBytes(payload);
        setSecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void sendJsonIfPossible(
            HttpExchange exchange,
            int status,
            Map<String, ?> payload
    ) {
        try {
            sendJson(exchange, status, payload);
        } catch (IOException ignored) {
        }
    }

    private void setSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    }

    private String actualWorkDir() {
        String configured = agent.getWorkDir();
        return configured == null || configured.isBlank()
                ? System.getProperty("user.dir")
                : configured;
    }

    private void openBrowser(URI uri) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(uri);
        } catch (Exception error) {
            System.out.println("[WebUI] 请手动打开: " + uri);
        }
    }

    private static String createCsrfToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
