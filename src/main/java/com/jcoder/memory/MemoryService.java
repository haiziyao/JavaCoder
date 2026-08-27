package com.jcoder.memory;

import com.jcoder.message.Message;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MemoryService implements AutoCloseable {

    private final MemoryStore store;
    private final MemoryPolicy policy;
    private final MemoryExtractor extractor;
    private final ExecutorService backgroundExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    private volatile String lastBackgroundError = "";

    public MemoryService(
            MemoryStore store,
            MemoryPolicy policy,
            MemoryExtractor extractor
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    public void extractAsync(
            String sourceSessionId,
            List<Message> turnMessages
    ) {
        List<Message> snapshot = List.copyOf(turnMessages);

        backgroundExecutor.submit(() -> {
            try {
                List<MemoryCandidate> candidates = extractor.extract(snapshot);
                remember(sourceSessionId, candidates);
                lastBackgroundError = "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastBackgroundError = "memory extraction interrupted";
            } catch (Exception e) {
                lastBackgroundError = safeMessage(e);
                System.err.println(
                        "[memory] automatic extraction failed: "
                                + lastBackgroundError
                );
            }
        });
    }

    public synchronized MemoryPolicy.GovernanceResult remember(
            String sourceSessionId,
            List<MemoryCandidate> candidates
    ) throws IOException {
        List<MemoryEntry> existing = store.load();
        MemoryPolicy.GovernanceResult result = policy.apply(
                existing,
                candidates,
                sourceSessionId
        );
        store.save(result.memories());
        return result;
    }

    public synchronized MemoryRecall.RecallResult recall(String query)
            throws IOException {
        List<MemoryEntry> memories = store.load();
        MemoryRecall.RecallResult result = MemoryRecall.select(memories, query);

        if (result.memoryIds().isEmpty()) {
            return result;
        }

        Set<String> usedIds = new HashSet<>(result.memoryIds());
        long now = Instant.now().toEpochMilli();
        List<MemoryEntry> updated = new ArrayList<>(memories.size());

        for (MemoryEntry memory : memories) {
            updated.add(
                    usedIds.contains(memory.id())
                            ? memory.withLastUsedAt(now)
                            : memory
            );
        }

        store.save(updated);
        return result;
    }

    public synchronized List<MemoryEntry> list(boolean includeInactive)
            throws IOException {
        return store.load().stream()
                .filter(memory -> includeInactive
                        || memory.status() == MemoryEntry.Status.ACTIVE)
                .sorted(
                        Comparator.comparingLong(MemoryEntry::updatedAt)
                                .reversed()
                )
                .toList();
    }

    public synchronized boolean delete(String memoryId) throws IOException {
        if (memoryId == null || memoryId.isBlank()) {
            return false;
        }

        List<MemoryEntry> memories = new ArrayList<>(store.load());
        long now = Instant.now().toEpochMilli();

        for (int index = 0; index < memories.size(); index++) {
            MemoryEntry memory = memories.get(index);
            if (memory.id().equals(memoryId)
                    && memory.status() != MemoryEntry.Status.DELETED) {
                memories.set(
                        index,
                        memory.withStatus(MemoryEntry.Status.DELETED, now)
                );
                store.save(memories);
                return true;
            }
        }
        return false;
    }

    public synchronized int clear(MemoryEntry.Scope scope) throws IOException {
        List<MemoryEntry> memories = new ArrayList<>(store.load());
        long now = Instant.now().toEpochMilli();
        int changed = 0;

        for (int index = 0; index < memories.size(); index++) {
            MemoryEntry memory = memories.get(index);
            boolean selected = scope == null || memory.scope() == scope;

            if (selected && memory.status() != MemoryEntry.Status.DELETED) {
                memories.set(
                        index,
                        memory.withStatus(MemoryEntry.Status.DELETED, now)
                );
                changed++;
            }
        }

        if (changed > 0) {
            store.save(memories);
        }
        return changed;
    }

    public String lastBackgroundError() {
        return lastBackgroundError;
    }

    @Override
    public void close() {
        backgroundExecutor.close();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}