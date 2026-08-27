package com.jcoder.hook.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Hook 配置的磁盘存储层。
 *
 * 文件位置：<project>/.mycoder/hooks/hooks.json
 */
public final class HookStore {

    private static final long MAX_FILE_BYTES =
            1_000_000L;

    private final ObjectMapper mapper;
    private final Path configDirectory;
    private final Path configFile;

    public HookStore(
            Path projectRoot
    ) {
        this(
                projectRoot,
                new ObjectMapper()
        );
    }

    HookStore(
            Path projectRoot,
            ObjectMapper mapper
    ) {
        Objects.requireNonNull(
                projectRoot,
                "projectRoot"
        );

        this.mapper =
                Objects.requireNonNull(
                        mapper,
                        "mapper"
                );

        Path normalizedRoot =
                projectRoot
                        .toAbsolutePath()
                        .normalize();

        this.configDirectory =
                normalizedRoot
                        .resolve(".mycoder")
                        .resolve("hooks")
                        .normalize();

        this.configFile =
                configDirectory
                        .resolve("hooks.json")
                        .normalize();

        if (!configFile.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(
                    "hook config path escaped project root"
            );
        }
    }

    public Path configFile() {
        return configFile;
    }

    public List<HookConfigEntry> load()
            throws IOException {
        if (!Files.exists(configFile)) {
            return List.of();
        }

        if (!Files.isRegularFile(configFile)) {
            throw new IOException(
                    "hook config is not a regular file: "
                            + configFile
            );
        }

        long size = Files.size(configFile);

        if (size > MAX_FILE_BYTES) {
            throw new IOException(
                    "hook config is too large: "
                            + size
                            + " bytes"
            );
        }

        HookConfigFile config =
                mapper.readValue(
                        configFile.toFile(),
                        HookConfigFile.class
                );

        return config.hooks();
    }

    public void save(
            List<HookConfigEntry> hooks
    ) throws IOException {
        HookConfigFile config =
                new HookConfigFile(
                        HookConfigFile.CURRENT_VERSION,
                        hooks
                );

        Files.createDirectories(
                configDirectory
        );

        Path temporary =
                Files.createTempFile(
                        configDirectory,
                        "hooks-",
                        ".tmp"
                );

        boolean moved = false;

        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            temporary.toFile(),
                            config
                    );

            try {
                Files.move(
                        temporary,
                        configFile,
                        StandardCopyOption
                                .ATOMIC_MOVE,
                        StandardCopyOption
                                .REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        configFile,
                        StandardCopyOption
                                .REPLACE_EXISTING
                );
            }

            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(
                        temporary
                );
            }
        }
    }
}
