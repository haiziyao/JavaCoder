package com.jcoder.command;

import com.jcoder.agent.Agent;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandRegistryTest {

    @Test
    void executesByCanonicalNameAndAliasAndPassesArguments() throws Exception {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        AtomicReference<CommandContext> receivedContext = new AtomicReference<>();
        AtomicReference<String> receivedArguments = new AtomicReference<>();

        registry.register(new SlashCommand(
                "compact",
                "compact context",
                List.of("c"),
                (context, arguments) -> {
                    receivedContext.set(context);
                    receivedArguments.set(arguments);
                    return CommandResult.success("done:" + arguments);
                }
        ));

        CommandContext context = commandContext();
        CommandResult byName = registry.execute(
                "/COMPACT  keep  this",
                context
        );
        assertTrue(byName.success());
        assertEquals("done:keep  this", byName.output());
        assertSame(context, receivedContext.get());
        assertEquals("keep  this", receivedArguments.get());

        CommandResult byAlias = registry.execute("/C retry", context);
        assertTrue(byAlias.success());
        assertEquals("done:retry", byAlias.output());
        assertEquals("retry", receivedArguments.get());
    }

    @Test
    void listCommandsContainsOnlyCanonicalCommandsAndIsSorted() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        SlashCommand zeta = command("zeta", "z");
        SlashCommand alpha = command("alpha", "a");
        SlashCommand middle = command("middle", "m");

        registry.register(zeta);
        registry.register(alpha);
        registry.register(middle);

        assertEquals(
                List.of("alpha", "middle", "zeta"),
                registry.listCommands().stream()
                        .map(SlashCommand::name)
                        .toList()
        );
        assertEquals(3, registry.listCommands().size(),
                "aliases must not appear as extra list entries");
    }

    @Test
    void rejectsCanonicalNameAgainstExistingCanonicalWithoutPollution() {
        assertConflictDoesNotPollute(
                command("alpha", "a"),
                command("ALPHA", "unique-one"),
                "unique-one"
        );
    }

    @Test
    void rejectsCanonicalNameAgainstExistingAliasWithoutPollution() {
        assertConflictDoesNotPollute(
                command("alpha", "a"),
                command("A", "unique-two"),
                "unique-two"
        );
    }

    @Test
    void rejectsAliasAgainstExistingCanonicalWithoutPollution() {
        assertConflictDoesNotPollute(
                command("alpha", "a"),
                new SlashCommand(
                        "beta", "", List.of("unique-three", "ALPHA"), NOOP_HANDLER
                ),
                "unique-three"
        );
    }

    @Test
    void rejectsAliasAgainstExistingAliasWithoutPollution() {
        assertConflictDoesNotPollute(
                command("alpha", "a"),
                new SlashCommand(
                        "gamma", "", List.of("unique-four", "A"), NOOP_HANDLER
                ),
                "unique-four"
        );
    }

    @Test
    void unknownAndInvalidInputReturnErrors() throws Exception {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        registry.register(command("help", "h"));
        CommandContext context = commandContext();

        CommandResult notSlash = registry.execute("help", context);
        CommandResult emptySlash = registry.execute("/   ", context);
        CommandResult unknown = registry.execute("/missing arg", context);

        assertFalse(notSlash.success());
        assertEquals("Invalid slash command", notSlash.output());
        assertFalse(emptySlash.success());
        assertEquals("Invalid slash command", emptySlash.output());
        assertFalse(unknown.success());
        assertTrue(unknown.output().contains("Unknown command: /missing"));
    }

    private static final SlashCommand.Handler NOOP_HANDLER =
            (context, arguments) -> CommandResult.success(arguments);

    private static SlashCommand command(String name, String alias) {
        return new SlashCommand(name, "", List.of(alias), NOOP_HANDLER);
    }

    private static void assertConflictDoesNotPollute(
            SlashCommand existing,
            SlashCommand conflicting,
            String uniqueNewNameOrAlias
    ) {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        registry.register(existing);

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.register(conflicting)
        );

        assertEquals(List.of(existing), registry.listCommands());
        assertSame(existing, registry.find(existing.name()).orElseThrow());
        for (String alias : existing.aliases()) {
            assertSame(existing, registry.find(alias).orElseThrow());
        }
        assertTrue(registry.find(conflicting.name()).isEmpty()
                        || registry.find(conflicting.name()).orElseThrow() == existing,
                "failed registration must not install the conflicting command");
        assertTrue(registry.find(uniqueNewNameOrAlias).isEmpty(),
                "failed registration must not leave a unique alias behind");
    }

    private static CommandContext commandContext() {
        Agent agent = new Agent(
                new NoopLlmClient(),
                new ToolRegister(),
                128_000,
                8_192
        );
        return new CommandContext(agent, new ConversationManager());
    }

    private static final class NoopLlmClient implements LLMClient {

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            return new LinkedBlockingQueue<>(List.of(
                    new StreamBlock.StreamEnd("stop")
            ));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("slash command unit tests must not call LLM");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
