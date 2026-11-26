package com.example.mcp.server;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Resource 定义与读取接口。
 */
public interface McpResource {

    String uri();

    String name();

    String description();

    String mimeType();

    String readText() throws Exception;

    default Map<String, Object> toDescriptor() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uri", uri());
        map.put("name", name());
        map.put("description", description());
        map.put("mimeType", mimeType());
        return map;
    }

    default Map<String, Object> toContent() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uri", uri());
        map.put("mimeType", mimeType());
        map.put("text", readText());
        return map;
    }
}
