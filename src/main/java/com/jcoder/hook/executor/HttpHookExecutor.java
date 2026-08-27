package com.jcoder.hook.executor;

import com.jcoder.hook.HookActionType;
import com.jcoder.hook.HookContext;
import com.jcoder.hook.HookDefinition;
import com.jcoder.hook.HookExecutionResult;
import com.jcoder.hook.action.HttpHookAction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 执行 HTTP Hook。
 *
 * 安全边界：
 * 1. URI 和 headers 来自用户可信配置，不使用 Tool 参数替换。
 * 2. 只有 bodyTemplate 可以读取 HookContext。
 * 3. 请求体和响应体都有大小限制。
 * 4. 整个请求和响应读取共享同一个 timeout。
 * 5. 默认不自动跟随重定向。
 */
public final class HttpHookExecutor
        implements HookActionExecutor {

    private static final int DEFAULT_MAX_REQUEST_CHARS =
            64_000;

    private static final int DEFAULT_MAX_RESPONSE_CHARS =
            20_000;

    private final HttpClient client;
    private final HookTemplateRenderer renderer;
    private final int maxRequestChars;
    private final int maxResponseChars;

    public HttpHookExecutor(
            HookTemplateRenderer renderer
    ) {
        this(
                createDefaultClient(),
                renderer,
                DEFAULT_MAX_REQUEST_CHARS,
                DEFAULT_MAX_RESPONSE_CHARS
        );
    }

    public HttpHookExecutor(
            HttpClient client,
            HookTemplateRenderer renderer,
            int maxRequestChars,
            int maxResponseChars
    ) {
        this.client =
                Objects.requireNonNull(
                        client,
                        "client"
                );

        this.renderer =
                Objects.requireNonNull(
                        renderer,
                        "renderer"
                );

        if (maxRequestChars <= 0) {
            throw new IllegalArgumentException(
                    "maxRequestChars must be positive"
            );
        }

        if (maxResponseChars <= 0) {
            throw new IllegalArgumentException(
                    "maxResponseChars must be positive"
            );
        }

        this.maxRequestChars =
                maxRequestChars;

        this.maxResponseChars =
                maxResponseChars;
    }

    @Override
    public HookActionType type() {
        return HookActionType.HTTP;
    }

    @Override
    public HookExecutionResult execute(
            HookDefinition definition,
            HookContext context
    ) {
        long startedAt =
                System.nanoTime();

        long deadline =
                startedAt
                        + TimeUnit.MILLISECONDS
                        .toNanos(
                                definition.timeoutMillis()
                        );

        if (!(definition.action()
                instanceof HttpHookAction action)) {
            return HookExecutionResult.failure(
                    "HTTP executor received "
                            + definition.action().type(),
                    "",
                    elapsedMillis(startedAt)
            );
        }

        final String requestBody;

        try {
            requestBody = renderer.render(
                    action.bodyTemplate(),
                    context
            );
        } catch (IllegalArgumentException exception) {
            return HookExecutionResult.failure(
                    "failed to render http hook body: "
                            + exceptionMessage(exception),
                    "",
                    elapsedMillis(startedAt)
            );
        }

        if (requestBody.length()
                > maxRequestChars) {
            return HookExecutionResult.failure(
                    "http hook request body is too large: "
                            + requestBody.length()
                            + " characters; maximum is "
                            + maxRequestChars,
                    "",
                    elapsedMillis(startedAt)
            );
        }

        final HttpRequest request;

        try {
            request = buildRequest(
                    action,
                    requestBody,
                    definition.timeoutMillis()
            );
        } catch (IllegalArgumentException exception) {
            return HookExecutionResult.failure(
                    "invalid http hook request: "
                            + exceptionMessage(exception),
                    "",
                    elapsedMillis(startedAt)
            );
        }

        ExecutorService bodyReader =
                Executors
                        .newVirtualThreadPerTaskExecutor();

        InputStream responseStream = null;

        try {
            HttpResponse<InputStream> response =
                    client.send(
                            request,
                            HttpResponse
                                    .BodyHandlers
                                    .ofInputStream()
                    );

            responseStream =
                    response.body();

            InputStream capturedStream =
                    responseStream;

            Future<String> responseFuture =
                    bodyReader.submit(
                            () -> readLimited(
                                    capturedStream,
                                    maxResponseChars
                            )
                    );

            long remainingMillis =
                    remainingMillis(deadline);

            if (remainingMillis <= 0) {
                closeQuietly(responseStream);
                responseFuture.cancel(true);

                return timeoutFailure(
                        definition,
                        startedAt
                );
            }

            final String responseBody;

            try {
                responseBody =
                        responseFuture.get(
                                remainingMillis,
                                TimeUnit.MILLISECONDS
                        );
            } catch (TimeoutException exception) {
                closeQuietly(responseStream);
                responseFuture.cancel(true);

                return timeoutFailure(
                        definition,
                        startedAt
                );
            } catch (ExecutionException exception) {
                return HookExecutionResult.failure(
                        "failed to read http hook response: "
                                + exceptionMessage(
                                exception.getCause()
                        ),
                        "",
                        elapsedMillis(startedAt)
                );
            }

            int statusCode =
                    response.statusCode();

            if (statusCode < 200
                    || statusCode >= 300) {
                return HookExecutionResult.failure(
                        "http hook returned status "
                                + statusCode,
                        responseBody,
                        elapsedMillis(startedAt)
                );
            }

            return HookExecutionResult.success(
                    responseBody,
                    elapsedMillis(startedAt)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            return HookExecutionResult.failure(
                    "http hook was interrupted",
                    "",
                    elapsedMillis(startedAt)
            );
        } catch (IOException exception) {
            return HookExecutionResult.failure(
                    "http hook request failed: "
                            + exceptionMessage(exception),
                    "",
                    elapsedMillis(startedAt)
            );
        } finally {
            closeQuietly(responseStream);
            bodyReader.shutdownNow();
        }
    }

    private static HttpClient createDefaultClient() {
        return HttpClient
                .newBuilder()
                .connectTimeout(
                        Duration.ofSeconds(10)
                )
                .followRedirects(
                        HttpClient
                                .Redirect
                                .NEVER
                )
                .build();
    }

    private static HttpRequest buildRequest(
            HttpHookAction action,
            String requestBody,
            long timeoutMillis
    ) {
        HttpRequest.Builder builder =
                HttpRequest
                        .newBuilder(action.uri())
                        .timeout(
                                Duration.ofMillis(
                                        timeoutMillis
                                )
                        );

        for (Map.Entry<String, String> header
                : action.headers().entrySet()) {
            builder.header(
                    header.getKey(),
                    header.getValue()
            );
        }

        HttpRequest.BodyPublisher publisher =
                requestBody.isEmpty()
                        ? HttpRequest
                        .BodyPublishers
                        .noBody()
                        : HttpRequest
                        .BodyPublishers
                        .ofString(
                                requestBody,
                                StandardCharsets.UTF_8
                        );

        return builder
                .method(
                        action.method(),
                        publisher
                )
                .build();
    }

    /**
     * 持续排空响应，但只在内存中保存限定字符。
     */
    private static String readLimited(
            InputStream inputStream,
            int limit
    ) throws IOException {
        try (
                inputStream;
                BufferedReader reader =
                        new BufferedReader(
                                new java.io.InputStreamReader(
                                        inputStream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {
            StringBuilder output =
                    new StringBuilder(
                            Math.min(limit, 4_096)
                    );

            char[] buffer =
                    new char[2_048];

            boolean truncated = false;
            int read;

            while ((read = reader.read(buffer)) >= 0) {
                int remaining =
                        limit - output.length();

                if (remaining > 0) {
                    int copied =
                            Math.min(
                                    remaining,
                                    read
                            );

                    output.append(
                            buffer,
                            0,
                            copied
                    );

                    if (copied < read) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            }

            if (truncated) {
                output.append(
                        System.lineSeparator()
                ).append(
                        "...[hook response truncated]..."
                );
            }

            return output.toString();
        }
    }

    private static HookExecutionResult timeoutFailure(
            HookDefinition definition,
            long startedAt
    ) {
        return HookExecutionResult.failure(
                "http hook timed out after "
                        + definition.timeoutMillis()
                        + " ms",
                "",
                elapsedMillis(startedAt)
        );
    }

    private static long remainingMillis(
            long deadline
    ) {
        long remainingNanos =
                deadline - System.nanoTime();

        if (remainingNanos <= 0) {
            return 0;
        }

        return Math.max(
                1,
                TimeUnit.NANOSECONDS
                        .toMillis(remainingNanos)
        );
    }

    private static void closeQuietly(
            InputStream inputStream
    ) {
        if (inputStream == null) {
            return;
        }

        try {
            inputStream.close();
        } catch (IOException ignored) {
            // 关闭失败不能覆盖原始 Hook 结果。
        }
    }

    private static long elapsedMillis(
            long startedAt
    ) {
        return Math.max(
                0,
                (System.nanoTime() - startedAt)
                        / 1_000_000L
        );
    }

    private static String exceptionMessage(
            Throwable throwable
    ) {
        if (throwable == null) {
            return "unknown error";
        }

        String message =
                throwable.getMessage();

        if (message == null
                || message.isBlank()) {
            return throwable
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }
}