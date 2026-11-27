package com.example.mcp.server;

import com.example.mcp.json.JsonCodec;
import com.example.mcp.protocol.JsonRpc;
import com.example.mcp.server.McpServer.DispatchOutcome;
import com.example.mcp.server.McpServer.McpSession;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 零第三方依赖的 MCP Streamable HTTP 传输层实现（基于 JDK 21 内置 {@link HttpServer} 与虚拟线程）。
 * <p>
 * 支持单端点（默认 {@code /mcp}）：
 * <ul>
 *   <li>{@code POST /mcp}：接收 JSON-RPC 2.0 请求/通知/批处理，返回 JSON 或 SSE 响应；仅通知返回 202 Accepted</li>
 *   <li>{@code GET /mcp}：建立 SSE 长连接通道（用于服务端推送与保活）</li>
 *   <li>{@code DELETE /mcp}：销毁指定 {@code Mcp-Session-Id} 会话</li>
 *   <li>{@code GET /health}：轻量健康检查端点</li>
 * </ul>
 */
public final class StreamableHttpTransport implements AutoCloseable {

    /** MCP 会话标识 HTTP 请求/响应头名称 */
    public static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    /** MCP 协议版本 HTTP 请求头名称 */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    /** 单次 HTTP 请求体最大允许 4 MiB，防止超大负载耗尽内存 */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    /** 核心 MCP 请求调度器 */
    private final McpServer server;
    /** 绑定的监听主机地址 */
    private final String host;
    /** 配置的监听端口（0 表示动态分配） */
    private final int port;
    /** 规范化后的 Streamable HTTP 端点路径 */
    private final String endpointPath;

    /** 活跃 HTTP 会话表（Key 为 Mcp-Session-Id） */
    private final ConcurrentMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    /** 所有已建立的 SSE 长连接集合（含无会话与有会话流），用于停机时统一唤醒关闭 */
    private final Set<SseStream> activeStreams = ConcurrentHashMap.newKeySet();
    /** 服务运行状态标志（CAS 保证 start/close 幂等） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** JDK 内置轻量 HTTP 服务器实例 */
    private HttpServer httpServer;
    /** 处理 HTTP 与 SSE 请求的 JDK 21 虚拟线程执行器 */
    private ExecutorService executor;

    /**
     * 构造 Streamable HTTP 传输实例（尚未绑定端口，需调用 {@link #start()} 启动）。
     *
     * @param server       核心 MCP 调度器
     * @param host         监听地址（为空时默认 {@code 0.0.0.0}）
     * @param port         监听端口（传 {@code 0} 可由系统自动分配可用端口）
     * @param endpointPath MCP 单端点路径（默认 {@code /mcp}）
     */
    public StreamableHttpTransport(McpServer server, String host, int port, String endpointPath) {
        this.server = server;
        this.host = (host == null || host.isBlank()) ? "0.0.0.0" : host.trim();
        this.port = port;
        this.endpointPath = normalizePath(endpointPath);
    }

    /**
     * 启动 HTTP 服务并开始监听请求（幂等操作）。
     * 使用 JDK 21 虚拟线程执行器，确保长连接 SSE（GET /mcp）不会阻塞普通 POST 请求。
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            InetSocketAddress address = new InetSocketAddress(host, port);
            httpServer = HttpServer.create(address, 64);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            httpServer.setExecutor(executor);
            httpServer.createContext("/", this::handleExchange);
            httpServer.start();
            server.log("MCP Streamable HTTP listening on http://" + displayHost() + ":" + getPort() + endpointPath);
        } catch (IOException | RuntimeException e) {
            // 启动失败时回滚运行标志并释放线程池，避免资源泄漏
            running.set(false);
            if (executor != null) {
                executor.shutdownNow();
            }
            throw e;
        }
    }

    /**
     * 返回实际绑定的监听端口（在传入端口 0 时可获取系统分配的真实端口）。
     */
    public int getPort() {
        return (httpServer != null) ? httpServer.getAddress().getPort() : port;
    }

    /**
     * 返回规范化后的 MCP HTTP 端点路径。
     */
    public String getEndpointPath() {
        return endpointPath;
    }

    /**
     * 按 {@code Mcp-Session-Id} 查询对应的会话上下文，不存在或已销毁则返回 {@code null}。
     */
    public McpSession getSession(String sessionId) {
        SessionEntry entry = (sessionId == null) ? null : sessions.get(sessionId);
        return (entry == null) ? null : entry.session;
    }

    /**
     * 优雅停止 HTTP 服务：先关闭全部活跃 SSE 流唤醒等待线程，再停止 HttpServer 与虚拟线程执行器。
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (SseStream stream : activeStreams) {
            stream.close();
        }
        activeStreams.clear();
        sessions.clear();
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        server.log("MCP Streamable HTTP stopped.");
    }

    /**
     * HTTP 请求总入口：统一注入 CORS 响应头、处理预检与健康检查，并将 MCP 请求路由至 POST/GET/DELETE 处理器。
     */
    private void handleExchange(HttpExchange exchange) {
        long startNanos = System.nanoTime();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = normalizePath(exchange.getRequestURI().getPath());
        String remoteAddr = String.valueOf(exchange.getRemoteAddress());
        try {
            addCorsHeaders(exchange.getResponseHeaders());

            if ("OPTIONS".equals(method)) {
                server.log("[http] <-- OPTIONS " + path + " from=" + remoteAddr + " -> 204");
                sendEmpty(exchange, 204);
                return;
            }

            if ("/health".equals(path) && "GET".equals(method)) {
                String healthJson = JsonCodec.stringify(JsonCodec.objectOf(
                        "status", "ok",
                        "serverName", server.getServerName(),
                        "serverVersion", server.getServerVersion(),
                        "transport", "streamable-http",
                        "endpoint", endpointPath,
                        "activeSessions", sessions.size()
                ));
                server.log("[http] <-- GET /health from=" + remoteAddr + " -> 200 (activeSessions="
                        + sessions.size() + ", durationMs=" + McpServer.elapsedMillis(startNanos) + ")");
                sendJson(exchange, 200, healthJson);
                return;
            }

            if (!endpointPath.equals(path)) {
                server.log("[http] <-- " + method + " " + path + " from=" + remoteAddr + " -> 404 (unknown endpoint)");
                sendJson(exchange, 404, JsonCodec.stringify(JsonCodec.objectOf(
                        "error", "Not Found",
                        "endpoint", endpointPath
                )));
                return;
            }

            switch (method) {
                case "POST" -> handlePost(exchange, remoteAddr, startNanos);
                case "GET" -> handleGet(exchange, remoteAddr, startNanos);
                case "DELETE" -> handleDelete(exchange, remoteAddr, startNanos);
                default -> {
                    server.log("[http] <-- " + method + " " + path + " from=" + remoteAddr + " -> 405 Method Not Allowed");
                    exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE, OPTIONS");
                    sendEmpty(exchange, 405);
                }
            }
        } catch (IOException e) {
            server.log("[http] I/O error handling " + method + " " + path + " from=" + remoteAddr + ": " + e.getMessage());
        } catch (Exception e) {
            server.log("[http] Unexpected error handling " + method + " " + path + " from=" + remoteAddr + ": " + e.getMessage());
            try {
                sendJson(exchange, 500, JsonRpc.stringifyError(null, JsonRpc.INTERNAL_ERROR, "Internal error: " + e.getMessage()));
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * 处理 {@code POST /mcp}：校验协议版本与会话、解析 JSON-RPC 消息并按客户端 Accept 头协商返回 JSON 或 SSE。
     */
    private void handlePost(HttpExchange exchange, String remoteAddr, long startNanos) throws IOException {
        Headers reqHeaders = exchange.getRequestHeaders();

        String protocolHeader = firstHeader(reqHeaders, HEADER_PROTOCOL_VERSION);
        if (protocolHeader != null && !protocolHeader.isBlank()
                && !McpServer.isSupportedProtocolVersion(protocolHeader.trim())) {
            server.log("[http] <-- POST " + endpointPath + " from=" + remoteAddr
                    + " rejected: unsupported MCP-Protocol-Version=" + protocolHeader.trim() + " -> 400");
            sendJson(exchange, 400, JsonRpc.stringifyError(
                    null,
                    JsonRpc.INVALID_REQUEST,
                    "Unsupported MCP-Protocol-Version: " + protocolHeader.trim()
            ));
            return;
        }

        String requestedSessionId = firstHeader(reqHeaders, HEADER_SESSION_ID);
        String acceptHeader = firstHeader(reqHeaders, "Accept");
        SessionEntry existingEntry = null;
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            existingEntry = sessions.get(requestedSessionId.trim());
            if (existingEntry == null) {
                server.log("[http] <-- POST " + endpointPath + " from=" + remoteAddr
                        + " session=" + requestedSessionId.trim() + " -> 404 (session not found)");
                sendJson(exchange, 404, JsonRpc.stringifyError(
                        null,
                        JsonRpc.INVALID_REQUEST,
                        "Session not found or expired: " + requestedSessionId.trim()
                ));
                return;
            }
        }

        String body;
        try {
            body = readBodyUtf8(exchange.getRequestBody());
        } catch (PayloadTooLargeException e) {
            server.log("[http] <-- POST " + endpointPath + " from=" + remoteAddr + " -> 413 (" + e.getMessage() + ")");
            sendJson(exchange, 413, JsonRpc.stringifyError(null, JsonRpc.INVALID_REQUEST, e.getMessage()));
            return;
        }

        // 优先使用已有会话上下文；未携带 Mcp-Session-Id 时使用临时无状态会话（兼容 curl 快捷直调）
        McpSession targetSession = (existingEntry != null)
                ? existingEntry.session
                : new McpSession("http-stateless");

        server.log("[http] <-- POST " + endpointPath + " from=" + remoteAddr
                + " session=" + targetSession.id()
                + " bytes=" + body.getBytes(StandardCharsets.UTF_8).length
                + " accept=" + (acceptHeader != null ? acceptHeader : "<none>"));

        DispatchOutcome outcome = server.handleMessage(body, targetSession);

        if (outcome.parseError()) {
            server.log("[http] --> POST " + endpointPath + " session=" + targetSession.id()
                    + " status=400 (JSON parse error, durationMs=" + McpServer.elapsedMillis(startNanos) + ")");
            sendJson(exchange, 400, outcome.responseJson());
            return;
        }

        String assignedSessionId = targetSession.id();
        // 若为 initialize 握手且客户端未携带既有会话，则分配新的 Mcp-Session-Id
        if (outcome.initializedHandshake() && existingEntry == null) {
            String newSessionId = UUID.randomUUID().toString();
            McpSession newSession = new McpSession(newSessionId);
            if (outcome.negotiatedProtocolVersion() != null) {
                newSession.setProtocolVersion(outcome.negotiatedProtocolVersion());
            }
            sessions.put(newSessionId, new SessionEntry(newSession));
            exchange.getResponseHeaders().set(HEADER_SESSION_ID, newSessionId);
            assignedSessionId = newSessionId;
            server.log("[http] Created session " + newSessionId
                    + " (protocol=" + newSession.protocolVersion() + ", activeSessions=" + sessions.size() + ")");
        }

        // 仅 Notification / Response 时按规范返回 202 Accepted（无响应体）
        if (outcome.responseJson() == null) {
            server.log("[http] --> POST " + endpointPath + " session=" + assignedSessionId
                    + " status=202 (notification-only, durationMs=" + McpServer.elapsedMillis(startNanos) + ")");
            sendEmpty(exchange, 202);
            return;
        }

        if (prefersEventStreamOnly(acceptHeader)) {
            String ssePayload = "event: message\ndata: " + outcome.responseJson() + "\n\n";
            byte[] bytes = ssePayload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            server.log("[http] --> POST " + endpointPath + " session=" + assignedSessionId
                    + " status=200 format=sse bytes=" + bytes.length
                    + " durationMs=" + McpServer.elapsedMillis(startNanos));
            return;
        }

        int respBytes = outcome.responseJson().getBytes(StandardCharsets.UTF_8).length;
        server.log("[http] --> POST " + endpointPath + " session=" + assignedSessionId
                + " status=200 format=json bytes=" + respBytes
                + " durationMs=" + McpServer.elapsedMillis(startNanos));
        sendJson(exchange, 200, outcome.responseJson());
    }

    /**
     * 处理 {@code GET /mcp}：建立 SSE 长连接流，发送初始建连注释并每 15 秒发送保活心跳。
     */
    private void handleGet(HttpExchange exchange, String remoteAddr, long startNanos) throws IOException {
        Headers reqHeaders = exchange.getRequestHeaders();
        String acceptHeader = firstHeader(reqHeaders, "Accept");
        if (acceptHeader != null && !acceptHeader.isBlank()) {
            String lower = acceptHeader.toLowerCase(Locale.ROOT);
            if (!lower.contains("text/event-stream") && !lower.contains("*/*")) {
                server.log("[http] <-- GET " + endpointPath + " from=" + remoteAddr
                        + " rejected: Accept=" + acceptHeader + " -> 406");
                sendJson(exchange, 406, JsonRpc.stringifyError(
                        null,
                        JsonRpc.INVALID_REQUEST,
                        "Accept header must allow text/event-stream for GET SSE stream"
                ));
                return;
            }
        }

        String requestedSessionId = firstHeader(reqHeaders, HEADER_SESSION_ID);
        SessionEntry sessionEntry = null;
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            sessionEntry = sessions.get(requestedSessionId.trim());
            if (sessionEntry == null) {
                server.log("[http] <-- GET " + endpointPath + " from=" + remoteAddr
                        + " session=" + requestedSessionId.trim() + " -> 404 (session not found)");
                sendJson(exchange, 404, JsonRpc.stringifyError(
                        null,
                        JsonRpc.INVALID_REQUEST,
                        "Session not found or expired: " + requestedSessionId.trim()
                ));
                return;
            }
        }

        // sendResponseHeaders(200, 0) 启用 HTTP 分块传输编码（Chunked），以便流式持续输出 SSE 帧
        Headers respHeaders = exchange.getResponseHeaders();
        respHeaders.set("Content-Type", "text/event-stream; charset=utf-8");
        respHeaders.set("Cache-Control", "no-cache");
        respHeaders.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        SseStream stream = new SseStream(exchange.getResponseBody());
        activeStreams.add(stream);
        if (sessionEntry != null) {
            sessionEntry.streams.add(stream);
        }

        String sessionTag = (sessionEntry != null) ? sessionEntry.session.id() : "<stateless>";
        server.log("[http] SSE stream opened from=" + remoteAddr + " session=" + sessionTag
                + " (activeStreams=" + activeStreams.size() + ")");

        try {
            stream.sendRaw(": connected\n\n");
            while (running.get() && !stream.isClosed()) {
                if (stream.awaitClose(15, TimeUnit.SECONDS)) {
                    break;
                }
                if (running.get() && !stream.isClosed()) {
                    stream.sendRaw(": keepalive\n\n");
                }
            }
        } catch (IOException ignored) {
            // 客户端断开连接属于正常 SSE 结束场景
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stream.close();
            activeStreams.remove(stream);
            if (sessionEntry != null) {
                sessionEntry.streams.remove(stream);
            }
            server.log("[http] SSE stream closed from=" + remoteAddr + " session=" + sessionTag
                    + " durationMs=" + McpServer.elapsedMillis(startNanos)
                    + " (activeStreams=" + activeStreams.size() + ")");
        }
    }

    /**
     * 处理 {@code DELETE /mcp}：按 {@code Mcp-Session-Id} 注销会话并关闭关联的所有 SSE 流。
     */
    private void handleDelete(HttpExchange exchange, String remoteAddr, long startNanos) throws IOException {
        String sessionId = firstHeader(exchange.getRequestHeaders(), HEADER_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            server.log("[http] <-- DELETE " + endpointPath + " from=" + remoteAddr + " -> 400 (missing Mcp-Session-Id)");
            sendJson(exchange, 400, JsonRpc.stringifyError(
                    null,
                    JsonRpc.INVALID_REQUEST,
                    "Missing required header: " + HEADER_SESSION_ID
            ));
            return;
        }
        String trimmedId = sessionId.trim();
        SessionEntry removed = sessions.remove(trimmedId);
        if (removed == null) {
            server.log("[http] <-- DELETE " + endpointPath + " from=" + remoteAddr
                    + " session=" + trimmedId + " -> 404 (session not found)");
            sendJson(exchange, 404, JsonRpc.stringifyError(
                    null,
                    JsonRpc.INVALID_REQUEST,
                    "Session not found: " + trimmedId
            ));
            return;
        }
        int closedStreams = 0;
        for (SseStream stream : removed.streams) {
            stream.close();
            activeStreams.remove(stream);
            closedStreams++;
        }
        removed.streams.clear();
        server.log("[http] <-- DELETE " + endpointPath + " from=" + remoteAddr
                + " session=" + trimmedId + " -> 204 (closedStreams=" + closedStreams
                + ", activeSessions=" + sessions.size()
                + ", durationMs=" + McpServer.elapsedMillis(startNanos) + ")");
        sendEmpty(exchange, 204);
    }

    /**
     * 将通配绑定地址 {@code 0.0.0.0} 转换为便于日志展示的本地回环地址 {@code 127.0.0.1}。
     */
    private String displayHost() {
        return "0.0.0.0".equals(host) ? "127.0.0.1" : host;
    }

    /**
     * 规范化端点路径：保证以 {@code /} 开头且去除多余尾部斜杠。
     */
    private static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/mcp";
        }
        String p = raw.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 添加跨域与 MCP 自定义头暴露配置，方便浏览器端工具（如 MCP Inspector）直接调试。
     */
    private static void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Accept, Mcp-Session-Id, MCP-Protocol-Version, Last-Event-ID");
        headers.set("Access-Control-Expose-Headers", "Mcp-Session-Id");
    }

    /**
     * 判断客户端是否仅接受 SSE（未包含 application/json 或 * / *），决定 POST 响应体封装格式。
     */
    private static boolean prefersEventStreamOnly(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        String lower = acceptHeader.toLowerCase(Locale.ROOT);
        return lower.contains("text/event-stream")
                && !lower.contains("application/json")
                && !lower.contains("*/*");
    }

    /**
     * 读取指定请求头的第一个值，不存在时返回 {@code null}。
     */
    private static String firstHeader(Headers headers, String name) {
        List<String> values = headers.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /**
     * 按 UTF-8 读取 HTTP 请求体，并在超过 {@link #MAX_BODY_BYTES} 时立即拒绝。
     */
    private static String readBodyUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException("Request body exceeds 4 MiB limit");
            }
            out.write(buf, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * 发送 UTF-8 JSON 响应并显式设置精确字节长度。
     */
    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 发送无响应体的 HTTP 状态码（contentLength=-1 表示没有响应体，如 202/204/405）。
     */
    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    /**
     * 单个 HTTP 会话条目，绑定会话状态及该会话下打开的所有 SSE 推送流。
     */
    private static final class SessionEntry {
        /** 会话核心状态 */
        final McpSession session;
        /** 该会话当前挂载的 SSE 推送流列表（支持并发遍历与移除） */
        final CopyOnWriteArrayList<SseStream> streams = new CopyOnWriteArrayList<>();

        /**
         * 构造绑定指定会话的条目。
         */
        SessionEntry(McpSession session) {
            this.session = session;
        }
    }

    /**
     * 线程安全的 SSE 响应流封装，支持并发写入与通过 {@link CountDownLatch} 即时唤醒关闭。
     */
    private static final class SseStream {
        /** 底层 HTTP 响应输出流 */
        private final OutputStream out;
        /** 流关闭状态标记（保证 close 幂等） */
        private final AtomicBoolean closed = new AtomicBoolean(false);
        /** 用于阻塞保活循环并在关闭时立即唤醒的同步门闩 */
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        /**
         * 构造 SSE 输出流包装器。
         */
        SseStream(OutputStream out) {
            this.out = out;
        }

        /**
         * 线程安全地向客户端写入并立即刷新 SSE 文本帧。
         */
        synchronized void sendRaw(String text) throws IOException {
            if (closed.get()) {
                return;
            }
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /**
         * 返回当前 SSE 流是否已关闭。
         */
        boolean isClosed() {
            return closed.get();
        }

        /**
         * 阻塞等待关闭信号或保活超时。
         */
        boolean awaitClose(long timeout, TimeUnit unit) throws InterruptedException {
            return closeLatch.await(timeout, unit);
        }

        /**
         * 幂等关闭 SSE 流并释放底层响应输出流。
         */
        void close() {
            if (closed.compareAndSet(false, true)) {
                closeLatch.countDown();
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 请求体超出最大字节上限时抛出的内部异常（映射为 HTTP 413）。
     */
    private static final class PayloadTooLargeException extends RuntimeException {
        /**
         * 构造包含大小限制说明的异常。
         */
        PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
