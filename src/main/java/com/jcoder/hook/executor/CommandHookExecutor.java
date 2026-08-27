package com.jcoder.hook.executor;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.action.CommandHookAction;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用本地进程执行 COMMAND Hook。
 *
 * Windows：
 *     cmd.exe /d /s /c <command>
 *
 * Unix：
 *     /bin/sh -lc <command>
 *
 * Tool 参数不会拼接到 command，
 * 而是通过受限环境变量传递。
 */
public final class CommandHookExecutor
        implements HookActionExecutor {

    private static final int DEFAULT_MAX_OUTPUT_CHARS =
            20_000;

    private static final int ENVIRONMENT_VALUE_LIMIT =
            2_048;

    private static final int ENVIRONMENT_TOTAL_LIMIT =
            8_192;

    private static final int MAX_ARGUMENT_VARIABLES =
            32;

    private final int maxOutputChars;

    public CommandHookExecutor() {
        this(DEFAULT_MAX_OUTPUT_CHARS);
    }

    public CommandHookExecutor(
            int maxOutputChars
    ) {
        if (maxOutputChars <= 0) {
            throw new IllegalArgumentException(
                    "maxOutputChars must be positive"
            );
        }

        this.maxOutputChars =
                maxOutputChars;
    }

    @Override
    public HookActionType type() {
        return HookActionType.COMMAND;
    }

    @Override
    public HookExecutionResult execute(
            HookDefinition definition,
            HookContext context
    ) {
        long startedAt =
                System.nanoTime();

        if (!(definition.action()
                instanceof CommandHookAction action)) {
            return HookExecutionResult.failure(
                    "COMMAND executor received "
                            + definition.action().type(),
                    "",
                    elapsedMillis(startedAt)
            );
        }

        Process process = null;

        ExecutorService outputReader =
                Executors
                        .newVirtualThreadPerTaskExecutor();

        try {
            ProcessBuilder builder =
                    new ProcessBuilder(
                            shellCommand(
                                    action.command()
                            )
                    );

            builder.directory(
                    context.workingDirectory()
                            .toFile()
            );

            /*
             * 合并 stdout 和 stderr，
             * 只需要持续排空一条管道，
             * 避免子进程因为输出缓冲区满而卡死。
             */
            builder.redirectErrorStream(true);

            addContextEnvironment(
                    builder.environment(),
                    definition,
                    context
            );

            process = builder.start();

            Process runningProcess =
                    process;

            Future<String> outputFuture =
                    outputReader.submit(
                            () -> readLimited(
                                    runningProcess
                                            .inputReader(
                                                    Charset
                                                            .defaultCharset()
                                            ),
                                    maxOutputChars
                            )
                    );

            boolean finished =
                    process.waitFor(
                            definition.timeoutMillis(),
                            TimeUnit.MILLISECONDS
                    );

            if (!finished) {
                stopProcess(process);

                String output =
                        awaitOutput(outputFuture);

                return HookExecutionResult.failure(
                        "command hook timed out after "
                                + definition.timeoutMillis()
                                + " ms",
                        output,
                        elapsedMillis(startedAt)
                );
            }

            int exitCode =
                    process.exitValue();

            String output =
                    awaitOutput(outputFuture);

            if (exitCode != 0) {
                return HookExecutionResult.failure(
                        "command exited with code "
                                + exitCode,
                        output,
                        elapsedMillis(startedAt)
                );
            }

            return HookExecutionResult.success(
                    output,
                    elapsedMillis(startedAt)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            stopProcess(process);

            return HookExecutionResult.failure(
                    "command hook was interrupted",
                    "",
                    elapsedMillis(startedAt)
            );
        } catch (IOException exception) {
            stopProcess(process);

            return HookExecutionResult.failure(
                    "failed to start command hook: "
                            + exceptionMessage(exception),
                    "",
                    elapsedMillis(startedAt)
            );
        } finally {
            stopProcess(process);
            outputReader.shutdownNow();
        }
    }

    private static List<String> shellCommand(
            String command
    ) {
        String operatingSystem =
                System.getProperty(
                        "os.name",
                        ""
                ).toLowerCase(Locale.ROOT);

        if (operatingSystem.contains("win")) {
            return List.of(
                    "cmd.exe",
                    "/d",
                    "/s",
                    "/c",
                    command
            );
        }

        return List.of(
                "/bin/sh",
                "-lc",
                command
        );
    }

    /**
     * 把事件数据通过环境变量交给子进程。
     *
     * Windows 脚本示例：
     *     echo %MYCODER_HOOK_TOOL_NAME%
     *     echo %MYCODER_HOOK_ARG_FILE_PATH%
     */
    private static void addContextEnvironment(
            Map<String, String> environment,
            HookDefinition definition,
            HookContext context
    ) {
        int remaining =
                ENVIRONMENT_TOTAL_LIMIT;

        remaining = putEnvironment(
                environment,
                "MYCODER_HOOK_ID",
                definition.id(),
                remaining
        );

        remaining = putEnvironment(
                environment,
                "MYCODER_HOOK_EVENT",
                context.event()
                        .name()
                        .toLowerCase(Locale.ROOT),
                remaining
        );

        remaining = putEnvironment(
                environment,
                "MYCODER_HOOK_SESSION_ID",
                context.sessionId(),
                remaining
        );

        remaining = putEnvironment(
                environment,
                "MYCODER_HOOK_WORKING_DIRECTORY",
                context.workingDirectory()
                        .toString(),
                remaining
        );

        remaining = putEnvironment(
                environment,
                "MYCODER_HOOK_MESSAGE",
                context.message(),
                remaining
        );

        remaining = putEnvironment(
                environment,
                "MYCODER_HOOK_TOOL_NAME",
                context.toolName(),
                remaining
        );

        remaining = putEnvironment(
                environment,
                "MYCODER_HOOK_TOOL_OUTPUT",
                context.toolOutput(),
                remaining
        );

        remaining = putEnvironment(
                environment,
                "MYCODER_HOOK_TOOL_ERROR",
                Boolean.toString(
                        context.toolError()
                ),
                remaining
        );

        remaining = putEnvironment(
                environment,
                "MYCODER_HOOK_TOOL_DURATION_MS",
                Long.toString(
                        context.toolDurationMillis()
                ),
                remaining
        );

        int argumentCount = 0;

        for (Map.Entry<String, Object> entry
                : context.toolArguments()
                .entrySet()) {

            if (remaining <= 0
                    || argumentCount
                    >= MAX_ARGUMENT_VARIABLES) {
                break;
            }

            String environmentName =
                    "MYCODER_HOOK_ARG_"
                            + normalizeEnvironmentName(
                            entry.getKey()
                    );

            Object rawValue =
                    entry.getValue();

            remaining = putEnvironment(
                    environment,
                    environmentName,
                    rawValue == null
                            ? ""
                            : String.valueOf(rawValue),
                    remaining
            );

            argumentCount++;
        }
    }

    private static int putEnvironment(
            Map<String, String> environment,
            String name,
            String value,
            int remaining
    ) {
        if (remaining <= 0) {
            return 0;
        }

        String safeValue =
                value == null
                        ? ""
                        : value.replace(
                                "\0",
                                "\uFFFD"
                        );

        int allowedLength =
                Math.min(
                        ENVIRONMENT_VALUE_LIMIT,
                        remaining
                );

        if (safeValue.length()
                > allowedLength) {
            safeValue =
                    safeValue.substring(
                            0,
                            allowedLength
                    );
        }

        environment.put(
                name,
                safeValue
        );

        return remaining
                - safeValue.length();
    }

    private static String normalizeEnvironmentName(
            String name
    ) {
        StringBuilder output =
                new StringBuilder();

        for (char character
                : name.toCharArray()) {

            if (Character.isLetterOrDigit(
                    character
            )) {
                output.append(
                        Character.toUpperCase(
                                character
                        )
                );
            } else {
                output.append('_');
            }
        }

        if (output.isEmpty()) {
            return "VALUE";
        }

        return output.toString();
    }

    /**
     * 持续读取完整管道，但只保存限定数量的字符。
     *
     * 继续读取是为了避免子进程输出缓冲区被填满；
     * 不继续追加是为了限制内存占用。
     */
    private static String readLimited(
            BufferedReader reader,
            int limit
    ) throws IOException {
        try (reader) {
            StringBuilder output =
                    new StringBuilder(
                            Math.min(limit, 4_096)
                    );

            char[] buffer =
                    new char[2_048];

            boolean truncated = false;
            int read;

            while ((read = reader.read(buffer)) >= 0) {
                int remaining =
                        limit - output.length();

                if (remaining > 0) {
                    int copied =
                            Math.min(
                                    remaining,
                                    read
                            );

                    output.append(
                            buffer,
                            0,
                            copied
                    );

                    if (copied < read) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            }

            if (truncated) {
                output.append(
                        System.lineSeparator()
                ).append(
                        "...[hook output truncated]..."
                );
            }

            return output.toString();
        }
    }

    private static String awaitOutput(
            Future<String> outputFuture
    ) throws InterruptedException {
        try {
            return outputFuture.get(
                    2,
                    TimeUnit.SECONDS
            );
        } catch (TimeoutException exception) {
            outputFuture.cancel(true);

            return "[hook output reader timed out]";
        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            return "[failed to read hook output: "
                    + exceptionMessage(cause)
                    + "]";
        }
    }

    private static void stopProcess(
            Process process
    ) {
        if (process == null) {
            return;
        }

        List<ProcessHandle> processTree =
                new ArrayList<>();

        /*
         * 必须在销毁父进程之前取得 descendants。
         * 父进程退出后，操作系统可能无法继续找到
         * 它原来创建的后代进程。
         */
        try {
            processTree.addAll(
                    process.descendants()
                            .toList()
            );
        } catch (RuntimeException ignored) {
            // 获取后代失败时仍继续销毁父进程。
        }

        processTree.add(
                process.toHandle()
        );

        requestProcessTreeStop(
                processTree,
                false
        );

        waitForProcessTree(
                processTree,
                200
        );

        requestProcessTreeStop(
                processTree,
                true
        );

        waitForProcessTree(
                processTree,
                1_000
        );
    }

    private static void requestProcessTreeStop(
            List<ProcessHandle> processTree,
            boolean forcibly
    ) {
        /*
         * 反向处理，优先停止更深层的后代进程。
         */
        for (int index = processTree.size() - 1;
             index >= 0;
             index--) {

            ProcessHandle handle =
                    processTree.get(index);

            if (!handle.isAlive()) {
                continue;
            }

            try {
                if (forcibly) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            } catch (RuntimeException ignored) {
                // 一个进程无法终止时仍继续处理其他进程。
            }
        }
    }

    private static void waitForProcessTree(
            List<ProcessHandle> processTree,
            long timeoutMillis
    ) {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS
                        .toNanos(timeoutMillis);

        for (ProcessHandle handle : processTree) {
            if (!handle.isAlive()) {
                continue;
            }

            long remaining =
                    deadline - System.nanoTime();

            if (remaining <= 0) {
                return;
            }

            try {
                handle.onExit().get(
                        remaining,
                        TimeUnit.NANOSECONDS
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException
                     | TimeoutException ignored) {
                // 超时或等待失败时继续处理进程树。
            }
        }
    }

    private static long elapsedMillis(
            long startedAt
    ) {
        return Math.max(
                0,
                (System.nanoTime() - startedAt)
                        / 1_000_000L
        );
    }

    private static String exceptionMessage(
            Throwable throwable
    ) {
        if (throwable == null) {
            return "unknown error";
        }

        String message =
                throwable.getMessage();

        if (message == null
                || message.isBlank()) {
            return throwable
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }
}
