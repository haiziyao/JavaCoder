package com.jcoder.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.config.ProviderConfig;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;

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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class OpenAIClient implements LLMClient{


    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProviderConfig providerConfig;

    private final String systemPrompt;
    private volatile String model;
    private volatile boolean thinking;
    private volatile int maxOutputTokens;

    public OpenAIClient(HttpClient httpClient, ProviderConfig providerConfig, String systemPrompt) {
        this.httpClient = httpClient;
        this.providerConfig = providerConfig;
        this.systemPrompt = systemPrompt;

        model = this.providerConfig.model();

        // 这个值我都不想自己加,让模型自己走默认值吧
        maxOutputTokens = this.providerConfig.maxOutputTokens();
    }

    @Override
    public BlockingQueue<StreamBlock> stream(RequestBodyHelper requestBodyHelper) {

        var queue = new LinkedBlockingQueue<StreamBlock>();
        Thread.startVirtualThread(()->{
            try {
                doStream(requestBodyHelper,queue);
            } catch (Exception e) {
                queue.add(new StreamBlock.StreamError(e.getMessage()));
            }
        });
        return queue;
    }

    private void doStream(RequestBodyHelper requestBodyHelper, LinkedBlockingQueue<StreamBlock> queue)  throws Exception {
        String requestBody =  requestBodyHelper.buildRequestBody(objectMapper,model,true,maxOutputTokens);


        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(providerConfig.baseUrl()+"/chat/completions"))
                .header("Authorization", "Bearer " + providerConfig.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
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

    private void parseSSE(InputStream input, LinkedBlockingQueue<StreamBlock> queue) throws Exception {
        try (
                var reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8)))
        {
            String line;
            Map<Integer, String> toolIds = new HashMap<>();
            Map<Integer, String> toolTypes = new HashMap<>();
            Map<Integer, String> toolNames = new HashMap<>();
            Map<Integer, StringBuilder> toolArguments = new HashMap<>();

            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }

                String data = line.substring(6).trim();

                if ("[DONE]".equals(data)) {
                    queue.put(new StreamBlock.StreamEnd("DONE"));
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
                        queue.put(new StreamBlock.ContentDelta(text));
                    }
                }

                JsonNode toolCalls = delta.path("tool_calls");

                if (!toolCalls.isMissingNode() && !toolCalls.isNull()) {
                    if (toolCalls.isArray()) {
                        for (JsonNode toolCall : toolCalls) {
                            int index = toolCall.path("index").asInt(0);
                            // 放 ID
                            String id = toolCall.path("id").asText();
                            if (!id.isEmpty()){toolIds.put(index, id);}

                            // 放 type
                            String type = toolCall.path("type").asText();
                            if (!type.isEmpty()){toolTypes.put(index, type);}

                            JsonNode function = toolCall.path("function");

                            // 放name
                            String name = function.path("name").asText("");
                            if (!name.isEmpty()) {toolNames.put(index, name);}

                            String arguments = function.path("arguments").asText("");

                            if (!arguments.isEmpty()) {toolArguments
                                    .computeIfAbsent(index, ignored -> new StringBuilder())
                                        .append(arguments);
                            }
                        }
                    }
                }


                JsonNode finishReason = root.path("choices")
                        .path(0)
                        .path("finish_reason");

                if (finishReason.asText().equals("tool_calls")){
                    for (Integer index : toolArguments.keySet()) {
                        String toolId = toolIds.get(index);
                        String toolName = toolNames.get(index);
                        String type = toolTypes.get(index);
                        String rawArguments = toolArguments.get(index).toString();

                        Map<String, Object> arguments;

                        try {
                            arguments = objectMapper.readValue(rawArguments, Map.class);
                        } catch (JsonProcessingException e) {
                            queue.put(new StreamBlock.StreamError(
                                    "Invalid tool arguments: " + rawArguments
                            ));
                            return;
                        }

                        queue.put(new StreamBlock.ToolCallComplete(
                                toolId,
                                toolName,
                                type,
                                arguments
                        ));
                    }
                }

                if (!finishReason.isMissingNode() && !finishReason.isNull() && !finishReason.asText().isBlank()) {

                    //TODO:  这里可以添加上 Usage
                    queue.put(new StreamBlock.StreamEnd(finishReason.asText()));
                    break;
                }
            }
        }

    }

    @Override
    public ResponseBody request(RequestBodyHelper requestBodyHelper) {
        return null;
    }
}
