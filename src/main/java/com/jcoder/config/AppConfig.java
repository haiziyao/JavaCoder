package com.jcoder.config;

import java.util.List;

public record AppConfig(
        List<ProviderConfig> providers,
        PromptConfig prompt
) {}
