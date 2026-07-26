package com.jcoder.tool.impl;

import com.jcoder.tool.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class ReadFileTool implements Tool {
    @Override
    public String name() {
        return "ReadFile";
    }

    @Override
    public String description() {
        return "读取文本文件，并返回带行号的文件内容。支持指定起始行和最大读取行数。";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, ToolParamDefinition> properties = Map.of(
                "file_path",
                new ToolParamDefinition(
                        "string",
                        "要读取的文件路径，可以是绝对路径或相对路径",
                        Map.of()
                ),

                "offset",
                new ToolParamDefinition(
                        "integer",
                        "从第几行开始读取，0 表示第一行",
                        Map.of("default", 0)
                ),

                "limit",
                new ToolParamDefinition(
                        "integer",
                        "最多读取多少行",
                        Map.of("default", 2000)
                )
        );

        return new ToolDefinition(
                name(),
                description(),
                properties,
                List.of("file_path"),
                new ToolReturnDefinition("string", Map.of())
        );
    }


    // 让AI写吧
    @Override
    public ToolExecuteResult execute(Map<String, Object> args) {
        String filePath = ToolArgsHelper.stringArg(args, "file_path", "");
        if (filePath.isBlank()) {
            return ToolExecuteResult.error(
                    "Error: file_path is required"
            );
        }

        int offset = ToolArgsHelper.intArg(args, "offset", 0);
        int limit = ToolArgsHelper.intArg(args, "limit", 2000);

        if (offset < 0) {
            return ToolExecuteResult.error(
                    "Error: offset cannot be negative"
            );
        }

        if (limit <= 0) {
            return ToolExecuteResult.error(
                    "Error: limit must be greater than 0"
            );
        }

        final Path path;

        try {
            path = Path.of(filePath).normalize();
        } catch (InvalidPathException e) {
            return ToolExecuteResult.error(
                    "Error: invalid file path: " + filePath
            );
        }

        if (!Files.exists(path)) {
            return ToolExecuteResult.error(
                    "Error: file not found: " + filePath
            );
        }

        if (!Files.isRegularFile(path)) {
            return ToolExecuteResult.error(
                    "Error: not a file: " + filePath
            );
        }

        final List<String> lines;

        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            return ToolExecuteResult.error(
                    "Error reading file: " + e.getMessage()
            );
        }

        if (offset >= lines.size()) {
            return ToolExecuteResult.success(
                    "No content: offset exceeds file length."
            );
        }

        int end = Math.min(offset + limit, lines.size());
        StringBuilder result = new StringBuilder();

        for (int i = offset; i < end; i++) {
            result.append(i + 1)
                    .append('\t')
                    .append(lines.get(i));

            if (i < end - 1) {
                result.append('\n');
            }
        }

        return ToolExecuteResult.success(result.toString());
    }

}
