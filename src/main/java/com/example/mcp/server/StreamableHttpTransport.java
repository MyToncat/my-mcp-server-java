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

    public static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final McpServer server;
    private final String host;
    private final int port;
    private final String endpointPath;

    private final ConcurrentMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Set<SseStream> activeStreams = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private HttpServer httpServer;
    private ExecutorService executor;

    public StreamableHttpTransport(McpServer server, String host, int port, String endpointPath) {
        this.server = server;
        this.host = (host == null || host.isBlank()) ? "0.0.0.0" : host.trim();
        this.port = port;
        this.endpointPath = normalizePath(endpointPath);
    }

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
            running.set(false);
            if (executor != null) {
                executor.shutdownNow();
            }
            throw e;
        }
    }

    public int getPort() {
        return (httpServer != null) ? httpServer.getAddress().getPort() : port;
    }

    public String getEndpointPath() {
        return endpointPath;
    }

    public McpSession getSession(String sessionId) {
        SessionEntry entry = (sessionId == null) ? null : sessions.get(sessionId);
        return (entry == null) ? null : entry.session;
    }

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

    private void handleExchange(HttpExchange exchange) {
        try {
            addCorsHeaders(exchange.getResponseHeaders());
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = normalizePath(exchange.getRequestURI().getPath());

            if ("OPTIONS".equals(method)) {
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
                sendJson(exchange, 200, healthJson);
                return;
            }

            if (!endpointPath.equals(path)) {
                sendJson(exchange, 404, JsonCodec.stringify(JsonCodec.objectOf(
                        "error", "Not Found",
                        "endpoint", endpointPath
                )));
                return;
            }

            switch (method) {
                case "POST" -> handlePost(exchange);
                case "GET" -> handleGet(exchange);
                case "DELETE" -> handleDelete(exchange);
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE, OPTIONS");
                    sendEmpty(exchange, 405);
                }
            }
        } catch (IOException e) {
            server.log("HTTP I/O error: " + e.getMessage());
        } catch (Exception e) {
            server.log("Unexpected HTTP error: " + e.getMessage());
            try {
                sendJson(exchange, 500, JsonRpc.stringifyError(null, JsonRpc.INTERNAL_ERROR, "Internal error: " + e.getMessage()));
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        Headers reqHeaders = exchange.getRequestHeaders();

        String protocolHeader = firstHeader(reqHeaders, HEADER_PROTOCOL_VERSION);
        if (protocolHeader != null && !protocolHeader.isBlank()
                && !McpServer.isSupportedProtocolVersion(protocolHeader.trim())) {
            sendJson(exchange, 400, JsonRpc.stringifyError(
                    null,
                    JsonRpc.INVALID_REQUEST,
                    "Unsupported MCP-Protocol-Version: " + protocolHeader.trim()
            ));
            return;
        }

        String requestedSessionId = firstHeader(reqHeaders, HEADER_SESSION_ID);
        SessionEntry existingEntry = null;
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            existingEntry = sessions.get(requestedSessionId.trim());
            if (existingEntry == null) {
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
            sendJson(exchange, 413, JsonRpc.stringifyError(null, JsonRpc.INVALID_REQUEST, e.getMessage()));
            return;
        }

        McpSession targetSession = (existingEntry != null)
                ? existingEntry.session
                : new McpSession("http-stateless");

        DispatchOutcome outcome = server.handleMessage(body, targetSession);

        if (outcome.parseError()) {
            sendJson(exchange, 400, outcome.responseJson());
            return;
        }

        // 若为 initialize 握手且客户端未携带既有会话，则分配新的 Mcp-Session-Id
        if (outcome.initializedHandshake() && existingEntry == null) {
            String newSessionId = UUID.randomUUID().toString();
            McpSession newSession = new McpSession(newSessionId);
            if (outcome.negotiatedProtocolVersion() != null) {
                newSession.setProtocolVersion(outcome.negotiatedProtocolVersion());
            }
            sessions.put(newSessionId, new SessionEntry(newSession));
            exchange.getResponseHeaders().set(HEADER_SESSION_ID, newSessionId);
        }

        // 仅 Notification / Response 时按规范返回 202 Accepted（无响应体）
        if (outcome.responseJson() == null) {
            sendEmpty(exchange, 202);
            return;
        }

        String acceptHeader = firstHeader(reqHeaders, "Accept");
        if (prefersEventStreamOnly(acceptHeader)) {
            String ssePayload = "event: message\ndata: " + outcome.responseJson() + "\n\n";
            byte[] bytes = ssePayload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            return;
        }

        sendJson(exchange, 200, outcome.responseJson());
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        Headers reqHeaders = exchange.getRequestHeaders();
        String acceptHeader = firstHeader(reqHeaders, "Accept");
        if (acceptHeader != null && !acceptHeader.isBlank()) {
            String lower = acceptHeader.toLowerCase(Locale.ROOT);
            if (!lower.contains("text/event-stream") && !lower.contains("*/*")) {
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
                sendJson(exchange, 404, JsonRpc.stringifyError(
                        null,
                        JsonRpc.INVALID_REQUEST,
                        "Session not found or expired: " + requestedSessionId.trim()
                ));
                return;
            }
        }

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
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        String sessionId = firstHeader(exchange.getRequestHeaders(), HEADER_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            sendJson(exchange, 400, JsonRpc.stringifyError(
                    null,
                    JsonRpc.INVALID_REQUEST,
                    "Missing required header: " + HEADER_SESSION_ID
            ));
            return;
        }
        SessionEntry removed = sessions.remove(sessionId.trim());
        if (removed == null) {
            sendJson(exchange, 404, JsonRpc.stringifyError(
                    null,
                    JsonRpc.INVALID_REQUEST,
                    "Session not found: " + sessionId.trim()
            ));
            return;
        }
        for (SseStream stream : removed.streams) {
            stream.close();
            activeStreams.remove(stream);
        }
        removed.streams.clear();
        sendEmpty(exchange, 204);
    }

    private String displayHost() {
        return "0.0.0.0".equals(host) ? "127.0.0.1" : host;
    }

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

    private static void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Accept, Mcp-Session-Id, MCP-Protocol-Version, Last-Event-ID");
        headers.set("Access-Control-Expose-Headers", "Mcp-Session-Id");
    }

    private static boolean prefersEventStreamOnly(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        String lower = acceptHeader.toLowerCase(Locale.ROOT);
        return lower.contains("text/event-stream")
                && !lower.contains("application/json")
                && !lower.contains("*/*");
    }

    private static String firstHeader(Headers headers, String name) {
        List<String> values = headers.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

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

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static final class SessionEntry {
        final McpSession session;
        final CopyOnWriteArrayList<SseStream> streams = new CopyOnWriteArrayList<>();

        SessionEntry(McpSession session) {
            this.session = session;
        }
    }

    private static final class SseStream {
        private final OutputStream out;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        SseStream(OutputStream out) {
            this.out = out;
        }

        synchronized void sendRaw(String text) throws IOException {
            if (closed.get()) {
                return;
            }
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        boolean isClosed() {
            return closed.get();
        }

        boolean awaitClose(long timeout, TimeUnit unit) throws InterruptedException {
            return closeLatch.await(timeout, unit);
        }

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

    private static final class PayloadTooLargeException extends RuntimeException {
        PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
