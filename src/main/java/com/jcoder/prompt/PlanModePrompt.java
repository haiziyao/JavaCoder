package com.jcoder.prompt;

import com.jcoder.message.Message;

public final class PlanModePrompt {

    private static final String PLAN_FULL = """
            # Plan Mode

            You are in PLAN mode.
            Inspect the project and clarify the task before proposing changes.
            Do not edit files or execute commands that change project state.
            Produce a concrete implementation plan with relevant files and ordered steps.
            """;

    private static final String PLAN_SPARSE = """
            You are still in PLAN mode. Continue analysis without modifying the project.
            """;

    private static final String EXECUTE_FULL = """
            # Execute Plan Mode

            You are in EXECUTE_PLAN mode.
            Follow the approved plan and keep changes limited to its scope.
            Verify each completed step when possible and report any required deviation.
            """;

    private static final String EXECUTE_SPARSE = """
            You are still in EXECUTE_PLAN mode. Continue following the approved plan.
            """;

    private PlanModePrompt() {
    }

    public static Message build(AgentMode mode, int turn) {
        if (mode == AgentMode.NORMAL) {
            return null;
        }

        boolean useFullPrompt = turn == 1 || (turn - 1) % 5 == 0;
        String content = switch (mode) {
            case PLAN -> useFullPrompt ? PLAN_FULL : PLAN_SPARSE;
            case EXECUTE_PLAN -> useFullPrompt ? EXECUTE_FULL : EXECUTE_SPARSE;
            case NORMAL -> throw new IllegalStateException("NORMAL mode has no reminder");
        };

        return SystemReminder.message(content);
    }
}
