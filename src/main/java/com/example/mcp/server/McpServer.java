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
 * 传输层无关的核心调度器，可同时服务于：
 * - Stdio 传输层（按行分隔的 UTF-8 JSON-RPC 2.0 消息，stdout 仅输出 JSON-RPC 响应，日志走 stderr）
 * - Streamable HTTP 传输层（单端点 /mcp，支持多会话隔离）
 */
public final class McpServer {

    public static final String LATEST_PROTOCOL_VERSION = "2025-03-26";
    public static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
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

    private final McpSession defaultSession = new McpSession("stdio");

    public McpServer(String serverName, String serverVersion, String instructions, PrintStream logStream) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.instructions = instructions;
        this.logStream = logStream;
    }

    public String getServerName() {
        return serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public static boolean isSupportedProtocolVersion(String version) {
        return version != null && SUPPORTED_PROTOCOL_VERSIONS.contains(version);
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
        return defaultSession.isInitialized();
    }

    /**
     * 单个 MCP 客户端会话状态（用于隔离 Stdio 与多个 HTTP 客户端会话）。
     */
    public static final class McpSession {
        private final String id;
        private volatile boolean initialized;
        private volatile String negotiatedProtocolVersion = LATEST_PROTOCOL_VERSION;

        public McpSession(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public boolean isInitialized() {
            return initialized;
        }

        void markInitialized() {
            this.initialized = true;
        }

        public String protocolVersion() {
            return negotiatedProtocolVersion;
        }

        void setProtocolVersion(String protocolVersion) {
            this.negotiatedProtocolVersion = protocolVersion;
        }
    }

    /**
     * 单次 JSON-RPC 消息处理结果，供 Stdio 与 HTTP 传输层复用。
     */
    public record DispatchOutcome(
            String responseJson,
            boolean parseError,
            boolean initializedHandshake,
            String negotiatedProtocolVersion
    ) {
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
     * 处理单行 JSON-RPC 2.0 消息（使用默认 Stdio 会话，支持单条请求与批量请求数组）。
     *
     * @return 需要写回客户端的 JSON 字符串；若消息仅为 Notification，则返回 {@code null}。
     */
    public String handleLine(String line) {
        return handleMessage(line, defaultSession).responseJson();
    }

    /**
     * 在指定会话上下文中处理一条 JSON-RPC 2.0 消息或批量请求数组。
     */
    public DispatchOutcome handleMessage(String payload, McpSession session) {
        if (payload == null) {
            return new DispatchOutcome(null, false, false, null);
        }
        String text = payload;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            return new DispatchOutcome(null, false, false, null);
        }

        McpSession activeSession = (session != null) ? session : defaultSession;
        Object parsed;
        try {
            parsed = JsonCodec.parse(text);
        } catch (JsonParseException e) {
            String err = JsonRpc.stringifyError(null, JsonRpc.PARSE_ERROR, "Parse error: " + e.getMessage());
            return new DispatchOutcome(err, true, false, null);
        }

        SessionTracker tracker = new SessionTracker();

        if (parsed instanceof Map<?, ?>) {
            Map<String, Object> response = processMessage(JsonCodec.asObject(parsed), activeSession, tracker);
            String json = (response == null) ? null : JsonCodec.stringify(response);
            return new DispatchOutcome(json, false, tracker.initializedHandshake, tracker.negotiatedVersion);
        }

        if (parsed instanceof List<?> batch) {
            if (batch.isEmpty()) {
                String err = JsonRpc.stringifyError(null, JsonRpc.INVALID_REQUEST, "Invalid Request: empty batch");
                return new DispatchOutcome(err, false, false, null);
            }
            List<Map<String, Object>> responses = new ArrayList<>();
            for (Object item : batch) {
                Map<String, Object> msg = JsonCodec.asObject(item);
                if (msg == null) {
                    responses.add(JsonRpc.errorResponse(null, JsonRpc.INVALID_REQUEST, "Invalid Request: batch item must be an object", null));
                } else {
                    Map<String, Object> resp = processMessage(msg, activeSession, tracker);
                    if (resp != null) {
                        responses.add(resp);
                    }
                }
            }
            String json = responses.isEmpty() ? null : JsonCodec.stringify(responses);
            return new DispatchOutcome(json, false, tracker.initializedHandshake, tracker.negotiatedVersion);
        }

        String err = JsonRpc.stringifyError(null, JsonRpc.INVALID_REQUEST, "Invalid Request: top-level JSON must be object or array");
        return new DispatchOutcome(err, false, false, null);
    }

    private static final class SessionTracker {
        boolean initializedHandshake;
        String negotiatedVersion;
    }

    private Map<String, Object> processMessage(Map<String, Object> msg, McpSession session, SessionTracker tracker) {
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
            handleNotification(method, params, session);
            return null;
        }

        // Request：必须返回带相同 id 的响应
        try {
            Object result = dispatchRequest(method, params, session, tracker);
            return JsonRpc.successResponse(id, result);
        } catch (JsonRpcException e) {
            return JsonRpc.errorResponse(id, e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            log("Internal error handling method '" + method + "': " + e.getMessage());
            return JsonRpc.errorResponse(id, JsonRpc.INTERNAL_ERROR, "Internal error: " + e.getMessage(), null);
        }
    }

    private void handleNotification(String method, Map<String, Object> params, McpSession session) {
        switch (method) {
            case "notifications/initialized" -> {
                session.markInitialized();
                log("Client [" + session.id() + "] completed MCP initialization handshake.");
            }
            case "notifications/cancelled" -> {
                Object requestId = params.get("requestId");
                log("Received cancellation notification for requestId=" + requestId + " (session=" + session.id() + ")");
            }
            default -> log("Ignored notification: " + method);
        }
    }

    private Object dispatchRequest(String method, Map<String, Object> params, McpSession session, SessionTracker tracker) throws Exception {
        return switch (method) {
            case "initialize" -> handleInitialize(params, session, tracker);
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

    private Map<String, Object> handleInitialize(Map<String, Object> params, McpSession session, SessionTracker tracker) {
        String requestedVersion = JsonCodec.asString(params.get("protocolVersion"));
        String negotiatedVersion = isSupportedProtocolVersion(requestedVersion)
                ? requestedVersion
                : LATEST_PROTOCOL_VERSION;
        session.setProtocolVersion(negotiatedVersion);
        tracker.initializedHandshake = true;
        tracker.negotiatedVersion = negotiatedVersion;

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

    public void log(String message) {
        if (logStream != null) {
            logStream.println("[mcp-server] " + message);
            logStream.flush();
        }
    }
}
