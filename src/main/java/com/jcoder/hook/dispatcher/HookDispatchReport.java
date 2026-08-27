package com.jcoder.hook.dispatcher;

import com.jcoder.hook.HookEvent;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Hook Event 的完整调度报告。
 */
public record HookDispatchReport(
        HookEvent event,
        List<HookInvocationResult> invocations
) {

    public HookDispatchReport {
        Objects.requireNonNull(
                event,
                "event"
        );

        invocations =
                invocations == null
                        ? List.of()
                        : List.copyOf(invocations);

        for (HookInvocationResult invocation
                : invocations) {
            Objects.requireNonNull(
                    invocation,
                    "invocation"
            );

            if (invocation.definition().event()
                    != event) {
                throw new IllegalArgumentException(
                        "hook invocation event does not "
                                + "match dispatch report: "
                                + invocation.definition().id()
                );
            }
        }
    }

    public static HookDispatchReport empty(
            HookEvent event
    ) {
        return new HookDispatchReport(
                event,
                List.of()
        );
    }

    public int matchedCount() {
        return invocations.size();
    }

    public long completedCount() {
        return invocations.stream()
                .filter(
                        HookInvocationResult::isCompleted
                )
                .count();
    }

    public long scheduledCount() {
        return invocations.stream()
                .filter(
                        HookInvocationResult::isScheduled
                )
                .count();
    }

    public List<HookInvocationResult> failures() {
        return invocations.stream()
                .filter(
                        HookInvocationResult::isFailure
                )
                .toList();
    }

    public boolean hasFailures() {
        return !failures().isEmpty();
    }
}
