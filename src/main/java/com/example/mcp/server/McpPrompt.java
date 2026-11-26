package com.example.mcp.server;

import com.example.mcp.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Prompt 模板定义与渲染接口。
 */
public interface McpPrompt {

    String name();

    String description();

    List<PromptArgument> arguments();

    List<PromptMessage> render(Map<String, Object> arguments);

    default Map<String, Object> toDescriptor() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name());
        map.put("description", description());
        map.put("arguments", arguments().stream().map(PromptArgument::toMap).toList());
        return map;
    }

    record PromptArgument(String name, String description, boolean required) {
        public Map<String, Object> toMap() {
            return JsonCodec.objectOf(
                    "name", name,
                    "description", description,
                    "required", required
            );
        }
    }

    record PromptMessage(String role, String text) {
        public Map<String, Object> toMap() {
            return JsonCodec.objectOf(
                    "role", role,
                    "content", JsonCodec.objectOf("type", "text", "text", text)
            );
        }
    }
}
