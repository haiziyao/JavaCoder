package com.jcoder.prompt;

import com.jcoder.config.ConfigManager;
import com.jcoder.config.PromptConfig;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class PromptBuilder {

    public PromptContent build(ConversationManager conversationManager,
                               List<ToolDefinition> tools,
                               EnvironmentContext environmentContext,
                               AgentMode mode,
                               int turn) {
        PromptConfig config = ConfigManager.promptConfig;

        List<PromptSection> sections = List.of(
                new PromptSection("Identity", 0, config.identity()),
                new PromptSection("Behavior", 10, config.behavior()),
                new PromptSection("Tool Usage", 20, config.toolUsage()),
                new PromptSection("Code Quality", 30, config.codeQuality()),
                new PromptSection("Security", 40, config.security()),
                new PromptSection("Task Pattern", 50, config.taskPattern()),
                new PromptSection("Output Style", 60, config.outputStyle())
        );

        String systemPrompt = sections.stream()
                .sorted(Comparator.comparingInt(PromptSection::priority))
                .map(PromptSection::content)
                .filter(content -> content != null && !content.isBlank())
                .map(String::strip)
                .collect(Collectors.joining("\n\n"));

        List<ToolDefinition> sortedTools = tools.stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();

        List<Message> messages = new ArrayList<>();
        messages.add(EnvironmentPrompt.build(environmentContext));
        messages.addAll(conversationManager.getHistoryCopy());

        Message modeReminder = PlanModePrompt.build(mode, turn);
        if (modeReminder != null) {
            messages.add(modeReminder);
        }

        return new PromptContent(
                systemPrompt,
                messages,
                sortedTools
        );
    }

}
