package com.jcoder.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolParamDefinition;

import java.util.List;
import java.util.Map;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class RequestBodyHelper {

    public String buildRequestBody(ObjectMapper objectMapper,
                                   PromptContent promptContent,
                                   String model, boolean isStream, int maxOutputTokens,
                                   boolean thinking,String reasoningEffort) throws JsonProcessingException {
        ObjectNode requestBodyRoot = objectMapper.createObjectNode();

        requestBodyRoot.put("model", model);
        requestBodyRoot.put("stream", isStream);
        requestBodyRoot.put("max_tokens", maxOutputTokens);
        requestBodyRoot.putObject("thinking").put("type", thinking ? "enabled" : "disabled");

        ArrayNode messages = buildMessages(objectMapper, promptContent);
        requestBodyRoot.set("messages", messages);
        // 推理强度
        if(reasoningEffort!=null && !reasoningEffort.isBlank()){
            requestBodyRoot.put("reasoning_effort", reasoningEffort);
        }

        // 工具注入
        if (!promptContent.tools().isEmpty()) {
            requestBodyRoot.set("tools", getToolsNode(objectMapper, promptContent.tools()));
        }

        return objectMapper.writeValueAsString(requestBodyRoot);

    }

    public ArrayNode buildMessages(ObjectMapper objectMapper,
                                   PromptContent promptContent) throws JsonProcessingException {
        ArrayNode messages = objectMapper.createArrayNode();

        if (!promptContent.system().isEmpty()) {
            ObjectNode systemPromptNode = objectMapper.createObjectNode();
            systemPromptNode.put("role","system");
            systemPromptNode.put("content", promptContent.system());
            messages.add(systemPromptNode);
        }

        List<Message> history = promptContent.messages();
        if (history.isEmpty()) {
            return messages;
        }

        for (Message message : history) {
            if (message == null) {continue;}

            // TODO:(DONE) tool_message
            List<ToolCallBlock> toolCalls = message.getToolCalls();
            if ("assistant".equals(message.getRole()) && toolCalls != null
                    && !toolCalls.isEmpty()){
                ObjectNode assistantNode = messages.addObject();
                assistantNode.put("role", "assistant");
                assistantNode.put("content", message.getContent());
                ArrayNode toolCallsNode = assistantNode.putArray("tool_calls");

                for (ToolCallBlock toolCall : toolCalls) {
                    ObjectNode toolCallNode = toolCallsNode.addObject();

                    toolCallNode.put("id", toolCall.toolId());
                    toolCallNode.put("type", toolCall.type() == null ? "function" : toolCall.type());
                    ObjectNode functionNode = toolCallNode.putObject("function");

                    functionNode.put("name", toolCall.toolName());

                    String argumentsJson = objectMapper.writeValueAsString(toolCall.params());

                    // 注意：arguments 必须是字符串
                    functionNode.put("arguments", argumentsJson);
                }
                continue;
            }

            // TODO:(DONE) tool_results
            List<ToolResult> toolResults = message.getToolResults();
            if (toolResults != null && !toolResults.isEmpty()) {

                for (ToolResult toolResult : toolResults) {
                    ObjectNode toolNode = messages.addObject();
                    toolNode.put("role", "tool");
                    toolNode.put("tool_call_id", toolResult.toolId());
                    toolNode.put("content", toolResult.content() == null ? "" : toolResult.content());
                }
                continue;
            }


            // TODO:(DONE) normal_message
            ObjectNode messageNode = messages.addObject();
            String role = message.getRole();
            if (!"user".equals(role) && !"assistant".equals(role)
                    && !"system".equals(role)&& !"tool".equals(role)) {
                role = "user";
            }
            messageNode.put("role",role);
            if (message.getContent() == null || message.getContent().isEmpty()) {
                messageNode.putNull("content");
            } else {
                messageNode.put("content", message.getContent());
            }

        }
        return messages;
    }

    // AI 写的, 懒得动手了
    public ArrayNode getToolsNode(ObjectMapper objectMapper,
                                  List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return objectMapper.createArrayNode();
        }

        ArrayNode toolsNode = objectMapper.createArrayNode();

        for (ToolDefinition tool : tools) {
            if (tool == null) {
                continue;
            }

            ObjectNode toolNode = toolsNode.addObject();
            toolNode.put("type", "function");

            ObjectNode functionNode = toolNode.putObject("function");
            functionNode.put("name", tool.name());
            functionNode.put("description", tool.description() == null ? "" : tool.description());

            ObjectNode parametersNode = functionNode.putObject("parameters");
            parametersNode.put("type", "object");

            ObjectNode propertiesNode = parametersNode.putObject("properties");
            if (tool.properties() != null) {
                for (Map.Entry<String, ToolParamDefinition> entry : tool.properties().entrySet()) {
                    ToolParamDefinition parameter = entry.getValue();
                    if (parameter == null) {
                        continue;
                    }

                    ObjectNode parameterNode = propertiesNode.putObject(entry.getKey());
                    parameterNode.put("type", parameter.type());
                    if (parameter.description() != null && !parameter.description().isEmpty()) {
                        parameterNode.put("description", parameter.description());
                    }

                    if (parameter.others() != null) {
                        parameter.others().forEach((key, value) ->
                                parameterNode.set(key, objectMapper.valueToTree(value)));
                    }
                }
            }

            if (tool.required() != null && !tool.required().isEmpty()) {
                parametersNode.set("required", objectMapper.valueToTree(tool.required()));
            }
        }

        return toolsNode;
    }
}
