package com.jcoder.agent;

import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryReminderTest {

    @Test
    void resetContextManagementClearsReminderAndCachedPrompt() throws Exception {
        CapturingClient client = new CapturingClient();
        Agent agent = new Agent(client, new ToolRegister(), 128_000, 8_192);
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("hello");
        agent.setLongTermMemoryReminder("remember Java 21");

        agent.agentLoop(conversation, new AgentEventQueue(16));
        assertEquals("remember Java 21", agent.getLongTermMemoryReminder());
        assertTrue(agent.getCurrentPromptContent().messages().stream()
                .anyMatch(message -> "remember Java 21".equals(message.getContent())));

        agent.resetContextManagement();

        assertEquals("", agent.getLongTermMemoryReminder());
        assertNull(agent.getCurrentPromptContent());
    }

    @Test
    void automaticCompactionRebuildsPromptWithReminderStillPresent()
            throws Exception {
        CompactionThenAnswerClient client = new CompactionThenAnswerClient();
        Agent agent = new Agent(client, new ToolRegister(), 30_000, 1_000);
        agent.setLongTermMemoryReminder("durable Java 21 constraint");
        ConversationManager conversation = new ConversationManager();
        for (int index = 0; index < 10; index++) {
            conversation.addMessage(new Message(
                    index % 2 == 0 ? "user" : "assistant",
                    "message-" + index + " " + "x".repeat(10_000)
            ));
        }

        agent.agentLoop(conversation, new AgentEventQueue(64));

        assertEquals(2, client.prompts.size(),
                "one summary request and one rebuilt main-agent request are expected");
        PromptContent rebuilt = agent.getCurrentPromptContent();
        assertTrue(rebuilt.messages().stream().anyMatch(message ->
                "durable Java 21 constraint".equals(message.getContent())));
        assertTrue(conversation.getHistoryCopy().getFirst().getContent()
                .contains("<context-summary>"));
        assertFalse(conversation.getHistoryCopy().stream().anyMatch(message ->
                "durable Java 21 constraint".equals(message.getContent())));
    }

    private static final class CapturingClient implements LLMClient {
        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            return new LinkedBlockingQueue<>(List.of(
                    new StreamBlock.StreamEnd("stop")
            ));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("Agent must use stream()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }

    private static final class CompactionThenAnswerClient implements LLMClient {
        private final List<PromptContent> prompts = new ArrayList<>();

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            prompts.add(promptContent);
            if (promptContent.system().contains("context compactor")) {
                return new LinkedBlockingQueue<>(List.of(
                        new StreamBlock.ContentDelta(
                                "<summary>earlier work summary</summary>"
                        ),
                        new StreamBlock.StreamEnd("stop")
                ));
            }
            return new LinkedBlockingQueue<>(List.of(
                    new StreamBlock.ContentDelta("done"),
                    new StreamBlock.StreamEnd("stop")
            ));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("Agent must use stream()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
