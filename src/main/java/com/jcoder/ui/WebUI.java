package com.jcoder.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.agent.Agent;
import com.jcoder.agent.AgentEvent;
import com.jcoder.agent.AgentEventQueue;
import com.jcoder.message.ConversationManager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * WebUI —— 用精致明亮的前端工作台替换命令行交互。
 *
 * <p>完全复用现有 Agent 事件总线，不侵入任何业务代码：
 * 通过 JDK 内置 HttpServer 提供 REST + SSE，把 AgentEvent 实时推送到浏览器。</p>
 *
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class WebUI {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单个回合的事件流（供 SSE 推送）。 */
    private static final class AgentSession {
        final String id;
        final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Object lock = new Object();
        int seq = 0;
        boolean done = false;
        volatile long lastAccess = System.currentTimeMillis(); // 最近被读取时间，用于 TTL 清理

        AgentSession(String id) { this.id = id; }
    }

    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private Agent agent;
    private ConversationManager conversationManager;

    /** 会话活跃周期（毫秒），超出后自动清理，避免 sessions 无限增长导致内存泄漏。 */
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L; // 30 分钟

    public void run(Agent agent, ConversationManager conversationManager) {
        this.agent = agent;
        this.conversationManager = conversationManager;

        int port = Integer.parseInt(System.getProperty("mycoder.port", "8080"));
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::handleRoot);
            server.createContext("/api/chat", this::handleChat);
            server.createContext("/api/events", this::handleEvents);
            server.createContext("/api/history", this::handleHistory);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();

            // 定时清理过期会话，防止内存无限增长
            Thread.startVirtualThread(this::sessionCleaner);

            System.out.println("🌿 MyCoder WebUI 已启动: http://localhost:" + port);
            System.out.println("   按 Ctrl+C 停止。");
        } catch (IOException e) {
            System.err.println("[WebUI] 端口 " + port + " 启动失败: " + e.getMessage());
        }
    }

    /** 周期清理：删除超过 TTL 且已完成的会话。 */
    private void sessionCleaner() {
        while (true) {
            try {
                Thread.sleep(60_000); // 每分钟检查一次
                long now = System.currentTimeMillis();
                sessions.entrySet().removeIf(e -> {
                    AgentSession s = e.getValue();
                    synchronized (s.lock) {
                        // 只剩已完成且长时间未被读取的会话
                        return s.done && (now - s.lastAccess > SESSION_TTL_MS);
                    }
                });
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /* ================== HTTP 端点 ================== */

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "Method Not Allowed");
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/webui.html")) {
            if (in == null) {
                respond(ex, 500, "缺少前端资源 webui.html");
                return;
            }
            byte[] html = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(html); }
        }
    }

    private void handleHistory(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "Method Not Allowed"); return; }
        try {
            StringBuilder sb = new StringBuilder("{\"history\":[");
            boolean first = true;
            for (var m : conversationManager.getHistoryCopy()) {
                String role = m.getRole();
                String content = m.getContent();
                if (content == null || content.isEmpty()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"role\":\"").append(escape(role))
                  .append("\",\"content\":\"").append(escape(content)).append("\"}");
            }
            sb.append("]}");
            respond(ex, 200, sb.toString());
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleChat(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "Method Not Allowed"); return; }
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<?, ?> req = JSON.readValue(body, Map.class);
            String prompt = (String) req.get("prompt");
            if (prompt == null || prompt.isBlank()) {
                respond(ex, 400, "{\"error\":\"prompt 不能为空\"}");
                return;
            }
            conversationManager.addUserMsg(prompt);

            AgentSession session = new AgentSession(java.util.UUID.randomUUID().toString());
            sessions.put(session.id, session);

            AgentEventQueue queue = agent.run(conversationManager);
            // 泵线程：把 Agent 事件序列化进 session，并唤醒 SSE
            Thread.startVirtualThread(() -> pump(session, queue));

            respond(ex, 200, "{\"sessionId\":\"" + session.id + "\"}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    /** 从 Agent 事件队列取出并序列化进 session；完成后通知前端。 */
    private void pump(AgentSession session, AgentEventQueue queue) {
        try {
            while (true) {
                AgentEvent event = queue.take();
                append(session, eventToJson(event));
                if (event instanceof AgentEvent.LoopComplete || event instanceof AgentEvent.Error) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (session.lock) {
                session.done = true;
                session.lock.notifyAll();
            }
        }
    }

    private void append(AgentSession session, String json) {
        synchronized (session.lock) {
            session.events.add(json);
            session.seq = session.events.size();
            session.lock.notifyAll();
        }
    }

    /** SSE：持续推送会话事件，断线由浏览器用 Last-Event-ID 重连续传。 */
    private void handleEvents(HttpExchange ex) throws IOException {
        String sessionId = query(ex, "session");
        if (sessionId == null) { respond(ex, 400, "missing session"); return; }
        AgentSession session = sessions.get(sessionId);
        if (session == null) { respond(ex, 404, "session not found"); return; }

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();

        // 断线续传游标
        int cursor = 0;
        String lastEventId = ex.getRequestHeaders().getFirst("Last-Event-ID");
        if (lastEventId != null) {
            try { cursor = Integer.parseInt(lastEventId.trim()); } catch (NumberFormatException ignored) { }
        }

        try {
            long lastKeep = System.currentTimeMillis();
            while (true) {
                synchronized (session.lock) {
                    session.lastAccess = System.currentTimeMillis(); // 刷新活跃时间，防止被 TTL 清理
                    while (cursor >= session.events.size() && !session.done) {
                        session.lock.wait(300);
                        session.lastAccess = System.currentTimeMillis();
                    }
                    int sent = 0;
                    if (cursor < session.events.size()) {
                        int from = cursor, to = session.events.size();
                        for (int i = from; i < to; i++) {
                            String data = session.events.get(i);
                            os.write(("id: " + (i + 1) + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                            sent++;
                        }
                        cursor = to;
                        os.flush();
                    }
                    // 会话结束且事件发完 -> 收尾
                    if (session.done && cursor >= session.events.size()) {
                        os.write(("event: end\ndata: done\n\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        break;
                    }
                    if (sent == 0) {
                        long now = System.currentTimeMillis();
                        if (now - lastKeep > 15000) {
                            os.write((": keepalive\n\n").getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            lastKeep = now;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 客户端断开，忽略
        } finally {
            try { os.close(); } catch (IOException ignored) { }
        }
    }

    /* ================== 序列化 ================== */

    private String eventToJson(AgentEvent event) {
        StringBuilder sb = new StringBuilder("{");
        switch (event) {
            case AgentEvent.Text t ->
                sb.append("\"type\":\"text\",\"text\":\"").append(escape(t.delta())).append("\"");
            case AgentEvent.ToolCall c ->
                sb.append("\"type\":\"tool_call\",\"id\":\"").append(escape(c.id()))
                  .append("\",\"name\":\"").append(escape(c.name()))
                  .append("\",\"args\":").append(toJson(c.args()));
            case AgentEvent.ToolResult r ->
                sb.append("\"type\":\"tool_result\",\"id\":\"").append(escape(r.id()))
                  .append("\",\"output\":\"").append(escape(r.output()))
                  .append("\",\"error\":").append(r.error());
            case AgentEvent.TurnComplete t ->
                sb.append("\"type\":\"turn_complete\",\"turn\":").append(t.turn());
            case AgentEvent.LoopComplete l ->
                sb.append("\"type\":\"loop_complete\",\"turns\":").append(l.turns());
            case AgentEvent.Error e ->
                sb.append("\"type\":\"error\",\"message\":\"").append(escape(e.message())).append("\"");
            case AgentEvent.Log lg ->
                sb.append("\"type\":\"log\",\"message\":\"").append(escape(lg.message())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String toJson(Object o) {
        try { return JSON.writeValueAsString(o); }
        catch (Exception e) { return "\"\""; }
    }

    /* ================== 小工具 ================== */

    private static String query(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return null;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = i >= 0 ? kv.substring(0, i) : kv;
            String v = i >= 0 ? kv.substring(i + 1) : "";
            if (k.equals(key)) {
                try { return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8); }
                catch (Exception e) { return v; }
            }
        }
        return null;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
