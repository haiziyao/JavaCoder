package com.jcoder.command;

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
import java.util.List;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceCommandsIntegrationTest {

    @TempDir
    Path projectRoot;

    @Test
    void sessionAndMemoryFailSafelyWhenManagersAreMissing() throws Exception {
        Fixture fixture = fixture();
        SlashCommandRegistry registry = DefaultCommands.create();
        CommandContext context = new CommandContext(
                fixture.agent(), fixture.conversation()
        );

        CommandResult session = registry.execute("/session list", context);
        CommandResult memory = registry.execute("/memory list", context);

        assertFalse(session.success());
        assertEquals("SessionManager 未装配", session.output());
        assertEquals(CommandResult.Delivery.LOCAL, session.delivery());
        assertFalse(memory.success());
        assertEquals("MemoryService 未装配", memory.output());
        assertEquals(CommandResult.Delivery.LOCAL, memory.delivery());
        assertEquals(0, fixture.client().streamCalls);
        assertEquals(0, fixture.conversation().size());
    }

    @Test
    void sessionCommandsSaveListCreateAndResumeWithoutSubmittingPrompt()
            throws Exception {
        Fixture fixture = fixture();
        SessionStore store = new SessionStore(projectRoot);
        SessionManager sessions = new SessionManager(
                store, fixture.conversation(), fixture.agent()
        );
        CommandContext context = new CommandContext(
                fixture.agent(), fixture.conversation(), sessions, null
        );
        SlashCommandRegistry registry = DefaultCommands.create();

        String originalId = sessions.currentSessionId();
        fixture.conversation().addUserMsg("original session message");
        fixture.agent().setLongTermMemoryReminder("temporary reminder");

        CommandResult save = registry.execute("/session save", context);
        CommandResult list = registry.execute("/session list", context);
        CommandResult create = registry.execute("/session new", context);
        String newId = sessions.currentSessionId();

        assertLocalSuccess(save);
        assertTrue(save.output().contains(originalId));
        assertLocalSuccess(list);
        assertTrue(list.output().contains(originalId));
        assertLocalSuccess(create);
        assertFalse(originalId.equals(newId));
        assertEquals(0, fixture.conversation().size());
        assertEquals("", fixture.agent().getLongTermMemoryReminder());

        fixture.conversation().addUserMsg("new session message");
        CommandResult resume = registry.execute(
                "/session resume " + originalId,
                context
        );

        assertLocalSuccess(resume);
        assertEquals(originalId, sessions.currentSessionId());
        assertEquals(1, fixture.conversation().size());
        assertEquals("original session message",
                fixture.conversation().getHistoryCopy().getFirst().getContent());
        assertTrue(store.exists(newId),
                "resuming another session must first save the current one");
        assertEquals("new session message",
                store.load(newId).getFirst().getContent());
        assertEquals(0, fixture.client().streamCalls,
                "LOCAL session commands must never call the model");
    }

    @Test
    void memoryCommandsListDeleteAndClearByScopeWithoutEnteringHistory()
            throws Exception {
        Fixture fixture = fixture();
        MemoryStore store = new MemoryStore(projectRoot);
        try (MemoryService memories = new MemoryService(
                store,
                new MemoryPolicy(),
                new MemoryExtractor(fixture.client())
        )) {
            MemoryPolicy.GovernanceResult seeded = memories.remember(
                    "session-source",
                    List.of(
                            new MemoryCandidate(
                                    MemoryEntry.Scope.PROJECT,
                                    MemoryEntry.Category.CONSTRAINT,
                                    "java.target_version",
                                    "Use Java 21 for this project.",
                                    0.99
                            ),
                            new MemoryCandidate(
                                    MemoryEntry.Scope.USER,
                                    MemoryEntry.Category.PREFERENCE,
                                    "workflow.test_ownership",
                                    "The assistant writes tests.",
                                    0.95
                            )
                    )
            );
            String projectMemoryId = seeded.memories().stream()
                    .filter(memory -> memory.scope() == MemoryEntry.Scope.PROJECT)
                    .findFirst()
                    .orElseThrow()
                    .id();

            fixture.conversation().addUserMsg("sentinel history message");
            CommandContext context = new CommandContext(
                    fixture.agent(), fixture.conversation(), null, memories
            );
            SlashCommandRegistry registry = DefaultCommands.create();

            CommandResult list = registry.execute("/memory list", context);
            assertLocalSuccess(list);
            assertTrue(list.output().contains("java.target_version"));
            assertTrue(list.output().contains("workflow.test_ownership"));

            CommandResult deleted = registry.execute(
                    "/memory delete " + projectMemoryId,
                    context
            );
            assertLocalSuccess(deleted);
            assertFalse(registry.execute("/memory list", context)
                    .output().contains(projectMemoryId));
            assertTrue(registry.execute("/memory list all", context)
                    .output().contains("DELETED"));

            CommandResult clearUser = registry.execute(
                    "/memory clear user",
                    context
            );
            assertLocalSuccess(clearUser);
            assertTrue(clearUser.output().contains("1 条"));
            assertTrue(memories.list(false).isEmpty());

            assertEquals(1, fixture.conversation().size());
            assertEquals("sentinel history message",
                    fixture.conversation().getHistoryCopy().getFirst().getContent());
            assertEquals(0, fixture.client().streamCalls,
                    "memory governance commands are local file operations");
        }
    }

    private static void assertLocalSuccess(CommandResult result) {
        assertTrue(result.success(), result.output());
        assertEquals(CommandResult.Delivery.LOCAL, result.delivery());
        assertFalse(result.shouldSubmitPrompt());
    }

    private Fixture fixture() {
        CountingClient client = new CountingClient();
        Agent agent = new Agent(client, new ToolRegister(), 128_000, 8_192);
        return new Fixture(client, agent, new ConversationManager());
    }

    private record Fixture(
            CountingClient client,
            Agent agent,
            ConversationManager conversation
    ) {
    }

    private static final class CountingClient implements LLMClient {
        private int streamCalls;

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            streamCalls++;
            throw new AssertionError("LOCAL persistence command reached the model");
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("LOCAL persistence command reached request()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
