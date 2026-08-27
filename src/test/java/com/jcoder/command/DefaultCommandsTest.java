package com.jcoder.command;

import com.jcoder.agent.Agent;
import com.jcoder.agent.AgentEventQueue;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.permission.PermissionChecker;
import com.jcoder.permission.PermissionMode;
import com.jcoder.prompt.PromptContent;
import com.jcoder.prompt.AgentMode;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCommandsTest {

    @Test
    void createRegistersCanonicalCommandsAndAliases() {
        SlashCommandRegistry registry = DefaultCommands.create();

        assertEquals(
                List.of(
                        "clear", "compact", "help", "memory",
                        "permission", "review", "session", "status"
                ),
                registry.listCommands().stream()
                        .map(SlashCommand::name)
                        .toList()
        );
        assertSame(registry.find("help").orElseThrow(),
                registry.find("h").orElseThrow());
        assertSame(registry.find("help").orElseThrow(),
                registry.find("?").orElseThrow());
        assertSame(registry.find("permission").orElseThrow(),
                registry.find("perm").orElseThrow());
        assertSame(registry.find("compact").orElseThrow(),
                registry.find("c").orElseThrow());
        assertSame(registry.find("status").orElseThrow(),
                registry.find("s").orElseThrow());
        assertSame(registry.find("memory").orElseThrow(),
                registry.find("mem").orElseThrow());
    }

    @Test
    void helpListsCanonicalCommandsAliasesAndCommandDetails() throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CommandContext context = context(new CountingLlmClient());

        CommandResult list = registry.execute("/help", context);
        assertTrue(list.success());
        assertTrue(list.output().contains("/compact (/c)"));
        assertTrue(list.output().contains("/help (/h, /?)"));
        assertTrue(list.output().contains("/permission (/perm)"));
        assertTrue(list.output().contains("/clear"));
        assertTrue(list.output().contains("/status (/s)"));
        assertTrue(list.output().contains("/review"));

        CommandResult detail = registry.execute("/help compact", context);
        assertTrue(detail.success());
        assertTrue(detail.output().contains("/compact - 手动压缩旧对话上下文"));
        assertTrue(detail.output().contains("别名: /c"));

        CommandResult unknown = registry.execute("/help missing", context);
        assertFalse(unknown.success());
        assertEquals("Unknown command: missing", unknown.output());
    }

    @Test
    void permissionAndAliasCycleCheckerMode() throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CountingLlmClient client = new CountingLlmClient();
        CommandContext context = context(client);
        PermissionChecker checker = new PermissionChecker(
                PermissionMode.DEFAULT,
                Path.of(".")
        );
        context.agent().setChecker(checker);

        CommandResult first = registry.execute("/permission", context);
        assertTrue(first.success());
        assertEquals(PermissionMode.ACCEPT_EDITS, checker.getMode());
        assertTrue(first.output().contains("ACCEPT_EDITS"));

        CommandResult second = registry.execute("/perm", context);
        assertTrue(second.success());
        assertEquals(PermissionMode.PLAN, checker.getMode());
        assertTrue(second.output().contains("PLAN"));
        assertEquals(0, client.streamCalls);
    }

    @Test
    void permissionRejectsArgumentsAndMissingChecker() throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CommandContext withoutChecker = context(new CountingLlmClient());

        CommandResult missing = registry.execute("/permission", withoutChecker);
        assertFalse(missing.success());
        assertEquals("PermissionChecker 未装配", missing.output());

        PermissionChecker checker = new PermissionChecker(
                PermissionMode.DEFAULT,
                Path.of(".")
        );
        withoutChecker.agent().setChecker(checker);
        CommandResult withArguments = registry.execute(
                "/permission bypass",
                withoutChecker
        );
        assertFalse(withArguments.success());
        assertEquals("Usage: /permission", withArguments.output());
        assertEquals(PermissionMode.DEFAULT, checker.getMode());
    }

    @Test
    void compactAndAliasNoOpWithoutCallingLlmAndArgumentsAreRejected()
            throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CountingLlmClient client = new CountingLlmClient();
        CommandContext context = context(client);

        CommandResult canonical = registry.execute("/compact", context);
        CommandResult alias = registry.execute("/c", context);
        CommandResult withArguments = registry.execute("/compact now", context);

        assertTrue(canonical.success());
        assertTrue(canonical.output().contains("没有足够的旧消息可压缩"));
        assertTrue(alias.success());
        assertTrue(alias.output().contains("没有足够的旧消息可压缩"));
        assertFalse(withArguments.success());
        assertEquals("Usage: /compact", withArguments.output());
        assertEquals(0, client.streamCalls,
                "no-op and argument validation must not call the model");
        assertTrue(context.conversation().getHistoryCopy().isEmpty());
    }

    @Test
    void clearReportsRemovedMessagesResetsPromptAndDoesNotCallLlm()
            throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CountingLlmClient client = new CountingLlmClient();
        CommandContext context = context(client);
        context.conversation().addUserMsg("first");
        context.conversation().addAssistantMsg("second");
        context.conversation().addUserMsg("third");

        context.agent().agentLoop(
                context.conversation(),
                new AgentEventQueue(16)
        );
        assertTrue(context.agent().getCurrentPromptContent() != null);
        int callsBeforeClear = client.streamCalls;

        CommandResult result = registry.execute("/clear", context);

        assertTrue(result.success());
        assertTrue(result.output().contains("已清空 3 条消息"));
        assertEquals(0, context.conversation().size());
        assertEquals(null, context.agent().getCurrentPromptContent());
        assertEquals(callsBeforeClear, client.streamCalls,
                "clear itself must not call the model");
    }

    @Test
    void clearWithArgumentsDoesNotClearConversationOrCallLlm()
            throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CountingLlmClient client = new CountingLlmClient();
        CommandContext context = context(client);
        context.conversation().addUserMsg("keep me");

        CommandResult result = registry.execute("/clear now", context);

        assertFalse(result.success());
        assertEquals("Usage: /clear", result.output());
        assertEquals(1, context.conversation().size());
        assertEquals("keep me", context.conversation()
                .getHistoryCopy().getFirst().getContent());
        assertEquals(0, client.streamCalls);
    }

    @Test
    void statusAndAliasShowConfiguredRuntimeStateWithoutCallingLlm()
            throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CountingLlmClient client = new CountingLlmClient();
        Agent agent = new Agent(
                client,
                ToolRegister.createDefault(),
                77_777,
                3_333
        );
        agent.setMode(AgentMode.PLAN);
        agent.setChecker(new PermissionChecker(
                PermissionMode.BYPASS,
                Path.of(".")
        ));
        agent.setWorkDir("E:\\explicit-status-workdir");
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("status should count this history");
        conversation.addAssistantMsg("and this response");
        CommandContext context = new CommandContext(agent, conversation);

        CommandResult canonical = registry.execute("/status", context);
        CommandResult alias = registry.execute("/s", context);

        assertTrue(canonical.success());
        assertEquals(canonical.output(), alias.output());
        assertTrue(canonical.output().contains("Agent mode:       PLAN"));
        assertTrue(canonical.output().contains("Permission mode:  BYPASS"));
        assertTrue(canonical.output().contains("Messages:         2"));
        assertTrue(canonical.output().matches("(?s).*History tokens:\\s+~[1-9]\\d*.*"));
        assertTrue(canonical.output().contains("Tools:            6"));
        assertTrue(canonical.output().contains("Context window:   77777"));
        assertTrue(canonical.output().contains("Max output:       3333"));
        assertTrue(canonical.output().contains(
                "Directory:        E:\\explicit-status-workdir"
        ));
        assertEquals(0, client.streamCalls);
    }

    @Test
    void statusShowsMissingCheckerAndRejectsArgumentsWithoutCallingLlm()
            throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CountingLlmClient client = new CountingLlmClient();
        CommandContext context = context(client);

        CommandResult status = registry.execute("/status", context);
        CommandResult withArguments = registry.execute("/status verbose", context);

        assertTrue(status.success());
        assertTrue(status.output().contains("Permission mode:  NOT_CONFIGURED"));
        assertFalse(withArguments.success());
        assertEquals("Usage: /status", withArguments.output());
        assertEquals(0, client.streamCalls);
    }

    @Test
    void reviewCreatesPromptWithReviewFocusWithoutCallingLlm()
            throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CountingLlmClient client = new CountingLlmClient();
        CommandContext context = context(client);

        CommandResult result = registry.execute("/review", context);

        assertTrue(result.success());
        assertTrue(result.shouldSubmitPrompt());
        assertEquals(CommandResult.Delivery.PROMPT, result.delivery());
        assertFalse(result.output().isBlank());
        assertTrue(result.output().contains("Review the current project changes"));
        assertTrue(result.output().contains("logic and correctness"));
        assertTrue(result.output().contains("security or destructive-operation risks"));
        assertTrue(result.output().contains("missing error handling and edge cases"));
        assertTrue(result.output().contains("regressions in existing behavior"));
        assertTrue(result.output().contains("tests that are missing"));
        assertEquals(0, client.streamCalls,
                "expanding a prompt command must not invoke the model itself");
    }

    @Test
    void reviewPreservesChineseAdditionalFocusWithoutCallingLlm()
            throws Exception {
        SlashCommandRegistry registry = DefaultCommands.create();
        CountingLlmClient client = new CountingLlmClient();
        CommandContext context = context(client);
        String focus = "额外关注并发安全、中文路径，以及 Windows 下的换行处理";

        CommandResult result = registry.execute(
                "/review " + focus,
                context
        );

        assertTrue(result.shouldSubmitPrompt());
        assertTrue(result.output().contains(
                "Additional review focus from the user:"
        ));
        assertTrue(result.output().contains(focus));
        assertEquals(0, client.streamCalls);
    }

    private static CommandContext context(CountingLlmClient client) {
        Agent agent = new Agent(
                client,
                new ToolRegister(),
                128_000,
                8_192
        );
        return new CommandContext(agent, new ConversationManager());
    }

    private static final class CountingLlmClient implements LLMClient {

        private int streamCalls;

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            streamCalls++;
            return new LinkedBlockingQueue<>(List.of(
                    new StreamBlock.StreamEnd("stop")
            ));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("slash command tests must not call request()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
