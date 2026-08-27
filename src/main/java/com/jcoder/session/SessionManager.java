package com.jcoder.session;

import com.jcoder.agent.Agent;
import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class SessionManager {

    private final SessionStore store;
    private final ConversationManager conversation;
    private final Agent agent;

    private String currentSessionId;

    public SessionManager(
            SessionStore store,
            ConversationManager conversation,
            Agent agent
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.agent = Objects.requireNonNull(agent, "agent");

        this.currentSessionId = store.newSessionId();
        agent.setSessionId(currentSessionId);
    }

    public synchronized String currentSessionId() {
        return currentSessionId;
    }

    public synchronized SessionStore.SessionInfo save() throws IOException {
        return store.save(currentSessionId, conversation.getHistoryCopy());
    }

    public synchronized String newSession() throws IOException {
        save();

        conversation.clear();
        agent.resetContextManagement();
        agent.setLongTermMemoryReminder("");

        currentSessionId = store.newSessionId();
        agent.setSessionId(currentSessionId);
        return currentSessionId;
    }

    public synchronized int resume(String sessionId) throws IOException {
        List<Message> restored = store.load(sessionId);

        if (!sessionId.equals(currentSessionId)) {
            save();
        }

        conversation.replaceHistory(restored);
        currentSessionId = sessionId;
        agent.setSessionId(sessionId);
        agent.resetContextManagement();
        agent.setLongTermMemoryReminder("");

        return restored.size();
    }

    public synchronized List<SessionStore.SessionInfo> listSessions()
            throws IOException {
        return store.listSessions();
    }
}