package com.hzy.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hzy.config.ProviderConfig;
import com.hzy.conversation.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class OpenAIClient implements LLMClient{

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ProviderConfig providerConfig;
    private final String systemPrompt;

    private final String model;
    private final boolean thinking;
    private volatile int maxOutputTokens;


    public OpenAIClient(HttpClient httpClient,  ProviderConfig providerConfig, String systemPrompt) {
        this.httpClient = httpClient;
        this.providerConfig = providerConfig;
        this.systemPrompt = systemPrompt;

        this.model = providerConfig.model();
        this.thinking = providerConfig.thinking();
    }


    @Override
    public void doStream(LLMRequestBody requestBody, BlockingQueue<StreamEvent> queue) throws Exception {
        String body = buildRequestBody(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(providerConfig.baseUrl()+"/chat/completions"))
                .header("Authorization", "Bearer " + providerConfig.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String error = new String(
                    response.body().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            throw new IOException(
                    "HTTP " + response.statusCode() + ": " + error
            );
        }

        parseSSE(response.body(), queue);

    }


    private void parseSSE(InputStream input, BlockingQueue<StreamEvent> queue) throws Exception {
        try (
                var reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)))
        {
            String line;

            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }

                String data = line.substring(6).trim();

                if ("[DONE]".equals(data)) {
                    queue.put(new StreamEvent.StreamEnd(
                            "end_turn",
                            0,
                            0
                    ));
                    break;
                }

                JsonNode root = objectMapper.readTree(data);
                JsonNode delta = root
                        .path("choices")
                        .path(0)
                        .path("delta");

                if (delta.has("content")) {
                    String text = delta
                            .get("content")
                            .asText();

                    if (!text.isEmpty()) {
                        queue.put(
                                new StreamEvent.TextDelta(text)
                        );
                    }
                }
                JsonNode finishReason = root.path("choices")
                        .path(0)
                        .path("finish_reason");

                if (!finishReason.isMissingNode()
                        && !finishReason.isNull()
                        && !finishReason.asText().isBlank()) {

                    queue.put(new StreamEvent.StreamEnd(
                            finishReason.asText(),
                            0,
                            0
                    ));
                    break;
                }
            }
        }
    }

    public String buildRequestBody(LLMRequestBody requestBody) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", providerConfig.model());
        root.put("stream", true);
        root.put("max_tokens", 8192);

        ArrayNode messages = buildMessages(requestBody.conversationManager().getHistoryCopy());
        root.set("messages", messages);

        if (requestBody.tools() != null && !requestBody.tools().isEmpty()) {
            root.set("tools", objectMapper.valueToTree(requestBody.tools()));
        }

        return objectMapper.writeValueAsString(root);
    }

    private ArrayNode buildMessages(List<Message> historyCopy) {
        ArrayNode messages = objectMapper.createArrayNode();

        if(systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode systemPrompt = objectMapper.createObjectNode();
            systemPrompt.put("role","system");
            systemPrompt.put("content",systemPrompt);
        }

        if (historyCopy == null || historyCopy.isEmpty()) {
            return messages;
        }

        for (Message message : historyCopy) {
            if (message == null) {
                continue;
            }

            // TODO: tool_message

            // TODO: normal_message
            ObjectNode messageNode = messages.addObject();
            String role = message.getRole();
            if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
                role = "user";
            }
            messageNode.put("role",role);
            if (message.getContent() == null || message.getContent().isEmpty()) {
                messageNode.putNull("content");
            } else {
                messageNode.put("content", message.getContent());
            }

            // TODO: assistant工具调用




        }

        return messages;
    }
}
