package com.jcoder.hook.config;

import java.util.List;

/**
 * hooks.json 顶层协议。
 */
public record HookConfigFile(
        int version,
        List<HookConfigEntry> hooks
) {
    public static final int CURRENT_VERSION = 1;

    public HookConfigFile {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported hooks config version: "
                            + version
            );
        }

        hooks = hooks == null
                ? List.of()
                : List.copyOf(hooks);
    }

    public static HookConfigFile empty() {
        return new HookConfigFile(
                CURRENT_VERSION,
                List.of()
        );
    }
}
