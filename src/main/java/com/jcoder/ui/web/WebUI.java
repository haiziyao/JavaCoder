package com.jcoder.ui.web;

import com.jcoder.agent.Agent;
import com.jcoder.agent.AgentEvent;
import com.jcoder.agent.AgentEventQueue;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.prompt.AgentMode;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.ui.UI;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 类 DeepSeek harness 的 Web UI。
 *
 * 使用 JDK 内置 HttpServer，提供：
 *  - /              首页（单文件前端）
 *  - /api/state     当前会话状态与 Prompt 快照
 *  - /api/chat      提交用户消息并启动 Agent，返回 runId
 *  - /api/event     与某个 Agent 运行绑定的 SSE 流（通过 runId 关联）
 *  - /api/mode      读取/切换 Agent 模式
 *  - /api/reset     新建会话
 *
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public final class WebUI implements UI {

    public static final int DEFAULT_PORT = 8090;

    private final int port;
    private final Map<Long, AgentEventQueue> runQueues = new ConcurrentHashMap<>();
    private final AtomicLong runIdGen = new AtomicLong(0);

    private Agent agent;
    private ConversationManager conversationManager;

    public WebUI() {
        this(DEFAULT_PORT);
    }

    public WebUI(int port) {
        this.port = port;
    }

    @Override
    public void run(Agent agent, ConversationManager conversationManager) {
        this.agent = agent;
        this.conversationManager = conversationManager;

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::handleIndex);
            server.createContext("/api/state", this::handleState);
            server.createContext("/api/chat", this::handleChat);
            server.createContext("/api/event", this::handleEvent);
            server.createContext("/api/mode", this::handleMode);
            server.createContext("/api/reset", this::handleReset);
            server.start();

            System.out.println("MyCoder Web UI listening on http://localhost:" + port);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Web UI on port " + port, e);
        }

        // UI 服务器启动后保持运行，直到 JVM 退出。
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private void handleIndex(HttpExchange exchange) throws IOException {
        byte[] body = WebPage.html().getBytes(StandardCharsets.UTF_8);
        send(exchange, 200, "text/html; charset=utf-8", body);
    }

    private void handleState(HttpExchange exchange) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("mode", agent == null ? AgentMode.NORMAL.name() : agent.getMode().name());
        state.put("workDir", agent == null ? "" : agent.getWorkDir());
        state.put("lastRequestJson", agent == null ? "" : agent.getLastRequestJson());
        state.put("tools", renderTools(agent == null ? null : agent.getCurrentPromptContent()));
        state.put("systemPrompt", renderSystem(agent == null ? null : agent.getCurrentPromptContent()));
        state.put("history", renderHistory());
        sendJson(exchange, 200, state);
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }

        byte[] raw = exchange.getRequestBody().readAllBytes();
        Map<String, Object> payload = parseJson(raw);
        String prompt = payload.get("message") == null ? "" : payload.get("message").toString();

        if (prompt == null || prompt.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "message is required"));
            return;
        }

        conversationManager.addUserMsg(prompt);
        AgentEventQueue queue = agent.run(conversationManager);
        long runId = runIdGen.incrementAndGet();
        runQueues.put(runId, queue);

        // 兜底清理：若 30 分钟内仍无人消费（前端未连接/已断开），移除映射。
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(30 * 60 * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            runQueues.remove(runId);
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("runId", runId);
        sendJson(exchange, 200, resp);
    }

    private void handleEvent(HttpExchange exchange) throws IOException {
        // SSE: 通过 runId 查询参数绑定到特定运行实例。
        String runIdParam = exchange.getRequestURI().getQuery() == null
                ? "" : exchange.getRequestURI().getQuery();
        long runId = -1;
        for (String pair : runIdParam.split("&")) {
            if (pair.startsWith("runId=")) {
                try {
                    runId = Long.parseLong(pair.substring("runId=".length()));
                } catch (NumberFormatException ignored) {
                    // fall through to not found
                }
            }
        }

        if (runId < 0) {
            sendJson(exchange, 400, Map.of("error", "runId query param is required"));
            return;
        }

        AgentEventQueue queue = runQueues.get(runId);
        if (queue == null) {
            sendJson(exchange, 404, Map.of("error", "run not found or already finished: " + runId));
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        // 逐事件转发，直到 LoopComplete / Error。
        try (OutputStream out = exchange.getResponseBody()) {
            while (true) {
                AgentEvent event;
                try {
                    event = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                String json = SseEvent.toJson(event);
                String frame = "data: " + json + "\n\n";
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();

                if (event instanceof AgentEvent.LoopComplete || event instanceof AgentEvent.Error) {
                    out.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    runQueues.remove(runId);
                    return;
                }
            }
        } catch (IOException e) {
            // 客户端断开连接，正常退出。
            runQueues.remove(runId);
        }
    }

    private void handleMode(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Map<String, Object> payload = parseJson(raw);
            String modeName = payload.get("mode") == null ? "" : payload.get("mode").toString();
            AgentMode mode;
            try {
                mode = AgentMode.valueOf(modeName);
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, Map.of("error", "unknown mode: " + modeName));
                return;
            }
            agent.setMode(mode);
        }
        sendJson(exchange, 200, Map.of("mode", agent.getMode().name()));
    }

    private void handleReset(HttpExchange exchange) throws IOException {
        // 清空会话历史，但保留 Agent 与工具注册。
        conversationManager.getHistoryMut().clear();
        sendJson(exchange, 200, Map.of("ok", true));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<Map<String, Object>> renderTools(PromptContent content) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (content == null) {
            return result;
        }
        for (ToolDefinition def : content.tools()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", def.name());
            m.put("description", def.description());
            result.add(m);
        }
        return result;
    }

    private String renderSystem(PromptContent content) {
        return content == null ? "" : content.system();
    }

    private List<Map<String, Object>> renderHistory() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message msg : conversationManager.getHistoryCopy()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent() == null ? "" : msg.getContent());
            List<Map<String, Object>> calls = new ArrayList<>();
            if (msg.getToolCalls() != null) {
                for (var tc : msg.getToolCalls()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", tc.toolId());
                    c.put("name", tc.toolName());
                    c.put("args", tc.params());
                    calls.add(c);
                }
            }
            if (!calls.isEmpty()) {
                m.put("toolCalls", calls);
            }
            List<Map<String, Object>> results = new ArrayList<>();
            if (msg.getToolResults() != null) {
                for (var tr : msg.getToolResults()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", tr.toolId());
                    r.put("content", tr.content());
                    r.put("isError", tr.isError());
                    results.add(r);
                }
            }
            if (!results.isEmpty()) {
                m.put("toolResults", results);
            }
            result.add(m);
        }
        return result;
    }

    private Map<String, Object> parseJson(byte[] raw) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = Json.mapper().readValue(raw, Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        try {
            byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
            send(exchange, status, "application/json; charset=utf-8", bytes);
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=utf-8",
                    "{\"error\":\"serialize failed\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
