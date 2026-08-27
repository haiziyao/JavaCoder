package com.jcoder.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryStoreTest {

    @TempDir
    Path projectRoot;

    @Test
    void missingFileLoadsAsEmptyList() throws Exception {
        MemoryStore store = new MemoryStore(projectRoot);

        assertTrue(store.load().isEmpty());
    }

    @Test
    void savesAndLoadsEveryMemoryField() throws Exception {
        MemoryStore store = new MemoryStore(projectRoot);
        List<MemoryEntry> expected = List.of(
                memory("mem-1", MemoryEntry.Status.ACTIVE),
                new MemoryEntry(
                        "mem-2",
                        MemoryEntry.Scope.USER,
                        MemoryEntry.Category.PREFERENCE,
                        "workflow.response_style",
                        "Prefer concise explanations.",
                        "session-2",
                        2_000,
                        2_100,
                        2_200,
                        0.91,
                        MemoryEntry.Status.SUPERSEDED
                )
        );

        store.save(expected);

        assertEquals(expected, store.load());
        assertTrue(Files.isRegularFile(
                projectRoot.resolve(".mycoder/memory/memories.json")
        ));
    }

    @Test
    void laterSaveReplacesPreviousSnapshot() throws Exception {
        MemoryStore store = new MemoryStore(projectRoot);
        store.save(List.of(memory("old", MemoryEntry.Status.ACTIVE)));

        List<MemoryEntry> replacement = List.of(
                memory("new", MemoryEntry.Status.DELETED)
        );
        store.save(replacement);

        assertEquals(replacement, store.load());
    }

    @Test
    void malformedJsonIsReportedWithMemoryFileContext() throws Exception {
        Path file = memoryFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not-json");

        IOException error = assertThrows(
                IOException.class,
                () -> new MemoryStore(projectRoot).load()
        );

        assertTrue(error.getMessage().contains("Failed to read memory file"));
        assertTrue(error.getCause() != null);
    }

    @Test
    void unsupportedFormatVersionIsRejected() throws Exception {
        Path file = memoryFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "formatVersion": 99,
                  "memories": []
                }
                """);

        IOException error = assertThrows(
                IOException.class,
                () -> new MemoryStore(projectRoot).load()
        );

        assertEquals("Unsupported memory format version: 99", error.getMessage());
    }

    @Test
    void nullMemoriesCollectionIsRejected() throws Exception {
        Path file = memoryFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "formatVersion": 1,
                  "memories": null
                }
                """);

        IOException error = assertThrows(
                IOException.class,
                () -> new MemoryStore(projectRoot).load()
        );

        assertEquals("Invalid memory file: memories is null", error.getMessage());
    }

    private Path memoryFile() {
        return projectRoot.resolve(".mycoder/memory/memories.json");
    }

    private static MemoryEntry memory(String id, MemoryEntry.Status status) {
        return new MemoryEntry(
                id,
                MemoryEntry.Scope.PROJECT,
                MemoryEntry.Category.CONSTRAINT,
                "java.target_version",
                "Use Java 21.",
                "session-1",
                1_000,
                1_100,
                1_200,
                0.95,
                status
        );
    }
}
