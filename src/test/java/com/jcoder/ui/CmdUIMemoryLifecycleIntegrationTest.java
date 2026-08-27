package com.jcoder.ui;

import com.jcoder.agent.Agent;
import com.jcoder.command.DefaultCommands;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CmdUIMemoryLifecycleIntegrationTest {

    @TempDir
    Path projectRoot;

    @Test
    void successfulTurnPersistsSessionAndExtractsMemory() throws Exception {
        LifecycleClient client = LifecycleClient.success();
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
            runUi(
                    new CmdUI(DefaultCommands.create(), sessions, memories),
                    agent,
                    conversation,
                    "以后这个项目固定使用 Java 21\nexit\n"
            );
        }

        assertEquals(2, sessionStore.load(sessions.currentSessionId()).size());
        assertEquals(1, client.mainCalls.get());
        assertEquals(1, client.extractionCalls.get());
        List<MemoryEntry> stored = memoryStore.load();
        assertEquals(1, stored.size());
        assertEquals("java.target_version", stored.getFirst().key());
        assertEquals(MemoryEntry.Status.ACTIVE, stored.getFirst().status());
    }

    @Test
    void agentErrorStillPersistsButDoesNotExtractMemory() throws Exception {
        LifecycleClient client = LifecycleClient.failure();
        ConversationManager conversation = new ConversationManager();
        Agent agent = new Agent(client, new ToolRegister(), 128_000, 8_192);
        SessionStore sessionStore = new SessionStore(projectRoot);
        SessionManager sessions = new SessionManager(
                sessionStore, conversation, agent
        );
        MemoryStore memoryStore = new MemoryStore(projectRoot);

        String stderr;
        try (MemoryService memories = new MemoryService(
                memoryStore,
                new MemoryPolicy(),
                new MemoryExtractor(client)
        )) {
            stderr = runUi(
                    new CmdUI(DefaultCommands.create(), sessions, memories),
                    agent,
                    conversation,
                    "记住这个失败回合\nexit\n"
            ).stderr();
        }

        assertTrue(stderr.contains("[Agent 错误]"));
        assertEquals(1, sessionStore.load(sessions.currentSessionId()).size());
        assertEquals("记住这个失败回合",
                sessionStore.load(sessions.currentSessionId())
                        .getFirst().getContent());
        assertEquals(1, client.mainCalls.get());
        assertEquals(0, client.extractionCalls.get());
        assertTrue(memoryStore.load().isEmpty());
    }

    private static CapturedIo runUi(
            CmdUI ui,
            Agent agent,
            ConversationManager conversation,
            String input
    ) {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try {
            System.setIn(new ByteArrayInputStream(
                    input.getBytes(StandardCharsets.UTF_8)
            ));
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            ui.run(agent, conversation);
            return new CapturedIo(
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8)
            );
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record CapturedIo(String stdout, String stderr) {
    }

    private static final class LifecycleClient implements LLMClient {
        private final boolean failMainTurn;
        private final AtomicInteger mainCalls = new AtomicInteger();
        private final AtomicInteger extractionCalls = new AtomicInteger();

        private LifecycleClient(boolean failMainTurn) {
            this.failMainTurn = failMainTurn;
        }

        static LifecycleClient success() {
            return new LifecycleClient(false);
        }

        static LifecycleClient failure() {
            return new LifecycleClient(true);
        }

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            if (promptContent.system().contains("extract durable memory")) {
                extractionCalls.incrementAndGet();
                return new LinkedBlockingQueue<>(List.of(
                        new StreamBlock.ContentDelta("""
                                <memories-json>
                                {"memories":[{
                                  "scope":"PROJECT",
                                  "category":"CONSTRAINT",
                                  "key":"java.target_version",
                                  "content":"Use Java 21 for this project.",
                                  "confidence":0.99
                                }]}
                                </memories-json>
                                """),
                        new StreamBlock.StreamEnd("stop")
                ));
            }

            mainCalls.incrementAndGet();
            if (failMainTurn) {
                return new LinkedBlockingQueue<>(List.of(
                        new StreamBlock.StreamError("simulated upstream failure")
                ));
            }
            return new LinkedBlockingQueue<>(List.of(
                    new StreamBlock.ContentDelta("understood"),
                    new StreamBlock.StreamEnd("stop")
            ));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("UI lifecycle must use stream()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
