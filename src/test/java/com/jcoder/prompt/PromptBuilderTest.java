package com.jcoder.prompt;

import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final EnvironmentContext environment = new EnvironmentContext(
            "E:\\workspace",
            "Windows 11",
            "amd64",
            "cmd.exe",
            LocalDate.of(2026, 8, 14)
    );

    @Test
    void assemblesSevenSystemSectionsInPriorityOrder() {
        PromptContent content = promptBuilder.build(
                new ConversationManager(),
                List.of(),
                environment,
                AgentMode.NORMAL,
                1
        );

        List<String> headings = List.of(
                "# Identity",
                "# Behavior",
                "# Tool Usage",
                "# Code Quality",
                "# Security",
                "# Task Patterns",
                "# Output Style"
        );

        int previousIndex = -1;
        for (String heading : headings) {
            int index = content.system().indexOf(heading);
            assertTrue(index > previousIndex, heading);
            previousIndex = index;
        }
    }

    @Test
    void placesEnvironmentBeforeHistoryAndPlanReminderAfterHistory() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("hello");

        PromptContent content = promptBuilder.build(
                conversation,
                List.of(),
                environment,
                AgentMode.PLAN,
                1
        );

        assertEquals(List.of("user", "user", "user"),
                content.messages().stream().map(Message::getRole).toList());
        assertTrue(content.messages().get(0).getContent().startsWith("<system-reminder>"));
        assertEquals("hello", content.messages().get(1).getContent());
        assertTrue(content.messages().get(2).getContent().contains("# Plan Mode"));
    }

    @Test
    void repeatsFullPlanReminderEveryFiveTurns() {
        ConversationManager conversation = new ConversationManager();

        PromptContent turnTwo = promptBuilder.build(
                conversation, List.of(), environment, AgentMode.PLAN, 2);
        PromptContent turnSix = promptBuilder.build(
                conversation, List.of(), environment, AgentMode.PLAN, 6);

        assertTrue(lastMessage(turnTwo).contains("still in PLAN mode"));
        assertTrue(lastMessage(turnSix).contains("# Plan Mode"));
    }

    @Test
    void tellsAgentToActInsteadOfEndingWithAPlanToAct() {
        PromptContent content = promptBuilder.build(
                new ConversationManager(), List.of(), environment, AgentMode.NORMAL, 1);

        assertTrue(content.system().contains("same response"));
        assertTrue(content.system().contains("Do not end a task response with only a promise"));
    }

    private static String lastMessage(PromptContent content) {
        return content.messages().get(content.messages().size() - 1).getContent();
    }
}
