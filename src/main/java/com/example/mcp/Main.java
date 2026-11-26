package com.example.mcp;

import com.example.mcp.json.JsonCodec;
import com.example.mcp.server.McpPrompt;
import com.example.mcp.server.McpResource;
import com.example.mcp.server.McpServer;
import com.example.mcp.server.McpTool;
import com.example.mcp.tools.BuiltinTools;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 手工编写的 Java MCP 服务端入口。
 * <p>
 * 注册了 Tools（工具）、Resources（资源）和 Prompts（提示词模板），
 * 并通过标准输入输出（Stdio）暴露给 MCP 客户端调用。
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
        McpServer server = createServer(utf8Err);
        server.run(System.in, utf8Out);
    }
}
