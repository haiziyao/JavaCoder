package com.jcoder.hook.executor;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.HookSelector;
import com.jcoder.hook.action.HttpHookAction;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpHookExecutorTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private HttpClient client;
    private HookTemplateRenderer renderer;

    @BeforeEach
    void createLocalServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        renderer = new HookTemplateRenderer();
    }

    @AfterEach
    void stopLocalServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void sendsConfiguredMethodHeaderAndRenderedBodyAndAccepts2xx() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/capture", exchange -> {
            method.set(exchange.getRequestMethod());
            header.set(exchange.getRequestHeaders().getFirst("X-Hook-Test"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, "created");
        });
        server.start();

        HookContext context = HookContext.preTool(
                "", Path.of("."), "WriteFile", Map.of("path", "application.json"));
        HttpHookAction action = new HttpHookAction(
                uri("/capture"), "PUT", Map.of("X-Hook-Test", "configured"),
                "{\"tool\":\"{{tool_name}}\",\"path\":\"{{args.path}}\"}");

        HookExecutionResult result = executor(1_000, 1_000).execute(
                definition(action, context, 2_000), context);

        assertEquals(HookActionType.HTTP, executor(1_000, 1_000).type());
        assertTrue(result.success(), result.errorMessage());
        assertEquals("created", result.output());
        assertEquals("PUT", method.get());
        assertEquals("configured", header.get());
        assertEquals(
                "{\"tool\":\"WriteFile\",\"path\":\"application.json\"}",
                body.get()
        );
    }

    @Test
    void non2xxResponseReturnsFailureWithResponseBody() {
        server.createContext("/invalid", exchange -> respond(exchange, 422, "invalid input"));
        server.start();
        HookContext context = HookContext.turnStart("", Path.of("."), "");

        HookExecutionResult result = executor(1_000, 1_000).execute(
                definition(new HttpHookAction(
                        uri("/invalid"), "POST", Map.of(), ""), context, 2_000),
                context
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("status 422"));
        assertEquals("invalid input", result.output());
    }

    @Test
    void rejectsRenderedRequestBodyAboveConfiguredLimitWithoutSending() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/limit", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });
        server.start();
        HookContext context = HookContext.turnStart("", Path.of("."), "123456");

        HookExecutionResult result = executor(5, 1_000).execute(
                definition(new HttpHookAction(
                        uri("/limit"), "POST", Map.of(), "{{message}}"), context, 2_000),
                context
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("6 characters; maximum is 5"));
        assertEquals(0, requests.get());
    }

    @Test
    void truncatesResponseButContinuesToDrainIt() {
        server.createContext("/large", exchange -> respond(exchange, 200, "x".repeat(100)));
        server.start();
        HookContext context = HookContext.turnStart("", Path.of("."), "");

        HookExecutionResult result = executor(1_000, 10).execute(
                definition(new HttpHookAction(
                        uri("/large"), "GET", Map.of(), ""), context, 2_000),
                context
        );

        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().startsWith("xxxxxxxxxx"));
        assertTrue(result.output().contains("...[hook response truncated]..."));
    }

    @Test
    void timesOutWhileWaitingForResponseHeaders() {
        server.createContext("/slow-response", exchange -> {
            try {
                Thread.sleep(2_000);
                respond(exchange, 200, "late");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Client timeout may close the exchange before the late response.
            }
        });
        server.start();
        HookContext context = HookContext.turnStart("", Path.of("."), "");

        HookExecutionResult result = executor(1_000, 1_000).execute(
                definition(new HttpHookAction(
                        uri("/slow-response"), "GET", Map.of(), ""), context, 150),
                context
        );

        assertFalse(result.success());
        String error = result.errorMessage().toLowerCase();
        assertTrue(error.contains("timed out") || error.contains("timeout"), error);
    }

    @Test
    void timesOutWhileReadingSlowResponseBody() {
        server.createContext("/slow-body", exchange -> {
            byte[] bytes = "slow-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes, 0, 1);
                output.flush();
                Thread.sleep(2_000);
                output.write(bytes, 1, bytes.length - 1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Executor closes the response stream on timeout.
            }
        });
        server.start();
        HookContext context = HookContext.turnStart("", Path.of("."), "");

        HookExecutionResult result = executor(1_000, 1_000).execute(
                definition(new HttpHookAction(
                        uri("/slow-body"), "GET", Map.of(), ""), context, 200),
                context
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("timed out after 200 ms"));
    }

    @Test
    void toolArgumentCannotReplaceTrustedConfiguredUri() {
        AtomicInteger trustedRequests = new AtomicInteger();
        AtomicInteger attackerRequests = new AtomicInteger();
        server.createContext("/trusted", exchange -> {
            trustedRequests.incrementAndGet();
            respond(exchange, 200, "trusted");
        });
        server.createContext("/attacker", exchange -> {
            attackerRequests.incrementAndGet();
            respond(exchange, 200, "attacker");
        });
        server.start();
        HookContext context = HookContext.preTool(
                "", Path.of("."), "Fetch",
                Map.of("uri", uri("/attacker").toString()));

        HookExecutionResult result = executor(1_000, 1_000).execute(
                definition(new HttpHookAction(
                        uri("/trusted"), "POST", Map.of(), "{{args.uri}}"),
                        context, 2_000),
                context
        );

        assertTrue(result.success(), result.errorMessage());
        assertEquals("trusted", result.output());
        assertEquals(1, trustedRequests.get());
        assertEquals(0, attackerRequests.get());
    }

    @Test
    void templateFailureDoesNotSendRequest() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/template", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "unexpected");
        });
        server.start();
        HookContext context = HookContext.turnStart("", Path.of("."), "");

        HookExecutionResult result = executor(1_000, 1_000).execute(
                definition(new HttpHookAction(
                        uri("/template"), "POST", Map.of(), "{{missing}}"),
                        context, 2_000),
                context
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("failed to render http hook body"));
        assertEquals(0, requests.get());
    }

    @Test
    void validatesConstructorDependenciesAndLimits() {
        assertThrows(NullPointerException.class,
                () -> new HttpHookExecutor(null, renderer, 1, 1));
        assertThrows(NullPointerException.class,
                () -> new HttpHookExecutor(client, null, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpHookExecutor(client, renderer, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpHookExecutor(client, renderer, 1, 0));
    }

    private HttpHookExecutor executor(int requestLimit, int responseLimit) {
        return new HttpHookExecutor(client, renderer, requestLimit, responseLimit);
    }

    private HookDefinition definition(
            HttpHookAction action,
            HookContext context,
            long timeoutMillis
    ) {
        return new HookDefinition(
                "http-test", context.event(), HookSelector.any(), action,
                false, false, false, HookErrorPolicy.CONTINUE, timeoutMillis);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
