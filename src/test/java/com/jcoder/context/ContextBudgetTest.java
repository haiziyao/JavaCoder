package com.jcoder.context;

import com.jcoder.prompt.PromptContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBudgetTest {

    @Test
    void reservesOutputSpaceBeforeCalculatingThreshold() {
        ContextBudget budget = ContextBudget.calculate(
                new PromptContent("", List.of(), List.of()),
                1_000,
                200
        );

        assertEquals(800, budget.inputLimit());
        assertEquals(680, budget.compactThreshold());
        assertEquals(800, budget.remainingInputTokens());
        assertFalse(budget.shouldCompact());
        assertFalse(budget.exceedsWindow());
    }

    @Test
    void distinguishesCompactThresholdFromHardLimit() {
        ContextBudget compactSoon =
                new ContextBudget(700, 1_000, 200, 680);

        assertTrue(compactSoon.shouldCompact());
        assertFalse(compactSoon.exceedsWindow());
        assertEquals(100, compactSoon.remainingInputTokens());

        ContextBudget exhausted =
                new ContextBudget(800, 1_000, 200, 680);

        assertTrue(exhausted.shouldCompact());
        assertTrue(exhausted.exceedsWindow());
        assertEquals(0, exhausted.remainingInputTokens());
    }

    @Test
    void rejectsInvalidWindow() {
        PromptContent prompt =
                new PromptContent("", List.of(), List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> ContextBudget.calculate(prompt, 0, 100)
        );
    }
}
