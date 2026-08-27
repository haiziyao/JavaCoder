package com.jcoder.hook.config;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.HookSelector;
import com.jcoder.hook.action.CommandHookAction;
import com.jcoder.hook.action.HookAction;
import com.jcoder.hook.action.HttpHookAction;
import com.jcoder.hook.action.PromptHookAction;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * hooks.json 中一条可持久化的 Hook 配置。
 *
 * 它与运行时 HookDefinition 分离，避免让 Jackson
 * 直接反序列化 sealed HookAction。
 */
public record HookConfigEntry(
        String id,
        Boolean enabled,
        HookEvent event,
        SelectorConfig selector,
        ActionConfig action,
        boolean reject,
        boolean once,
        boolean async,
        HookErrorPolicy onError,
        long timeoutMillis
) {

    public HookConfigEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "hook id is required"
            );
        }

        id = id.strip();
        enabled = enabled == null ? Boolean.TRUE : enabled;
        Objects.requireNonNull(event, "event");
        selector = selector == null ? SelectorConfig.any() : selector;
        Objects.requireNonNull(action, "action");
        onError = onError == null
                ? HookErrorPolicy.CONTINUE
                : onError;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public HookDefinition toDefinition() {
        return new HookDefinition(
                id,
                event,
                selector.toSelector(),
                action.toAction(),
                reject,
                once,
                async,
                onError,
                timeoutMillis
        );
    }

    public HookConfigEntry withEnabled(
            boolean newEnabled
    ) {
        return new HookConfigEntry(
                id,
                newEnabled,
                event,
                selector,
                action,
                reject,
                once,
                async,
                onError,
                timeoutMillis
        );
    }

    public record SelectorConfig(
            String toolName,
            Map<String, String> argumentEquals,
            Boolean toolError
    ) {
        public SelectorConfig {
            if (toolName != null) {
                toolName = toolName.strip();
                if (toolName.isEmpty()) {
                    toolName = null;
                }
            }

            Map<String, String> copied =
                    new LinkedHashMap<>();

            if (argumentEquals != null) {
                copied.putAll(argumentEquals);
            }

            argumentEquals =
                    Collections.unmodifiableMap(copied);
        }

        public static SelectorConfig any() {
            return new SelectorConfig(
                    null,
                    Map.of(),
                    null
            );
        }

        public HookSelector toSelector() {
            return new HookSelector(
                    toolName,
                    argumentEquals,
                    toolError
            );
        }
    }

    public record ActionConfig(
            HookActionType type,
            String template,
            String command,
            String uri,
            String method,
            Map<String, String> headers,
            String bodyTemplate
    ) {
        public ActionConfig {
            Objects.requireNonNull(
                    type,
                    "action type"
            );

            Map<String, String> copiedHeaders =
                    new LinkedHashMap<>();

            if (headers != null) {
                copiedHeaders.putAll(headers);
            }

            headers = Collections.unmodifiableMap(
                    copiedHeaders
            );
        }

        public HookAction toAction() {
            return switch (type) {
                case PROMPT -> {
                    rejectUnexpected(
                            command,
                            "command",
                            type
                    );
                    rejectUnexpected(
                            uri,
                            "uri",
                            type
                    );
                    yield new PromptHookAction(template);
                }

                case COMMAND -> {
                    rejectUnexpected(
                            template,
                            "template",
                            type
                    );
                    rejectUnexpected(
                            uri,
                            "uri",
                            type
                    );
                    yield new CommandHookAction(command);
                }

                case HTTP -> {
                    rejectUnexpected(
                            template,
                            "template",
                            type
                    );
                    rejectUnexpected(
                            command,
                            "command",
                            type
                    );

                    if (uri == null || uri.isBlank()) {
                        throw new IllegalArgumentException(
                                "HTTP hook uri is required"
                        );
                    }

                    yield new HttpHookAction(
                            URI.create(uri.strip()),
                            method,
                            headers,
                            bodyTemplate
                    );
                }
            };
        }

        private static void rejectUnexpected(
                String value,
                String field,
                HookActionType type
        ) {
            if (value != null && !value.isBlank()) {
                throw new IllegalArgumentException(
                        field
                                + " is not valid for "
                                + type
                                + " hook action"
                );
            }
        }
    }
}
