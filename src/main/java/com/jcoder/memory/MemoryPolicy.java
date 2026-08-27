package com.jcoder.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MemoryPolicy {

    public static final int MAX_CONTENT_LENGTH = 500;
    public static final int MAX_ACTIVE_MEMORIES = 100;
    public static final int MAX_TOTAL_MEMORIES = 200;
    public static final double MIN_CONFIDENCE = 0.65;

    private static final Pattern VALID_KEY =
            Pattern.compile("[a-z0-9][a-z0-9._-]{1,79}");

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile(
                    "(?i)(api[_-]?key|access[_-]?token|secret|password)"
                            + "\\s*[:=]\\s*\\S+"
            ),
            Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/-]{12,}"),
            Pattern.compile("(?i)sk-[a-z0-9_-]{16,}"),
            Pattern.compile("(?i)gh[pousr]_[a-z0-9]{20,}")
    );

    public GovernanceResult apply(
            List<MemoryEntry> existing,
            List<MemoryCandidate> candidates,
            String sourceSessionId
    ) {
        List<MemoryEntry> working = new ArrayList<>(
                existing == null ? List.of() : existing
        );
        List<MemoryCandidate> sourceCandidates =
                candidates == null ? List.of() : candidates;

        long now = Instant.now().toEpochMilli();
        String sessionId = sourceSessionId == null ? "" : sourceSessionId;

        int added = 0;
        int updated = 0;
        int superseded = 0;
        int rejected = 0;

        for (MemoryCandidate candidate : sourceCandidates) {
            NormalizedCandidate normalized = normalize(candidate);

            if (normalized == null) {
                rejected++;
                continue;
            }

            int duplicateIndex = findExactDuplicate(working, normalized);
            if (duplicateIndex >= 0) {
                MemoryEntry old = working.get(duplicateIndex);
                working.set(
                        duplicateIndex,
                        new MemoryEntry(
                                old.id(),
                                old.scope(),
                                old.category(),
                                old.key(),
                                old.content(),
                                sessionId,
                                old.createdAt(),
                                now,
                                old.lastUsedAt(),
                                Math.max(old.confidence(), normalized.confidence()),
                                MemoryEntry.Status.ACTIVE
                        )
                );
                updated++;
                continue;
            }

            int sameKeyIndex = findSameActiveKey(working, normalized);
            if (sameKeyIndex >= 0) {
                MemoryEntry old = working.get(sameKeyIndex);
                working.set(
                        sameKeyIndex,
                        old.withStatus(MemoryEntry.Status.SUPERSEDED, now)
                );
                superseded++;
            }

            working.add(new MemoryEntry(
                    newMemoryId(now),
                    normalized.scope(),
                    normalized.category(),
                    normalized.key(),
                    normalized.content(),
                    sessionId,
                    now,
                    now,
                    0,
                    normalized.confidence(),
                    MemoryEntry.Status.ACTIVE
            ));
            added++;
        }

        enforceActiveLimit(working, now);
        working = enforceTotalLimit(working);
        working.sort(Comparator.comparingLong(MemoryEntry::createdAt));

        return new GovernanceResult(
                List.copyOf(working),
                added,
                updated,
                superseded,
                rejected
        );
    }

    private NormalizedCandidate normalize(MemoryCandidate candidate) {
        if (candidate == null
                || candidate.scope() == null
                || candidate.category() == null
                || candidate.confidence() < MIN_CONFIDENCE
                || candidate.confidence() > 1.0) {
            return null;
        }

        String key = candidate.key()
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "_");
        String content = candidate.content()
                .replaceAll("\\s+", " ")
                .strip();

        if (!VALID_KEY.matcher(key).matches()
                || content.isBlank()
                || content.length() > MAX_CONTENT_LENGTH
                || containsSecret(content)) {
            return null;
        }

        return new NormalizedCandidate(
                candidate.scope(),
                candidate.category(),
                key,
                content,
                candidate.confidence()
        );
    }

    private boolean containsSecret(String content) {
        for (Pattern pattern : SECRET_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    private int findExactDuplicate(
            List<MemoryEntry> memories,
            NormalizedCandidate candidate
    ) {
        String comparableContent = candidate.content().toLowerCase(Locale.ROOT);

        for (int index = 0; index < memories.size(); index++) {
            MemoryEntry memory = memories.get(index);
            if (memory.status() == MemoryEntry.Status.ACTIVE
                    && memory.scope() == candidate.scope()
                    && memory.category() == candidate.category()
                    && memory.content()
                    .replaceAll("\\s+", " ")
                    .strip()
                    .toLowerCase(Locale.ROOT)
                    .equals(comparableContent)) {
                return index;
            }
        }
        return -1;
    }

    private int findSameActiveKey(
            List<MemoryEntry> memories,
            NormalizedCandidate candidate
    ) {
        for (int index = 0; index < memories.size(); index++) {
            MemoryEntry memory = memories.get(index);
            if (memory.status() == MemoryEntry.Status.ACTIVE
                    && memory.scope() == candidate.scope()
                    && memory.category() == candidate.category()
                    && memory.key().equals(candidate.key())) {
                return index;
            }
        }
        return -1;
    }

    private void enforceActiveLimit(List<MemoryEntry> memories, long now) {
        List<MemoryEntry> active = memories.stream()
                .filter(memory -> memory.status() == MemoryEntry.Status.ACTIVE)
                .sorted(Comparator.comparingLong(MemoryEntry::updatedAt).reversed())
                .toList();

        if (active.size() <= MAX_ACTIVE_MEMORIES) {
            return;
        }

        for (MemoryEntry overflow :
                active.subList(MAX_ACTIVE_MEMORIES, active.size())) {
            int index = indexOfId(memories, overflow.id());
            memories.set(
                    index,
                    overflow.withStatus(MemoryEntry.Status.DELETED, now)
            );
        }
    }

    private List<MemoryEntry> enforceTotalLimit(List<MemoryEntry> memories) {
        if (memories.size() <= MAX_TOTAL_MEMORIES) {
            return memories;
        }

        List<MemoryEntry> active = memories.stream()
                .filter(memory -> memory.status() == MemoryEntry.Status.ACTIVE)
                .toList();
        List<MemoryEntry> inactive = memories.stream()
                .filter(memory -> memory.status() != MemoryEntry.Status.ACTIVE)
                .sorted(Comparator.comparingLong(MemoryEntry::updatedAt).reversed())
                .toList();

        List<MemoryEntry> kept = new ArrayList<>(active);
        int remaining = MAX_TOTAL_MEMORIES - kept.size();

        if (remaining > 0) {
            kept.addAll(inactive.subList(0, Math.min(remaining, inactive.size())));
        }
        return kept;
    }

    private int indexOfId(List<MemoryEntry> memories, String id) {
        for (int index = 0; index < memories.size(); index++) {
            if (memories.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalStateException("Memory disappeared during governance: " + id);
    }

    private String newMemoryId(long now) {
        return "mem-" + now + "-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8);
    }

    private record NormalizedCandidate(
            MemoryEntry.Scope scope,
            MemoryEntry.Category category,
            String key,
            String content,
            double confidence
    ) {
    }

    public record GovernanceResult(
            List<MemoryEntry> memories,
            int added,
            int updated,
            int superseded,
            int rejected
    ) {
        public GovernanceResult {
            memories = List.copyOf(memories);
        }
    }
}