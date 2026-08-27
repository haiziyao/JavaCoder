package com.jcoder.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPolicyTest {

    private final MemoryPolicy policy = new MemoryPolicy();

    @Test
    void exactDuplicateRefreshesExistingEntryWithoutAddingAnother() {
        MemoryEntry existing = entry(
                "existing",
                "java.target_version",
                "Use Java 21.",
                0.80,
                MemoryEntry.Status.ACTIVE,
                1_000
        );
        MemoryCandidate duplicate = candidate(
                "some.other.key",
                "  use   JAVA 21.  ",
                0.97
        );

        MemoryPolicy.GovernanceResult result = policy.apply(
                List.of(existing),
                List.of(duplicate),
                "latest-session"
        );

        assertEquals(1, result.memories().size());
        assertEquals(0, result.added());
        assertEquals(1, result.updated());
        assertEquals(0, result.superseded());
        MemoryEntry refreshed = result.memories().getFirst();
        assertEquals("existing", refreshed.id());
        assertEquals("java.target_version", refreshed.key());
        assertEquals("Use Java 21.", refreshed.content());
        assertEquals(1_000, refreshed.createdAt());
        assertEquals("latest-session", refreshed.sourceSessionId());
        assertEquals(0.97, refreshed.confidence());
        assertTrue(refreshed.updatedAt() > existing.updatedAt());
    }

    @Test
    void sameKeyWithNewValueSupersedesOldEntryAndAddsActiveReplacement() {
        MemoryEntry java21 = entry(
                "java-21",
                "java.target_version",
                "Use Java 21.",
                0.95,
                MemoryEntry.Status.ACTIVE,
                1_000
        );

        MemoryPolicy.GovernanceResult result = policy.apply(
                List.of(java21),
                List.of(candidate(
                        "JAVA.Target Version",
                        "Use Java 23.",
                        0.99
                )),
                "session-new"
        );

        assertEquals(2, result.memories().size());
        assertEquals(1, result.added());
        assertEquals(0, result.updated());
        assertEquals(1, result.superseded());

        MemoryEntry old = find(result.memories(), "java-21");
        assertEquals(MemoryEntry.Status.SUPERSEDED, old.status());

        MemoryEntry replacement = result.memories().stream()
                .filter(memory -> !memory.id().equals("java-21"))
                .findFirst()
                .orElseThrow();
        assertEquals("java.target_version", replacement.key());
        assertEquals("Use Java 23.", replacement.content());
        assertEquals("session-new", replacement.sourceSessionId());
        assertEquals(MemoryEntry.Status.ACTIVE, replacement.status());
    }

    @Test
    void rejectsLowConfidenceSecretsInvalidKeysAndOversizedContent() {
        List<MemoryCandidate> invalid = List.of(
                candidate("valid.key", "A durable fact", 0.64),
                candidate("credential.key", "api_key = super-secret-value", 0.99),
                candidate("bad$key", "A durable fact", 0.99),
                candidate(
                        "valid.long_key",
                        "x".repeat(MemoryPolicy.MAX_CONTENT_LENGTH + 1),
                        0.99
                )
        );

        MemoryPolicy.GovernanceResult result = policy.apply(
                List.of(),
                invalid,
                "session-1"
        );

        assertTrue(result.memories().isEmpty());
        assertEquals(4, result.rejected());
        assertEquals(0, result.added());
    }

    @Test
    void activeCapacityDeletesOldestUpdatedEntries() {
        List<MemoryEntry> existing = IntStream.rangeClosed(
                        1,
                        MemoryPolicy.MAX_ACTIVE_MEMORIES + 1
                )
                .mapToObj(index -> entry(
                        "active-" + index,
                        "topic.key_" + index,
                        "Fact " + index,
                        0.90,
                        MemoryEntry.Status.ACTIVE,
                        index * 1_000L
                ))
                .toList();

        MemoryPolicy.GovernanceResult result = policy.apply(
                existing,
                List.of(),
                "session-1"
        );

        long activeCount = result.memories().stream()
                .filter(memory -> memory.status() == MemoryEntry.Status.ACTIVE)
                .count();
        assertEquals(MemoryPolicy.MAX_ACTIVE_MEMORIES, activeCount);
        assertEquals(
                MemoryEntry.Status.DELETED,
                find(result.memories(), "active-1").status()
        );
        assertEquals(
                MemoryEntry.Status.ACTIVE,
                find(result.memories(), "active-101").status()
        );
    }

    @Test
    void totalCapacityKeepsAllActiveAndNewestInactiveEntries() {
        List<MemoryEntry> existing = new ArrayList<>();
        for (int index = 1; index <= 50; index++) {
            existing.add(entry(
                    "active-" + index,
                    "active.key_" + index,
                    "Active " + index,
                    0.90,
                    MemoryEntry.Status.ACTIVE,
                    index * 1_000L
            ));
        }
        for (int index = 1; index <= 160; index++) {
            existing.add(entry(
                    "inactive-" + index,
                    "inactive.key_" + index,
                    "Inactive " + index,
                    0.90,
                    MemoryEntry.Status.SUPERSEDED,
                    (1_000 + index) * 1_000L
            ));
        }

        MemoryPolicy.GovernanceResult result = policy.apply(
                existing,
                List.of(),
                "session-1"
        );

        assertEquals(MemoryPolicy.MAX_TOTAL_MEMORIES, result.memories().size());
        assertEquals(50, result.memories().stream()
                .filter(memory -> memory.status() == MemoryEntry.Status.ACTIVE)
                .count());
        assertTrue(hasId(result.memories(), "inactive-160"));
        assertTrue(hasId(result.memories(), "inactive-11"));
        assertFalse(hasId(result.memories(), "inactive-10"));
        assertFalse(hasId(result.memories(), "inactive-1"));
    }

    private static MemoryCandidate candidate(
            String key,
            String content,
            double confidence
    ) {
        return new MemoryCandidate(
                MemoryEntry.Scope.PROJECT,
                MemoryEntry.Category.CONSTRAINT,
                key,
                content,
                confidence
        );
    }

    private static MemoryEntry entry(
            String id,
            String key,
            String content,
            double confidence,
            MemoryEntry.Status status,
            long timestamp
    ) {
        return new MemoryEntry(
                id,
                MemoryEntry.Scope.PROJECT,
                MemoryEntry.Category.CONSTRAINT,
                key,
                content,
                "source-session",
                timestamp,
                timestamp + 1,
                0,
                confidence,
                status
        );
    }

    private static MemoryEntry find(List<MemoryEntry> memories, String id) {
        return memories.stream()
                .filter(memory -> memory.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasId(List<MemoryEntry> memories, String id) {
        return memories.stream().anyMatch(memory -> memory.id().equals(id));
    }
}
