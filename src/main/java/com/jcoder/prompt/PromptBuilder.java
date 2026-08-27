package com.jcoder.prompt;

import com.jcoder.config.ConfigManager;
import com.jcoder.config.PromptConfig;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.skill.SkillRuntime;
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

    public PromptContent build(
            ConversationManager conversationManager,
            List<ToolDefinition> tools,
            EnvironmentContext environmentContext,
            AgentMode mode,
            int turn
    ) {
        return build(
                conversationManager,
                tools,
                environmentContext,
                mode,
                turn,
                "",
                null
        );
    }

    public PromptContent build(
            ConversationManager conversationManager,
            List<ToolDefinition> tools,
            EnvironmentContext environmentContext,
            AgentMode mode,
            int turn,
            String longTermMemoryReminder
    ) {
        return build(
                conversationManager,
                tools,
                environmentContext,
                mode,
                turn,
                longTermMemoryReminder,
                null
        );
    }

    public PromptContent build(
            ConversationManager conversationManager,
            List<ToolDefinition> tools,
            EnvironmentContext environmentContext,
            AgentMode mode,
            int turn,
            String longTermMemoryReminder,
            SkillRuntime skillRuntime
    ) {
        PromptConfig config =
                ConfigManager.promptConfig;

        /*
         * 这里改成 ArrayList，
         * 因为 Available Skills 是运行时可选段落。
         */
        List<PromptSection> sections =
                new ArrayList<>();

        sections.add(
                new PromptSection(
                        "Identity",
                        0,
                        config.identity()
                )
        );

        sections.add(
                new PromptSection(
                        "Behavior",
                        10,
                        config.behavior()
                )
        );

        sections.add(
                new PromptSection(
                        "Tool Usage",
                        20,
                        config.toolUsage()
                )
        );

        sections.add(
                new PromptSection(
                        "Code Quality",
                        30,
                        config.codeQuality()
                )
        );

        sections.add(
                new PromptSection(
                        "Security",
                        40,
                        config.security()
                )
        );

        sections.add(
                new PromptSection(
                        "Task Pattern",
                        50,
                        config.taskPattern()
                )
        );

        sections.add(
                new PromptSection(
                        "Output Style",
                        60,
                        config.outputStyle()
                )
        );

        /*
         * 发现层：
         * 只注入名称和 description。
         *
         * 完整 SOP 不能放在这里，否则所有安装的 Skill
         * 都会永久占用 system prompt。
         */
        if (skillRuntime != null) {
            String availableSkills =
                    skillRuntime.renderAvailableSkills();

            if (!availableSkills.isBlank()) {
                sections.add(
                        new PromptSection(
                                "Available Skills",
                                70,
                                availableSkills
                        )
                );
            }
        }

        String systemPrompt =
                sections.stream()
                        .sorted(
                                Comparator.comparingInt(
                                        PromptSection::priority
                                )
                        )
                        .map(
                                PromptSection::content
                        )
                        .filter(content ->
                                content != null
                                        && !content.isBlank()
                        )
                        .map(String::strip)
                        .collect(
                                Collectors.joining(
                                        "\n\n"
                                )
                        );

        List<ToolDefinition> sortedTools =
                tools.stream()
                        .sorted(
                                Comparator.comparing(
                                        ToolDefinition::name
                                )
                        )
                        .toList();

        List<Message> messages =
                new ArrayList<>();

        messages.add(
                EnvironmentPrompt.build(
                        environmentContext
                )
        );

        if (longTermMemoryReminder != null
                && !longTermMemoryReminder.isBlank()) {
            messages.add(
                    new Message(
                            "system",
                            longTermMemoryReminder.strip()
                    )
            );
        }

        /*
         * 激活层：
         * 只有 active Skill 的完整 SOP 才会进入请求。
         *
         * 它是临时 Prompt 消息，不写入 Conversation。
         */
        if (skillRuntime != null) {
            String activeSkills =
                    skillRuntime.renderActiveSkills();

            if (!activeSkills.isBlank()) {
                messages.add(
                        new Message(
                                "system",
                                activeSkills
                        )
                );
            }
        }

        messages.addAll(
                conversationManager.getHistoryCopy()
        );

        Message modeReminder =
                PlanModePrompt.build(
                        mode,
                        turn
                );

        if (modeReminder != null) {
            messages.add(
                    modeReminder
            );
        }

        return new PromptContent(
                systemPrompt,
                messages,
                sortedTools
        );
    }

}
