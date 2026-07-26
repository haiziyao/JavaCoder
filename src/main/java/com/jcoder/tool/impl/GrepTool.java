package com.jcoder.tool.impl;

import com.jcoder.tool.Tool;
import com.jcoder.tool.ToolArgsHelper;
import com.jcoder.tool.ToolCategory;
import com.jcoder.tool.ToolDefinition;
import com.jcoder.tool.ToolExecuteResult;
import com.jcoder.tool.ToolParamDefinition;
import com.jcoder.tool.ToolReturnDefinition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class GrepTool implements Tool {

    private static final int MAX_OUTPUT_CHARS = 10_000;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".venv", "node_modules", "target", "build",
            "__pycache__", ".tox", ".mypy_cache");

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return "使用正则表达式搜索文件内容，返回文件路径、行号和匹配行。";
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
                                "string", "要搜索的正则表达式", Map.of()),
                        "path", new ToolParamDefinition(
                                "string", "搜索起始目录", Map.of("default", ".")),
                        "include", new ToolParamDefinition(
                                "string", "可选的文件名 glob 过滤器，例如 *.java", Map.of())
                ),
                List.of("pattern"),
                new ToolReturnDefinition("string", Map.of())
        );
    }

    @Override
    public ToolExecuteResult execute(Map<String, Object> args) {
        String patternText = ToolArgsHelper.stringArg(args, "pattern", "");
        String basePath = ToolArgsHelper.stringArg(args, "path", ".");
        String include = ToolArgsHelper.stringArg(args, "include", "");

        if (patternText.isBlank()) {
            return ToolExecuteResult.error("Error: pattern is required");
        }
        if (basePath.isBlank()) {
            basePath = ".";
        }

        final Path root;
        final Pattern pattern;
        final PathMatcher includeMatcher;
        try {
            root = Path.of(basePath).normalize();
            pattern = Pattern.compile(patternText);
            includeMatcher = include.isBlank()
                    ? null
                    : FileSystems.getDefault().getPathMatcher("glob:" + include);
        } catch (InvalidPathException | PatternSyntaxException e) {
            return ToolExecuteResult.error("Error: invalid path or pattern: " + e.getMessage());
        }

        if (!Files.isDirectory(root)) {
            return ToolExecuteResult.error("Error: path not found: " + basePath);
        }

        List<Path> files = new ArrayList<>();
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
                    if (includeMatcher == null || includeMatcher.matches(file.getFileName())) {
                        files.add(file);
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

        Collections.sort(files);
        List<String> results = new ArrayList<>();
        int totalChars = 0;

        for (Path file : files) {
            if (isBinary(file)) {
                continue;
            }

            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (!pattern.matcher(line).find()) {
                        continue;
                    }

                    String entry = root.relativize(file) + ":" + lineNumber + ":" + line;
                    totalChars += entry.length() + 1;
                    if (totalChars > MAX_OUTPUT_CHARS) {
                        results.add("... output truncated (max " + MAX_OUTPUT_CHARS + " chars)");
                        return ToolExecuteResult.success(String.join("\n", results));
                    }
                    results.add(entry);
                }
            } catch (IOException | SecurityException ignored) {
                // Ignore files which become unreadable while searching.
            }
        }

        if (results.isEmpty()) {
            return ToolExecuteResult.success("No matches found.");
        }
        return ToolExecuteResult.success(String.join("\n", results));
    }

    private static boolean isBinary(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[512];
            int count = input.read(buffer);
            for (int i = 0; i < count; i++) {
                if (buffer[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException | SecurityException e) {
            return true;
        }
    }
}
