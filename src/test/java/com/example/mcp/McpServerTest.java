package com.example.mcp;

import com.example.mcp.json.JsonCodec;
import com.example.mcp.json.JsonCodec.JsonParseException;
import com.example.mcp.protocol.JsonRpc;
import com.example.mcp.server.McpServer;
import com.example.mcp.server.StreamableHttpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    private McpServer server;

    @BeforeEach
    void setUp() {
        server = Main.createServer(null);
    }

    @Test
    void jsonCodecRoundTripAndEscapes() {
        Map<String, Object> input = JsonCodec.objectOf(
                "text", "你好\n\"MCP\"\t\\u4e2d",
                "count", 42L,
                "ratio", 3.14,
                "active", true,
                "empty", null,
                "items", List.of("a", 1L, false)
        );
        String json = JsonCodec.stringify(input);
        Map<String, Object> parsed = JsonCodec.asObject(JsonCodec.parse(json));
        assertNotNull(parsed);
        assertEquals("你好\n\"MCP\"\t\\u4e2d", parsed.get("text"));
        assertEquals(42L, parsed.get("count"));
        assertEquals(3.14, (Double) parsed.get("ratio"), 1e-9);
        assertEquals(true, parsed.get("active"));
        assertNull(parsed.get("empty"));
        assertEquals(List.of("a", 1L, false), parsed.get("items"));
    }

    @Test
    void jsonCodecRejectsMalformedInput() {
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("{invalid}"));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("01"));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("[1, 2,]"));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("{\"a\":1} trailing"));
    }

    @Test
    void initializeAndNotificationsLifecycle() {
        String initReq = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-client","version":"0.1"}}}
                """;
        Map<String, Object> resp = JsonCodec.asObject(JsonCodec.parse(server.handleLine(initReq)));
        assertEquals("2.0", resp.get("jsonrpc"));
        assertEquals(1L, resp.get("id"));
        Map<String, Object> result = JsonCodec.asObject(resp.get("result"));
        assertEquals("2024-11-05", result.get("protocolVersion"));
        assertNotNull(JsonCodec.asObject(result.get("capabilities")).get("tools"));
        assertEquals("mcp-server-java", JsonCodec.asObject(result.get("serverInfo")).get("name"));

        // notifications/initialized 属于 Notification，不应产生任何返回行
        assertFalse(server.isInitialized());
        assertNull(server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertTrue(server.isInitialized());

        // ping 应返回空对象
        Map<String, Object> pingResp = JsonCodec.asObject(
                JsonCodec.parse(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":\"ping-1\",\"method\":\"ping\"}"))
        );
        assertEquals("ping-1", pingResp.get("id"));
        assertEquals(Map.of(), pingResp.get("result"));
    }

    @Test
    void toolsListAndCall() {
        Map<String, Object> listResp = JsonCodec.asObject(
                JsonCodec.parse(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
        );
        List<Object> tools = JsonCodec.asArray(JsonCodec.asObject(listResp.get("result")).get("tools"));
        assertEquals(3, tools.size());

        // calculate 工具正常调用
        String calcReq = """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"calculate","arguments":{"expression":"(12 + 8) * 3.5 / 2 + sqrt(16)"}}}
                """;
        Map<String, Object> calcResp = JsonCodec.asObject(JsonCodec.parse(server.handleLine(calcReq)));
        Map<String, Object> calcResult = JsonCodec.asObject(calcResp.get("result"));
        assertEquals(false, calcResult.get("isError"));
        String calcText = JsonCodec.asString(JsonCodec.asObject(JsonCodec.asArray(calcResult.get("content")).get(0)).get("text"));
        assertTrue(calcText.endsWith("= 39"));

        // calculate 除零返回 isError=true
        String divZeroReq = """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"calculate","arguments":{"expression":"10 / 0"}}}
                """;
        Map<String, Object> divZeroResp = JsonCodec.asObject(JsonCodec.parse(server.handleLine(divZeroReq)));
        assertEquals(true, JsonCodec.asObject(divZeroResp.get("result")).get("isError"));

        // get_current_time 与 text_stats 调用
        String timeReq = """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_current_time","arguments":{"timezone":"UTC"}}}
                """;
        Map<String, Object> timeResp = JsonCodec.asObject(JsonCodec.parse(server.handleLine(timeReq)));
        assertEquals(false, JsonCodec.asObject(timeResp.get("result")).get("isError"));

        String statsReq = """
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"text_stats","arguments":{"text":"hello world\\n你好"}}}
                """;
        Map<String, Object> statsResp = JsonCodec.asObject(JsonCodec.parse(server.handleLine(statsReq)));
        String statsJson = JsonCodec.asString(
                JsonCodec.asObject(JsonCodec.asArray(JsonCodec.asObject(statsResp.get("result")).get("content")).get(0)).get("text")
        );
        Map<String, Object> statsMap = JsonCodec.asObject(JsonCodec.parse(statsJson));
        assertEquals(3L, statsMap.get("words"));
        assertEquals(2L, statsMap.get("lines"));
    }

    @Test
    void resourcesAndPrompts() {
        Map<String, Object> resList = JsonCodec.asObject(
                JsonCodec.parse(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"resources/list\"}"))
        );
        assertEquals(1, JsonCodec.asArray(JsonCodec.asObject(resList.get("result")).get("resources")).size());

        Map<String, Object> resRead = JsonCodec.asObject(
                JsonCodec.parse(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"resources/read\",\"params\":{\"uri\":\"info://server/status\"}}"))
        );
        String statusText = JsonCodec.asString(
                JsonCodec.asObject(JsonCodec.asArray(JsonCodec.asObject(resRead.get("result")).get("contents")).get(0)).get("text")
        );
        assertTrue(statusText.contains("mcp-server-java"));

        Map<String, Object> promptGet = JsonCodec.asObject(
                JsonCodec.parse(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"prompts/get\",\"params\":{\"name\":\"code_review\",\"arguments\":{\"language\":\"Java\",\"code\":\"int a = 1;\"}}}"))
        );
        List<Object> messages = JsonCodec.asArray(JsonCodec.asObject(promptGet.get("result")).get("messages"));
        assertEquals(1, messages.size());
        String promptText = JsonCodec.asString(JsonCodec.asObject(JsonCodec.asObject(messages.get(0)).get("content")).get("text"));
        assertTrue(promptText.contains("int a = 1;"));
    }

    @Test
    void jsonRpcErrorCodesAndStdioStreamLoop() throws Exception {
        // Parse error (-32700)
        Map<String, Object> parseErr = JsonCodec.asObject(JsonCodec.parse(server.handleLine("{bad json")));
        assertEquals((long) JsonRpc.PARSE_ERROR, JsonCodec.asObject(parseErr.get("error")).get("code"));

        // Method not found (-32601)
        Map<String, Object> notFoundErr = JsonCodec.asObject(
                JsonCodec.parse(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"unknown/method\"}"))
        );
        assertEquals((long) JsonRpc.METHOD_NOT_FOUND, JsonCodec.asObject(notFoundErr.get("error")).get("code"));

        // Unknown tool (-32602)
        Map<String, Object> unknownTool = JsonCodec.asObject(
                JsonCodec.parse(server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\"}}"))
        );
        assertEquals((long) JsonRpc.INVALID_PARAMS, JsonCodec.asObject(unknownTool.get("error")).get("code"));

        // 带 UTF-8 BOM 的首行消息应可正常解析（兼容 Windows 管道输出）
        Map<String, Object> bomResp = JsonCodec.asObject(
                JsonCodec.parse(server.handleLine("\uFEFF{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"ping\"}"))
        );
        assertEquals(22L, bomResp.get("id"));

        // Stdio 流端到端测试
        String inputLines = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"
        ) + "\n";
        ByteArrayInputStream in = new ByteArrayInputStream(inputLines.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        server.run(in, new PrintStream(outBuf, true, StandardCharsets.UTF_8));

        String[] outputLines = outBuf.toString(StandardCharsets.UTF_8).trim().split("\r?\n");
        assertEquals(2, outputLines.length);
        assertEquals(1L, JsonCodec.asObject(JsonCodec.parse(outputLines[0])).get("id"));
        assertEquals(2L, JsonCodec.asObject(JsonCodec.parse(outputLines[1])).get("id"));
    }

    @Test
    void streamableHttpFullSessionLifecycle() throws Exception {
        try (StreamableHttpTransport http = new StreamableHttpTransport(server, "127.0.0.1", 0, "/mcp")) {
            http.start();
            String baseUrl = "http://127.0.0.1:" + http.getPort();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            // 1. /health 健康检查
            HttpResponse<String> healthResp = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(200, healthResp.statusCode());
            assertEquals("streamable-http", JsonCodec.asObject(JsonCodec.parse(healthResp.body())).get("transport"));

            // 2. initialize 获取 Mcp-Session-Id
            String initBody = """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"http-test","version":"1.0"}}}
                    """;
            HttpResponse<String> initResp = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(initBody, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(200, initResp.statusCode());
            String sessionId = initResp.headers().firstValue(StreamableHttpTransport.HEADER_SESSION_ID).orElse(null);
            assertNotNull(sessionId);
            assertFalse(http.getSession(sessionId).isInitialized());

            // 3. notifications/initialized 应返回 202 Accepted 且无响应体
            HttpResponse<String> notifResp = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                            .header("Content-Type", "application/json")
                            .header(StreamableHttpTransport.HEADER_SESSION_ID, sessionId)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(202, notifResp.statusCode());
            assertTrue(http.getSession(sessionId).isInitialized());
            // HTTP 会话应与默认 Stdio 会话隔离
            assertFalse(server.isInitialized());

            // 4. 携带 Session 调用 tools/call
            String calcBody = """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"calculate","arguments":{"expression":"sqrt(81) + 1"}}}
                    """;
            HttpResponse<String> calcResp = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .header(StreamableHttpTransport.HEADER_SESSION_ID, sessionId)
                            .header(StreamableHttpTransport.HEADER_PROTOCOL_VERSION, "2025-03-26")
                            .POST(HttpRequest.BodyPublishers.ofString(calcBody, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(200, calcResp.statusCode());
            Map<String, Object> calcJson = JsonCodec.asObject(JsonCodec.parse(calcResp.body()));
            String text = JsonCodec.asString(
                    JsonCodec.asObject(JsonCodec.asArray(JsonCodec.asObject(calcJson.get("result")).get("content")).get(0)).get("text")
            );
            assertTrue(text.endsWith("= 10"));

            // 5. DELETE /mcp 注销会话
            HttpResponse<String> delResp = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                            .header(StreamableHttpTransport.HEADER_SESSION_ID, sessionId)
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(204, delResp.statusCode());

            // 6. 使用已销毁的 Session 应返回 404
            HttpResponse<String> afterDel = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                            .header("Content-Type", "application/json")
                            .header(StreamableHttpTransport.HEADER_SESSION_ID, sessionId)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(404, afterDel.statusCode());
        }
    }

    @Test
    void streamableHttpStatelessSseAndErrors() throws Exception {
        try (StreamableHttpTransport http = new StreamableHttpTransport(server, "127.0.0.1", 0, "/mcp")) {
            http.start();
            String mcpUrl = "http://127.0.0.1:" + http.getPort() + "/mcp";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            // 1. 不带 Mcp-Session-Id 直接手工调用 tools/list
            HttpResponse<String> statelessResp = client.send(
                    HttpRequest.newBuilder(URI.create(mcpUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(200, statelessResp.statusCode());
            List<Object> tools = JsonCodec.asArray(
                    JsonCodec.asObject(JsonCodec.asObject(JsonCodec.parse(statelessResp.body())).get("result")).get("tools")
            );
            assertEquals(3, tools.size());

            // 2. Accept 仅指定 text/event-stream 时返回 SSE 格式
            HttpResponse<String> ssePostResp = client.send(
                    HttpRequest.newBuilder(URI.create(mcpUrl))
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(200, ssePostResp.statusCode());
            assertTrue(ssePostResp.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            assertTrue(ssePostResp.body().contains("event: message\ndata: "));

            // 3. 非法 JSON 返回 400 + -32700
            HttpResponse<String> badJsonResp = client.send(
                    HttpRequest.newBuilder(URI.create(mcpUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{bad", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(400, badJsonResp.statusCode());
            assertEquals((long) JsonRpc.PARSE_ERROR,
                    JsonCodec.asObject(JsonCodec.asObject(JsonCodec.parse(badJsonResp.body())).get("error")).get("code"));

            // 4. 不支持的 MCP-Protocol-Version 返回 400
            HttpResponse<String> badProtoResp = client.send(
                    HttpRequest.newBuilder(URI.create(mcpUrl))
                            .header("Content-Type", "application/json")
                            .header(StreamableHttpTransport.HEADER_PROTOCOL_VERSION, "1999-01-01")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(400, badProtoResp.statusCode());

            // 5. GET /mcp 建立 SSE 流并收到初始保活注释
            HttpResponse<InputStream> getSseResp = client.send(
                    HttpRequest.newBuilder(URI.create(mcpUrl))
                            .header("Accept", "text/event-stream")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            assertEquals(200, getSseResp.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(getSseResp.body(), StandardCharsets.UTF_8))) {
                assertEquals(": connected", reader.readLine());
            }

            // 6. CLI 参数解析验证
            Main.ServerConfig cfg = Main.parseConfig(new String[]{"--transport", "http", "--host", "127.0.0.1", "--port", "9090", "--path", "/custom"});
            assertEquals(Main.TransportMode.HTTP, cfg.transport());
            assertEquals("127.0.0.1", cfg.host());
            assertEquals(9090, cfg.port());
            assertEquals("/custom", cfg.path());
        }
    }
}
