package com.jcoder.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

public final class MemoryStore {

    private static final int FORMAT_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final Path memoryDirectory;
    private final Path memoryFile;

    public MemoryStore(Path projectRoot) {
        this(projectRoot, new ObjectMapper());
    }

    MemoryStore(Path projectRoot, ObjectMapper objectMapper) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");

        this.memoryDirectory = projectRoot
                .toAbsolutePath()
                .normalize()
                .resolve(".mycoder")
                .resolve("memory")
                .normalize();
        this.memoryFile = memoryDirectory.resolve("memories.json");
    }

    public synchronized List<MemoryEntry> load() throws IOException {
        if (!Files.isRegularFile(memoryFile)) {
            return List.of();
        }

        MemoryDocument document;
        try {
            document = objectMapper.readValue(
                    memoryFile.toFile(),
                    MemoryDocument.class
            );
        } catch (IOException e) {
            throw new IOException(
                    "Failed to read memory file: " + memoryFile,
                    e
            );
        }

        if (document == null) {
            throw new IOException("Invalid memory file: empty document");
        }
        if (document.formatVersion() != FORMAT_VERSION) {
            throw new IOException(
                    "Unsupported memory format version: "
                            + document.formatVersion()
            );
        }
        if (document.memories() == null) {
            throw new IOException("Invalid memory file: memories is null");
        }

        return List.copyOf(document.memories());
    }

    public synchronized void save(List<MemoryEntry> memories)
            throws IOException {
        Objects.requireNonNull(memories, "memories");
        List<MemoryEntry> snapshot = List.copyOf(memories);

        Files.createDirectories(memoryDirectory);
        Path temporaryFile = Files.createTempFile(
                memoryDirectory,
                ".memories-",
                ".tmp"
        );

        try {
            MemoryDocument document = new MemoryDocument(
                    FORMAT_VERSION,
                    snapshot
            );

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporaryFile.toFile(), document);

            try {
                Files.move(
                        temporaryFile,
                        memoryFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(
                        temporaryFile,
                        memoryFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private record MemoryDocument(
            int formatVersion,
            List<MemoryEntry> memories
    ) {
    }
}