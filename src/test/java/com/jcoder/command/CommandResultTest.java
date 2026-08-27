package com.jcoder.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandResultTest {

    @Test
    void successAndErrorAreLocalResults() {
        CommandResult success = CommandResult.success("done");
        CommandResult error = CommandResult.error("failed");

        assertTrue(success.success());
        assertEquals(CommandResult.Delivery.LOCAL, success.delivery());
        assertFalse(success.shouldSubmitPrompt());

        assertFalse(error.success());
        assertEquals(CommandResult.Delivery.LOCAL, error.delivery());
        assertFalse(error.shouldSubmitPrompt());
    }

    @Test
    void promptCreatesSuccessfulPromptDelivery() {
        CommandResult result = CommandResult.prompt("review the project");
        CommandResult direct = new CommandResult(
                true,
                "direct prompt",
                CommandResult.Delivery.PROMPT
        );

        assertTrue(result.success());
        assertEquals("review the project", result.output());
        assertEquals(CommandResult.Delivery.PROMPT, result.delivery());
        assertTrue(result.shouldSubmitPrompt());
        assertTrue(direct.success());
        assertEquals("direct prompt", direct.output());
        assertEquals(CommandResult.Delivery.PROMPT, direct.delivery());
        assertTrue(direct.shouldSubmitPrompt());
    }

    @Test
    void rejectsBlankPromptAndFailedPromptDelivery() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandResult.prompt(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandResult.prompt("   ")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandResult(
                        true,
                        null,
                        CommandResult.Delivery.PROMPT
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandResult(
                        true,
                        "  \t\n  ",
                        CommandResult.Delivery.PROMPT
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandResult(
                        false,
                        "failed",
                        CommandResult.Delivery.PROMPT
                )
        );
    }

    @Test
    void twoArgumentConstructorRetainsLocalCompatibility() {
        CommandResult success = new CommandResult(true, "old success");
        CommandResult error = new CommandResult(false, "old error");

        assertEquals(CommandResult.Delivery.LOCAL, success.delivery());
        assertFalse(success.shouldSubmitPrompt());
        assertEquals(CommandResult.Delivery.LOCAL, error.delivery());
        assertFalse(error.shouldSubmitPrompt());
    }
}
