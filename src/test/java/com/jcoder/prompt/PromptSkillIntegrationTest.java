package com.jcoder.prompt;

import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.skill.SkillCatalog;
import com.jcoder.skill.SkillRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSkillIntegrationTest {

    private static final String SKILL_NAME = "java-review";
    private static final String DESCRIPTION = "Review Java concurrency.";
    private static final String BODY = "UNIQUE_JAVA_REVIEW_SOP_BODY";

    @TempDir
    Path temporaryDirectory;

    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final EnvironmentContext environment = new EnvironmentContext(
            "E:\\workspace",
            "Windows 11",
            "amd64",
            "powershell.exe",
            LocalDate.of(2026, 8, 27)
    );

    @Test
    void inactiveSkillExposesOnlyNameAndDescriptionInSystemPrompt() throws Exception {
        SkillRuntime runtime = runtime();
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("review this project");

        PromptContent prompt = promptBuilder.build(
                conversation, List.of(), environment, AgentMode.NORMAL, 1, "", runtime);

        assertTrue(prompt.system().contains(SKILL_NAME));
        assertTrue(prompt.system().contains(DESCRIPTION));
        assertFalse(prompt.system().contains(BODY));
        assertFalse(prompt.messages().stream()
                .anyMatch(message -> message.getContent().contains(BODY)));
        assertEquals(1, conversation.size());
        assertEquals("review this project", conversation.getHistoryCopy().getFirst().getContent());
    }

    @Test
    void activeSkillBodyAppearsOnceAsTemporarySystemMessageOnly() throws Exception {
        SkillRuntime runtime = runtime();
        runtime.activate(SKILL_NAME);
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("review this project");

        PromptContent prompt = promptBuilder.build(
                conversation, List.of(), environment, AgentMode.NORMAL, 1, "", runtime);

        List<Message> activeMessages = prompt.messages().stream()
                .filter(message -> message.getContent().contains(BODY))
                .toList();
        String serializedPrompt = prompt.system() + "\n" + prompt.messages().stream()
                .map(Message::getContent)
                .reduce("", (left, right) -> left + "\n" + right);

        assertEquals(1, activeMessages.size());
        assertEquals("system", activeMessages.getFirst().getRole());
        assertEquals(1, occurrences(serializedPrompt, BODY));
        assertFalse(prompt.system().contains(BODY));
        assertEquals(1, conversation.size());
        assertFalse(conversation.getHistoryCopy().stream()
                .anyMatch(message -> message.getContent().contains(BODY)));
    }

    @Test
    void legacyOverloadsRetainTheirPreviousBehavior() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMsg("hello");

        PromptContent legacyFive = promptBuilder.build(
                conversation, List.of(), environment, AgentMode.NORMAL, 1);
        PromptContent explicitNull = promptBuilder.build(
                conversation, List.of(), environment, AgentMode.NORMAL, 1, "", null);
        PromptContent legacyMemory = promptBuilder.build(
                conversation, List.of(), environment, AgentMode.NORMAL, 1, "memory reminder");

        assertEquivalent(legacyFive, explicitNull);
        assertFalse(legacyFive.system().contains("## Available Skills"));
        assertTrue(legacyMemory.messages().stream()
                .anyMatch(message -> "memory reminder".equals(message.getContent())));
        assertFalse(legacyMemory.messages().stream()
                .anyMatch(message -> message.getContent().contains("<active-skills>")));
    }

    private SkillRuntime runtime() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("project-skills");
        Path directory = projectRoot.resolve(SKILL_NAME);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), "---\n"
                        + "name: " + SKILL_NAME + "\n"
                        + "description: " + DESCRIPTION + "\n"
                        + "---\n\n" + BODY + "\n",
                StandardCharsets.UTF_8);
        SkillCatalog catalog = new SkillCatalog(
                temporaryDirectory.resolve("user-skills"), projectRoot);
        catalog.reload();
        return new SkillRuntime(catalog);
    }

    private static void assertEquivalent(PromptContent expected, PromptContent actual) {
        assertEquals(expected.system(), actual.system());
        assertEquals(expected.tools(), actual.tools());
        assertEquals(
                expected.messages().stream()
                        .map(message -> message.getRole() + "\u0000" + message.getContent())
                        .toList(),
                actual.messages().stream()
                        .map(message -> message.getRole() + "\u0000" + message.getContent())
                        .toList()
        );
    }

    private static int occurrences(String text, String expected) {
        return (text.length() - text.replace(expected, "").length()) / expected.length();
    }
}
