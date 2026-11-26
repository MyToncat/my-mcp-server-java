package com.example.mcp.server;

import com.example.mcp.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool 定义与执行接口。
 */
public interface McpTool {

    String name();

    String description();

    /**
     * 返回符合 JSON Schema 规范的 inputSchema 对象。
     */
    Map<String, Object> inputSchema();

    ToolResult execute(Map<String, Object> arguments) throws Exception;

    default Map<String, Object> toDescriptor() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name());
        map.put("description", description());
        map.put("inputSchema", inputSchema());
        return map;
    }

    record ToolResult(List<Map<String, Object>> content, boolean isError) {

        public static ToolResult text(String text) {
            return new ToolResult(List.of(JsonCodec.objectOf("type", "text", "text", text)), false);
        }

        public static ToolResult error(String message) {
            return new ToolResult(List.of(JsonCodec.objectOf("type", "text", "text", message)), true);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("content", content);
            map.put("isError", isError);
            return map;
        }
    }
}
