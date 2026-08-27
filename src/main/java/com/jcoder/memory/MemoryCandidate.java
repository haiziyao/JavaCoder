package com.jcoder.memory;

import java.util.Objects;

public record MemoryCandidate(
        MemoryEntry.Scope scope,
        MemoryEntry.Category category,
        String key,
        String content,
        double confidence
) {
    public MemoryCandidate {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(category, "category");
        key = key == null ? "" : key;
        content = content == null ? "" : content;
    }
}