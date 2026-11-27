package com.example.mcp.server;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Resource 定义与读取接口。
 */
public interface McpResource {

    /**
     * 返回资源唯一 URI（如 {@code info://server/status}）。
     */
    String uri();

    /**
     * 返回资源展示名称。
     */
    String name();

    /**
     * 返回资源用途说明。
     */
    String description();

    /**
     * 返回资源内容的 MIME 类型（如 {@code application/json}）。
     */
    String mimeType();

    /**
     * 读取并返回资源文本内容。
     */
    String readText() throws Exception;

    /**
     * 将当前资源转换为 {@code resources/list} 所需的元数据描述。
     */
    default Map<String, Object> toDescriptor() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uri", uri());
        map.put("name", name());
        map.put("description", description());
        map.put("mimeType", mimeType());
        return map;
    }

    /**
     * 读取资源并封装为 {@code resources/read} 的 {@code contents} 项。
     */
    default Map<String, Object> toContent() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uri", uri());
        map.put("mimeType", mimeType());
        map.put("text", readText());
        return map;
    }
}
