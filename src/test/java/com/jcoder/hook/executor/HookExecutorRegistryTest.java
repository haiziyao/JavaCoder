package com.jcoder.hook.executor;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookErrorPolicy;
import com.jcoder.hook.HookEvent;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.HookSelector;
import com.jcoder.hook.action.CommandHookAction;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookExecutorRegistryTest {

    @Test
    void defaultRegistryContainsAllFirstVersionExecutors() {
        HookExecutorRegistry registry = HookExecutorRegistry.createDefault();

        assertEquals(
                java.util.Set.of(
                        HookActionType.PROMPT,
                        HookActionType.COMMAND,
                        HookActionType.HTTP
                ),
                registry.registeredTypes()
        );
        assertTrue(registry.contains(HookActionType.PROMPT));
        assertTrue(registry.contains(HookActionType.COMMAND));
        assertTrue(registry.contains(HookActionType.HTTP));
    }

    @Test
    void registersAndRoutesToExecutorByActionType() {
        HookExecutionResult expected = HookExecutionResult.success("meow", 3);
        CapturingExecutor executor = new CapturingExecutor(expected);
        HookExecutorRegistry registry = new HookExecutorRegistry();
        HookDefinition definition = definition(HookEvent.TURN_START);
        HookContext context = HookContext.turnStart("session-1", Path.of("."), "hello");

        assertSame(registry, registry.register(executor));
        assertTrue(registry.contains(HookActionType.COMMAND));
        assertEquals(java.util.Set.of(HookActionType.COMMAND), registry.registeredTypes());
        assertSame(expected, registry.execute(definition, context));
        assertSame(definition, executor.definition);
        assertSame(context, executor.context);
        assertEquals(1, executor.calls.get());
    }

    @Test
    void registeredTypesIsImmutable() {
        HookExecutorRegistry registry = new HookExecutorRegistry()
                .register(new CapturingExecutor(HookExecutionResult.success("", 0)));
        java.util.Set<HookActionType> types = registry.registeredTypes();

        assertThrows(UnsupportedOperationException.class,
                () -> types.add(HookActionType.HTTP));
    }

    @Test
    void rejectsNullAndDuplicateRegistration() {
        HookExecutorRegistry registry = new HookExecutorRegistry();

        assertThrows(NullPointerException.class, () -> registry.register(null));
        registry.register(new CapturingExecutor(HookExecutionResult.success("", 0)));
        assertThrows(IllegalStateException.class,
                () -> registry.register(
                        new CapturingExecutor(HookExecutionResult.success("other", 0))));
        assertThrows(NullPointerException.class, () -> registry.contains(null));
    }

    @Test
    void missingExecutorReturnsFailure() {
        HookExecutionResult result = new HookExecutorRegistry().execute(
                definition(HookEvent.TURN_START),
                HookContext.turnStart("", Path.of("."), "")
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("no hook executor registered for COMMAND"));
    }

    @Test
    void eventMismatchReturnsFailureWithoutCallingExecutor() {
        CapturingExecutor executor = new CapturingExecutor(HookExecutionResult.success("", 0));
        HookExecutorRegistry registry = new HookExecutorRegistry().register(executor);

        HookExecutionResult result = registry.execute(
                definition(HookEvent.TURN_START),
                HookContext.turnEnd("", Path.of("."), "")
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("TURN_START != TURN_END"));
        assertEquals(0, executor.calls.get());
    }

    @Test
    void executorRuntimeExceptionIsConvertedToFailure() {
        HookExecutorRegistry registry = new HookExecutorRegistry().register(
                new ThrowingExecutor(new IllegalStateException("speaker unavailable")));

        HookExecutionResult result = registry.execute(
                definition(HookEvent.TURN_START),
                HookContext.turnStart("", Path.of("."), "")
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("speaker unavailable"));
    }

    @Test
    void executorExceptionWithoutMessageUsesClassName() {
        HookExecutorRegistry registry = new HookExecutorRegistry().register(
                new ThrowingExecutor(new IllegalStateException()));

        HookExecutionResult result = registry.execute(
                definition(HookEvent.TURN_START),
                HookContext.turnStart("", Path.of("."), "")
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("IllegalStateException"));
    }

    @Test
    void nullExecutorResultIsConvertedToFailure() {
        HookExecutorRegistry registry = new HookExecutorRegistry().register(
                new CapturingExecutor(null));

        HookExecutionResult result = registry.execute(
                definition(HookEvent.TURN_START),
                HookContext.turnStart("", Path.of("."), "")
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("executor returned null"));
    }

    @Test
    void executeRequiresDefinitionAndContext() {
        HookExecutorRegistry registry = new HookExecutorRegistry();
        HookDefinition definition = definition(HookEvent.TURN_START);
        HookContext context = HookContext.turnStart("", Path.of("."), "");

        assertThrows(NullPointerException.class, () -> registry.execute(null, context));
        assertThrows(NullPointerException.class, () -> registry.execute(definition, null));
    }

    private static HookDefinition definition(HookEvent event) {
        return new HookDefinition(
                "registry-test", event, HookSelector.any(),
                new CommandHookAction("echo meow"), false, false, false,
                HookErrorPolicy.CONTINUE, 1_000);
    }

    private static final class CapturingExecutor implements HookActionExecutor {
        private final HookExecutionResult result;
        private final AtomicInteger calls = new AtomicInteger();
        private HookDefinition definition;
        private HookContext context;

        private CapturingExecutor(HookExecutionResult result) {
            this.result = result;
        }

        @Override
        public HookActionType type() {
            return HookActionType.COMMAND;
        }

        @Override
        public HookExecutionResult execute(HookDefinition definition, HookContext context) {
            calls.incrementAndGet();
            this.definition = definition;
            this.context = context;
            return result;
        }
    }

    private static final class ThrowingExecutor implements HookActionExecutor {
        private final RuntimeException exception;

        private ThrowingExecutor(RuntimeException exception) {
            this.exception = exception;
        }

        @Override
        public HookActionType type() {
            return HookActionType.COMMAND;
        }

        @Override
        public HookExecutionResult execute(HookDefinition definition, HookContext context) {
            throw exception;
        }
    }
}
