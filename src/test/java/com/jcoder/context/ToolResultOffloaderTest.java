package com.jcoder.context;

import com.jcoder.message.ConversationManager;
import com.jcoder.message.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultOffloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void offloadsSingleLargeResultAndKeepsIdentity() throws Exception {
        String original = "x".repeat(
                ToolResultOffloader.SINGLE_RESULT_LIMIT + 1
        );

        ConversationManager conversation = new ConversationManager();
        conversation.addToolResultsMsg(List.of(
                new ToolResult("../../danger", original, true)
        ));

        ToolResultOffloader.OffloadReport report =
                ToolResultOffloader.apply(conversation, tempDir);

        assertTrue(report.changed());
        assertEquals(1, report.offloadedResults());
        assertEquals(original.length(), report.originalCharacters());
        assertEquals(1, report.files().size());

        Path expectedDirectory = tempDir
                .resolve(".mycoder/context/tool-results")
                .toAbsolutePath()
                .normalize();

        Path outputFile = report.files().getFirst();
        assertTrue(outputFile.startsWith(expectedDirectory),
                "unsafe tool id must not escape the spill directory");
        assertEquals(original, Files.readString(outputFile));

        ToolResult replacement = conversation
                .getHistoryCopy()
                .getFirst()
                .getToolResults()
                .getFirst();

        assertEquals("../../danger", replacement.toolId());
        assertTrue(replacement.isError());
        assertTrue(replacement.content()
                .startsWith("<persisted-tool-result>"));
        assertTrue(replacement.content().contains(outputFile.toString()));
        assertTrue(replacement.content().length() < original.length());
    }

    @Test
    void keepsSmallResultInMemory() {
        ConversationManager conversation = new ConversationManager();
        conversation.addToolResultsMsg(List.of(
                new ToolResult("small", "hello", false)
        ));

        ToolResultOffloader.OffloadReport report =
                ToolResultOffloader.apply(conversation, tempDir);

        assertFalse(report.changed());
        assertEquals("hello", conversation
                .getHistoryCopy()
                .getFirst()
                .getToolResults()
                .getFirst()
                .content());
    }

    @Test
    void offloadsLargestResultsUntilAggregateFits() {
        List<ToolResult> results = new ArrayList<>();

        for (int index = 0; index < 5; index++) {
            results.add(new ToolResult(
                    "aggregate-" + index,
                    String.valueOf(index).repeat(45_000),
                    false
            ));
        }

        ConversationManager conversation = new ConversationManager();
        conversation.addToolResultsMsg(results);

        ToolResultOffloader.OffloadReport report =
                ToolResultOffloader.apply(conversation, tempDir);

        assertTrue(report.changed());
        assertEquals(1, report.offloadedResults(),
                "one 45k result is enough to bring 225k below 200k");

        long persisted = conversation
                .getHistoryCopy()
                .getFirst()
                .getToolResults()
                .stream()
                .filter(result -> result.content()
                        .startsWith("<persisted-tool-result>"))
                .count();

        assertEquals(1, persisted);
    }

    @Test
    void secondPassIsIdempotent() {
        ConversationManager conversation = new ConversationManager();
        conversation.addToolResultsMsg(List.of(
                new ToolResult(
                        "repeat",
                        "r".repeat(
                                ToolResultOffloader.SINGLE_RESULT_LIMIT + 1
                        ),
                        false
                )
        ));

        ToolResultOffloader.OffloadReport first =
                ToolResultOffloader.apply(conversation, tempDir);

        String replacement = conversation
                .getHistoryCopy()
                .getFirst()
                .getToolResults()
                .getFirst()
                .content();

        ToolResultOffloader.OffloadReport second =
                ToolResultOffloader.apply(conversation, tempDir);

        assertTrue(first.changed());
        assertFalse(second.changed());
        assertEquals(replacement, conversation
                .getHistoryCopy()
                .getFirst()
                .getToolResults()
                .getFirst()
                .content());
    }

    @Test
    void keepsOriginalWhenSpillDirectoryCannotBeCreated() throws Exception {
        Path regularFile = tempDir.resolve("not-a-directory");
        Files.writeString(regularFile, "occupied");

        String original = "z".repeat(
                ToolResultOffloader.SINGLE_RESULT_LIMIT + 1
        );

        ConversationManager conversation = new ConversationManager();
        conversation.addToolResultsMsg(List.of(
                new ToolResult("write-failure", original, false)
        ));

        ToolResultOffloader.OffloadReport report =
                ToolResultOffloader.apply(conversation, regularFile);

        assertFalse(report.changed());
        assertEquals(original, conversation
                .getHistoryCopy()
                .getFirst()
                .getToolResults()
                .getFirst()
                .content());
    }
}
