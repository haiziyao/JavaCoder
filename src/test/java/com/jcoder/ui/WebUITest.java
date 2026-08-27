package com.jcoder.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.agent.Agent;
import com.jcoder.config.ProviderConfig;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionMode;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebUITest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private WebUI webUI;
    private URI baseUri;
    private String csrfToken;

    @BeforeEach
    void startServer() throws Exception {
        ProviderConfig provider = new ProviderConfig(
                "test", "gpt", "http://localhost", "fake-model", null,
                "", false, 128_000, 8_192
        );
        Agent agent = new Agent(new FakeLlmClient(), new ToolRegister(), 128_000, 8_192);
        agent.setChecker(new PermissionChecker(PermissionMode.DEFAULT, Path.of(".")));
        webUI = new WebUI(0, provider, 2, 3, List.of("test warning"));
        baseUri = webUI.start(agent, new ConversationManager());

        HttpResponse<String> response = get("api/state");
        Map<String, Object> state = json(response.body());
        csrfToken = (String) state.get("csrfToken");
    }

    @AfterEach
    void stopServer() {
        webUI.stop();
    }

    @Test
    void servesApplicationAssetsAndState() throws Exception {
        HttpResponse<String> index = get("");
        HttpResponse<String> css = get("app.css");
        HttpResponse<String> script = get("app.js");
        Map<String, Object> state = json(get("api/state").body());

        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("MyCoder"));
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("content-type").orElse("").contains("text/css"));
        assertEquals(200, script.statusCode());
        assertTrue(script.body().contains("EventSource"));
        assertEquals("IDLE", state.get("runState"));
        assertEquals("DEFAULT", state.get("permissionMode"));
    }

    @Test
    void rejectsMutationWithoutCsrfToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("api/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hello\"}"))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    void createsRunAndStreamsTextToCompletion() throws Exception {
        HttpResponse<String> created = post("api/runs", "{\"message\":\"hello\"}");
        Map<String, Object> run = json(created.body());
        String runId = (String) run.get("runId");

        HttpResponse<String> events = get("api/runs/" + runId + "/events");

        assertEquals(202, created.statusCode());
        assertEquals(200, events.statusCode());
        assertTrue(events.body().contains("event: text"));
        assertTrue(events.body().contains("hello from web"));
        assertTrue(events.body().contains("event: loop_complete"));

        Map<String, Object> state = json(get("api/state").body());
        assertEquals("COMPLETED", state.get("runState"));
        Map<String, Object> conversation = json(get("api/conversation").body());
        assertEquals(2, ((List<?>) conversation.get("items")).size());
    }

    private HttpResponse<String> get(String relative) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(relative)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String relative, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(relative))
                .header("Content-Type", "application/json")
                .header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> json(String body) throws Exception {
        return mapper.readValue(body, new TypeReference<>() {
        });
    }

    private static final class FakeLlmClient implements LLMClient {
        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            return new LinkedBlockingQueue<>(List.of(
                    new StreamBlock.ContentDelta("hello from web"),
                    new StreamBlock.StreamEnd("stop")
            ));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("request() should not be called");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
