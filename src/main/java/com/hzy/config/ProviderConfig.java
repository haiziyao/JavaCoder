package com.hzy.config;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record ProviderConfig(
        String name,
        String protocol,
        String baseUrl,
        String model,
        String apiKey,
        boolean thinking,
        Integer contextWindow,
        Integer maxOutputTokens
) {}


// TODO: private volatile Integer fetchedContextWindow;