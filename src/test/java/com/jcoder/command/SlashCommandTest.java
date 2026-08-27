package com.jcoder.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandTest {

    private static final SlashCommand.Handler NOOP_HANDLER =
            (context, arguments) -> CommandResult.success(arguments);

    @Test
    void normalizesNameAndAliasesAndMatchesBoth() {
        SlashCommand command = new SlashCommand(
                "  HeLP  ",
                "  show commands  ",
                List.of(" H ", "UsAgE"),
                NOOP_HANDLER
        );

        assertEquals("help", command.name());
        assertEquals("show commands", command.description());
        assertEquals(List.of("h", "usage"), command.aliases());
        assertTrue(command.matches(" HELP "));
        assertTrue(command.matches("h"));
        assertTrue(command.matches(" UsAgE "));
        assertFalse(command.matches("missing"));
        assertFalse(command.matches(null));
    }

    @Test
    void rejectsInvalidNamesAndAliases() {
        assertThrows(IllegalArgumentException.class, () -> command(null));
        assertThrows(IllegalArgumentException.class, () -> command("   "));
        assertThrows(IllegalArgumentException.class, () -> command("/help"));
        assertThrows(IllegalArgumentException.class, () -> command("bad name"));
        assertThrows(IllegalArgumentException.class, () -> new SlashCommand(
                "help", "", List.of("bad/alias"), NOOP_HANDLER
        ));
        assertThrows(IllegalArgumentException.class, () -> new SlashCommand(
                "help", "", List.of("bad alias"), NOOP_HANDLER
        ));
    }

    @Test
    void rejectsAliasThatDuplicatesNameOrAnotherAlias() {
        assertThrows(IllegalArgumentException.class, () -> new SlashCommand(
                "help", "", List.of("HELP"), NOOP_HANDLER
        ));
        assertThrows(IllegalArgumentException.class, () -> new SlashCommand(
                "help", "", List.of("h", " H "), NOOP_HANDLER
        ));
    }

    private static SlashCommand command(String name) {
        return new SlashCommand(name, "", List.of(), NOOP_HANDLER);
    }
}
