package com.example.mcp;

import com.example.mcp.json.JsonCodec;
import com.example.mcp.server.McpPrompt;
import com.example.mcp.server.McpResource;
import com.example.mcp.server.McpServer;
import com.example.mcp.server.McpTool;
import com.example.mcp.server.StreamableHttpTransport;
import com.example.mcp.tools.BuiltinTools;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 手工编写的 Java MCP 服务端入口。
 * <p>
 * 注册了 Tools（工具）、Resources（资源）和 Prompts（提示词模板），
 * 默认同时启动 Stdio 传输与 Streamable HTTP 传输（{@code /mcp}），
 * 也支持通过命令行参数或环境变量单独启用其中一种模式。
 */
public final class Main {

    public static final String SERVER_NAME = "mcp-server-java";
    public static final String SERVER_VERSION = "1.0.0";

    private Main() {
    }

    public static McpServer createServer(PrintStream logStream) {
        McpServer server = new McpServer(
                SERVER_NAME,
                SERVER_VERSION,
                "纯手工编写的零依赖 Java MCP 服务，提供时间查询、安全计算器、文本统计工具，以及运行时状态资源与代码审查提示词。",
                logStream
        );

        // 1. 注册 Tools
        for (McpTool tool : BuiltinTools.all()) {
            server.registerTool(tool);
        }

        // 2. 注册 Resources
        server.registerResource(new McpResource() {
            @Override
            public String uri() {
                return "info://server/status";
            }

            @Override
            public String name() {
                return "Server Runtime Status";
            }

            @Override
            public String description() {
                return "当前 Java MCP 服务端的运行环境、JVM 版本与内存状态。";
            }

            @Override
            public String mimeType() {
                return "application/json";
            }

            @Override
            public String readText() {
                Runtime runtime = Runtime.getRuntime();
                long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
                Map<String, Object> status = JsonCodec.objectOf(
                        "serverName", SERVER_NAME,
                        "serverVersion", SERVER_VERSION,
                        "javaVersion", System.getProperty("java.version"),
                        "osName", System.getProperty("os.name"),
                        "uptimeMs", uptimeMs,
                        "freeMemoryBytes", runtime.freeMemory(),
                        "totalMemoryBytes", runtime.totalMemory()
                );
                return JsonCodec.stringify(status);
            }
        });

        // 3. 注册 Prompts
        server.registerPrompt(new McpPrompt() {
            @Override
            public String name() {
                return "code_review";
            }

            @Override
            public String description() {
                return "生成结构化代码审查提示词模板，帮助检查正确性、边界条件与代码规范。";
            }

            @Override
            public List<PromptArgument> arguments() {
                return List.of(
                        new PromptArgument("code", "待审查的源代码片段", true),
                        new PromptArgument("language", "编程语言（可选，如 Java、Python）", false)
                );
            }

            @Override
            public List<PromptMessage> render(Map<String, Object> arguments) {
                String code = String.valueOf(arguments.get("code"));
                String language = JsonCodec.asString(arguments.get("language"));
                String langTag = (language == null || language.isBlank()) ? "" : language.trim();
                String text = """
                        请作为资深工程师对以下%s代码进行全面审查，重点关注：
                        1. 逻辑正确性与潜在 Bug
                        2. 边界条件、空指针与异常处理
                        3. 安全性与性能问题
                        4. 可读性与可维护性改进建议

                        ```%s
                        %s
                        ```
                        """.formatted(
                        langTag.isEmpty() ? "" : " " + langTag + " ",
                        langTag,
                        code
                );
                return List.of(new PromptMessage("user", text));
            }
        });

        return server;
    }

    public static void main(String[] args) throws IOException {
        PrintStream utf8Out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream utf8Err = new PrintStream(System.err, true, StandardCharsets.UTF_8);

        ServerConfig config;
        try {
            config = parseConfig(args);
        } catch (IllegalArgumentException e) {
            utf8Err.println("[mcp-server] 参数错误: " + e.getMessage());
            printUsage(utf8Err);
            System.exit(2);
            return;
        }

        if (config.help) {
            printUsage(utf8Err);
            return;
        }

        McpServer server = createServer(utf8Err);
        switch (config.transport) {
            case STDIO -> server.run(System.in, utf8Out);
            case HTTP -> runHttpOnly(server, config);
            case BOTH -> runStdioAndHttp(server, config, utf8Out);
        }
    }

    private static void runHttpOnly(McpServer server, ServerConfig config) throws IOException {
        CountDownLatch stopLatch = new CountDownLatch(1);
        try (StreamableHttpTransport http = new StreamableHttpTransport(server, config.host, config.port, config.path)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                http.close();
                stopLatch.countDown();
            }, "mcp-http-shutdown"));
            http.start();
            try {
                stopLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void runStdioAndHttp(McpServer server, ServerConfig config, PrintStream utf8Out) throws IOException {
        try (StreamableHttpTransport http = new StreamableHttpTransport(server, config.host, config.port, config.path)) {
            Runtime.getRuntime().addShutdownHook(new Thread(http::close, "mcp-http-shutdown"));
            http.start();
            server.log("Running in dual mode (Stdio + Streamable HTTP). Close stdin or press Ctrl+C to exit.");
            server.run(System.in, utf8Out);
        }
    }

    static ServerConfig parseConfig(String[] args) {
        TransportMode transport = parseTransport(envOrDefault("MCP_TRANSPORT", "both"));
        String host = envOrDefault("MCP_HOST", "0.0.0.0");
        int port = parsePort(envOrDefault("MCP_PORT", "8080"));
        String path = envOrDefault("MCP_PATH", "/mcp");
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> help = true;
                case "--transport" -> transport = parseTransport(nextArgValue(args, ++i, "--transport"));
                case "--host" -> host = nextArgValue(args, ++i, "--host");
                case "--port" -> port = parsePort(nextArgValue(args, ++i, "--port"));
                case "--path" -> path = nextArgValue(args, ++i, "--path");
                default -> throw new IllegalArgumentException("未知参数: " + arg);
            }
        }
        return new ServerConfig(transport, host, port, path, help);
    }

    private static String nextArgValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少参数值");
        }
        return args[index];
    }

    private static String envOrDefault(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val == null || val.isBlank()) ? defaultValue : val.trim();
    }

    private static TransportMode parseTransport(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "both", "all" -> TransportMode.BOTH;
            case "stdio" -> TransportMode.STDIO;
            case "http", "streamable-http" -> TransportMode.HTTP;
            default -> throw new IllegalArgumentException("不支持的 transport 模式: " + value + "（可选: both, stdio, http）");
        };
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("端口号必须在 0..65535 之间: " + value);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效端口号: " + value);
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                用法: java -Dfile.encoding=UTF-8 -jar mcp-server-java-1.0.0.jar [选项]

                选项:
                  --transport <both|stdio|http>  传输模式，默认 both（同时开启 Stdio 与 Streamable HTTP）
                  --host <addr>                  HTTP 监听地址，默认 0.0.0.0
                  --port <port>                  HTTP 监听端口，默认 8080
                  --path <path>                  Streamable HTTP 端点路径，默认 /mcp
                  -h, --help                     显示帮助信息

                环境变量（命令行参数优先级更高）:
                  MCP_TRANSPORT, MCP_HOST, MCP_PORT, MCP_PATH""");
    }

    enum TransportMode {
        BOTH, STDIO, HTTP
    }

    record ServerConfig(TransportMode transport, String host, int port, String path, boolean help) {
    }
}
