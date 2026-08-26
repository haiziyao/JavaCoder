package com.jcoder.context;

import com.jcoder.message.ConversationManager;
import com.jcoder.message.Message;
import com.jcoder.message.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ToolResultOffloader {

    /**
     * 单个工具结果超过约 50 KB 时落盘。
     */
    public static final int SINGLE_RESULT_LIMIT =
            50_000;

    /**
     * 同一条 tool message 中，所有结果总长度的上限。
     */
    public static final int MESSAGE_AGGREGATE_LIMIT =
            200_000;

    /**
     * Conversation 中保留的结果预览长度。
     */
    public static final int PREVIEW_CHARACTERS =
            2_000;

    private static final int ESTIMATED_METADATA_SIZE =
            300;

    private static final String OFFLOADED_PREFIX =
            "<persisted-tool-result>";

    private static final Pattern UNSAFE_FILE_NAME =
            Pattern.compile("[^a-zA-Z0-9._-]");

    private ToolResultOffloader() {
    }

    public record OffloadReport(
            int offloadedResults,
            long originalCharacters,
            long retainedCharacters,
            List<Path> files
    ) {
        public OffloadReport {
            files = files == null ? List.of() : List.copyOf(files);
        }

        public boolean changed() {
            return offloadedResults > 0;
        }

        public long removedCharacters() {
            return Math.max(
                    0, originalCharacters - retainedCharacters);
        }
    }

    private record SpillResult(
            ToolResult replacement,
            Path file
    ) {
    }

    /**
     * 直接修改 ConversationManager 中的大型 ToolResult。
     *
     * 写文件失败时保留原始结果，避免因为上下文管理
     * 导致工具结果丢失。
     */
    public static OffloadReport apply(
            ConversationManager conversation,
            Path projectRoot
    ) {
        Objects.requireNonNull(
                conversation,
                "conversation"
        );
        Objects.requireNonNull(
                projectRoot,
                "projectRoot"
        );

        Path spillDirectory =
                projectRoot
                        .toAbsolutePath()
                        .normalize()
                        .resolve(".mycoder")
                        .resolve("context")
                        .resolve("tool-results");

        int offloadedResults = 0;
        long originalCharacters = 0;
        long retainedCharacters = 0;

        List<Path> files = new ArrayList<>();

        List<Message> messages =
                conversation.getHistoryMut();

        for (Message message : messages) {
            if (message == null) {
                continue;
            }

            List<ToolResult> results =
                    message.getToolResults();

            if (results == null || results.isEmpty()) {
                continue;
            }

            boolean[] selected =
                    selectResultsToOffload(results);

            List<ToolResult> replacements = new ArrayList<>(results);

            boolean messageChanged = false;

            for (int index = 0; index < results.size(); index++) {

                if (!selected[index]) {
                    continue;
                }

                ToolResult result = results.get(index);

                if (result == null) {
                    continue;
                }

                String content =
                        safeContent(result.content());

                SpillResult spilled = spill(result, content, spillDirectory);

                if (spilled == null) {
                    continue;
                }

                replacements.set(
                        index,
                        spilled.replacement()
                );

                messageChanged = true;
                offloadedResults++;

                originalCharacters +=
                        content.length();

                retainedCharacters +=
                        spilled.replacement()
                                .content()
                                .length();

                files.add(spilled.file());
            }

            if (messageChanged) {
                message.setToolResults(
                        List.copyOf(replacements)
                );
            }
        }

        return new OffloadReport(
                offloadedResults,
                originalCharacters,
                retainedCharacters,
                files
        );
    }

    /**
     * 两次选择：
     *
     * 1. 先选择单个超过 50 KB 的结果；
     * 2. 如果整条消息仍超过 200 KB，
     *    再从最大的未选择结果开始落盘。
     */
    private static boolean[] selectResultsToOffload(List<ToolResult> results) {
        boolean[] selected = new boolean[results.size()];

        long effectiveCharacters = 0;

        List<Integer> remainingIndexes = new ArrayList<>();

        for (int index = 0; index < results.size(); index++) {

            ToolResult result = results.get(index);

            if (result == null) {
                continue;
            }

            String content = safeContent(result.content());

            effectiveCharacters += content.length();

            if (isAlreadyOffloaded(content)) {
                continue;
            }

            if (content.length() > SINGLE_RESULT_LIMIT) {

                selected[index] = true;

                effectiveCharacters -= estimatedRemovedCharacters(content.length());
            } else {
                remainingIndexes.add(index);
            }
        }

        if (effectiveCharacters <= MESSAGE_AGGREGATE_LIMIT) {
            return selected;
        }

        remainingIndexes.sort(
                Comparator.comparingInt((Integer index) -> safeContent(
                                        results.get(index).content()).length()).reversed()
        );

        for (Integer index : remainingIndexes) {

            if (effectiveCharacters
                    <= MESSAGE_AGGREGATE_LIMIT) {
                break;
            }

            String content = safeContent(results.get(index).content());

            long removed = estimatedRemovedCharacters(content.length());

            /*
             * 如果替换后的占位内容反而比原文更大，
             * 就没有必要落盘。
             */
            if (removed <= 0) {
                continue;
            }

            selected[index] = true;
            effectiveCharacters -= removed;
        }

        return selected;
    }

    private static long estimatedRemovedCharacters(
            int originalLength
    ) {
        int estimatedReplacementLength =
                Math.min(
                        originalLength,
                        PREVIEW_CHARACTERS
                ) + ESTIMATED_METADATA_SIZE;

        return Math.max(
                0,
                originalLength
                        - estimatedReplacementLength
        );
    }

    private static SpillResult spill(
            ToolResult result,
            String content,
            Path spillDirectory
    ) {
        try {
            Files.createDirectories(
                    spillDirectory
            );

            Path outputFile =
                    spillDirectory
                            .resolve(
                                    safeFileName(
                                            result.toolId()
                                    )
                            )
                            .normalize();

            /*
             * 防止异常 tool id 逃出目标目录。
             */
            if (!outputFile.startsWith(
                    spillDirectory)) {

                return null;
            }

            Files.writeString(
                    outputFile,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            String replacementContent =
                    buildReplacement(
                            result.toolId(),
                            content,
                            outputFile
                    );

            ToolResult replacement =
                    new ToolResult(
                            result.toolId(),
                            replacementContent,
                            result.isError()
                    );

            return new SpillResult(
                    replacement,
                    outputFile
            );

        } catch (IOException e) {
            return null;
        }
    }

    private static String buildReplacement(
            String toolId,
            String content,
            Path outputFile
    ) {
        int previewLength =
                Math.min(
                        content.length(),
                        PREVIEW_CHARACTERS
                );

        String preview =
                content.substring(
                        0,
                        previewLength
                );

        String suffix =
                content.length()
                        > PREVIEW_CHARACTERS
                        ? "\n..."
                        : "";

        return """
                <persisted-tool-result>
                toolCallId: %s
                originalCharacters: %d
                file: %s

                preview:
                %s%s
                </persisted-tool-result>
                """.formatted(
                toolId == null ? "" : toolId,
                content.length(),
                outputFile,
                preview,
                suffix
        );
    }

    private static String safeFileName(
            String toolId
    ) {
        String original =
                toolId == null
                        ? "unknown"
                        : toolId;

        String safe =
                UNSAFE_FILE_NAME
                        .matcher(original)
                        .replaceAll("_");

        if (safe.isBlank()) {
            safe = "tool-result";
        }

        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }

        String hash =
                Integer.toUnsignedString(
                        original.hashCode(),
                        16
                );

        return safe
                + "-"
                + hash
                + ".txt";
    }

    private static boolean isAlreadyOffloaded(
            String content
    ) {
        return content.startsWith(
                OFFLOADED_PREFIX
        );
    }

    private static String safeContent(
            String content
    ) {
        return content == null
                ? ""
                : content;
    }
}