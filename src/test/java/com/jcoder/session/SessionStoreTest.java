package com.jcoder.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @TempDir
    Path projectRoot;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roundTripsOrdinaryMessagesAndCreatesExpectedFile() throws Exception {
        SessionStore store = new SessionStore(projectRoot);
        String sessionId = "session-basic";

        SessionStore.SessionInfo info = store.save(sessionId, List.of(
                new Message("user", "  first\nrequest  "),
                new Message("assistant", "answer")
        ));

        List<Message> restored = store.load(sessionId);
        assertEquals(2, restored.size());
        assertMessage(restored.get(0), "user", "  first\nrequest  ");
        assertMessage(restored.get(1), "assistant", "answer");
        assertEquals("first request", info.preview());
        assertEquals(2, info.messageCount());
        assertEquals(sessionId, info.id());
        assertTrue(info.createdAt() > 0);
        assertEquals(info.createdAt(), info.updatedAt());
        assertTrue(store.exists(sessionId));
        assertTrue(Files.isRegularFile(sessionFile(sessionId)));
    }

    @Test
    void roundTripsToolCallsAndToolResults() throws Exception {
        SessionStore store = new SessionStore(projectRoot);
        Message toolCallMessage = new Message("assistant", "");
        toolCallMessage.setToolCalls(List.of(new ToolCallBlock(
                "call-42",
                "function",
                "ReadFile",
                Map.of(
                        "file_path", "pom.xml",
                        "options", Map.of("start", 3, "end", 8)
                )
        )));
        Message toolResultMessage = new Message("tool", "");
        toolResultMessage.setToolResults(List.of(new ToolResult(
                "call-42",
                "<project>...</project>",
                false
        )));

        store.save("session-tools", List.of(toolCallMessage, toolResultMessage));
        List<Message> restored = store.load("session-tools");

        ToolCallBlock call = restored.get(0).getToolCalls().getFirst();
        assertEquals("call-42", call.toolId());
        assertEquals("function", call.type());
        assertEquals("ReadFile", call.toolName());
        assertEquals("pom.xml", call.params().get("file_path"));
        assertEquals(Map.of("start", 3, "end", 8), call.params().get("options"));

        ToolResult result = restored.get(1).getToolResults().getFirst();
        assertEquals("call-42", result.toolId());
        assertEquals("<project>...</project>", result.content());
        assertFalse(result.isError());
    }

    @Test
    void secondSaveOverwritesSnapshotAndPreservesCreatedAt() throws Exception {
        SessionStore store = new SessionStore(projectRoot);
        SessionStore.SessionInfo first = store.save(
                "session-overwrite",
                List.of(new Message("user", "old"))
        );
        waitUntilAfter(first.updatedAt());

        SessionStore.SessionInfo second = store.save(
                "session-overwrite",
                List.of(
                        new Message("user", "new"),
                        new Message("assistant", "new answer")
                )
        );

        assertEquals(first.createdAt(), second.createdAt());
        assertTrue(second.updatedAt() > first.updatedAt());
        assertEquals(2, second.messageCount());
        List<Message> restored = store.load("session-overwrite");
        assertEquals(2, restored.size());
        assertEquals("new", restored.getFirst().getContent());
        assertNotEquals("old", restored.getFirst().getContent());
    }

    @Test
    void listSessionsIsNewestFirstAndIgnoresUnrelatedFiles() throws Exception {
        SessionStore store = new SessionStore(projectRoot);
        SessionStore.SessionInfo older = store.save(
                "session-older",
                List.of(new Message("user", "older"))
        );
        waitUntilAfter(older.updatedAt());
        SessionStore.SessionInfo newer = store.save(
                "session-newer",
                List.of(new Message("user", "newer"))
        );
        Files.writeString(sessionFile("ignored").resolveSibling("notes.txt"), "ignore");
        Files.writeString(sessionFile("ignored.bad.id"), "{}");

        List<SessionStore.SessionInfo> sessions = store.listSessions();

        assertEquals(List.of("session-newer", "session-older"),
                sessions.stream().map(SessionStore.SessionInfo::id).toList());
        assertEquals("newer", sessions.getFirst().preview());
        assertEquals(newer.updatedAt(), sessions.getFirst().updatedAt());
    }

    @Test
    void listSessionsReturnsEmptyWhenDirectoryDoesNotExist() throws Exception {
        SessionStore store = new SessionStore(projectRoot);

        assertTrue(store.listSessions().isEmpty());
    }

    @Test
    void rejectsInvalidIdsAndPathTraversal() {
        SessionStore store = new SessionStore(projectRoot);
        List<String> invalidIds = List.of(
                "../outside",
                "..\\outside",
                ".hidden",
                "has.dot",
                "has/slash",
                "has space",
                "",
                "a".repeat(97)
        );

        for (String id : invalidIds) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.save(id, List.of()), id);
            assertThrows(IllegalArgumentException.class,
                    () -> store.load(id), id);
            assertThrows(IllegalArgumentException.class,
                    () -> store.exists(id), id);
        }
        assertThrows(IllegalArgumentException.class,
                () -> store.load(null));
        assertFalse(Files.exists(projectRoot.resolve("outside.json")));
    }

    @Test
    void loadReportsMissingSession() {
        SessionStore store = new SessionStore(projectRoot);

        IOException error = assertThrows(
                IOException.class,
                () -> store.load("session-missing")
        );

        assertTrue(error.getMessage().contains("Session not found"));
    }

    @Test
    void loadReportsMalformedJson() throws Exception {
        SessionStore store = new SessionStore(projectRoot);
        Files.createDirectories(sessionFile("session-broken").getParent());
        Files.writeString(sessionFile("session-broken"), "{ definitely not json");

        IOException error = assertThrows(
                IOException.class,
                () -> store.load("session-broken")
        );

        assertTrue(error.getMessage().contains("Failed to read session 'session-broken'"));
    }

    @Test
    void loadRejectsUnsupportedFormatVersion() throws Exception {
        SessionStore store = new SessionStore(projectRoot);
        store.save("session-version", List.of(new Message("user", "hello")));
        JsonNode document = objectMapper.readTree(sessionFile("session-version").toFile());
        ((ObjectNode) document).put("formatVersion", 2);
        objectMapper.writeValue(sessionFile("session-version").toFile(), document);

        IOException error = assertThrows(
                IOException.class,
                () -> store.load("session-version")
        );

        assertTrue(error.getMessage().contains("unsupported format version: 2"));
    }

    @Test
    void loadRejectsDocumentWhoseIdDoesNotMatchFilename() throws Exception {
        SessionStore store = new SessionStore(projectRoot);
        store.save("session-original", List.of(new Message("user", "hello")));
        Files.copy(sessionFile("session-original"), sessionFile("session-copy"));

        IOException error = assertThrows(
                IOException.class,
                () -> store.load("session-copy")
        );

        assertTrue(error.getMessage().contains("document id does not match filename"));
    }

    @Test
    void saveRejectsNullMessagesAndNullElements() {
        SessionStore store = new SessionStore(projectRoot);

        assertThrows(NullPointerException.class,
                () -> store.save("session-null-list", null));
        assertThrows(NullPointerException.class,
                () -> store.save("session-null-message", java.util.Arrays.asList(
                        new Message("user", "ok"), null
                )));
    }

    private Path sessionFile(String sessionId) {
        return projectRoot.resolve(".mycoder/sessions/" + sessionId + ".json");
    }

    private static void waitUntilAfter(long timestamp) {
        while (System.currentTimeMillis() <= timestamp) {
            Thread.onSpinWait();
        }
    }

    private static void assertMessage(Message message, String role, String content) {
        assertEquals(role, message.getRole());
        assertEquals(content, message.getContent());
    }
}
