package com.jcoder.agent;

import com.jcoder.llm.LLMClient;
import com.jcoder.llm.model.ResponseBody;
import com.jcoder.llm.model.StreamBlock;
import com.jcoder.message.ConversationManager;
import com.jcoder.prompt.PromptContent;
import com.jcoder.skill.SkillCatalog;
import com.jcoder.skill.SkillRuntime;
import com.jcoder.tool.ToolRegister;
import com.jcoder.tool.impl.LoadSkillTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillIntegrationTest {

    private static final String BODY = "UNIQUE_AGENT_SKILL_SOP";

    @TempDir
    Path temporaryDirectory;

    @Test
    void resetContextManagementClearsActiveSkillsAndCachedPrompt() throws Exception {
        SkillRuntime runtime = runtime();
        runtime.activate("java-review");
        Agent agent = new Agent(new FinalAnswerClient(), new ToolRegister(), 128_000, 8_192);
        agent.setSkillRuntime(runtime);
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("hello");

        agent.agentLoop(conversation, new AgentEventQueue(16));
        assertFalse(runtime.activeNames().isEmpty());
        assertTrue(agent.getCurrentPromptContent().messages().stream()
                .anyMatch(message -> message.getContent().contains(BODY)));

        agent.resetContextManagement();

        assertTrue(runtime.activeNames().isEmpty());
        assertNull(agent.getCurrentPromptContent());
    }

    @Test
    void loadSkillToolCallMakesSopVisibleOnNextModelRequest() throws Exception {
        SkillRuntime runtime = runtime();
        ToolRegister tools = new ToolRegister();
        tools.register(new LoadSkillTool(runtime));
        ToolThenAnswerClient client = new ToolThenAnswerClient();
        Agent agent = new Agent(client, tools, 128_000, 8_192);
        agent.setSkillRuntime(runtime);
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("review this Java code");

        agent.agentLoop(conversation, new AgentEventQueue(32));

        assertEquals(2, client.prompts.size());
        assertFalse(containsBody(client.prompts.get(0)));
        assertTrue(containsBody(client.prompts.get(1)));
        assertEquals(List.of("java-review"), runtime.activeNames());
        assertFalse(conversation.getHistoryCopy().stream()
                .anyMatch(message -> message.getContent().contains(BODY)));
    }

    private SkillRuntime runtime() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        Path directory = projectRoot.resolve("java-review");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), """
                ---
                name: java-review
                description: Review Java source.
                ---

                %s
                """.formatted(BODY), StandardCharsets.UTF_8);
        SkillCatalog catalog = new SkillCatalog(
                temporaryDirectory.resolve("user-skills"), projectRoot);
        catalog.reload();
        return new SkillRuntime(catalog);
    }

    private static boolean containsBody(PromptContent prompt) {
        return prompt.system().contains(BODY) || prompt.messages().stream()
                .anyMatch(message -> message.getContent().contains(BODY));
    }

    private static BlockingQueue<StreamBlock> queue(StreamBlock... blocks) {
        BlockingQueue<StreamBlock> queue = new LinkedBlockingQueue<>();
        queue.addAll(List.of(blocks));
        return queue;
    }

    private static final class FinalAnswerClient implements LLMClient {
        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            return queue(new StreamBlock.ContentDelta("done"), new StreamBlock.StreamEnd("stop"));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("Agent must use stream()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }

    private static final class ToolThenAnswerClient implements LLMClient {
        private final List<PromptContent> prompts = new ArrayList<>();

        @Override
        public BlockingQueue<StreamBlock> stream(PromptContent promptContent) {
            prompts.add(promptContent);
            if (prompts.size() == 1) {
                return queue(
                        new StreamBlock.ToolCall(
                                "call-1", "LoadSkill", "function",
                                Map.of("name", "java-review")),
                        new StreamBlock.StreamEnd("tool_calls")
                );
            }
            return queue(new StreamBlock.ContentDelta("done"), new StreamBlock.StreamEnd("stop"));
        }

        @Override
        public ResponseBody request(PromptContent promptContent) {
            throw new AssertionError("Agent must use stream()");
        }

        @Override
        public String getLastRequestJson() {
            return "";
        }
    }
}
