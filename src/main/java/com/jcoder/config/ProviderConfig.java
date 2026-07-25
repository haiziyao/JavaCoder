package com.jcoder.config;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public record ProviderConfig(
        String name,  // 其实不要也行
        String protocol,
        String baseUrl,
        String model,
        String apiKey,
        boolean thinking,
        Integer contextWindow,
        Integer maxOutputTokens
) {}
