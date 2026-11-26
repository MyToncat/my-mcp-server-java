package com.example.mcp;

import com.example.mcp.json.JsonCodec;
import com.example.mcp.json.JsonCodec.JsonParseException;
import com.example.mcp.protocol.JsonRpc;
import com.example.mcp.server.McpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
}
