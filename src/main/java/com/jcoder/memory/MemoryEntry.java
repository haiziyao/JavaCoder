package com.jcoder.memory;

import java.util.Objects;

public record MemoryEntry(
        String id,
        Scope scope,
        Category category,
        String key,
        String content,
        String sourceSessionId,
        long createdAt,
        long updatedAt,
        long lastUsedAt,
        double confidence,
        Status status
) {
    public MemoryEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("memory id is required");
        }
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(category, "category");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("memory key is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("memory content is required");
        }
        sourceSessionId = sourceSessionId == null ? "" : sourceSessionId;
        if (createdAt <= 0 || updatedAt <= 0 || lastUsedAt < 0) {
            throw new IllegalArgumentException("invalid memory timestamps");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        Objects.requireNonNull(status, "status");
    }

    public MemoryEntry withStatus(Status newStatus, long now) {
        return new MemoryEntry(
                id, scope, category, key, content, sourceSessionId,
                createdAt, now, lastUsedAt, confidence, newStatus
        );
    }

    public MemoryEntry withLastUsedAt(long value) {
        return new MemoryEntry(
                id, scope, category, key, content, sourceSessionId,
                createdAt, updatedAt, value, confidence, status
        );
    }

    public enum Scope {
        USER,
        PROJECT
    }

    public enum Category {
        PREFERENCE,
        DECISION,
        CONSTRAINT,
        FACT,
        FEEDBACK
    }

    public enum Status {
        ACTIVE,
        SUPERSEDED,
        DELETED
    }
}