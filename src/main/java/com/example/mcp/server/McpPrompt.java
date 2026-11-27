package com.example.mcp.server;

import com.example.mcp.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Prompt 模板定义与渲染接口。
 */
public interface McpPrompt {

    /**
     * 返回提示词模板唯一名称。
     */
    String name();

    /**
     * 返回提示词模板功能描述。
     */
    String description();

    /**
     * 返回模板接受的参数定义列表。
     */
    List<PromptArgument> arguments();

    /**
     * 根据传入参数渲染生成对话消息列表。
     */
    List<PromptMessage> render(Map<String, Object> arguments);

    /**
     * 将模板转换为 {@code prompts/list} 所需的描述对象。
     */
    default Map<String, Object> toDescriptor() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name());
        map.put("description", description());
        map.put("arguments", arguments().stream().map(PromptArgument::toMap).toList());
        return map;
    }

    /**
     * 提示词模板参数声明。
     *
     * @param name        参数名
     * @param description 参数说明
     * @param required    是否必填
     */
    record PromptArgument(String name, String description, boolean required) {
        /**
         * 转换为 MCP 协议要求的描述 Map。
         */
        public Map<String, Object> toMap() {
            return JsonCodec.objectOf(
                    "name", name,
                    "description", description,
                    "required", required
            );
        }
    }

    /**
     * 渲染后的单条提示词消息。
     *
     * @param role 消息角色（如 {@code user}、{@code assistant}）
     * @param text 文本消息内容
     */
    record PromptMessage(String role, String text) {
        /**
         * 转换为 MCP {@code prompts/get} 响应中的消息 Map。
         */
        public Map<String, Object> toMap() {
            return JsonCodec.objectOf(
                    "role", role,
                    "content", JsonCodec.objectOf("type", "text", "text", text)
            );
        }
    }
}
