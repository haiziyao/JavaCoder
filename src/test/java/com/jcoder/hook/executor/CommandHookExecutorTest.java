package com.jcoder.hook.executor;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.HookSelector;
import com.jcoder.hook.action.CommandHookAction;
import com.jcoder.hook.action.PromptHookAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class CommandHookExecutorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsCommandTypeAndValidatesOutputLimit() {
        assertEquals(HookActionType.COMMAND, new CommandHookExecutor().type());
        assertThrows(IllegalArgumentException.class, () -> new CommandHookExecutor(0));
        assertThrows(IllegalArgumentException.class, () -> new CommandHookExecutor(-1));
    }

    @Test
    void executesSuccessfulCommandAndCapturesOutput() {
        HookExecutionResult result = execute(
                new CommandHookExecutor(), "echo meow",
                HookContext.turnStart("session-1", temporaryDirectory, "hello"), 2_000);

        assertTrue(result.success(), result.errorMessage());
        assertEquals("meow", result.output().strip());
        assertTrue(result.durationMillis() >= 0);
    }

    @Test
    void capturesMergedOutputWhenCommandExitsNonZero() {
        HookExecutionResult result = execute(
                new CommandHookExecutor(), "echo command-failed & exit /b 7",
                HookContext.turnStart("", temporaryDirectory, ""), 2_000);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("code 7"));
        assertTrue(result.output().contains("command-failed"));
    }

    @Test
    void terminatesCommandAfterConfiguredTimeout() {
        HookExecutionResult result = execute(
                new CommandHookExecutor(), "ping -n 4 127.0.0.1 >nul",
                HookContext.turnStart("", Path.of("."), ""), 100);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("timed out after 100 ms"));
    }

    @Test
    void timeoutTerminatesActualDescendantProcessTree() throws Exception {
        Path pidFile = temporaryDirectory.resolve("descendant.pid");
        String escapedPidFile = pidFile.toAbsolutePath().toString().replace("'", "''");
        String command = "powershell -NoProfile -Command \""
                + "$child = Start-Process -FilePath 'ping.exe' "
                + "-ArgumentList '-n 30 127.0.0.1' -WindowStyle Hidden -PassThru; "
                + "Set-Content -LiteralPath '" + escapedPidFile + "' -Value $child.Id; "
                + "Wait-Process -Id $child.Id\"";

        long descendantPid = -1;
        try {
            HookExecutionResult result = execute(
                    new CommandHookExecutor(),
                    command,
                    HookContext.turnStart("", temporaryDirectory, ""),
                    1_500
            );

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("timed out after 1500 ms"));
            assertTrue(Files.exists(pidFile), "child process did not publish its PID");

            descendantPid = Long.parseLong(Files.readString(pidFile).strip());
            assertTrue(
                    awaitNotAlive(descendantPid, Duration.ofSeconds(2)),
                    "descendant process is still alive after hook timeout: " + descendantPid
            );
        } finally {
            if (descendantPid > 0) {
                ProcessHandle.of(descendantPid)
                        .filter(ProcessHandle::isAlive)
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void truncatesStoredOutputWhileContinuingToDrainProcessPipe() {
        HookExecutionResult result = execute(
                new CommandHookExecutor(10),
                "powershell -NoProfile -Command \"[Console]::Out.Write('x' * 100)\"",
                HookContext.turnStart("", temporaryDirectory, ""), 2_000);

        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().startsWith("xxxxxxxxxx"), result.output());
        assertTrue(result.output().contains("...[hook output truncated]..."));
    }

    @Test
    void startsProcessInContextWorkingDirectory() throws Exception {
        Path workingDirectory = Files.createDirectory(
                temporaryDirectory.resolve("working directory"));

        HookExecutionResult result = execute(
                new CommandHookExecutor(), "cd",
                HookContext.turnStart("", workingDirectory, ""), 2_000);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(
                workingDirectory.toAbsolutePath().normalize().toString().toLowerCase(),
                result.output().strip().toLowerCase()
        );
    }

    @Test
    void passesContextAndNormalizedArgumentsThroughEnvironment() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("file-path", "application.json");
        arguments.put("count.value", 2);
        HookContext context = HookContext.postTool(
                "session-42", temporaryDirectory, "WriteFile", arguments,
                "saved", true, 27);
        String command = "echo %MYCODER_HOOK_ID%^|%MYCODER_HOOK_EVENT%"
                + "^|%MYCODER_HOOK_SESSION_ID%^|%MYCODER_HOOK_TOOL_NAME%"
                + "^|%MYCODER_HOOK_TOOL_OUTPUT%^|%MYCODER_HOOK_TOOL_ERROR%"
                + "^|%MYCODER_HOOK_TOOL_DURATION_MS%^|%MYCODER_HOOK_ARG_FILE_PATH%"
                + "^|%MYCODER_HOOK_ARG_COUNT_VALUE%";

        HookExecutionResult result = execute(
                new CommandHookExecutor(), command, context, 2_000);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(
                "command-test|post_tool_use|session-42|WriteFile|saved|true|27|application.json|2",
                result.output().strip()
        );
    }

    @Test
    void toolArgumentsAreNotInterpolatedIntoTrustedCommand() {
        HookContext context = HookContext.preTool(
                "", temporaryDirectory, "WriteFile",
                Map.of("path", "& echo INJECTED_MARKER &"));

        HookExecutionResult result = execute(
                new CommandHookExecutor(), "echo STATIC_COMMAND", context, 2_000);

        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().contains("STATIC_COMMAND"));
        assertFalse(result.output().contains("INJECTED_MARKER"));
    }

    @Test
    void wrongActionTypeReturnsFailureInsteadOfCasting() {
        HookDefinition definition = new HookDefinition(
                "wrong-action", com.jcoder.hook.HookEvent.TURN_START,
                HookSelector.any(), new PromptHookAction("hello"),
                false, false, false, HookErrorPolicy.CONTINUE, 1_000);

        HookExecutionResult result = new CommandHookExecutor().execute(
                definition, HookContext.turnStart("", temporaryDirectory, ""));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("received PROMPT"));
    }

    private static HookExecutionResult execute(
            CommandHookExecutor executor,
            String command,
            HookContext context,
            long timeoutMillis
    ) {
        HookDefinition definition = new HookDefinition(
                "command-test", context.event(), HookSelector.any(),
                new CommandHookAction(command), false, false, false,
                HookErrorPolicy.CONTINUE, timeoutMillis);
        return executor.execute(definition, context);
    }

    private static boolean awaitNotAlive(long pid, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(20);
                continue;
            }
            return true;
        }

        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false;
    }
}
