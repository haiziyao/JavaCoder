package com.jcoder.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ConfigManager {

    private static final String CONFIG_FILE = "application.json";
    private static final String PROMPT_DIRECTORY = "sys_prompt/";

    public final static AppConfig appConfig;
    public static volatile PromptConfig promptConfig;

    static{
        ObjectMapper mapper = new ObjectMapper();

        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            appConfig = mapper.readValue(in, AppConfig.class);
            promptConfig = loadPromptConfig();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static synchronized void savePrompt(PromptConfig.Section section, String content) {
        try {
            var resource = ConfigManager.class.getClassLoader()
                    .getResource(PROMPT_DIRECTORY + section.fileName());
            Files.writeString(java.nio.file.Path.of(resource.toURI()), content, StandardCharsets.UTF_8);
            promptConfig = loadPromptConfig();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static synchronized void reloadPrompts() {
        promptConfig = loadPromptConfig();
    }

    private static PromptConfig loadPromptConfig() {
        return new PromptConfig(
                readPrompt(PromptConfig.Section.IDENTITY),
                readPrompt(PromptConfig.Section.BEHAVIOR),
                readPrompt(PromptConfig.Section.TOOL_USAGE),
                readPrompt(PromptConfig.Section.CODE_QUALITY),
                readPrompt(PromptConfig.Section.SECURITY),
                readPrompt(PromptConfig.Section.TASK_PATTERN),
                readPrompt(PromptConfig.Section.OUTPUT_STYLE)
        );
    }

    private static String readPrompt(PromptConfig.Section section) {
        try (InputStream in = ConfigManager.class.getClassLoader()
                .getResourceAsStream(PROMPT_DIRECTORY + section.fileName())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
