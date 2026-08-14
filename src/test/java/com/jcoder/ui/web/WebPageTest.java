package com.jcoder.ui.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebPageTest {

    @Test
    void exposesCodeAgentWorkspaceAndRunStates() {
        String html = WebPage.html();

        assertTrue(html.contains("class=\"activity-bar\""));
        assertTrue(html.contains("class=\"inspector"));
        assertTrue(html.contains("data-testid=\"run-state\""));
        assertTrue(html.contains("连接中断"));
        assertFalse(html.contains("class=\"inspector open\""));
    }
}
