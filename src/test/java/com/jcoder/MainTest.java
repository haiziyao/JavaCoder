package com.jcoder;

import com.jcoder.ui.WebUI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void defaultsToWebUi() {
        Main.LaunchOptions options = Main.LaunchOptions.parse(new String[0]);

        assertFalse(options.cli());
        assertEquals(WebUI.DEFAULT_PORT, options.port());
    }

    @Test
    void supportsCliAndWebPortArguments() {
        assertTrue(Main.LaunchOptions.parse(new String[]{"--cli"}).cli());
        assertEquals(9090,
                Main.LaunchOptions.parse(new String[]{"--web", "--port", "9090"}).port());
        assertEquals(9091,
                Main.LaunchOptions.parse(new String[]{"--port=9091"}).port());
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.LaunchOptions.parse(new String[]{"--unknown"}));
        assertThrows(IllegalArgumentException.class,
                () -> Main.LaunchOptions.parse(new String[]{"--port", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> Main.LaunchOptions.parse(new String[]{"--cli", "--port", "8081"}));
    }
}
