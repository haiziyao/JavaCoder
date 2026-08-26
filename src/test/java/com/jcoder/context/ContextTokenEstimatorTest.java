package com.jcoder.context;

import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolParamDefinition;
import com.jcoder.tool.ToolReturnDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextTokenEstimatorTest {

    @Test
    void estimatesAsciiAndChineseSeparately() {
        assertEquals(2,
                ContextTokenEstimator.estimateText("abcdefgh"));
        assertEquals(3,
                ContextTokenEstimator.estimateText("上下文"));
        assertEquals(0,
                ContextTokenEstimator.estimateText(null));
    }

    @Test
    void includesToolCallsAndToolResults() {
        Message plain = new Message("user", "run");

        Message assistant = new Message("assistant", "");
        assistant.setToolCalls(List.of(
                new ToolCallBlock(
                        "call-1",
                        "function",
                        "read_file",
                        Map.of("path", "README.md")
                )
        ));

        Message result = new Message("tool", "");
        result.setToolResults(List.of(
                new ToolResult(
                        "call-1",
                        "x".repeat(4_000),
                        false
                )
        ));

        int plainTokens =
                ContextTokenEstimator.estimateMessages(
                        List.of(plain)
                );

        int completeTokens =
                ContextTokenEstimator.estimateMessages(
                        List.of(plain, assistant, result)
                );

        assertTrue(completeTokens > plainTokens + 900,
                "large tool output must be included in the estimate");
    }

    @Test
    void includesSystemAndToolSchemas() {
        PromptContent withoutTools = new PromptContent(
                "system instruction",
                List.of(new Message("user", "hello")),
                List.of()
        );

        ToolDefinition tool = new ToolDefinition(
                "search_docs",
                "Search library documentation",
                Map.of(
                        "query",
                        new ToolParamDefinition(
                                "string",
                                "query text",
                                Map.of("enum", List.of("java", "python"))
                        )
                ),
                List.of("query"),
                new ToolReturnDefinition("string", Map.of())
        );

        PromptContent withTools = new PromptContent(
                withoutTools.system(),
                withoutTools.messages(),
                List.of(tool)
        );

        assertTrue(
                ContextTokenEstimator.estimate(withTools)
                        > ContextTokenEstimator.estimate(withoutTools),
                "tool schemas consume context even before a tool is called"
        );
    }
}
