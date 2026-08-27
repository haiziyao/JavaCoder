package com.jcoder.hook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hook 的简单匹配条件。
 *
 * 第一版支持：
 * 1. 精确匹配工具名称。
 * 2. 精确匹配工具参数。
 * 3. 匹配工具是否执行失败。
 */
public record HookSelector(
        String toolName,
        Map<String, String> argumentEquals,
        Boolean toolError
) {

    public HookSelector {
        if (toolName != null) {
            toolName = toolName.strip();

            if (toolName.isEmpty()) {
                toolName = null;
            }
        }

        Map<String, String> normalizedArguments =
                new LinkedHashMap<>();

        if (argumentEquals != null) {
            for (Map.Entry<String, String> entry
                    : argumentEquals.entrySet()) {

                String name = entry.getKey();
                String expectedValue = entry.getValue();

                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException(
                            "hook selector argument name is required"
                    );
                }

                if (expectedValue == null) {
                    throw new IllegalArgumentException(
                            "hook selector expected value is required: "
                                    + name
                    );
                }

                normalizedArguments.put(
                        name.strip(),
                        expectedValue
                );
            }
        }

        argumentEquals = Collections.unmodifiableMap(
                normalizedArguments
        );
    }

    /**
     * 不限制任何条件，事件发生时直接匹配。
     */
    public static HookSelector any() {
        return new HookSelector(
                null,
                Map.of(),
                null
        );
    }

    /**
     * 当前 Selector 是否依赖 Tool 数据。
     */
    public boolean usesToolData() {
        return toolName != null
                || !argumentEquals.isEmpty()
                || toolError != null;
    }

    /**
     * 检查事件现场是否满足条件。
     */
    public boolean matches(HookContext context) {
        Objects.requireNonNull(
                context,
                "context"
        );

        if (toolName != null
                && !toolName.equals(context.toolName())) {
            return false;
        }

        for (Map.Entry<String, String> entry
                : argumentEquals.entrySet()) {

            Object actualValue =
                    context.toolArguments()
                            .get(entry.getKey());

            if (actualValue == null
                    || !entry.getValue().equals(
                            String.valueOf(actualValue)
                    )) {
                return false;
            }
        }

        if (toolError != null
                && toolError != context.toolError()) {
            return false;
        }

        return true;
    }
}