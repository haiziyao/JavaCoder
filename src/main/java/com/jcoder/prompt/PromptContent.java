package com.jcoder.prompt;

import com.jcoder.message.Message;
import com.jcoder.tool.ToolDefinition;

import java.util.List;

public record PromptContent(
        String system,
        List<Message> messages,
        List<ToolDefinition> tools
) {
    public PromptContent {
        system = system == null ? "" : system;
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
