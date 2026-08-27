package com.jcoder.memory;

import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.Message;
import com.jcoder.prompt.PromptContent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractorTest {

    @Test
    void extractionRequestHasNoToolsAndParsesTaggedJson() throws Exception {
        FakeLlmClient client = FakeLlmClient.respondingWith(
                new StreamBlock.ContentDelta("prefix<memories-json>"),
                new StreamBlock.ContentDelta("""
                        {
                          "memories": [
                            {
                              "scope": "project",
                              "category": "constraint",
                              "key": "java.target_version",
                              "content": "Use Java 21.",
                              "confidence": 0.98
                            }
                          ]
                        }
                        """),
                new StreamBlock.ContentDelta("</memories-json>suffix"),
                new StreamBlock.StreamEnd("stop")
        );
        MemoryExtractor extractor = new MemoryExtractor(client);

        List<MemoryCandidate> candidates = extractor.extract(List.of(
                new Message("user", "This project must use Java 21."),
                new Message("tool", "untrusted raw tool output"),
                new Message("assistant", "Understood.")
        ));

        assertEquals(1, client.streamCalls);
        assertTrue(client.lastPrompt.tools().isEmpty());
        assertTrue(client.lastPrompt.system().contains("Do not call tools"));
        String transcript = client.lastPrompt.messages().getFirst().getContent();
        assertTrue(transcript.contains("This project must use Java 21."));
        assertTrue(transcript.contains("Understood."));
        assertFalse(transcript.contains("untrusted raw tool output"));
        assertEquals(List.of(new MemoryCandidate(
                MemoryEntry.Scope.PROJECT,
                MemoryEntry.Category.CONSTRAINT,
                "java.target_version",
                "Use Java 21.",
                0.98
        )), candidates);
    }

    @Test
    void fallsBackToFirstAndLastJsonBracesWhenTagsAreAbsent() throws Exception {
        FakeLlmClient client = FakeLlmClient.success("""
                Here is the result:
                {"memories":[{
                  "scope":"USER",
                  "category":"PREFERENCE",
                  "key":"workflow.style",
                  "content":"Prefer concise answers.",
                  "confidence":0.9
                }]}
                Done.
                """);

        List<MemoryCandidate> candidates = new MemoryExtractor(client).extract(
                List.of(new Message("user", "I prefer concise answers."))
        );

        assertEquals(1, candidates.size());
        assertEquals(MemoryEntry.Scope.USER, candidates.getFirst().scope());
        assertEquals(MemoryEntry.Category.PREFERENCE,
                candidates.getFirst().category());
    }

    @Test
    void emptyMemoriesArrayProducesNoCandidates() throws Exception {
        FakeLlmClient client = FakeLlmClient.success(
                "<memories-json>{\"memories\":[]}</memories-json>"
        );

        List<MemoryCandidate> candidates = new MemoryExtractor(client).extract(
                List.of(new Message("user", "hello"))
        );

        assertTrue(candidates.isEmpty());
    }

    @Test
    void malformedCandidateIsSkippedWhileValidCandidateSurvives() throws Exception {
        FakeLlmClient client = FakeLlmClient.success("""
                <memories-json>
                {"memories":[
                  {"scope":"wrong","category":"FACT","key":"bad","content":"bad","confidence":0.9},
                  {"scope":"PROJECT","category":"DECISION","key":"build.tool","content":"Use Maven.","confidence":0.88}
                ]}
                </memories-json>
                """);

        List<MemoryCandidate> candidates = new MemoryExtractor(client).extract(
                List.of(new Message("user", "Use Maven."))
        );

        assertEquals(1, candidates.size());
        assertEquals("build.tool", candidates.getFirst().key());
    }

    @Test
    void badJsonIsRejected() {
        FakeLlmClient client = FakeLlmClient.success(
                "<memories-json>{not-json}</memories-json>"
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new MemoryExtractor(client).extract(
                        List.of(new Message("user", "remember this"))
                )
        );

        assertEquals("Invalid memory extraction JSON", error.getMessage());
    }

    @Test
    void toolCallIsRejected() {
        FakeLlmClient client = FakeLlmClient.respondingWith(
                new StreamBlock.ToolCall(
                        "call-1",
                        "read_file",
                        "function",
                        Map.of("path", "pom.xml")
                )
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new MemoryExtractor(client).extract(
                        List.of(new Message("user", "remember Java 21"))
                )
        );

        assertTrue(error.getMessage().contains("unexpectedly called tool"));
        assertTrue(error.getMessage().contains("read_file"));
    }

    @Test
    void streamErrorIsRejected() {
        FakeLlmClient client = FakeLlmClient.respondingWith(
                new StreamBlock.StreamError("upstream unavailable")
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new MemoryExtractor(client).extract(
                        List.of(new Message("user", "remember Java 21"))
                )
        );

        assertEquals(
                "memory extraction failed: upstream unavailable",
                error.getMessage()
        );
    }

    @Test
    void timeoutIsRejectedWithinBoundedTime() {
        FakeLlmClient client = FakeLlmClient.respondingWith();
        MemoryExtractor extractor = new MemoryExtractor(client);

        assertTimeout(Duration.ofMillis(250), () -> {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> extractor.extract(
                            List.of(new Message("user", "remember Java 21")),
                            Duration.ofMillis(20)
                    )
            );
            assertEquals("memory extraction timed out", error.getMessage());
        });
    }

    @Test
    void transcriptWithoutUserOrAssistantContentDoesNotCallLlm() throws Exception {
        FakeLlmClient client = FakeLlmClient.success(
                "<memories-json>{\"memories\":[]}</memories-json>"
        );

        List<MemoryCandidate> result = new MemoryExtractor(client).extract(
                List.of(new Message("tool", "raw result"))
        );

        assertTrue(result.isEmpty());
        assertEquals(0, client.streamCalls);
    }

    private static final class FakeLlmClient implements LLMClient {

        private final List<StreamBlock> response;
        private PromptContent lastPrompt;
        private int streamCalls;

        private FakeLlmClient(List<StreamBlock> response) {
            this.response = List.copyOf(response);
        }

        static FakeLlmClient respondingWith(StreamBlock... response) {
            return new FakeLlmClient(List.of(response));
        }

        static FakeLlmClient success(String content) {
            return respondingWith(
                    new StreamBlock.ContentDelta(content),
                    new StreamBlock.StreamEnd("stop")
            );
        }

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            streamCalls++;
            lastPrompt = promptContent;
            BlockingQueue<StreamBlock> queue = new LinkedBlockingQueue<>();
            queue.addAll(response);
            return queue;
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("memory extraction must use streaming API");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
