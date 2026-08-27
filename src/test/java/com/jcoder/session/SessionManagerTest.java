package com.jcoder.session;

import com.jcoder.agent.Agent;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @TempDir
    Path projectRoot;

    @Test
    void constructorCreatesIdentityAndSynchronizesAgent() {
        Fixture fixture = fixture();

        assertTrue(fixture.manager.currentSessionId().startsWith("session-"));
        assertEquals(fixture.manager.currentSessionId(), fixture.agent.getSessionId());
        assertEquals(0, fixture.conversation.size());
    }

    @Test
    void savePersistsCurrentConversation() throws Exception {
        Fixture fixture = fixture();
        fixture.conversation.addUserMsg("persist me");

        SessionStore.SessionInfo info = fixture.manager.save();

        assertEquals(fixture.manager.currentSessionId(), info.id());
        assertEquals("persist me",
                fixture.store.load(info.id()).getFirst().getContent());
    }

    @Test
    void newSessionSavesOldHistoryThenClearsRuntimeState() throws Exception {
        Fixture fixture = fixture();
        String oldId = fixture.manager.currentSessionId();
        fixture.conversation.addUserMsg("old conversation");
        fixture.agent.setLongTermMemoryReminder("remembered reminder");

        String newId = fixture.manager.newSession();

        assertNotEquals(oldId, newId);
        assertEquals(newId, fixture.manager.currentSessionId());
        assertEquals(newId, fixture.agent.getSessionId());
        assertEquals("", fixture.agent.getLongTermMemoryReminder());
        assertEquals(0, fixture.conversation.size());
        assertEquals("old conversation",
                fixture.store.load(oldId).getFirst().getContent());
    }

    @Test
    void resumeSavesCurrentSessionAndRestoresTargetSession() throws Exception {
        Fixture fixture = fixture();
        String firstId = fixture.manager.currentSessionId();
        fixture.conversation.addUserMsg("first session");
        fixture.manager.save();

        String secondId = fixture.manager.newSession();
        fixture.conversation.addUserMsg("second session");
        fixture.agent.setLongTermMemoryReminder("temporary reminder");

        int restoredCount = fixture.manager.resume(firstId);

        assertEquals(1, restoredCount);
        assertEquals(firstId, fixture.manager.currentSessionId());
        assertEquals(firstId, fixture.agent.getSessionId());
        assertEquals("", fixture.agent.getLongTermMemoryReminder());
        assertEquals("first session",
                fixture.conversation.getHistoryCopy().getFirst().getContent());
        assertEquals("second session",
                fixture.store.load(secondId).getFirst().getContent());
    }

    @Test
    void resumingCurrentSessionReloadsSavedSnapshotWithoutSavingUnsavedChanges()
            throws Exception {
        Fixture fixture = fixture();
        String currentId = fixture.manager.currentSessionId();
        fixture.conversation.addUserMsg("saved");
        fixture.manager.save();
        fixture.conversation.addAssistantMsg("unsaved");

        int restoredCount = fixture.manager.resume(currentId);

        assertEquals(1, restoredCount);
        assertEquals(1, fixture.conversation.size());
        assertEquals("saved", fixture.conversation.getHistoryCopy().getFirst().getContent());
    }

    @Test
    void newSessionSaveFailurePreservesIdentityHistoryAndReminder() throws Exception {
        Fixture fixture = fixture();
        String originalId = fixture.manager.currentSessionId();
        Message originalMessage = new Message("user", "must remain");
        fixture.conversation.addMessage(originalMessage);
        fixture.agent.setLongTermMemoryReminder("must remain too");
        blockCurrentSessionPath(fixture, originalId);

        assertThrows(IOException.class, fixture.manager::newSession);

        assertEquals(originalId, fixture.manager.currentSessionId());
        assertEquals(originalId, fixture.agent.getSessionId());
        assertEquals("must remain too", fixture.agent.getLongTermMemoryReminder());
        assertEquals(List.of(originalMessage), fixture.conversation.getHistoryCopy());
    }

    @Test
    void resumeMissingTargetPreservesCurrentState() {
        Fixture fixture = fixture();
        String originalId = fixture.manager.currentSessionId();
        Message originalMessage = new Message("user", "must remain");
        fixture.conversation.addMessage(originalMessage);
        fixture.agent.setLongTermMemoryReminder("still active");

        assertThrows(IOException.class,
                () -> fixture.manager.resume("session-missing"));

        assertEquals(originalId, fixture.manager.currentSessionId());
        assertEquals(originalId, fixture.agent.getSessionId());
        assertEquals("still active", fixture.agent.getLongTermMemoryReminder());
        assertEquals(List.of(originalMessage), fixture.conversation.getHistoryCopy());
    }

    @Test
    void resumePreservesCurrentStateWhenSavingCurrentSessionFails() throws Exception {
        Fixture fixture = fixture();
        String originalId = fixture.manager.currentSessionId();
        Message originalMessage = new Message("user", "current unsaved state");
        fixture.conversation.addMessage(originalMessage);
        fixture.agent.setLongTermMemoryReminder("still active");
        fixture.store.save("session-target", List.of(
                new Message("user", "target state")
        ));
        blockCurrentSessionPath(fixture, originalId);

        assertThrows(IOException.class,
                () -> fixture.manager.resume("session-target"));

        assertEquals(originalId, fixture.manager.currentSessionId());
        assertEquals(originalId, fixture.agent.getSessionId());
        assertEquals("still active", fixture.agent.getLongTermMemoryReminder());
        assertEquals(List.of(originalMessage), fixture.conversation.getHistoryCopy());
    }

    private void blockCurrentSessionPath(Fixture fixture, String sessionId)
            throws IOException {
        Path sessionPath = projectRoot.resolve(
                ".mycoder/sessions/" + sessionId + ".json"
        );
        Files.createDirectories(sessionPath);
    }

    private Fixture fixture() {
        SessionStore store = new SessionStore(projectRoot);
        ConversationManager conversation = new ConversationManager();
        Agent agent = new Agent(
                new NeverCalledLlmClient(),
                new ToolRegister(),
                128_000,
                8_192
        );
        SessionManager manager = new SessionManager(store, conversation, agent);
        return new Fixture(store, conversation, agent, manager);
    }

    private record Fixture(
            SessionStore store,
            ConversationManager conversation,
            Agent agent,
            SessionManager manager
    ) {
    }

    private static final class NeverCalledLlmClient implements LLMClient {

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            throw new AssertionError("session lifecycle must not call the model");
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("session lifecycle must not call the model");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
