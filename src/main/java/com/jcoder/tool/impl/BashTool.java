package com.jcoder.tool.impl;

import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolArgsHelper;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolParamDefinition;
import com.jcoder.tool.ToolReturnDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_OUTPUT_CHARS = 10_000;

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return "执行一条 shell 命令并返回标准输出和标准错误。命令默认最多运行 120 秒。";
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
                        "command", new ToolParamDefinition(
                                "string", "要执行的命令", Map.of()),
                        "timeout", new ToolParamDefinition(
                                "integer", "超时时间（秒）", Map.of("default", DEFAULT_TIMEOUT_SECONDS)),
                        "workdir", new ToolParamDefinition(
                                "string", "命令工作目录", Map.of("default", "."))
                ),
                List.of("command"),
                new ToolReturnDefinition("string", Map.of())
        );
    }

    @Override
    public ToolExecuteResult execute(Map<String, Object> args) {
        String command = ToolArgsHelper.stringArg(args, "command", "");
        if (command.isBlank()) {
            return ToolExecuteResult.error("Error: command is required");
        }

        int timeout = ToolArgsHelper.intArg(args, "timeout", DEFAULT_TIMEOUT_SECONDS);
        timeout = Math.max(1, Math.min(timeout, MAX_TIMEOUT_SECONDS));
        String workdir = ToolArgsHelper.stringArg(args, "workdir", ".");

        List<String> commandLine = isWindows()
                ? List.of("cmd.exe", "/c", command)
                : List.of("bash", "-c", command);

        try {
            ProcessBuilder builder = new ProcessBuilder(commandLine)
                    .redirectErrorStream(true);
            if (!workdir.isBlank()) {
                builder.directory(new java.io.File(workdir));
            }

            Process process = builder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(
                    () -> readOutput(process.getInputStream()));

            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                outputFuture.cancel(true);
                return ToolExecuteResult.error(
                        "Error: command timed out after " + timeout + "s");
            }

            String output = outputFuture.get(5, TimeUnit.SECONDS);
            StringBuilder result = new StringBuilder(limitOutput(output));
            if (process.exitValue() != 0) {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != '\n') {
                    result.append('\n');
                }
                result.append("Exit code ").append(process.exitValue());
            }
            return ToolExecuteResult.success(result.toString());
        } catch (IOException e) {
            return ToolExecuteResult.error("Error executing command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolExecuteResult.error("Error: command interrupted");
        } catch (Exception e) {
            return ToolExecuteResult.error("Error reading command output: " + rootMessage(e));
        }
    }

    private static String readOutput(InputStream input) {
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static String limitOutput(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS)
                + "\n... output truncated (max " + MAX_OUTPUT_CHARS + " chars)";
    }

    private static String rootMessage(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win");
    }
}
