package com.jcoder.ui;

import com.jcoder.agent.Agent;
import com.jcoder.command.CommandResult;
import com.jcoder.command.SlashCommand;
import com.jcoder.command.SlashCommandRegistry;
import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolRegister;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CmdUITest {

    @Test
    void injectedSlashCommandExecutesAndNeverEntersConversation() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<String> receivedArguments = new AtomicReference<>();
        registry.register(new SlashCommand(
                "ping",
                "test command",
                List.of(),
                (context, arguments) -> {
                    executions.incrementAndGet();
                    receivedArguments.set(arguments);
                    return CommandResult.success("pong");
                }
        ));

        ConversationManager conversation = new ConversationManager();
        CapturedIo captured = runUi(
                registry,
                conversation,
                "/ping hello world\nexit\n"
        );

        assertEquals(1, executions.get());
        assertEquals("hello world", receivedArguments.get());
        assertTrue(captured.stdout().contains("pong"));
        assertTrue(conversation.getHistoryCopy().isEmpty());
    }

    @Test
    void unknownSlashCommandReportsErrorAndNeverEntersConversation() {
        ConversationManager conversation = new ConversationManager();

        CapturedIo captured = runUi(
                new SlashCommandRegistry(),
                conversation,
                "/missing value\nexit\n"
        );

        assertTrue(captured.stderr().contains("[命令错误]"));
        assertTrue(captured.stderr().contains("Unknown command: /missing"));
        assertTrue(conversation.getHistoryCopy().isEmpty());
    }

    @Test
    void promptCommandSubmitsExpandedPromptInsteadOfSlashText() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        registry.register(new SlashCommand(
                "expand",
                "expand into a model prompt",
                List.of(),
                (context, arguments) -> CommandResult.prompt(
                        "expanded prompt: " + arguments
                )
        ));
        ConversationManager conversation = new ConversationManager();
        CapturingLlmClient client = new CapturingLlmClient();

        runUi(
                registry,
                conversation,
                "/expand 中文附加要求\nexit\n",
                client
        );

        assertEquals(1, client.streamCalls);
        assertEquals(1, conversation.getHistoryCopy().size());
        String submitted = conversation.getHistoryCopy().getFirst().getContent();
        assertEquals("expanded prompt: 中文附加要求", submitted);
        assertTrue(!submitted.contains("/expand"));
        assertTrue(client.lastPrompt.messages().stream()
                .anyMatch(message -> submitted.equals(message.getContent())),
                "the model must receive the same expanded prompt stored in history");
    }

    private static CapturedIo runUi(
            SlashCommandRegistry registry,
            ConversationManager conversation,
            String input
    ) {
        return runUi(
                registry,
                conversation,
                input,
                new FailingLlmClient()
        );
    }

    private static CapturedIo runUi(
            SlashCommandRegistry registry,
            ConversationManager conversation,
            String input,
            LLMClient client
    ) {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try {
            System.setIn(new ByteArrayInputStream(
                    input.getBytes(StandardCharsets.UTF_8)
            ));
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));

            Agent agent = new Agent(
                    client,
                    new ToolRegister(),
                    128_000,
                    8_192
            );
            new CmdUI(registry).run(agent, conversation);

            return new CapturedIo(
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8)
            );
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record CapturedIo(String stdout, String stderr) {
    }

    private static final class FailingLlmClient implements LLMClient {

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            throw new AssertionError("slash commands must not reach the LLM");
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("slash commands must not reach the LLM");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }

    private static final class CapturingLlmClient implements LLMClient {

        private volatile PromptContent lastPrompt;
        private int streamCalls;

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            streamCalls++;
            lastPrompt = promptContent;
            return new LinkedBlockingQueue<>(List.of(
                    new StreamBlock.StreamEnd("stop")
            ));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("CmdUI must use stream()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
