package com.example.mcp.protocol;

import com.example.mcp.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 协议常量、异常定义与响应构造工具。
 */
public final class JsonRpc {

    /** JSON-RPC 协议版本号常量 */
    public static final String VERSION = "2.0";

    /** 标准错误码：JSON 语法解析失败 */
    public static final int PARSE_ERROR = -32700;
    /** 标准错误码：无效的 JSON-RPC 请求结构 */
    public static final int INVALID_REQUEST = -32600;
    /** 标准错误码：请求方法不存在 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 标准错误码：方法参数无效 */
    public static final int INVALID_PARAMS = -32602;
    /** 标准错误码：服务端内部错误 */
    public static final int INTERNAL_ERROR = -32603;

    /** MCP 扩展错误码：请求的资源 URI 未找到 */
    public static final int RESOURCE_NOT_FOUND = -32002;

    /** 工具类私有构造器，禁止实例化 */
    private JsonRpc() {
    }

    /**
     * 构造 JSON-RPC 2.0 成功响应对象（使用 LinkedHashMap 保持字段输出顺序）。
     */
    public static Map<String, Object> successResponse(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", VERSION);
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    /**
     * 构造 JSON-RPC 2.0 错误响应对象；可选的 {@code data} 为 null 时省略该字段。
     */
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

    /**
     * 校验 JSON-RPC 请求 {@code id} 是否合法（规范仅允许 null、字符串或数值）。
     */
    public static boolean isValidId(Object id) {
        return id == null || id instanceof String || id instanceof Number;
    }

    /**
     * 构造并直接序列化成功响应为单行 JSON 字符串。
     */
    public static String stringifySuccess(Object id, Object result) {
        return JsonCodec.stringify(successResponse(id, result));
    }

    /**
     * 构造并直接序列化错误响应为单行 JSON 字符串。
     */
    public static String stringifyError(Object id, int code, String message) {
        return JsonCodec.stringify(errorResponse(id, code, message, null));
    }

    /**
     * 表示需向客户端返回特定 JSON-RPC 错误码的协议异常。
     */
    public static final class JsonRpcException extends RuntimeException {
        /** JSON-RPC 标准或扩展错误码 */
        private final int code;
        /** 可选的附加错误详情数据 */
        private final Object data;

        /**
         * 构造不带附加 {@code data} 的 JSON-RPC 协议异常。
         */
        public JsonRpcException(int code, String message) {
            this(code, message, null);
        }

        /**
         * 构造包含错误码、错误描述和附加详情的 JSON-RPC 协议异常。
         */
        public JsonRpcException(int code, String message, Object data) {
            super(message);
            this.code = code;
            this.data = data;
        }

        /**
         * 获取 JSON-RPC 错误码。
         */
        public int getCode() {
            return code;
        }

        /**
         * 获取附加错误详情数据（可能为 {@code null}）。
         */
        public Object getData() {
            return data;
        }
    }
}
