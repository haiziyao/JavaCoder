package com.hzy.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hzy.config.ProviderConfig;
import com.hzy.conversation.ConversationManager;
import com.hzy.conversation.Message;
import com.hzy.tool.ToolDefinition;

import java.util.List;

public record LLMRequestBody(
        String systemPrompt,
        ConversationManager conversationManager,
        List<ToolDefinition> tools,
        int maxOutputTokens,
        boolean thinking
) {
}