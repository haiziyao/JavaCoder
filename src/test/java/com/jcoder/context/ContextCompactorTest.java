package com.jcoder.context;

import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;
import com.jcoder.prompt.PromptContent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactorTest {

    @Test
    void summaryRequestHasNoToolsAndContainsOnlyOldPrefix() throws Exception {
        ConversationManager conversation = conversationWithMessages(10, 2_000);
        List<Message> before = conversation.getHistoryCopy();
        FakeLlmClient client = FakeLlmClient.success(
                "<summary>old work summarized</summary>"
        );

        ContextCompactor.CompactionResult result =
                ContextCompactor.compact(conversation, client);

        assertTrue(result.compacted());
        assertEquals(1, client.streamCalls);
        assertTrue(client.lastPrompt.tools().isEmpty(),
                "the compaction request must never expose tools");

        String request = client.lastPrompt.messages().getFirst().getContent();
        for (int index = 0; index < 4; index++) {
            assertTrue(request.contains(marker(index)));
        }
        for (int index = 4; index < 10; index++) {
            assertFalse(request.contains(marker(index)),
                    "recent messages must not be sent for summarization");
        }

        List<Message> after = conversation.getHistoryCopy();
        assertEquals(7, after.size());
        assertTrue(after.getFirst().getContent().contains("old work summarized"));
        for (int index = 0; index < 6; index++) {
            assertSame(before.get(index + 4), after.get(index + 1),
                    "recent messages must be retained as the original objects");
        }
    }

    @Test
    void keepsToolCallAndToolResultTogetherWhenBoundaryFallsOnResult()
            throws Exception {
        ConversationManager conversation = new ConversationManager();
        for (int index = 0; index < 4; index++) {
            conversation.addUserMsg("old-" + index);
        }

        Message toolCallMessage = new Message("assistant", "");
        toolCallMessage.setToolCalls(List.of(new ToolCallBlock(
                "call-boundary",
                "function",
                "read_file",
                Map.of("path", "README.md")
        )));
        conversation.addMessage(toolCallMessage);

        Message toolResultMessage = new Message("tool", "");
        toolResultMessage.setToolResults(List.of(new ToolResult(
                "call-boundary",
                "boundary-result",
                false
        )));
        conversation.addMessage(toolResultMessage);

        for (int index = 0; index < 5; index++) {
            conversation.addAssistantMsg("recent-" + index);
        }

        assertEquals(4, ContextCompactor.computeKeepStartIndex(
                conversation.getHistoryCopy()
        ));

        FakeLlmClient client = FakeLlmClient.success(
                "<summary>earlier messages</summary>"
        );
        ContextCompactor.compact(conversation, client);

        String summaryInput = client.lastPrompt.messages().getFirst().getContent();
        assertFalse(summaryInput.contains("call-boundary"));
        assertFalse(summaryInput.contains("boundary-result"));

        List<Message> after = conversation.getHistoryCopy();
        assertSame(toolCallMessage, after.get(1));
        assertSame(toolResultMessage, after.get(2));
    }

    @Test
    void streamErrorLeavesOriginalHistoryUntouched() {
        ConversationManager conversation = conversationWithMessages(10, 100);
        List<Message> original = conversation.getHistoryCopy();
        FakeLlmClient client = FakeLlmClient.failure("summary unavailable");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> ContextCompactor.compact(conversation, client)
        );

        assertTrue(error.getMessage().contains("summary unavailable"));
        List<Message> after = conversation.getHistoryCopy();
        assertEquals(original, after);
        for (int index = 0; index < original.size(); index++) {
            assertSame(original.get(index), after.get(index));
        }
    }

    @Test
    void summaryTimeoutLeavesOriginalHistoryUntouched() {
        ConversationManager conversation = conversationWithMessages(10, 100);
        List<Message> original = conversation.getHistoryCopy();
        FakeLlmClient client = new FakeLlmClient(List.of());

        assertTimeout(
                Duration.ofMillis(100),
                () -> {
                    IllegalStateException error = assertThrows(
                            IllegalStateException.class,
                            () -> ContextCompactor.compact(
                                    conversation,
                                    client,
                                    Duration.ofMillis(20)
                            )
                    );
                    assertEquals("context summary timed out", error.getMessage());
                }
        );

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
    void extractsTaggedSummaryAndFallsBackToRawText() {
        assertEquals(
                "important state",
                ContextCompactor.extractSummary(
                        "prefix\n<summary>\n important state \n</summary>\nsuffix"
                )
        );
        assertEquals(
                "plain summary",
                ContextCompactor.extractSummary("  plain summary  ")
        );
        assertEquals("", ContextCompactor.extractSummary(null));
    }

    @Test
    void compactionReducesEstimatedMessageTokens() throws Exception {
        ConversationManager conversation = conversationWithMessages(14, 8_000);
        FakeLlmClient client = FakeLlmClient.success(
                "<summary>short durable summary</summary>"
        );

        ContextCompactor.CompactionResult result =
                ContextCompactor.compact(conversation, client);

        assertTrue(result.compacted());
        assertTrue(result.afterTokens() < result.beforeTokens());
        assertTrue(result.removedTokens() > 0);
        assertEquals(
                ContextTokenEstimator.estimateMessages(
                        conversation.getHistoryCopy()
                ),
                result.afterTokens()
        );
    }

    @Test
    void tooFewOldMessagesIsNoOpAndDoesNotCallLlm() throws Exception {
        ConversationManager conversation = conversationWithMessages(9, 100);
        List<Message> original = conversation.getHistoryCopy();
        FakeLlmClient client = FakeLlmClient.success(
                "<summary>must not be used</summary>"
        );

        ContextCompactor.CompactionResult result =
                ContextCompactor.compact(conversation, client);

        assertFalse(result.compacted());
        assertEquals(0, client.streamCalls);
        assertEquals(original, conversation.getHistoryCopy());
        assertEquals(result.beforeMessages(), result.afterMessages());
        assertEquals(result.beforeTokens(), result.afterTokens());
    }

    private static ConversationManager conversationWithMessages(
            int count,
            int payloadCharacters
    ) {
        ConversationManager conversation = new ConversationManager();
        for (int index = 0; index < count; index++) {
            String content = marker(index)
                    + " "
                    + Character.toString('a' + index % 26)
                    .repeat(payloadCharacters);
            conversation.addMessage(new Message(
                    index % 2 == 0 ? "user" : "assistant",
                    content
            ));
        }
        return conversation;
    }

    private static String marker(int index) {
        return "unique-message-marker-" + index;
    }

    private static final class FakeLlmClient implements LLMClient {

        private final List<StreamBlock> response;
        private PromptContent lastPrompt;
        private int streamCalls;

        private FakeLlmClient(List<StreamBlock> response) {
            this.response = List.copyOf(response);
        }

        static FakeLlmClient success(String content) {
            return new FakeLlmClient(List.of(
                    new StreamBlock.ContentDelta(content),
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
            lastPrompt = promptContent;
            return new LinkedBlockingQueue<>(response);
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("compaction must use the streaming API");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
