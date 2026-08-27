package com.jcoder.agent;

import com.jcoder.context.CompactionCircuitBreaker;
import com.jcoder.context.ContextCompactor;
import com.jcoder.command.CommandContext;
import com.jcoder.command.CommandResult;
import com.jcoder.command.DefaultCommands;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCompactionTest {

    @Test
    void compactNowSucceedsAndClearsExistingFailures() throws Exception {
        FakeLlmClient client = FakeLlmClient.success(
                "<summary>manual summary</summary>"
        );
        Agent agent = new Agent(client, new ToolRegister(), 128_000, 8_192);
        CompactionCircuitBreaker breaker = agent.compactionCircuitBreaker();
        breaker.recordFailure();
        breaker.recordFailure();

        ConversationManager conversation = conversationWithMessages(10);
        ContextCompactor.CompactionResult result =
                agent.compactNow(conversation);

        assertTrue(result.compacted());
        assertEquals(7, conversation.getHistoryCopy().size());
        assertEquals(0, breaker.consecutiveFailures());
        assertFalse(breaker.isOpen());
        assertEquals(1, client.streamCalls);
    }

    @Test
    void compactNowResetsOpenBreakerThenCountsCurrentFailureOnce() {
        FakeLlmClient client = FakeLlmClient.failure("manual failure");
        Agent agent = new Agent(client, new ToolRegister(), 128_000, 8_192);
        CompactionCircuitBreaker breaker = agent.compactionCircuitBreaker();
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.isOpen());

        ConversationManager conversation = conversationWithMessages(10);
        List<Message> original = conversation.getHistoryCopy();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> agent.compactNow(conversation)
        );

        assertTrue(error.getMessage().contains("manual failure"));
        assertEquals(1, breaker.consecutiveFailures(),
                "manual retry resets old failures before recording this failure");
        assertFalse(breaker.isOpen());
        assertEquals(1, client.streamCalls);

        List<Message> after = conversation.getHistoryCopy();
        assertEquals(original, after);
        for (int index = 0; index < original.size(); index++) {
            assertSame(original.get(index), after.get(index));
            assertEquals(
                    original.get(index).getContent(),
                    after.get(index).getContent()
            );
        }
    }

    @Test
    void clearCommandClosesOpenCompactionBreaker() throws Exception {
        FakeLlmClient client = FakeLlmClient.success(
                "<summary>must not be used</summary>"
        );
        Agent agent = new Agent(client, new ToolRegister(), 128_000, 8_192);
        CompactionCircuitBreaker breaker = agent.compactionCircuitBreaker();
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.isOpen());

        ConversationManager conversation = conversationWithMessages(3);
        CommandResult result = DefaultCommands.create().execute(
                "/clear",
                new CommandContext(agent, conversation)
        );

        assertTrue(result.success());
        assertEquals(0, conversation.size());
        assertEquals(0, breaker.consecutiveFailures());
        assertFalse(breaker.isOpen());
        assertEquals(0, client.streamCalls,
                "clear must reset state without invoking the model");
    }

    private static ConversationManager conversationWithMessages(int count) {
        ConversationManager conversation = new ConversationManager();
        for (int index = 0; index < count; index++) {
            conversation.addMessage(new Message(
                    index % 2 == 0 ? "user" : "assistant",
                    "message-" + index + " " + "x".repeat(200)
            ));
        }
        return conversation;
    }

    private static final class FakeLlmClient implements LLMClient {

        private final List<StreamBlock> response;
        private int streamCalls;

        private FakeLlmClient(List<StreamBlock> response) {
            this.response = List.copyOf(response);
        }

        static FakeLlmClient success(String summary) {
            return new FakeLlmClient(List.of(
                    new StreamBlock.ContentDelta(summary),
                    new StreamBlock.StreamEnd("stop")
            ));
        }

        static FakeLlmClient failure(String message) {
            return new FakeLlmClient(List.of(
                    new StreamBlock.StreamError(message)
            ));
        }

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            streamCalls++;
            return new LinkedBlockingQueue<>(response);
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("manual compaction must use stream()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
