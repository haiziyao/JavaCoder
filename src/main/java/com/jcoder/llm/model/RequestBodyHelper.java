package com.jcoder.llm.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.tool.ToolDefinition;

import java.util.List;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class RequestBodyHelper {

    private ConversationManager conversationManager;
    private String systemPrompt;
    private List<ToolDefinition> tools;

    // 这俩仅仅作为提醒吧,先不实现
    int maxOutputTokens;
    boolean thinking;

    public RequestBodyHelper(ConversationManager conversationManager, String systemPrompt, List<ToolDefinition> tools) {
        this.conversationManager = conversationManager;
        this.systemPrompt = systemPrompt;
        this.tools = tools;
    }

    public String buildRequestBody(ObjectMapper objectMapper,
                                   String model, boolean isStream, int maxOutputTokens) throws JsonProcessingException {
        ObjectNode requestBodyRoot = objectMapper.createObjectNode();

        requestBodyRoot.put("model", model);
        requestBodyRoot.put("stream", isStream);
        requestBodyRoot.put("max_tokens", maxOutputTokens);

        ArrayNode messages = buildMessages(objectMapper);
        requestBodyRoot.set("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            requestBodyRoot.set("tools",objectMapper.valueToTree(tools));
        }

        return objectMapper.writeValueAsString(requestBodyRoot);

    }

    public ArrayNode buildMessages(ObjectMapper objectMapper) {
        ArrayNode messages = objectMapper.createArrayNode();

        if(systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode systemPromptNode = objectMapper.createObjectNode();
            systemPromptNode.put("role","system");
            systemPromptNode.put("content",systemPrompt);
            messages.add(systemPromptNode);
        }

        List<Message> history = conversationManager.getHistoryMut();
        if (history == null || history.isEmpty()) {
            return messages;
        }

        for (Message message : history) {
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
