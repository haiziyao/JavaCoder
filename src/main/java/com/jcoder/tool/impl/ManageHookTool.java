package com.jcoder.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.hook.config.HookConfigEntry;
import com.jcoder.hook.config.HookManager;
import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolArgsHelper;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolParamDefinition;
import com.jcoder.tool.ToolReturnDefinition;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 让 Agent 在用户授权后管理项目级 Hook。
 *
 * 该工具会持久化并热加载配置，因此固定归类为 COMMAND，
 * 不能因为 operation=list 就降低整个工具的权限等级。
 */
public final class ManageHookTool implements Tool {

    private static final List<String> OPERATIONS =
            List.of(
                    "list",
                    "get",
                    "create",
                    "update",
                    "delete",
                    "enable",
                    "disable",
                    "reload"
            );

    private final HookManager manager;
    private final ObjectMapper mapper;

    public ManageHookTool(
            HookManager manager
    ) {
        this(
                manager,
                new ObjectMapper()
        );
    }

    ManageHookTool(
            HookManager manager,
            ObjectMapper mapper
    ) {
        this.manager = Objects.requireNonNull(
                manager,
                "manager"
        );

        this.mapper = Objects.requireNonNull(
                mapper,
                "mapper"
        );
    }

    @Override
    public String name() {
        return "ManageHook";
    }

    @Override
    public String description() {
        return """
                Manage persistent project Hooks only when the user asks for it. \
                Supported operations: list, get, create, update, delete, enable, \
                disable, reload. create/update require a complete hook object. \
                Hook JSON uses uppercase event/action enum values. Example: \
                {"id":"meow-before-write","enabled":true,\
                "event":"PRE_TOOL_USE",\
                "selector":{"toolName":"WriteFile"},\
                "action":{"type":"COMMAND","command":"echo meow"},\
                "reject":false,"once":false,"async":false,\
                "onError":"CONTINUE","timeoutMillis":5000}. \
                COMMAND and HTTP Hooks are persistent side effects; summarize the \
                exact action to the user before requesting permission.
                """.strip();
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                name(),
                description(),
                Map.of(
                        "operation",
                        new ToolParamDefinition(
                                "string",
                                "管理操作",
                                Map.of("enum", OPERATIONS)
                        ),
                        "id",
                        new ToolParamDefinition(
                                "string",
                                "get/update/delete/enable/disable 的 Hook ID",
                                Map.of()
                        ),
                        "hook",
                        new ToolParamDefinition(
                                "object",
                                "create/update 使用的完整 Hook 配置对象",
                                Map.of(
                                        "additionalProperties",
                                        true
                                )
                        )
                ),
                List.of("operation"),
                new ToolReturnDefinition(
                        "string",
                        Map.of()
                )
        );
    }

    @Override
    public ToolExecuteResult execute(
            Map<String, Object> args
    ) {
        String operation =
                ToolArgsHelper.stringArg(
                        args,
                        "operation",
                        ""
                ).strip().toLowerCase();

        if (!OPERATIONS.contains(operation)) {
            return ToolExecuteResult.error(
                    "Error: unsupported operation: "
                            + operation
            );
        }

        try {
            return switch (operation) {
                case "list" -> successJson(
                        Map.of(
                                "configFile",
                                manager.store()
                                        .configFile()
                                        .toString(),
                                "hooks",
                                manager.list()
                        )
                );

                case "get" -> {
                    String id = requiredId(args);
                    HookConfigEntry entry =
                            manager.get(id)
                                    .orElseThrow(
                                            () -> new IllegalArgumentException(
                                                    "unknown hook: " + id
                                            )
                                    );
                    yield successJson(entry);
                }

                case "create" -> {
                    HookConfigEntry created =
                            manager.create(
                                    requiredHook(args)
                            );
                    yield successJson(
                            Map.of(
                                    "message",
                                    "hook created and reloaded",
                                    "hook",
                                    created
                            )
                    );
                }

                case "update" -> {
                    String id = requiredId(args);
                    HookConfigEntry updated =
                            manager.update(
                                    id,
                                    requiredHook(args)
                            );
                    yield successJson(
                            Map.of(
                                    "message",
                                    "hook updated and reloaded",
                                    "hook",
                                    updated
                            )
                    );
                }

                case "delete" -> {
                    HookConfigEntry deleted =
                            manager.delete(
                                    requiredId(args)
                            );
                    yield successJson(
                            Map.of(
                                    "message",
                                    "hook deleted and reloaded",
                                    "hook",
                                    deleted
                            )
                    );
                }

                case "enable", "disable" -> {
                    boolean enabled =
                            "enable".equals(operation);
                    HookConfigEntry changed =
                            manager.setEnabled(
                                    requiredId(args),
                                    enabled
                            );
                    yield successJson(
                            Map.of(
                                    "message",
                                    "hook "
                                            + operation
                                            + "d and reloaded",
                                    "hook",
                                    changed
                            )
                    );
                }

                case "reload" -> {
                    int count = manager.reload();
                    yield successJson(
                            Map.of(
                                    "message",
                                    "hooks reloaded",
                                    "count",
                                    count
                            )
                    );
                }

                default -> throw new IllegalStateException(
                        "unreachable operation: "
                                + operation
                );
            };
        } catch (IllegalArgumentException exception) {
            return ToolExecuteResult.error(
                    "Error: "
                            + exceptionMessage(exception)
            );
        } catch (IOException exception) {
            return ToolExecuteResult.error(
                    "Error managing hook config: "
                            + exceptionMessage(exception)
            );
        }
    }

    private HookConfigEntry requiredHook(
            Map<String, Object> args
    ) {
        Object raw = args == null
                ? null
                : args.get("hook");

        if (!(raw instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    "hook object is required"
            );
        }

        try {
            return mapper.convertValue(
                    raw,
                    HookConfigEntry.class
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "invalid hook object: "
                            + exceptionMessage(exception),
                    exception
            );
        }
    }

    private static String requiredId(
            Map<String, Object> args
    ) {
        String id = ToolArgsHelper.stringArg(
                args,
                "id",
                ""
        ).strip();

        if (id.isEmpty()) {
            throw new IllegalArgumentException(
                    "id is required"
            );
        }

        return id;
    }

    private ToolExecuteResult successJson(
            Object value
    ) throws JsonProcessingException {
        return ToolExecuteResult.success(
                mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(value)
        );
    }

    private static String exceptionMessage(
            Throwable throwable
    ) {
        String message = throwable.getMessage();

        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }

        return message;
    }
}
