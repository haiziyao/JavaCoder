package com.jcoder.tool.impl;

import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolArgsHelper;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolParamDefinition;
import com.jcoder.tool.ToolReturnDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class EditFileTool implements Tool {

    @Override
    public String name() {
        return "EditFile";
    }

    @Override
    public String description() {
        return "在已有文本文件中替换一段精确字符串；old_string 必须在文件中恰好出现一次。";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                name(),
                description(),
                Map.of(
                        "file_path", new ToolParamDefinition(
                                "string", "要编辑的文件路径", Map.of()),
                        "old_string", new ToolParamDefinition(
                                "string", "要替换的原始字符串，必须唯一", Map.of()),
                        "new_string", new ToolParamDefinition(
                                "string", "替换后的字符串，可以为空", Map.of())
                ),
                List.of("file_path", "old_string", "new_string"),
                new ToolReturnDefinition("string", Map.of())
        );
    }

    @Override
    public ToolExecuteResult execute(Map<String, Object> args) {
        String filePath = ToolArgsHelper.stringArg(args, "file_path", "");
        String oldString = ToolArgsHelper.stringArg(args, "old_string", "");
        String newString = ToolArgsHelper.stringArg(args, "new_string", "");

        if (filePath.isBlank()) {
            return ToolExecuteResult.error("Error: file_path is required");
        }
        if (oldString.isEmpty()) {
            return ToolExecuteResult.error("Error: old_string is required");
        }
        if (oldString.equals(newString)) {
            return ToolExecuteResult.error("Error: new_string must differ from old_string");
        }

        final Path path;
        try {
            path = Path.of(filePath).normalize();
        } catch (InvalidPathException e) {
            return ToolExecuteResult.error("Error: invalid file path: " + filePath);
        }

        if (!Files.exists(path)) {
            return ToolExecuteResult.error("Error: file not found: " + filePath);
        }
        if (!Files.isRegularFile(path)) {
            return ToolExecuteResult.error("Error: not a file: " + filePath);
        }

        final String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            return ToolExecuteResult.error("Error reading file: " + e.getMessage());
        }

        int count = countOccurrences(content, oldString);
        if (count == 0) {
            return ToolExecuteResult.error("Error: old_string not found in file");
        }
        if (count > 1) {
            return ToolExecuteResult.error(
                    "Error: old_string found " + count + " times, must be unique");
        }

        try {
            Files.writeString(path, content.replace(oldString, newString), StandardCharsets.UTF_8);
            return ToolExecuteResult.success("Successfully edited " + filePath);
        } catch (IOException | SecurityException e) {
            return ToolExecuteResult.error("Error writing file: " + e.getMessage());
        }
    }

    private static int countOccurrences(String text, String target) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }
}
