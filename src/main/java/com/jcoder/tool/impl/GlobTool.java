package com.jcoder.tool.impl;

import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolArgsHelper;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolParamDefinition;
import com.jcoder.tool.ToolReturnDefinition;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GlobTool implements Tool {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".venv", "node_modules", "target", "build",
            "__pycache__", ".tox", ".mypy_cache");

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return "按 glob 模式查找文件，例如 **/*.java；返回相对于搜索目录的路径。";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                name(),
                description(),
                Map.of(
                        "pattern", new ToolParamDefinition(
                                "string", "glob 模式，例如 **/*.java", Map.of()),
                        "path", new ToolParamDefinition(
                                "string", "搜索起始目录", Map.of("default", "."))
                ),
                List.of("pattern"),
                new ToolReturnDefinition("string", Map.of())
        );
    }

    @Override
    public ToolExecuteResult execute(Map<String, Object> args) {
        String pattern = ToolArgsHelper.stringArg(args, "pattern", "");
        String basePath = ToolArgsHelper.stringArg(args, "path", ".");

        if (pattern.isBlank()) {
            return ToolExecuteResult.error("Error: pattern is required");
        }
        if (basePath.isBlank()) {
            basePath = ".";
        }

        final Path root;
        final PathMatcher matcher;
        try {
            root = Path.of(basePath).normalize();
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (InvalidPathException | java.util.regex.PatternSyntaxException e) {
            return ToolExecuteResult.error("Error: invalid path or glob pattern: " + e.getMessage());
        }

        if (!Files.isDirectory(root)) {
            return ToolExecuteResult.error("Error: path not found: " + basePath);
        }

        List<Path> matches = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (name != null && SKIP_DIRS.contains(name.toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = root.relativize(file);
                    if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                        matches.add(relative);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException e) {
            return ToolExecuteResult.error("Error searching files: " + e.getMessage());
        }

        matches.sort((left, right) -> compareModifiedTime(root, left, right));
        if (matches.isEmpty()) {
            return ToolExecuteResult.success("No files matched the pattern.");
        }

        return ToolExecuteResult.success(
                String.join("\n", matches.stream().map(Path::toString).toList()));
    }

    private static int compareModifiedTime(Path root, Path left, Path right) {
        try {
            long leftTime = Files.getLastModifiedTime(root.resolve(left)).toMillis();
            long rightTime = Files.getLastModifiedTime(root.resolve(right)).toMillis();
            return Long.compare(rightTime, leftTime);
        } catch (IOException e) {
            return left.toString().compareTo(right.toString());
        }
    }
}
