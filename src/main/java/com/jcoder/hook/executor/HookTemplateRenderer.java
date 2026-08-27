package com.jcoder.hook.executor;

import com.jcoder.hook.HookContext;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 HookContext 中的数据渲染进 Prompt 或 HTTP 模板。
 *
 * CommandHookExecutor 不使用这个渲染器，
 * 避免 Tool 参数被直接拼接进 shell 命令。
 */
public final class HookTemplateRenderer {

    private static final Pattern VARIABLE =
            Pattern.compile(
                    "\\{\\{\\s*"
                            + "([a-zA-Z0-9_.-]+)"
                            + "\\s*}}"
            );

    /**
     * 检测没有被正常解析的模板占位符。
     *
     * 这里只检查以 {{ 开始的内容，不能因为普通 JSON
     * 使用连续的右花括号结尾就把它误判成模板错误。
     */
    private static final Pattern UNRESOLVED_VARIABLE =
            Pattern.compile(
                    "\\{\\{[^\\r\\n]*}}"
            );

    public String render(
            String template,
            HookContext context
    ) {
        if (template == null) {
            throw new IllegalArgumentException(
                    "hook template is required"
            );
        }

        Objects.requireNonNull(
                context,
                "context"
        );

        Matcher matcher =
                VARIABLE.matcher(template);

        StringBuffer output =
                new StringBuffer();

        while (matcher.find()) {
            String variable =
                    matcher.group(1);

            String value =
                    resolve(variable, context);

            matcher.appendReplacement(
                    output,
                    Matcher.quoteReplacement(value)
            );
        }

        matcher.appendTail(output);

        String rendered =
                output.toString();

        if (rendered.contains("{{")
                || UNRESOLVED_VARIABLE
                .matcher(rendered)
                .find()) {
            throw new IllegalArgumentException(
                    "malformed hook template variable"
            );
        }

        return rendered;
    }

    private String resolve(
            String variable,
            HookContext context
    ) {
        return switch (variable) {
            case "event" ->
                    context.event()
                            .name()
                            .toLowerCase(Locale.ROOT);

            case "session_id" ->
                    context.sessionId();

            case "working_directory" ->
                    context.workingDirectory()
                            .toString();

            case "message" ->
                    context.message();

            case "tool_name" ->
                    nullToEmpty(
                            context.toolName()
                    );

            case "tool_output" ->
                    context.toolOutput();

            case "tool_error" ->
                    Boolean.toString(
                            context.toolError()
                    );

            case "tool_duration_ms" ->
                    Long.toString(
                            context.toolDurationMillis()
                    );

            default -> resolveDynamicVariable(
                    variable,
                    context
            );
        };
    }

    private String resolveDynamicVariable(
            String variable,
            HookContext context
    ) {
        if (!variable.startsWith("args.")) {
            throw new IllegalArgumentException(
                    "unknown hook template variable: "
                            + variable
            );
        }

        String argumentName =
                variable.substring(
                        "args.".length()
                );

        if (argumentName.isBlank()) {
            throw new IllegalArgumentException(
                    "hook argument variable "
                            + "requires a name"
            );
        }

        if (!context.toolArguments()
                .containsKey(argumentName)) {
            throw new IllegalArgumentException(
                    "unknown hook tool argument: "
                            + argumentName
            );
        }

        Object value =
                context.toolArguments()
                        .get(argumentName);

        return value == null
                ? ""
                : String.valueOf(value);
    }

    private static String nullToEmpty(
            String value
    ) {
        return value == null
                ? ""
                : value;
    }
}
