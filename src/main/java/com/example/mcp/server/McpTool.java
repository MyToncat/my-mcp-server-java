package com.example.mcp.server;

import com.example.mcp.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool 定义与执行接口。
 */
public interface McpTool {

    /**
     * 返回工具唯一名称（供 {@code tools/call} 按名查找）。
     */
    String name();

    /**
     * 返回给模型和客户端阅读的工具功能描述。
     */
    String description();

    /**
     * 返回符合 JSON Schema 规范的 inputSchema 对象。
     */
    Map<String, Object> inputSchema();

    /**
     * 根据传入参数执行工具逻辑并返回结果。
     */
    ToolResult execute(Map<String, Object> arguments) throws Exception;

    /**
     * 将当前工具转换为 {@code tools/list} 所需的描述对象。
     */
    default Map<String, Object> toDescriptor() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name());
        map.put("description", description());
        map.put("inputSchema", inputSchema());
        return map;
    }

    /**
     * MCP 工具执行结果封装。
     *
     * @param content 输出内容块列表（如 text 内容块）
     * @param isError 是否为业务执行错误（为 {@code true} 时仍以 JSON-RPC 成功帧返回，由模型感知错误）
     */
    record ToolResult(List<Map<String, Object>> content, boolean isError) {

        /**
         * 构造常规纯文本成功结果。
         */
        public static ToolResult text(String text) {
            return new ToolResult(List.of(JsonCodec.objectOf("type", "text", "text", text)), false);
        }

        /**
         * 构造标记 {@code isError=true} 的文本错误结果。
         */
        public static ToolResult error(String message) {
            return new ToolResult(List.of(JsonCodec.objectOf("type", "text", "text", message)), true);
        }

        /**
         * 转换为 MCP {@code tools/call} 响应中的 Map 结构。
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("content", content);
            map.put("isError", isError);
            return map;
        }
    }
}
