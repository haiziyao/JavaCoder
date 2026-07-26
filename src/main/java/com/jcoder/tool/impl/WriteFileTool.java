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

public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "WriteFile";
    }

    @Override
    public String description() {
        return "写入文本文件；如果父目录不存在会自动创建。已有文件会被覆盖。";
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
                                "string", "要写入的文件路径", Map.of()),
                        "content", new ToolParamDefinition(
                                "string", "要写入的完整文本内容", Map.of())
                ),
                List.of("file_path", "content"),
                new ToolReturnDefinition("string", Map.of())
        );
    }

    @Override
    public ToolExecuteResult execute(Map<String, Object> args) {
        String filePath = ToolArgsHelper.stringArg(args, "file_path", "");
        String content = ToolArgsHelper.stringArg(args, "content", "");

        if (filePath.isBlank()) {
            return ToolExecuteResult.error("Error: file_path is required");
        }

        final Path path;
        try {
            path = Path.of(filePath).normalize();
        } catch (InvalidPathException e) {
            return ToolExecuteResult.error("Error: invalid file path: " + filePath);
        }

        if (Files.exists(path) && !Files.isRegularFile(path)) {
            return ToolExecuteResult.error("Error: not a file: " + filePath);
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return ToolExecuteResult.success("Successfully wrote to " + filePath);
        } catch (IOException | SecurityException e) {
            return ToolExecuteResult.error("Error writing file: " + e.getMessage());
        }
    }
}
