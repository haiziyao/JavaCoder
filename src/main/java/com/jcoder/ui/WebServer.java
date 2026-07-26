package com.jcoder.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.run.AgentEventListener;
import com.jcoder.run.CodingAgent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** 零前端依赖的本地 Web UI 服务。 */
public final class WebServer {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodingAgent agent = new CodingAgent();
    private final int port;

    public WebServer(int port) {
        this.port = port;
    }

    public void start(boolean openBrowser) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::staticFile);
        server.createContext("/api/chat", this::chat);
        server.createContext("/api/clear", this::clear);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        String url = "http://127.0.0.1:" + port;
        System.out.println("MyCoder UI 已启动: " + url);
        if (openBrowser && Desktop.isDesktopSupported()) {
            try { Desktop.getDesktop().browse(URI.create(url)); } catch (Exception ignored) { }
        }
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not Found");
            return;
        }
        try (var in = WebServer.class.getClassLoader().getResourceAsStream("ui/index.html")) {
            if (in == null) { send(exchange, 500, "text/plain", "UI resource missing"); return; }
            send(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private void chat(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, "text/plain", "Method Not Allowed"); return; }
        Map<?, ?> request;
        try { request = mapper.readValue(exchange.getRequestBody(), Map.class); }
        catch (Exception e) { send(exchange, 400, "application/json", "{\"error\":\"请求格式错误\"}"); return; }
        Object messageValue = request.get("message");
        String message = messageValue == null ? "" : String.valueOf(messageValue);

        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            AgentEventListener events = new AgentEventListener() {
                private void event(String type, Object... values) {
                    try {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("type", type);
                        for (int i = 0; i < values.length; i += 2) data.put((String) values[i], values[i + 1]);
                        out.write(mapper.writeValueAsBytes(data));
                        out.write('\n');
                        out.flush();
                    } catch (IOException e) { throw new StreamClosedException(e); }
                }
                public void onStatus(String status) { event("status", "text", status); }
                public void onContent(String delta) { event("delta", "text", delta); }
                public void onToolStart(String name, Map<String, Object> args) { event("tool_start", "name", name, "args", args); }
                public void onToolEnd(String name, String output, boolean error) { event("tool_end", "name", name, "output", output, "error", error); }
                public void onDone() { event("done"); }
            };
            try { agent.chat(message, events); }
            catch (StreamClosedException ignored) { }
            catch (Exception e) {
                try {
                    out.write(mapper.writeValueAsBytes(Map.of("type", "error", "message", safeMessage(e))));
                    out.write('\n'); out.flush();
                } catch (IOException ignored) { }
            }
        }
    }

    private void clear(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, "text/plain", "Method Not Allowed"); return; }
        agent.clear();
        send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void send(HttpExchange exchange, int status, String type, String text) throws IOException {
        send(exchange, status, type, text.getBytes(StandardCharsets.UTF_8));
    }
    private void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static final class StreamClosedException extends RuntimeException {
        StreamClosedException(Throwable cause) { super(cause); }
    }
}
