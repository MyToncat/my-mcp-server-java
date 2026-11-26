package com.example.mcp.protocol;

import com.example.mcp.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 协议常量、异常定义与响应构造工具。
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    // 标准 JSON-RPC 2.0 错误码
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    // MCP 扩展错误码：资源未找到
    public static final int RESOURCE_NOT_FOUND = -32002;

    private JsonRpc() {
    }

    public static Map<String, Object> successResponse(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", VERSION);
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    public static Map<String, Object> errorResponse(Object id, int code, String message, Object data) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.put("data", data);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", VERSION);
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    public static boolean isValidId(Object id) {
        return id == null || id instanceof String || id instanceof Number;
    }

    public static String stringifySuccess(Object id, Object result) {
        return JsonCodec.stringify(successResponse(id, result));
    }

    public static String stringifyError(Object id, int code, String message) {
        return JsonCodec.stringify(errorResponse(id, code, message, null));
    }

    public static final class JsonRpcException extends RuntimeException {
        private final int code;
        private final Object data;

        public JsonRpcException(int code, String message) {
            this(code, message, null);
        }

        public JsonRpcException(int code, String message, Object data) {
            super(message);
            this.code = code;
            this.data = data;
        }

        public int getCode() {
            return code;
        }

        public Object getData() {
            return data;
        }
    }
}
