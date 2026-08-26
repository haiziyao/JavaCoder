package com.jcoder.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoder.message.Message;
import com.jcoder.message.ToolCallBlock;
import com.jcoder.message.ToolResult;
import com.jcoder.prompt.PromptContent;
import com.jcoder.tool.ToolDefinition;

import java.util.List;
import java.util.Objects;

public final class ContextTokenEstimator {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    private static final int MESSAGE_OVERHEAD = 4;
    private static final int TOOL_CALL_OVERHEAD = 12;
    private static final int TOOL_RESULT_OVERHEAD = 8;
    private static final int TOOL_DEFINITION_OVERHEAD = 8;

    private ContextTokenEstimator() {
    }

    /**
     * 估算一次真正发给模型的完整 Prompt。
     *
     * 包括：
     * 1. system prompt
     * 2. 环境消息、历史消息、Plan 提醒
     * 3. tool call 和 tool result
     * 4. 本轮注入的全部工具 Schema
     */
    public static int estimate(
            PromptContent promptContent
    ) {
        Objects.requireNonNull(
                promptContent,
                "promptContent"
        );

        int total = estimateText(
                promptContent.system()
        );

        total += estimateMessages(
                promptContent.messages()
        );

        for (ToolDefinition tool :
                promptContent.tools()) {

            total += TOOL_DEFINITION_OVERHEAD;
            total += estimateJson(tool);
        }

        return total;
    }

    public static int estimateMessages(
            List<Message> messages
    ) {
        if (messages == null
                || messages.isEmpty()) {
            return 0;
        }

        int total = 0;

        for (Message message : messages) {
            if (message == null) {
                continue;
            }

            total += MESSAGE_OVERHEAD;
            total += estimateText(
                    message.getRole()
            );
            total += estimateText(
                    message.getContent()
            );

            List<ToolCallBlock> toolCalls =
                    message.getToolCalls();

            if (toolCalls != null) {
                for (ToolCallBlock call :
                        toolCalls) {

                    if (call == null) {
                        continue;
                    }

                    total += TOOL_CALL_OVERHEAD;
                    total += estimateText(
                            call.toolId()
                    );
                    total += estimateText(
                            call.type()
                    );
                    total += estimateText(
                            call.toolName()
                    );
                    total += estimateJson(
                            call.params()
                    );
                }
            }

            List<ToolResult> toolResults =
                    message.getToolResults();

            if (toolResults != null) {
                for (ToolResult result :
                        toolResults) {

                    if (result == null) {
                        continue;
                    }

                    total += TOOL_RESULT_OVERHEAD;
                    total += estimateText(
                            result.toolId()
                    );
                    total += estimateText(
                            result.content()
                    );
                }
            }
        }

        return total;
    }

    /**
     * 简单估算规则：
     *
     * ASCII 内容约 4 个字符一个 token；
     * 中文等非 ASCII 字符按一个字符一个 token。
     *
     * 这不是精确 tokenizer，但比统一按字符数除以 4
     * 更适合中英文混合的代码对话。
     */
    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int asciiCharacters = 0;
        int nonAsciiCharacters = 0;

        for (int index = 0;
             index < text.length();) {

            int codePoint =
                    text.codePointAt(index);

            if (codePoint <= 0x7F) {
                asciiCharacters++;
            } else {
                nonAsciiCharacters++;
            }

            index += Character.charCount(codePoint);
        }

        int asciiTokens =
                (asciiCharacters + 3) / 4;

        return asciiTokens
                + nonAsciiCharacters;
    }

    private static int estimateJson(Object value) {
        if (value == null) {
            return 0;
        }

        try {
            return estimateText(
                    OBJECT_MAPPER.writeValueAsString(
                            value
                    )
            );
        } catch (JsonProcessingException e) {
            return estimateText(
                    String.valueOf(value)
            );
        }
    }
}