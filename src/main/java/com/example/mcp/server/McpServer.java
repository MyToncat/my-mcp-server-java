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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /** 日志时间戳格式化器（精确到毫秒） */
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 单条日志摘要预览的最大字符长度 */
    private static final int MAX_LOG_PREVIEW_LEN = 300;

    /** 默认优先协商的 MCP 协议最新版本 */
    public static final String LATEST_PROTOCOL_VERSION = "2025-03-26";
    /** 当前服务端支持的 MCP 协议版本集合 */
    public static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18"
    );

    /** 服务端名称 */
    private final String serverName;
    /** 服务端版本号 */
    private final String serverVersion;
    /** 握手时向客户端/模型提供的使用说明 */
    private final String instructions;
    /** 日志输出流（通常为 stderr，可为 null 以禁用日志） */
    private final PrintStream logStream;

    /** 已注册的 Tool 映射表（保持注册顺序） */
    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    /** 已注册的 Resource 映射表（Key 为 URI，保持注册顺序） */
    private final Map<String, McpResource> resources = new LinkedHashMap<>();
    /** 已注册的 Prompt 映射表（保持注册顺序） */
    private final Map<String, McpPrompt> prompts = new LinkedHashMap<>();

    /** 默认 Stdio 会话上下文 */
    private final McpSession defaultSession = new McpSession("stdio");

    /**
     * 构造 MCP 核心服务端实例。
     *
     * @param serverName    服务端名称
     * @param serverVersion 服务端版本号
     * @param instructions  服务端使用说明（initialize 时可选返回）
     * @param logStream     日志输出流（运行期应传 {@code stderr}）
     */
    public McpServer(String serverName, String serverVersion, String instructions, PrintStream logStream) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.instructions = instructions;
        this.logStream = logStream;
    }

    /**
     * 获取服务端名称（用于 initialize 握手与 HTTP 健康检查）。
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * 获取服务端版本号。
     */
    public String getServerVersion() {
        return serverVersion;
    }

    /**
     * 判断客户端声明或请求的 MCP 协议版本是否在当前服务端支持列表内。
     */
    public static boolean isSupportedProtocolVersion(String version) {
        return version != null && SUPPORTED_PROTOCOL_VERSIONS.contains(version);
    }

    /**
     * 注册一个 MCP Tool（同名工具后注册者覆盖先注册者）。
     */
    public McpServer registerTool(McpTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    /**
     * 注册一个 MCP Resource（按 URI 索引）。
     */
    public McpServer registerResource(McpResource resource) {
        resources.put(resource.uri(), resource);
        return this;
    }

    /**
     * 注册一个 MCP Prompt 模板（按名称索引）。
     */
    public McpServer registerPrompt(McpPrompt prompt) {
        prompts.put(prompt.name(), prompt);
        return this;
    }

    /**
     * 返回默认 Stdio 会话是否已完成初始化握手。
     */
    public boolean isInitialized() {
        return defaultSession.isInitialized();
    }

    /**
     * 单个 MCP 客户端会话状态（用于隔离 Stdio 默认会话与多个 HTTP 客户端会话）。
     */
    public static final class McpSession {
        /** 会话唯一标识（Stdio 固定为 {@code stdio}，HTTP 为 UUID 或 {@code http-stateless}） */
        private final String id;
        /** 标记客户端是否已发送 notifications/initialized 完成初始化握手 */
        private volatile boolean initialized;
        /** 本会话协商确定的 MCP 协议版本 */
        private volatile String negotiatedProtocolVersion = LATEST_PROTOCOL_VERSION;

        /**
         * 使用指定会话 ID 构造会话状态对象。
         */
        public McpSession(String id) {
            this.id = id;
        }

        /**
         * 返回会话 ID。
         */
        public String id() {
            return id;
        }

        /**
         * 返回该会话是否已完成 {@code notifications/initialized} 握手。
         */
        public boolean isInitialized() {
            return initialized;
        }

        /**
         * 标记该会话已完成初始化握手。
         */
        void markInitialized() {
            this.initialized = true;
        }

        /**
         * 返回该会话协商确定的 MCP 协议版本。
         */
        public String protocolVersion() {
            return negotiatedProtocolVersion;
        }

        /**
         * 更新该会话协商确定的 MCP 协议版本。
         */
        void setProtocolVersion(String protocolVersion) {
            this.negotiatedProtocolVersion = protocolVersion;
        }
    }

    /**
     * 单次 JSON-RPC 消息处理结果，供 Stdio 与 HTTP 传输层复用。
     *
     * @param responseJson              序列化后的 JSON-RPC 响应字符串；仅通知/响应输入时为 {@code null}
     * @param parseError                是否为 JSON 语法解析失败（HTTP 层据此返回 400）
     * @param initializedHandshake      本次消息是否包含 {@code initialize} 握手请求（HTTP 层据此签发新 Session）
     * @param negotiatedProtocolVersion 握手协商后的协议版本号
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
        log("Starting MCP server [" + serverName + " v" + serverVersion + "] over stdio"
                + " (tools=" + tools.size() + ", resources=" + resources.size() + ", prompts=" + prompts.size() + ")");
        long lineCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                lineCount++;
                log("[stdio] <-- recv line#" + lineCount + " (" + line.length() + " chars)");
                String responseLine = handleLine(line);
                if (responseLine != null) {
                    out.println(responseLine);
                    out.flush();
                    log("[stdio] --> send line#" + lineCount + " (" + responseLine.length() + " chars)");
                } else {
                    log("[stdio] --> no response for line#" + lineCount + " (notification-only)");
                }
            }
        }
        log("[stdio] Input stream closed (EOF), processed " + lineCount + " message(s), shutting down MCP stdio loop.");
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
     * <p>
     * 关键流程：
     * 1. 剥离可能存在的 UTF-8 BOM（兼容 Windows 管道/文件输入）；
     * 2. 解析 JSON 文本，语法错误时返回 {@code -32700 Parse error} 并标记 {@code parseError=true}；
     * 3. 区分单条对象请求与 JSON-RPC 批处理数组，逐条分发并记录 {@code initialize} 握手状态；
     * 4. 若全部消息均为 Notification（无需响应），返回 {@code responseJson=null}。
     */
    public DispatchOutcome handleMessage(String payload, McpSession session) {
        if (payload == null) {
            return new DispatchOutcome(null, false, false, null);
        }
        String text = payload;
        // 剥离 UTF-8 BOM，避免 Windows 终端或管道首字符干扰 JSON 解析
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
            log("[session=" + activeSession.id() + "] JSON parse error: " + e.getMessage()
                    + " | raw=" + truncateForLog(text));
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
            // 按 JSON-RPC 2.0 规范，空数组属于无效批处理请求
            if (batch.isEmpty()) {
                log("[session=" + activeSession.id() + "] Rejected empty JSON-RPC batch");
                String err = JsonRpc.stringifyError(null, JsonRpc.INVALID_REQUEST, "Invalid Request: empty batch");
                return new DispatchOutcome(err, false, false, null);
            }
            log("[session=" + activeSession.id() + "] Processing JSON-RPC batch (size=" + batch.size() + ")");
            List<Map<String, Object>> responses = new ArrayList<>();
            for (Object item : batch) {
                Map<String, Object> msg = JsonCodec.asObject(item);
                if (msg == null) {
                    log("[session=" + activeSession.id() + "] Rejected non-object batch item");
                    responses.add(JsonRpc.errorResponse(null, JsonRpc.INVALID_REQUEST, "Invalid Request: batch item must be an object", null));
                } else {
                    Map<String, Object> resp = processMessage(msg, activeSession, tracker);
                    // Notification 不产生响应对象，不放入批处理结果数组
                    if (resp != null) {
                        responses.add(resp);
                    }
                }
            }
            String json = responses.isEmpty() ? null : JsonCodec.stringify(responses);
            return new DispatchOutcome(json, false, tracker.initializedHandshake, tracker.negotiatedVersion);
        }

        log("[session=" + activeSession.id() + "] Rejected non-object/non-array top-level JSON");
        String err = JsonRpc.stringifyError(null, JsonRpc.INVALID_REQUEST, "Invalid Request: top-level JSON must be object or array");
        return new DispatchOutcome(err, false, false, null);
    }

    /**
     * 追踪单次 {@link #handleMessage} 调用期间是否触发了 initialize 握手及其协商版本。
     */
    private static final class SessionTracker {
        /** 是否处理了 initialize 请求 */
        boolean initializedHandshake;
        /** 协商后的协议版本号 */
        String negotiatedVersion;
    }

    /**
     * 校验并处理单条 JSON-RPC 2.0 消息对象（区分 Notification 与 Request）。
     */
    private Map<String, Object> processMessage(Map<String, Object> msg, McpSession session, SessionTracker tracker) {
        if (msg == null) {
            log("[session=" + session.id() + "] Rejected null JSON-RPC message");
            return JsonRpc.errorResponse(null, JsonRpc.INVALID_REQUEST, "Invalid Request", null);
        }

        boolean hasId = msg.containsKey("id");
        Object id = msg.get("id");
        if (hasId && !JsonRpc.isValidId(id)) {
            log("[session=" + session.id() + "] Invalid request 'id': " + id);
            return JsonRpc.errorResponse(null, JsonRpc.INVALID_REQUEST, "Invalid Request: 'id' must be string, number, or null", null);
        }

        String jsonrpc = JsonCodec.asString(msg.get("jsonrpc"));
        String method = JsonCodec.asString(msg.get("method"));
        if (!JsonRpc.VERSION.equals(jsonrpc) || method == null || method.isBlank()) {
            log("[session=" + session.id() + "] Invalid request (hasId=" + hasId + ", id=" + id
                    + ", jsonrpc=" + jsonrpc + ", method=" + method + ")");
            // Notification（无 id）即使格式无效也不得返回错误响应
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
            log("[session=" + session.id() + "] Invalid params type for method=" + method + " (id=" + id + ")");
            if (!hasId) {
                return null;
            }
            return JsonRpc.errorResponse(id, JsonRpc.INVALID_PARAMS, "Invalid params: 'params' must be an object", null);
        }

        // Notification：不包含 id，服务端处理后不得返回响应
        if (!hasId) {
            log("[session=" + session.id() + "] <-- Notification method=" + method + " params=" + summarizeJson(params));
            handleNotification(method, params, session);
            return null;
        }

        // Request：必须返回带相同 id 的响应
        long startNanos = System.nanoTime();
        log("[session=" + session.id() + "] <-- Request id=" + id + " method=" + method + " params=" + summarizeJson(params));
        try {
            Object result = dispatchRequest(method, params, session, tracker);
            long elapsedMs = elapsedMillis(startNanos);
            log("[session=" + session.id() + "] --> Response id=" + id + " method=" + method
                    + " status=OK durationMs=" + elapsedMs + " result=" + summarizeJson(result));
            return JsonRpc.successResponse(id, result);
        } catch (JsonRpcException e) {
            long elapsedMs = elapsedMillis(startNanos);
            log("[session=" + session.id() + "] --> Response id=" + id + " method=" + method
                    + " status=ERROR code=" + e.getCode() + " durationMs=" + elapsedMs
                    + " message=" + truncateForLog(e.getMessage()));
            return JsonRpc.errorResponse(id, e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            long elapsedMs = elapsedMillis(startNanos);
            log("[session=" + session.id() + "] --> Response id=" + id + " method=" + method
                    + " status=INTERNAL_ERROR durationMs=" + elapsedMs + " error=" + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return JsonRpc.errorResponse(id, JsonRpc.INTERNAL_ERROR, "Internal error: " + e.getMessage(), null);
        }
    }

    /**
     * 处理无需响应的 MCP 通知消息（如初始化完成通知、取消请求通知）。
     */
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

    /**
     * 按 MCP 方法名路由到对应的请求处理方法，未知方法抛出 {@code -32601 Method not found}。
     */
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

    /**
     * 处理 {@code initialize} 握手请求：协商协议版本并声明当前已注册的 Capabilities。
     */
    private Map<String, Object> handleInitialize(Map<String, Object> params, McpSession session, SessionTracker tracker) {
        String requestedVersion = JsonCodec.asString(params.get("protocolVersion"));
        // 若客户端请求版本受支持则原样协商，否则回退到服务端最新默认版本
        String negotiatedVersion = isSupportedProtocolVersion(requestedVersion)
                ? requestedVersion
                : LATEST_PROTOCOL_VERSION;
        session.setProtocolVersion(negotiatedVersion);
        tracker.initializedHandshake = true;
        tracker.negotiatedVersion = negotiatedVersion;

        Map<String, Object> clientInfo = JsonCodec.asObject(params.get("clientInfo"));
        String clientName = (clientInfo != null) ? JsonCodec.asString(clientInfo.get("name")) : null;
        String clientVersion = (clientInfo != null) ? JsonCodec.asString(clientInfo.get("version")) : null;

        // 仅向客户端声明实际非空的能力模块
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
        log("[session=" + session.id() + "] Initialized handshake: client="
                + (clientName != null ? clientName : "<unknown>")
                + (clientVersion != null ? " v" + clientVersion : "")
                + ", requestedProtocol=" + requestedVersion
                + ", negotiatedProtocol=" + negotiatedVersion
                + ", capabilities=" + capabilities.keySet());
        return result;
    }

    /**
     * 处理 {@code tools/list}：返回所有已注册工具的描述与 JSON Schema。
     */
    private Map<String, Object> handleToolsList() {
        List<Map<String, Object>> toolDescriptors = tools.values().stream()
                .map(McpTool::toDescriptor)
                .toList();
        log("Listed " + toolDescriptors.size() + " tool(s): " + tools.keySet());
        return JsonCodec.objectOf("tools", toolDescriptors);
    }

    /**
     * 处理 {@code tools/call}：校验工具名与参数对象，捕获执行异常并转换为 {@code isError=true} 工具结果。
     */
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

        long startNanos = System.nanoTime();
        log("[tool:" + name + "] Executing with arguments=" + summarizeJson(arguments));
        try {
            McpTool.ToolResult result = tool.execute(arguments);
            long elapsedMs = elapsedMillis(startNanos);
            log("[tool:" + name + "] Completed in " + elapsedMs + " ms (isError=" + result.isError()
                    + ", content=" + summarizeJson(result.content()) + ")");
            return result.toMap();
        } catch (Exception e) {
            // 工具内部业务/运行时异常以 ToolResult.error 返回，使调用方模型能获知错误详情并重试
            long elapsedMs = elapsedMillis(startNanos);
            log("[tool:" + name + "] Execution failed in " + elapsedMs + " ms: " + e.getMessage());
            return McpTool.ToolResult.error("Tool execution error: " + e.getMessage()).toMap();
        }
    }

    /**
     * 处理 {@code resources/list}：返回已注册资源的元数据列表。
     */
    private Map<String, Object> handleResourcesList() {
        List<Map<String, Object>> resourceDescriptors = resources.values().stream()
                .map(McpResource::toDescriptor)
                .toList();
        log("Listed " + resourceDescriptors.size() + " resource(s): " + resources.keySet());
        return JsonCodec.objectOf("resources", resourceDescriptors);
    }

    /**
     * 处理 {@code resources/read}：按 URI 读取资源文本内容，未找到时返回 MCP 扩展错误码 {@code -32002}。
     */
    private Map<String, Object> handleResourcesRead(Map<String, Object> params) throws Exception {
        String uri = JsonCodec.asString(params.get("uri"));
        if (uri == null || uri.isBlank()) {
            throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Missing required parameter: 'uri'");
        }
        McpResource resource = resources.get(uri);
        if (resource == null) {
            throw new JsonRpcException(JsonRpc.RESOURCE_NOT_FOUND, "Resource not found: " + uri);
        }
        long startNanos = System.nanoTime();
        Map<String, Object> content = resource.toContent();
        long elapsedMs = elapsedMillis(startNanos);
        log("[resource:" + uri + "] Read completed in " + elapsedMs + " ms (mimeType=" + resource.mimeType() + ")");
        return JsonCodec.objectOf("contents", List.of(content));
    }

    /**
     * 处理 {@code prompts/list}：返回所有已注册提示词模板的元数据。
     */
    private Map<String, Object> handlePromptsList() {
        List<Map<String, Object>> promptDescriptors = prompts.values().stream()
                .map(McpPrompt::toDescriptor)
                .toList();
        log("Listed " + promptDescriptors.size() + " prompt(s): " + prompts.keySet());
        return JsonCodec.objectOf("prompts", promptDescriptors);
    }

    /**
     * 处理 {@code prompts/get}：校验必填模板参数并渲染返回提示词消息列表。
     */
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

        // 逐项检查必填参数是否存在且非空白
        for (McpPrompt.PromptArgument arg : prompt.arguments()) {
            if (arg.required()) {
                Object value = arguments.get(arg.name());
                if (value == null || String.valueOf(value).isBlank()) {
                    throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Missing required prompt argument: '" + arg.name() + "'");
                }
            }
        }

        long startNanos = System.nanoTime();
        List<Map<String, Object>> messages = prompt.render(arguments).stream()
                .map(McpPrompt.PromptMessage::toMap)
                .toList();
        long elapsedMs = elapsedMillis(startNanos);
        log("[prompt:" + name + "] Rendered " + messages.size() + " message(s) in " + elapsedMs
                + " ms (arguments=" + summarizeJson(arguments) + ")");
        return JsonCodec.objectOf(
                "description", prompt.description(),
                "messages", messages
        );
    }

    /**
     * 将带时间戳的调试与调用链路日志写入 {@code logStream}（通常绑定到 {@code stderr}，严禁写入 {@code stdout}）。
     */
    public void log(String message) {
        if (logStream != null) {
            String timestamp = LocalDateTime.now().format(LOG_TIME_FMT);
            logStream.println("[" + timestamp + "] [mcp-server] " + message);
            logStream.flush();
        }
    }

    /**
     * 计算自 {@code startNanos} 起的耗时（毫秒）。
     */
    public static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 将结构化对象序列化为单行摘要文本，供日志打印使用。
     */
    public static String summarizeJson(Object value) {
        if (value == null) {
            return "null";
        }
        return truncateForLog(JsonCodec.stringify(value));
    }

    /**
     * 转义换行符并按上限截断长文本，确保每条日志占据单行且不刷屏。
     */
    public static String truncateForLog(String text) {
        if (text == null) {
            return "null";
        }
        String singleLine = text.replace("\r", "\\r").replace("\n", "\\n");
        if (singleLine.length() <= MAX_LOG_PREVIEW_LEN) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_LOG_PREVIEW_LEN) + "...(" + singleLine.length() + " chars)";
    }
}
