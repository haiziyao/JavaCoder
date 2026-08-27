package com.jcoder.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.agent.Agent;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.memory.MemoryCandidate;
import com.jcoder.memory.MemoryEntry;
import com.jcoder.memory.MemoryExtractor;
import com.jcoder.memory.MemoryPolicy;
import com.jcoder.memory.MemoryService;
import com.jcoder.memory.MemoryStore;
import com.jcoder.message.ConversationManager;
import com.jcoder.prompt.PromptContent;
import com.jcoder.session.SessionManager;
import com.jcoder.session.SessionStore;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRunCoordinatorMemoryIntegrationTest {

    @TempDir
    Path projectRoot;

    @Test
    void webRunRecallsMemoryThenPersistsSessionAndExtractsAfterSuccess()
            throws Exception {
        WebLifecycleClient client = new WebLifecycleClient();
        ConversationManager conversation = new ConversationManager();
        Agent agent = new Agent(client, new ToolRegister(), 128_000, 8_192);
        SessionStore sessionStore = new SessionStore(projectRoot);
        SessionManager sessions = new SessionManager(
                sessionStore, conversation, agent
        );
        MemoryStore memoryStore = new MemoryStore(projectRoot);

        try (MemoryService memories = new MemoryService(
                memoryStore,
                new MemoryPolicy(),
                new MemoryExtractor(client)
        )) {
            memories.remember(
                    "seed-session",
                    List.of(new MemoryCandidate(
                            MemoryEntry.Scope.PROJECT,
                            MemoryEntry.Category.CONSTRAINT,
                            "java.target_version",
                            "Use Java 21 for this project.",
                            0.99
                    ))
            );

            WebRunCoordinator coordinator = new WebRunCoordinator(
                    agent,
                    conversation,
                    new ObjectMapper(),
                    sessions,
                    memories
            );

            WebRunCoordinator.RunSnapshot run = coordinator.startUserRun(
                    "continue the Java implementation"
            );
            awaitTerminal(coordinator, run.runId());
        }

        assertEquals(1, client.mainCalls.get());
        assertEquals(1, client.extractionCalls.get());
        assertNotNull(client.mainPrompt);
        assertTrue(client.mainPrompt.messages().stream().anyMatch(message ->
                message.getContent() != null
                        && message.getContent().contains("java.target_version")
                        && message.getContent().contains("Use Java 21")));
        assertEquals(2, sessionStore.load(sessions.currentSessionId()).size());
        assertEquals(1, memoryStore.load().size(),
                "empty extraction output must leave the recalled memory intact");
    }

    private static void awaitTerminal(
            WebRunCoordinator coordinator,
            String runId
    ) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!coordinator.isTerminal(runId)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("web run did not finish within 5 seconds");
            }
            Thread.sleep(10);
        }
        assertEquals(
                WebRunCoordinator.RunState.COMPLETED,
                coordinator.currentSnapshot().state()
        );
    }

    private static final class WebLifecycleClient implements LLMClient {
        private final AtomicInteger mainCalls = new AtomicInteger();
        private final AtomicInteger extractionCalls = new AtomicInteger();
        private volatile PromptContent mainPrompt;

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            if (promptContent.system().contains("extract durable memory")) {
                extractionCalls.incrementAndGet();
                return new LinkedBlockingQueue<>(List.of(
                        new StreamBlock.ContentDelta(
                                "<memories-json>{\"memories\":[]}</memories-json>"
                        ),
                        new StreamBlock.StreamEnd("stop")
                ));
            }

            mainCalls.incrementAndGet();
            mainPrompt = promptContent;
            return new LinkedBlockingQueue<>(List.of(
                    new StreamBlock.ContentDelta("web answer"),
                    new StreamBlock.StreamEnd("stop")
            ));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("web lifecycle must use stream()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
