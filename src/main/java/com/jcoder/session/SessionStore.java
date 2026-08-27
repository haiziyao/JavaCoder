package com.jcoder.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SessionStore {

    private static final int FORMAT_VERSION = 1;
    private static final Pattern VALID_SESSION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,95}");

    private final ObjectMapper objectMapper;
    private final Path sessionsDirectory;

    public SessionStore(Path projectRoot) {
        this(projectRoot, new ObjectMapper());
    }

    SessionStore(Path projectRoot, ObjectMapper objectMapper) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");

        this.sessionsDirectory = projectRoot
                .toAbsolutePath()
                .normalize()
                .resolve(".mycoder")
                .resolve("sessions")
                .normalize();
    }

    public String newSessionId() {
        String randomPart = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8);

        return "session-" + Instant.now().toEpochMilli() + "-" + randomPart;
    }

    public SessionInfo save(String sessionId, List<Message> messages)
            throws IOException {
        String validId = requireValidSessionId(sessionId);
        Objects.requireNonNull(messages, "messages");

        List<StoredMessage> storedMessages = messages.stream()
                .map(StoredMessage::from)
                .toList();

        Files.createDirectories(sessionsDirectory);
        Path sessionFile = sessionPath(validId);
        long now = Instant.now().toEpochMilli();
        long createdAt = now;

        if (Files.exists(sessionFile)) {
            createdAt = readDocument(sessionFile, validId).createdAt();
        }

        SessionDocument document = new SessionDocument(
                FORMAT_VERSION,
                validId,
                createdAt,
                now,
                storedMessages
        );

        writeAtomically(sessionFile, document);
        return toSessionInfo(document);
    }

    public List<Message> load(String sessionId) throws IOException {
        String validId = requireValidSessionId(sessionId);
        Path sessionFile = sessionPath(validId);

        if (!Files.isRegularFile(sessionFile)) {
            throw new IOException("Session not found: " + validId);
        }

        SessionDocument document = readDocument(sessionFile, validId);
        List<Message> messages = new ArrayList<>(document.messages().size());

        for (StoredMessage stored : document.messages()) {
            messages.add(stored.toMessage());
        }

        return List.copyOf(messages);
    }

    public List<SessionInfo> listSessions() throws IOException {
        if (!Files.isDirectory(sessionsDirectory)) {
            return List.of();
        }

        List<SessionInfo> sessions = new ArrayList<>();

        try (Stream<Path> files = Files.list(sessionsDirectory)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }

                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".json")) {
                    continue;
                }

                String sessionId = fileName.substring(
                        0,
                        fileName.length() - ".json".length()
                );

                if (!VALID_SESSION_ID.matcher(sessionId).matches()) {
                    continue;
                }

                sessions.add(toSessionInfo(readDocument(file, sessionId)));
            }
        }

        sessions.sort(
                Comparator.comparingLong(SessionInfo::updatedAt).reversed()
        );
        return List.copyOf(sessions);
    }

    public boolean exists(String sessionId) {
        return Files.isRegularFile(
                sessionPath(requireValidSessionId(sessionId))
        );
    }

    private void writeAtomically(Path target, SessionDocument document)
            throws IOException {
        Path temporaryFile = Files.createTempFile(
                sessionsDirectory,
                "." + document.id() + "-",
                ".tmp"
        );

        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporaryFile.toFile(), document);

            try {
                Files.move(
                        temporaryFile,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(
                        temporaryFile,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private SessionDocument readDocument(Path file, String expectedId)
            throws IOException {
        SessionDocument document;

        try {
            document = objectMapper.readValue(file.toFile(), SessionDocument.class);
        } catch (IOException e) {
            throw new IOException(
                    "Failed to read session '" + expectedId + "' from " + file,
                    e
            );
        }

        if (document == null) {
            throw invalidSession(expectedId, "empty document");
        }
        if (document.formatVersion() != FORMAT_VERSION) {
            throw invalidSession(
                    expectedId,
                    "unsupported format version: " + document.formatVersion()
            );
        }
        if (!expectedId.equals(document.id())) {
            throw invalidSession(expectedId, "document id does not match filename");
        }
        if (document.createdAt() <= 0 || document.updatedAt() <= 0) {
            throw invalidSession(expectedId, "invalid timestamps");
        }
        if (document.messages() == null) {
            throw invalidSession(expectedId, "messages must not be null");
        }

        for (StoredMessage message : document.messages()) {
            if (message == null
                    || message.role() == null
                    || message.role().isBlank()) {
                throw invalidSession(expectedId, "message role must not be blank");
            }
            if (message.toolCalls() == null || message.toolResults() == null) {
                throw invalidSession(expectedId, "tool collections must not be null");
            }
        }

        return document;
    }

    private IOException invalidSession(String sessionId, String reason) {
        return new IOException("Invalid session '" + sessionId + "': " + reason);
    }

    private Path sessionPath(String sessionId) {
        Path path = sessionsDirectory.resolve(sessionId + ".json").normalize();

        if (!path.startsWith(sessionsDirectory)) {
            throw new IllegalArgumentException(
                    "Session path escapes session directory"
            );
        }
        return path;
    }

    private String requireValidSessionId(String sessionId) {
        if (sessionId == null
                || !VALID_SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid session id: " + sessionId);
        }
        return sessionId;
    }

    private SessionInfo toSessionInfo(SessionDocument document) {
        return new SessionInfo(
                document.id(),
                document.messages().size(),
                findPreview(document.messages()),
                document.createdAt(),
                document.updatedAt()
        );
    }

    private String findPreview(List<StoredMessage> messages) {
        for (StoredMessage message : messages) {
            if ("user".equals(message.role())
                    && message.content() != null
                    && !message.content().isBlank()) {
                return shorten(message.content());
            }
        }
        for (StoredMessage message : messages) {
            if (message.content() != null && !message.content().isBlank()) {
                return shorten(message.content());
            }
        }
        return "";
    }

    private String shorten(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80
                ? normalized
                : normalized.substring(0, 77) + "...";
    }

    private record SessionDocument(
            int formatVersion,
            String id,
            long createdAt,
            long updatedAt,
            List<StoredMessage> messages
    ) {
    }

    private record StoredMessage(
            String role,
            String content,
            List<ToolCallBlock> toolCalls,
            List<ToolResult> toolResults
    ) {
        private static StoredMessage from(Message message) {
            Objects.requireNonNull(message, "message");
            if (message.getRole() == null || message.getRole().isBlank()) {
                throw new IllegalArgumentException("Message role must not be blank");
            }

            return new StoredMessage(
                    message.getRole(),
                    message.getContent(),
                    copy(message.getToolCalls()),
                    copy(message.getToolResults())
            );
        }

        private Message toMessage() {
            Message message = new Message(role, content);
            if (!toolCalls.isEmpty()) {
                message.setToolCalls(List.copyOf(toolCalls));
            }
            if (!toolResults.isEmpty()) {
                message.setToolResults(List.copyOf(toolResults));
            }
            return message;
        }

        private static <T> List<T> copy(List<T> source) {
            return source == null ? List.of() : List.copyOf(source);
        }
    }

    public record SessionInfo(
            String id,
            int messageCount,
            String preview,
            long createdAt,
            long updatedAt
    ) {
    }
}