package com.jcoder.context;

import com.jcoder.prompt.PromptContent;

import java.util.Objects;

public record ContextBudget(
        int estimatedInputTokens,
        int contextWindow,
        int reservedOutputTokens,
        int compactThreshold
) {

    private static final double COMPACT_RATIO =
            0.85;

    public ContextBudget {
        if (estimatedInputTokens < 0) {
            throw new IllegalArgumentException(
                    "estimatedInputTokens must not be negative"
            );
        }

        if (contextWindow <= 0) {
            throw new IllegalArgumentException(
                    "contextWindow must be positive"
            );
        }

        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens must not be negative"
            );
        }

        if (compactThreshold < 0) {
            throw new IllegalArgumentException(
                    "compactThreshold must not be negative"
            );
        }
    }

    public static ContextBudget calculate(
            PromptContent promptContent,
            int contextWindow,
            int maxOutputTokens
    ) {
        Objects.requireNonNull(
                promptContent,
                "promptContent"
        );

        if (contextWindow <= 0) {
            throw new IllegalArgumentException(
                    "contextWindow must be positive"
            );
        }

        int reservedOutputTokens =
                Math.min(
                        Math.max(maxOutputTokens, 0),
                        contextWindow
                );

        int inputLimit =
                contextWindow
                        - reservedOutputTokens;

        int compactThreshold =
                inputLimit == 0 ? 0 : Math.max(1, (int) Math.floor(
                                        inputLimit * COMPACT_RATIO));

        int estimatedInputTokens =
                ContextTokenEstimator.estimate(
                        promptContent
                );

        return new ContextBudget(
                estimatedInputTokens,
                contextWindow,
                reservedOutputTokens,
                compactThreshold
        );
    }

    /**
     * 真正允许输入占用的空间。
     *
     * contextWindow 不能全部交给输入，
     * 必须为模型本轮输出预留 maxOutputTokens。
     */
    public int inputLimit() {
        return Math.max(
                0,
                contextWindow
                        - reservedOutputTokens
        );
    }

    public int remainingInputTokens() {
        return Math.max(
                0,
                inputLimit()
                        - estimatedInputTokens
        );
    }

    public boolean shouldCompact() {
        return estimatedInputTokens
                >= compactThreshold;
    }

    public boolean exceedsWindow() {
        return estimatedInputTokens
                >= inputLimit();
    }

    public double inputUsageRatio() {
        if (inputLimit() == 0) {
            return 1.0;
        }

        return (double) estimatedInputTokens
                / inputLimit();
    }
}