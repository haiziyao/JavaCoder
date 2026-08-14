package com.jcoder.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConfigManagerTest {

    @Test
    void loadsAllPromptSections() {
        assertEquals(7, PromptConfig.Section.values().length);

        for (PromptConfig.Section section : PromptConfig.Section.values()) {
            assertFalse(ConfigManager.promptConfig.prompt(section).isBlank(), section.name());
        }
    }
}
