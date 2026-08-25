package com.jcoder.mcp;

import com.jcoder.config.McpServerConfig;
import com.jcoder.tool.ToolRegister;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class McpManager {

    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(60);

    private static final Pattern ENV_VARIABLE =
            Pattern.compile("\\$\\{([^}]+)}");

    private static final Set<String> WINDOWS_CMD_COMMANDS =
            Set.of("npx", "npm", "pnpm", "yarn");

    private final List<McpServerConfig> configs;

    private final Map<String, McpSyncClient> clients =
            new LinkedHashMap<>();

    public McpManager(List<McpServerConfig> configs) {
        this.configs = configs == null
                ? List.of()
                : List.copyOf(configs);
    }

    public List<String> connectAndRegister(
            ToolRegister toolRegister
    ) {
        Objects.requireNonNull(
                toolRegister,
                "toolRegister"
        );

        List<String> errors = new ArrayList<>();
        Set<String> serverNames = new HashSet<>();

        for (McpServerConfig config : configs) {
            McpSyncClient client = null;

            try {
                validate(config);

                if (!serverNames.add(config.name())) {
                    throw new IllegalArgumentException(
                            "duplicate MCP server name: "
                                    + config.name()
                    );
                }

                client = createClient(config);

                /*
                 * MCP 生命周期第一步：
                 * initialize 请求及 initialized 通知。
                 */
                client.initialize();

                /*
                 * MCP 生命周期第二步：
                 * 请求 tools/list。
                 */
                McpSchema.ListToolsResult result =
                        client.listTools();

                if (result != null
                        && result.tools() != null) {

                    for (McpSchema.Tool sdkTool :
                            result.tools()) {

                        toolRegister.register(
                                new McpToolWrapper(
                                        config.name(),
                                        sdkTool,
                                        client
                                )
                        );
                    }
                }

                /*
                 * 只有 initialize 和 tools/list 都成功，
                 * 才把 client 记录为已连接。
                 */
                clients.put(config.name(), client);

            } catch (Exception e) {
                if (client != null) {
                    try {
                        client.closeGracefully();
                    } catch (Exception ignored) {
                    }
                }

                String serverName =
                        config == null
                                ? "<null>"
                                : String.valueOf(
                                        config.name()
                                );

                errors.add(
                        "MCP server '"
                                + serverName
                                + "': "
                                + rootMessage(e)
                );
            }
        }

        return List.copyOf(errors);
    }

    private McpSyncClient createClient(
            McpServerConfig config
    ) {
        McpClientTransport transport;

        if (hasText(config.command())) {
            transport = createStdioTransport(config);
        } else {
            transport = createHttpTransport(config);
        }

        return McpClient.sync(transport)
                .clientInfo(
                        new McpSchema.Implementation(
                                "mycoder",
                                "0.1.0"
                        )
                )
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
    }

    private McpClientTransport createStdioTransport(
            McpServerConfig config
    ) {
        String command =
                windowsSafe(config.command());

        ServerParameters.Builder builder =
                ServerParameters.builder(command);

        if (config.args() != null) {
            builder.args(config.args());
        }

        if (config.env() != null
                && !config.env().isEmpty()) {

            builder.env(
                    resolveEnvironmentMap(config.env())
            );
        }

        return new StdioClientTransport(
                builder.build(),
                McpJsonDefaults.getMapper()
        );
    }

    private McpClientTransport createHttpTransport(
            McpServerConfig config
    ) {
        var builder =
                HttpClientStreamableHttpTransport
                        .builder(config.url());

        if (config.headers() != null
                && !config.headers().isEmpty()) {

            builder.customizeRequest(requestBuilder ->
                    config.headers().forEach(
                            (name, value) ->
                                    requestBuilder.header(
                                            name,
                                            resolveEnvVars(value)
                                    )
                    )
            );
        }

        return builder.build();
    }

    public void shutdown() {
        for (McpSyncClient client :
                clients.values()) {

            try {
                client.closeGracefully();
            } catch (Exception ignored) {
            }
        }

        clients.clear();
    }

    public int connectedServerCount() {
        return clients.size();
    }

    private static void validate(
            McpServerConfig config
    ) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "MCP server config is null"
            );
        }

        if (!hasText(config.name())) {
            throw new IllegalArgumentException(
                    "MCP server name is required"
            );
        }

        boolean hasCommand =
                hasText(config.command());

        boolean hasUrl =
                hasText(config.url());

        /*
         * true == true：command 和 url 都配置了。
         * false == false：二者都没有配置。
         */
        if (hasCommand == hasUrl) {
            throw new IllegalArgumentException(
                    "configure exactly one of "
                            + "'command' or 'url'"
            );
        }
    }

    private static Map<String, String>
    resolveEnvironmentMap(
            Map<String, String> source
    ) {
        Map<String, String> result =
                new HashMap<>();

        source.forEach(
                (name, value) ->
                        result.put(
                                name,
                                resolveEnvVars(value)
                        )
        );

        return result;
    }

    static String resolveEnvVars(String value) {
        if (value == null) {
            return null;
        }

        return ENV_VARIABLE
                .matcher(value)
                .replaceAll(match -> {
                    String variableName =
                            match.group(1);

                    String environmentValue =
                            System.getenv(variableName);

                    String replacement =
                            environmentValue == null
                                    ? match.group(0)
                                    : environmentValue;

                    return Matcher.quoteReplacement(
                            replacement
                    );
                });
    }

    static String windowsSafe(String command) {
        String osName =
                System.getProperty(
                        "os.name",
                        ""
                ).toLowerCase();

        if (!osName.contains("win")) {
            return command;
        }

        if (WINDOWS_CMD_COMMANDS.contains(
                command.toLowerCase()
        )) {
            return command + ".cmd";
        }

        return command;
    }

    private static boolean hasText(String value) {
        return value != null
                && !value.isBlank();
    }

    private static String rootMessage(
            Exception exception
    ) {
        Throwable cause = exception;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null
                ? cause.toString()
                : cause.getMessage();
    }
}