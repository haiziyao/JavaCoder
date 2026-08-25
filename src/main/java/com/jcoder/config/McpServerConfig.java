package com.jcoder.config;

import java.util.List;
import java.util.Map;


public record McpServerConfig(
        String name,
        String command,
        List<String> args,
        String url,
        Map<String, String> headers,
        Map<String, String> env
) {}