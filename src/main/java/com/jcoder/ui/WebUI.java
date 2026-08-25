package com.jcoder.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.agent.Agent;
import com.jcoder.agent.AgentEvent;
import com.jcoder.agent.AgentEventQueue;
import com.jcoder.message.ConversationManager;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionMode;
import com.jcoder.permission.PermissionResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebUI implements UI {
    private static final int DEFAULT_PORT = 8080;
    private static final int MAX_REQUEST_BYTES = 256 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, CompletableFuture<PermissionResponse>> permissionRequests = new ConcurrentHashMap<>();
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private HttpServer server;

    @Override
    public void run(Agent agent, ConversationManager conversationManager) {
        try {
            int port = Integer.getInteger("mycoder.web.port", DEFAULT_PORT);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::serveIndex);
            server.createContext("/api/state", exchange -> serveState(exchange, agent));
            server.createContext("/api/chat", exchange -> serveChat(exchange, agent, conversationManager));
            server.createContext("/api/permission-mode", exchange -> changePermissionMode(exchange, agent));
            server.createContext("/api/permission", this::resolvePermission);
            server.start();

            URI uri = URI.create("http://127.0.0.1:" + port);
            System.out.println("[WebUI] MyCoder 已启动: " + uri);
            openBrowser(uri);
            shutdown.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new IllegalStateException("WebUI 启动失败: " + e.getMessage(), e);
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    private void serveIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
            sendJson(exchange, 404, Map.of("error", "Not found"));
            return;
        }
        try (InputStream input = WebUI.class.getClassLoader().getResourceAsStream("index.html")) {
            if (input == null) {
                sendJson(exchange, 500, Map.of("error", "index.html not found"));
                return;
            }
            byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private void serveState(HttpExchange exchange, Agent agent) throws IOException {
        if (!allowApiRequest(exchange, "GET")) return;
        PermissionChecker checker = agent.getChecker();
        sendJson(exchange, 200, Map.of(
                "busy", running.get(),
                "permissionMode", checker == null ? "UNAVAILABLE" : checker.getMode().name()
        ));
    }

    private void serveChat(HttpExchange exchange, Agent agent,
                           ConversationManager conversationManager) throws IOException {
        if (!allowApiRequest(exchange, "POST")) return;
        if (!running.compareAndSet(false, true)) {
            sendJson(exchange, 409, Map.of("error", "上一条任务仍在运行"));
            return;
        }

        try {
            Map<String, Object> request = readJson(exchange);
            String message = request.get("message") instanceof String value ? value.trim() : "";
            if (message.isEmpty()) {
                sendJson(exchange, 400, Map.of("error", "消息不能为空"));
                return;
            }

            conversationManager.addUserMsg(message);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream output = exchange.getResponseBody()) {
                writeEvent(output, Map.of("type", "start"));
                AgentEventQueue events = agent.run(conversationManager);
                while (true) {
                    AgentEvent event = events.take();
                    writeEvent(output, toPayload(event));
                    if (event instanceof AgentEvent.LoopComplete || event instanceof AgentEvent.Error) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (IllegalArgumentException e) {
            sendJsonIfPossible(exchange, 400, Map.of("error", e.getMessage()));
        } finally {
            running.set(false);
        }
    }

    private Map<String, Object> toPayload(AgentEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (event) {
            case AgentEvent.Text e -> {
                payload.put("type", "text");
                payload.put("delta", e.delta());
            }
            case AgentEvent.ToolCall e -> {
                payload.put("type", "tool_call");
                payload.put("id", e.id());
                payload.put("name", e.name());
                payload.put("args", e.args());
            }
            case AgentEvent.ToolResult e -> {
                payload.put("type", "tool_result");
                payload.put("id", e.id());
                payload.put("output", e.output());
                payload.put("error", e.error());
            }
            case AgentEvent.TurnComplete e -> {
                payload.put("type", "turn_complete");
                payload.put("turn", e.turn());
            }
            case AgentEvent.LoopComplete e -> {
                payload.put("type", "loop_complete");
                payload.put("turns", e.turns());
            }
            case AgentEvent.Error e -> {
                payload.put("type", "error");
                payload.put("message", e.message());
            }
            case AgentEvent.Log e -> {
                payload.put("type", "log");
                payload.put("message", e.message());
            }
            case AgentEvent.PermissionRequest e -> {
                String requestId = UUID.randomUUID().toString();
                permissionRequests.put(requestId, e.future());
                e.future().whenComplete((ignored, error) -> permissionRequests.remove(requestId));
                payload.put("type", "permission_request");
                payload.put("requestId", requestId);
                payload.put("toolName", e.toolName());
                payload.put("description", e.description());
            }
        }
        return payload;
    }

    private void changePermissionMode(HttpExchange exchange, Agent agent) throws IOException {
        if (!allowApiRequest(exchange, "POST")) return;
        PermissionChecker checker = agent.getChecker();
        if (checker == null) {
            sendJson(exchange, 409, Map.of("error", "权限检查器未启用"));
            return;
        }
        Map<String, Object> request = readJson(exchange);
        String requestedMode = request.get("mode") instanceof String value ? value : "";
        try {
            PermissionMode mode = PermissionMode.valueOf(requestedMode);
            checker.setMode(mode);
            sendJson(exchange, 200, Map.of("permissionMode", mode.name()));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", "未知权限模式"));
        }
    }

    private void resolvePermission(HttpExchange exchange) throws IOException {
        if (!allowApiRequest(exchange, "POST")) return;
        Map<String, Object> request = readJson(exchange);
        String requestId = request.get("requestId") instanceof String value ? value : "";
        String decision = request.get("decision") instanceof String value ? value : "";
        CompletableFuture<PermissionResponse> future = permissionRequests.remove(requestId);
        if (future == null) {
            sendJson(exchange, 404, Map.of("error", "权限请求已失效"));
            return;
        }
        try {
            PermissionResponse response = PermissionResponse.valueOf(decision);
            future.complete(response);
            sendJson(exchange, 200, Map.of("resolved", true));
        } catch (IllegalArgumentException e) {
            permissionRequests.put(requestId, future);
            sendJson(exchange, 400, Map.of("error", "未知权限决定"));
        }
    }

    private boolean allowApiRequest(HttpExchange exchange, String method) throws IOException {
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return false;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (host == null || !(host.startsWith("127.0.0.1:") || host.startsWith("localhost:"))
                || (origin != null && !origin.equals("http://" + host))) {
            sendJson(exchange, 403, Map.of("error", "Forbidden"));
            return false;
        }
        return true;
    }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("请求内容过大");
        }
        try {
            return mapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("请求 JSON 无效");
        }
    }

    private void writeEvent(OutputStream output, Map<String, Object> payload) throws IOException {
        output.write(mapper.writeValueAsBytes(payload));
        output.write('\n');
        output.flush();
    }

    private void sendJson(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] body = mapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void sendJsonIfPossible(HttpExchange exchange, int status, Map<String, Object> payload) {
        try {
            sendJson(exchange, status, payload);
        } catch (IOException ignored) {
        }
    }

    private void openBrowser(URI uri) {
        if (!Desktop.isDesktopSupported()) return;
        try {
            Desktop.getDesktop().browse(uri);
        } catch (Exception e) {
            System.out.println("[WebUI] 请手动打开: " + uri);
        }
    }
}
