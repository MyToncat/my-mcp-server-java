package com.example.mcp.server;

import com.example.mcp.json.JsonCodec;
import com.example.mcp.json.JsonCodec.JsonParseException;
import com.example.mcp.protocol.JsonRpc;
import com.example.mcp.protocol.JsonRpc.JsonRpcException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 手工编写的 MCP (Model Context Protocol) 核心服务端。
 * <p>
 * 基于 Stdio 传输层（按行分隔的 UTF-8 JSON-RPC 2.0 消息）：
 * - stdout 仅输出合法的 JSON-RPC 响应行
 * - 日志与调试信息统一输出到 stderr，绝不污染 stdout
 */
public final class McpServer {

    private static final String LATEST_PROTOCOL_VERSION = "2025-03-26";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18"
    );

    private final String serverName;
    private final String serverVersion;
    private final String instructions;
    private final PrintStream logStream;

    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final Map<String, McpResource> resources = new LinkedHashMap<>();
    private final Map<String, McpPrompt> prompts = new LinkedHashMap<>();

    private volatile boolean initialized = false;

    public McpServer(String serverName, String serverVersion, String instructions, PrintStream logStream) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.instructions = instructions;
        this.logStream = logStream;
    }

    public McpServer registerTool(McpTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public McpServer registerResource(McpResource resource) {
        resources.put(resource.uri(), resource);
        return this;
    }

    public McpServer registerPrompt(McpPrompt prompt) {
        prompts.put(prompt.name(), prompt);
        return this;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 启动 Stdio 消息循环，阻塞直到输入流关闭 (EOF)。
     */
    public void run(InputStream in, PrintStream out) throws IOException {
        log("Starting MCP server [" + serverName + " v" + serverVersion + "] over stdio");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String responseLine = handleLine(line);
                if (responseLine != null) {
                    out.println(responseLine);
                    out.flush();
                }
            }
        }
        log("Input stream closed (EOF), shutting down MCP server.");
    }

    /**
     * 处理单行 JSON-RPC 2.0 消息（支持单条请求与批量请求数组）。
     *
     * @return 需要写回客户端的 JSON 字符串；若消息仅为 Notification，则返回 {@code null}。
     */
    public String handleLine(String line) {
        if (line == null) {
            return null;
        }
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            line = line.substring(1);
        }
        if (line.isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = JsonCodec.parse(line);
        } catch (JsonParseException e) {
            return JsonRpc.stringifyError(null, JsonRpc.PARSE_ERROR, "Parse error: " + e.getMessage());
        }

        if (parsed instanceof Map<?, ?>) {
            Map<String, Object> response = processMessage(JsonCodec.asObject(parsed));
            return response == null ? null : JsonCodec.stringify(response);
        }

        if (parsed instanceof List<?> batch) {
            if (batch.isEmpty()) {
                return JsonRpc.stringifyError(null, JsonRpc.INVALID_REQUEST, "Invalid Request: empty batch");
            }
            List<Map<String, Object>> responses = new ArrayList<>();
            for (Object item : batch) {
                Map<String, Object> msg = JsonCodec.asObject(item);
                if (msg == null) {
                    responses.add(JsonRpc.errorResponse(null, JsonRpc.INVALID_REQUEST, "Invalid Request: batch item must be an object", null));
                } else {
                    Map<String, Object> resp = processMessage(msg);
                    if (resp != null) {
                        responses.add(resp);
                    }
                }
            }
            return responses.isEmpty() ? null : JsonCodec.stringify(responses);
        }

        return JsonRpc.stringifyError(null, JsonRpc.INVALID_REQUEST, "Invalid Request: top-level JSON must be object or array");
    }

    private Map<String, Object> processMessage(Map<String, Object> msg) {
        if (msg == null) {
            return JsonRpc.errorResponse(null, JsonRpc.INVALID_REQUEST, "Invalid Request", null);
        }

        boolean hasId = msg.containsKey("id");
        Object id = msg.get("id");
        if (hasId && !JsonRpc.isValidId(id)) {
            return JsonRpc.errorResponse(null, JsonRpc.INVALID_REQUEST, "Invalid Request: 'id' must be string, number, or null", null);
        }

        String jsonrpc = JsonCodec.asString(msg.get("jsonrpc"));
        String method = JsonCodec.asString(msg.get("method"));
        if (!JsonRpc.VERSION.equals(jsonrpc) || method == null || method.isBlank()) {
            if (!hasId) {
                return null;
            }
            return JsonRpc.errorResponse(id, JsonRpc.INVALID_REQUEST, "Invalid Request: missing valid 'jsonrpc' or 'method'", null);
        }

        Object rawParams = msg.get("params");
        Map<String, Object> params;
        if (rawParams == null) {
            params = Collections.emptyMap();
        } else if (rawParams instanceof Map<?, ?>) {
            params = JsonCodec.asObject(rawParams);
        } else {
            if (!hasId) {
                return null;
            }
            return JsonRpc.errorResponse(id, JsonRpc.INVALID_PARAMS, "Invalid params: 'params' must be an object", null);
        }

        // Notification：不包含 id，服务端处理后不得返回响应
        if (!hasId) {
            handleNotification(method, params);
            return null;
        }

        // Request：必须返回带相同 id 的响应
        try {
            Object result = dispatchRequest(method, params);
            return JsonRpc.successResponse(id, result);
        } catch (JsonRpcException e) {
            return JsonRpc.errorResponse(id, e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            log("Internal error handling method '" + method + "': " + e.getMessage());
            return JsonRpc.errorResponse(id, JsonRpc.INTERNAL_ERROR, "Internal error: " + e.getMessage(), null);
        }
    }

    private void handleNotification(String method, Map<String, Object> params) {
        switch (method) {
            case "notifications/initialized" -> {
                initialized = true;
                log("Client completed MCP initialization handshake.");
            }
            case "notifications/cancelled" -> {
                Object requestId = params.get("requestId");
                log("Received cancellation notification for requestId=" + requestId);
            }
            default -> log("Ignored notification: " + method);
        }
    }

    private Object dispatchRequest(String method, Map<String, Object> params) throws Exception {
        return switch (method) {
            case "initialize" -> handleInitialize(params);
            case "ping" -> Collections.emptyMap();
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(params);
            case "resources/list" -> handleResourcesList();
            case "resources/read" -> handleResourcesRead(params);
            case "prompts/list" -> handlePromptsList();
            case "prompts/get" -> handlePromptsGet(params);
            default -> throw new JsonRpcException(JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method);
        };
    }

    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        String requestedVersion = JsonCodec.asString(params.get("protocolVersion"));
        String negotiatedVersion = (requestedVersion != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion))
                ? requestedVersion
                : LATEST_PROTOCOL_VERSION;

        Map<String, Object> capabilities = new LinkedHashMap<>();
        if (!tools.isEmpty()) {
            capabilities.put("tools", JsonCodec.objectOf("listChanged", false));
        }
        if (!resources.isEmpty()) {
            capabilities.put("resources", JsonCodec.objectOf("subscribe", false, "listChanged", false));
        }
        if (!prompts.isEmpty()) {
            capabilities.put("prompts", JsonCodec.objectOf("listChanged", false));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiatedVersion);
        result.put("capabilities", capabilities);
        result.put("serverInfo", JsonCodec.objectOf("name", serverName, "version", serverVersion));
        if (instructions != null && !instructions.isBlank()) {
            result.put("instructions", instructions);
        }
        log("Handled initialize (client requested=" + requestedVersion + ", negotiated=" + negotiatedVersion + ")");
        return result;
    }

    private Map<String, Object> handleToolsList() {
        List<Map<String, Object>> toolDescriptors = tools.values().stream()
                .map(McpTool::toDescriptor)
                .toList();
        return JsonCodec.objectOf("tools", toolDescriptors);
    }

    private Map<String, Object> handleToolsCall(Map<String, Object> params) {
        String name = JsonCodec.asString(params.get("name"));
        if (name == null || name.isBlank()) {
            throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Missing required parameter: 'name'");
        }
        McpTool tool = tools.get(name);
        if (tool == null) {
            throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Unknown tool: " + name);
        }

        Object rawArgs = params.get("arguments");
        Map<String, Object> arguments;
        if (rawArgs == null) {
            arguments = Collections.emptyMap();
        } else if (rawArgs instanceof Map<?, ?>) {
            arguments = JsonCodec.asObject(rawArgs);
        } else {
            throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Parameter 'arguments' must be an object");
        }

        try {
            McpTool.ToolResult result = tool.execute(arguments);
            return result.toMap();
        } catch (Exception e) {
            log("Tool '" + name + "' execution error: " + e.getMessage());
            return McpTool.ToolResult.error("Tool execution error: " + e.getMessage()).toMap();
        }
    }

    private Map<String, Object> handleResourcesList() {
        List<Map<String, Object>> resourceDescriptors = resources.values().stream()
                .map(McpResource::toDescriptor)
                .toList();
        return JsonCodec.objectOf("resources", resourceDescriptors);
    }

    private Map<String, Object> handleResourcesRead(Map<String, Object> params) throws Exception {
        String uri = JsonCodec.asString(params.get("uri"));
        if (uri == null || uri.isBlank()) {
            throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Missing required parameter: 'uri'");
        }
        McpResource resource = resources.get(uri);
        if (resource == null) {
            throw new JsonRpcException(JsonRpc.RESOURCE_NOT_FOUND, "Resource not found: " + uri);
        }
        return JsonCodec.objectOf("contents", List.of(resource.toContent()));
    }

    private Map<String, Object> handlePromptsList() {
        List<Map<String, Object>> promptDescriptors = prompts.values().stream()
                .map(McpPrompt::toDescriptor)
                .toList();
        return JsonCodec.objectOf("prompts", promptDescriptors);
    }

    private Map<String, Object> handlePromptsGet(Map<String, Object> params) {
        String name = JsonCodec.asString(params.get("name"));
        if (name == null || name.isBlank()) {
            throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Missing required parameter: 'name'");
        }
        McpPrompt prompt = prompts.get(name);
        if (prompt == null) {
            throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Unknown prompt: " + name);
        }

        Object rawArgs = params.get("arguments");
        Map<String, Object> arguments;
        if (rawArgs == null) {
            arguments = Collections.emptyMap();
        } else if (rawArgs instanceof Map<?, ?>) {
            arguments = JsonCodec.asObject(rawArgs);
        } else {
            throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Parameter 'arguments' must be an object");
        }

        for (McpPrompt.PromptArgument arg : prompt.arguments()) {
            if (arg.required()) {
                Object value = arguments.get(arg.name());
                if (value == null || String.valueOf(value).isBlank()) {
                    throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Missing required prompt argument: '" + arg.name() + "'");
                }
            }
        }

        List<Map<String, Object>> messages = prompt.render(arguments).stream()
                .map(McpPrompt.PromptMessage::toMap)
                .toList();
        return JsonCodec.objectOf(
                "description", prompt.description(),
                "messages", messages
        );
    }

    private void log(String message) {
        if (logStream != null) {
            logStream.println("[mcp-server] " + message);
            logStream.flush();
        }
    }
}
