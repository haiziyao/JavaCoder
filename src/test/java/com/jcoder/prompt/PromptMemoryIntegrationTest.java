package com.jcoder.prompt;

import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptMemoryIntegrationTest {

    @Test
    void reminderIsInjectedIntoRequestWithoutEnteringConversation() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("implement the next class");
        List<Message> originalHistory = conversation.getHistoryCopy();
        String reminder = """
                <long-term-memory>
                - [PROJECT/CONSTRAINT/java.target_version] Use Java 21.
                </long-term-memory>
                """;

        PromptContent prompt = new PromptBuilder().build(
                conversation,
                List.of(),
                new EnvironmentContext(
                        "E:\\workspace", "Windows 11", "amd64", "powershell",
                        LocalDate.of(2026, 8, 27)
                ),
                AgentMode.NORMAL,
                1,
                reminder
        );

        assertEquals(3, prompt.messages().size());
        assertTrue(prompt.messages().getFirst().getContent()
                .startsWith("<system-reminder>"));
        assertEquals("system", prompt.messages().get(1).getRole());
        assertEquals(reminder.strip(), prompt.messages().get(1).getContent());
        assertEquals("implement the next class",
                prompt.messages().get(2).getContent());

        assertEquals(originalHistory, conversation.getHistoryCopy());
        assertEquals(1, conversation.size());
        assertFalse(conversation.getHistoryCopy().stream()
                .anyMatch(message -> reminder.strip().equals(message.getContent())));
    }

    @Test
    void blankReminderAddsNoSyntheticMessage() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("hello");

        PromptContent prompt = new PromptBuilder().build(
                conversation,
                List.of(),
                new EnvironmentContext(
                        "E:\\workspace", "Windows 11", "amd64", "powershell",
                        LocalDate.of(2026, 8, 27)
                ),
                AgentMode.NORMAL,
                1,
                "   "
        );

        assertEquals(2, prompt.messages().size());
        assertEquals(List.of("user", "user"), prompt.messages().stream()
                .map(Message::getRole)
                .toList());
    }
}
