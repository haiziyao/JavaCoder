package com.jcoder.hook.config;

import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.dispatcher.HookDispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * HookStore 与 HookDispatcher 之间的治理层。
 *
 * 所有写操作先构造并验证完整的新快照，保存成功后
 * 再原子替换 Dispatcher definitions。
 */
public final class HookManager {

    private static final int MAX_HOOKS = 100;

    private final HookStore store;
    private final HookDispatcher dispatcher;

    private volatile List<HookConfigEntry> entries =
            List.of();

    public HookManager(
            HookStore store,
            HookDispatcher dispatcher
    ) {
        this.store = Objects.requireNonNull(
                store,
                "store"
        );

        this.dispatcher = Objects.requireNonNull(
                dispatcher,
                "dispatcher"
        );
    }

    public synchronized int reload()
            throws IOException {
        List<HookConfigEntry> loaded =
                store.load();

        PreparedConfig prepared =
                prepare(loaded);

        dispatcher.replaceDefinitions(
                prepared.enabledDefinitions()
        );

        entries = prepared.entries();
        return entries.size();
    }

    public List<HookConfigEntry> list() {
        return entries;
    }

    public Optional<HookConfigEntry> get(
            String id
    ) {
        if (id == null) {
            return Optional.empty();
        }

        return entries.stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst();
    }

    public synchronized HookConfigEntry create(
            HookConfigEntry entry
    ) throws IOException {
        Objects.requireNonNull(entry, "entry");

        if (get(entry.id()).isPresent()) {
            throw new IllegalArgumentException(
                    "hook already exists: "
                            + entry.id()
            );
        }

        List<HookConfigEntry> updated =
                new ArrayList<>(entries);

        updated.add(entry);
        commit(updated);
        return entry;
    }

    public synchronized HookConfigEntry update(
            String id,
            HookConfigEntry replacement
    ) throws IOException {
        requireId(id);
        Objects.requireNonNull(
                replacement,
                "replacement"
        );

        if (!id.equals(replacement.id())) {
            throw new IllegalArgumentException(
                    "replacement hook id must remain "
                            + id
            );
        }

        List<HookConfigEntry> updated =
                new ArrayList<>(entries);

        int index = indexOf(id);
        updated.set(index, replacement);
        commit(updated);
        return replacement;
    }

    public synchronized HookConfigEntry delete(
            String id
    ) throws IOException {
        requireId(id);

        List<HookConfigEntry> updated =
                new ArrayList<>(entries);

        int index = indexOf(id);
        HookConfigEntry removed =
                updated.remove(index);

        commit(updated);
        return removed;
    }

    public synchronized HookConfigEntry setEnabled(
            String id,
            boolean enabled
    ) throws IOException {
        requireId(id);

        List<HookConfigEntry> updated =
                new ArrayList<>(entries);

        int index = indexOf(id);
        HookConfigEntry changed =
                updated.get(index)
                        .withEnabled(enabled);

        updated.set(index, changed);
        commit(updated);
        return changed;
    }

    public HookStore store() {
        return store;
    }

    private void commit(
            List<HookConfigEntry> updated
    ) throws IOException {
        PreparedConfig prepared =
                prepare(updated);

        /*
         * prepare 已证明 Dispatcher replace 不会因配置内容失败。
         * 先持久化，避免磁盘写失败时改变当前运行状态。
         */
        store.save(prepared.entries());

        dispatcher.replaceDefinitions(
                prepared.enabledDefinitions()
        );

        entries = prepared.entries();
    }

    private PreparedConfig prepare(
            List<HookConfigEntry> candidate
    ) {
        List<HookConfigEntry> copied =
                candidate == null
                        ? List.of()
                        : List.copyOf(candidate);

        if (copied.size() > MAX_HOOKS) {
            throw new IllegalArgumentException(
                    "too many hooks: "
                            + copied.size()
                            + "; maximum is "
                            + MAX_HOOKS
            );
        }

        Set<String> ids = new HashSet<>();
        List<HookDefinition> enabled =
                new ArrayList<>();

        for (HookConfigEntry entry : copied) {
            Objects.requireNonNull(
                    entry,
                    "hook entry"
            );

            if (!ids.add(entry.id())) {
                throw new IllegalArgumentException(
                        "duplicate hook id: "
                                + entry.id()
                );
            }

            /*
             * disabled Hook 也必须能够转换，防止把非法配置
             * 藏在 disabled 状态中，等 enable 时才爆炸。
             */
            HookDefinition definition =
                    entry.toDefinition();

            if (entry.isEnabled()) {
                enabled.add(definition);
            }
        }

        return new PreparedConfig(
                copied,
                List.copyOf(enabled)
        );
    }

    private int indexOf(
            String id
    ) {
        for (int index = 0;
             index < entries.size();
             index++) {
            if (entries.get(index).id().equals(id)) {
                return index;
            }
        }

        throw new IllegalArgumentException(
                "unknown hook: " + id
        );
    }

    private static void requireId(
            String id
    ) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "hook id is required"
            );
        }
    }

    private record PreparedConfig(
            List<HookConfigEntry> entries,
            List<HookDefinition> enabledDefinitions
    ) {
    }
}
