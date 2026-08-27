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

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptHookExecutorTest {

    private final HookTemplateRenderer renderer = new HookTemplateRenderer();

    @Test
    void rendersPromptFromContextWithoutChangingItsText() {
        HookContext context = HookContext.preTool(
                "session-1", Path.of("."), "WriteFile",
                Map.of("path", "application.json"));
        PromptHookExecutor executor = new PromptHookExecutor(renderer);

        HookExecutionResult result = executor.execute(
                definition(new PromptHookAction(
                        "Before {{tool_name}}: inspect {{args.path}}"), context),
                context
        );

        assertEquals(HookActionType.PROMPT, executor.type());
        assertTrue(result.success(), result.errorMessage());
        assertEquals("Before WriteFile: inspect application.json", result.output());
    }

    @Test
    void rejectsPromptThatRendersToEmptyOrWhitespaceOnly() {
        HookContext context = HookContext.turnStart("", Path.of("."), "");
        PromptHookExecutor executor = new PromptHookExecutor(renderer);

        HookExecutionResult empty = executor.execute(
                definition(new PromptHookAction("{{message}}"), context), context);
        HookExecutionResult whitespace = executor.execute(
                definition(new PromptHookAction(" {{message}} "), context), context);

        assertFalse(empty.success());
        assertTrue(empty.errorMessage().contains("rendered an empty message"));
        assertFalse(whitespace.success());
        assertTrue(whitespace.errorMessage().contains("rendered an empty message"));
    }

    @Test
    void acceptsExactOutputLimitAndRejectsLargerPrompt() {
        HookContext context = HookContext.turnStart("", Path.of("."), "");
        PromptHookExecutor executor = new PromptHookExecutor(renderer, 5);

        HookExecutionResult exact = executor.execute(
                definition(new PromptHookAction("12345"), context), context);
        HookExecutionResult tooLarge = executor.execute(
                definition(new PromptHookAction("123456"), context), context);

        assertTrue(exact.success(), exact.errorMessage());
        assertEquals("12345", exact.output());
        assertFalse(tooLarge.success());
        assertTrue(tooLarge.errorMessage().contains("6 characters; maximum is 5"));
    }

    @Test
    void convertsTemplateErrorsToExecutionFailure() {
        HookContext context = HookContext.turnStart("", Path.of("."), "");
        HookExecutionResult result = new PromptHookExecutor(renderer).execute(
                definition(new PromptHookAction("{{unknown}}"), context), context);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("failed to render prompt hook"));
        assertTrue(result.errorMessage().contains("unknown hook template variable"));
    }

    @Test
    void wrongActionTypeReturnsFailure() {
        HookContext context = HookContext.turnStart("", Path.of("."), "");
        HookExecutionResult result = new PromptHookExecutor(renderer).execute(
                definition(new CommandHookAction("echo meow"), context), context);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("PROMPT executor received COMMAND"));
    }

    @Test
    void validatesConstructorDependenciesAndLimit() {
        assertThrows(NullPointerException.class, () -> new PromptHookExecutor(null));
        assertThrows(IllegalArgumentException.class,
                () -> new PromptHookExecutor(renderer, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PromptHookExecutor(renderer, -1));
    }

    private static HookDefinition definition(
            com.jcoder.hook.action.HookAction action,
            HookContext context
    ) {
        return new HookDefinition(
                "prompt-test", context.event(), HookSelector.any(), action,
                false, false, false, HookErrorPolicy.CONTINUE, 1_000);
    }
}
