package com.jcoder.hook.action;

import com.jcoder.hook.HookActionType;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 发送 HTTP 请求的 Hook 动作。
 */
public record HttpHookAction(
        URI uri,
        String method,
        Map<String, String> headers,
        String bodyTemplate
) implements HookAction {

    private static final Set<String> SUPPORTED_METHODS =
            Set.of(
                    "GET",
                    "POST",
                    "PUT",
                    "PATCH",
                    "DELETE"
            );

    public HttpHookAction {
        if (uri == null || !uri.isAbsolute()) {
            throw new IllegalArgumentException(
                    "http hook requires an absolute URI"
            );
        }

        String scheme = uri.getScheme();

        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "unsupported http hook URI scheme: "
                            + scheme
            );
        }

        if (method == null || method.isBlank()) {
            method = "POST";
        } else {
            method = method
                    .strip()
                    .toUpperCase(Locale.ROOT);
        }

        if (!SUPPORTED_METHODS.contains(method)) {
            throw new IllegalArgumentException(
                    "unsupported http hook method: "
                            + method
            );
        }

        Map<String, String> normalizedHeaders =
                new LinkedHashMap<>();

        if (headers != null) {
            for (Map.Entry<String, String> entry
                    : headers.entrySet()) {

                String name = entry.getKey();
                String value = entry.getValue();

                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException(
                            "http hook header name is required"
                    );
                }

                if (value == null) {
                    throw new IllegalArgumentException(
                            "http hook header value is required: "
                                    + name
                    );
                }

                name = name.strip();

                if (containsLineBreak(name)
                        || containsLineBreak(value)) {
                    throw new IllegalArgumentException(
                            "http hook headers cannot contain line breaks"
                    );
                }

                normalizedHeaders.put(name, value);
            }
        }

        headers = Collections.unmodifiableMap(
                normalizedHeaders
        );

        if (bodyTemplate == null) {
            bodyTemplate = "";
        }
    }

    private static boolean containsLineBreak(
            String value
    ) {
        return value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0;
    }

    @Override
    public HookActionType type() {
        return HookActionType.HTTP;
    }
}