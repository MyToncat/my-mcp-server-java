package com.example.mcp.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖、纯手工编写的 RFC 8259 兼容 JSON 解析器与序列化器。
 * <p>
 * 映射规则：
 * - JSON Object -> LinkedHashMap&lt;String, Object&gt;（保留字段顺序）
 * - JSON Array  -> ArrayList&lt;Object&gt;
 * - JSON String -> String
 * - JSON Number -> Long（整型）或 Double（浮点型）
 * - JSON Boolean -> Boolean
 * - JSON null   -> null
 */
public final class JsonCodec {

    /** 工具类私有构造器，禁止实例化 */
    private JsonCodec() {
    }

    /**
     * 解析 JSON 文本。
     *
     * @throws JsonParseException 当输入不符合 JSON 语法时抛出
     */
    public static Object parse(String json) {
        if (json == null) {
            throw new JsonParseException("JSON input cannot be null");
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        // 严格拒绝根值后多余的非法尾随字符
        if (!parser.isAtEnd()) {
            throw new JsonParseException("Unexpected trailing character at index " + parser.pos);
        }
        return value;
    }

    /**
     * 将 Java 对象序列化为紧凑单行 JSON 字符串（适合 Stdio 行协议传输）。
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(128);
        writeJson(value, sb);
        return sb.toString();
    }

    /**
     * 构造有序 Map 的便捷方法（支持 null 值，避免 Map.of 不允许 null 的限制）。
     */
    public static Map<String, Object> objectOf(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Key-value arguments must be even");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 安全地将解析结果转换为 JSON Object 对应的 Map，非 Map 时返回 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    /**
     * 安全地将解析结果转换为 JSON Array 对应的 List，非 List 时返回 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return null;
    }

    /**
     * 安全地将解析结果转换为字符串，非 String 时返回 {@code null}。
     */
    public static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    /**
     * 递归将任意支持的 Java 值序列化并追加到 {@link StringBuilder}。
     */
    private static void writeJson(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(s, sb);
            case Boolean b -> sb.append(b ? "true" : "false");
            case Number n -> {
                // RFC 8259 禁止 NaN 与 Infinity 非有限浮点值
                if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
                    throw new IllegalArgumentException("JSON does not support NaN or Infinite numbers");
                }
                if (n instanceof Float f && (f.isNaN() || f.isInfinite())) {
                    throw new IllegalArgumentException("JSON does not support NaN or Infinite numbers");
                }
                sb.append(n);
            }
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(String.valueOf(entry.getKey()), sb);
                    sb.append(':');
                    writeJson(entry.getValue(), sb);
                }
                sb.append('}');
            }
            case Iterable<?> list -> {
                sb.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeJson(item, sb);
                }
                sb.append(']');
            }
            default -> writeString(String.valueOf(value), sb);
        }
    }

    /**
     * 按 RFC 8259 规则转义并输出 JSON 字符串字面量（包含控制字符的 \\uXXXX 转义）。
     */
    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // 小于 0x20 的 ASCII 控制字符必须转义为 4 位十六进制 Unicode 形式
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * JSON 语法解析失败时抛出的运行时异常。
     */
    public static final class JsonParseException extends RuntimeException {
        /**
         * 构造带错误位置或原因说明的解析异常。
         */
        public JsonParseException(String message) {
            super(message);
        }
    }

    /**
     * 内部递归下降 JSON 解析器状态机。
     */
    private static final class Parser {
        /** 原始 JSON 输入字符串 */
        private final String src;
        /** 当前扫描字符游标索引 */
        private int pos = 0;

        /**
         * 构造解析器实例并绑定输入字符串。
         */
        Parser(String src) {
            this.src = src;
        }

        /**
         * 判断游标是否已到达输入末尾。
         */
        boolean isAtEnd() {
            return pos >= src.length();
        }

        /**
         * 跳过 RFC 8259 定义的四种空白字符（空格、制表符、换行、回车）。
         */
        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        /**
         * 根据首字符分派解析任意 JSON 值。
         */
        Object parseValue() {
            skipWhitespace();
            if (isAtEnd()) {
                throw new JsonParseException("Unexpected end of JSON input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new JsonParseException("Unexpected character '" + c + "' at index " + pos);
                }
            };
        }

        /**
         * 解析 JSON 对象（{...}），使用 LinkedHashMap 保持键顺序。
         */
        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw new JsonParseException("Expected string key at index " + pos);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    break;
                } else {
                    throw new JsonParseException("Expected ',' or '}' at index " + pos);
                }
            }
            return map;
        }

        /**
         * 解析 JSON 数组（[...]），拒绝尾随逗号。
         */
        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    break;
                } else {
                    throw new JsonParseException("Expected ',' or ']' at index " + pos);
                }
            }
            return list;
        }

        /**
         * 解析 JSON 字符串，校验未转义控制字符并还原标准转义及 \\uXXXX 字符。
         */
        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                // JSON 字符串中不允许出现未转义的控制字符（0x00..0x1F）
                if (c < 0x20) {
                    throw new JsonParseException("Unescaped control character in string at index " + (pos - 1));
                }
                if (c == '\\') {
                    if (isAtEnd()) {
                        throw new JsonParseException("Unterminated escape sequence");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            // 解析 4 位十六进制 Unicode 码元
                            if (pos + 4 > src.length()) {
                                throw new JsonParseException("Incomplete unicode escape at index " + pos);
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new JsonParseException("Invalid unicode escape: \\u" + hex);
                            }
                        }
                        default -> throw new JsonParseException("Invalid escape character '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonParseException("Unterminated string literal");
        }

        /**
         * 解析符合 RFC 8259 规则的数字字面量（禁止前导 0），整数优先映射为 Long，浮点或溢出映射为 Double。
         */
        private Number parseNumber() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            if (isAtEnd()) {
                throw new JsonParseException("Invalid number at index " + start);
            }
            // RFC 8259 规定 0 后不能紧跟数字（禁止 01 这类前导零）
            if (peek('0')) {
                pos++;
                if (!isAtEnd() && Character.isDigit(src.charAt(pos))) {
                    throw new JsonParseException("Leading zero not allowed in JSON number at index " + start);
                }
            } else if (src.charAt(pos) >= '1' && src.charAt(pos) <= '9') {
                consumeDigits();
            } else {
                throw new JsonParseException("Invalid number at index " + start);
            }

            boolean isFloat = false;
            if (peek('.')) {
                isFloat = true;
                pos++;
                if (isAtEnd() || !Character.isDigit(src.charAt(pos))) {
                    throw new JsonParseException("Expected digit after decimal point at index " + pos);
                }
                consumeDigits();
            }
            if (!isAtEnd() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (!isAtEnd() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                if (isAtEnd() || !Character.isDigit(src.charAt(pos))) {
                    throw new JsonParseException("Expected exponent digit at index " + pos);
                }
                consumeDigits();
            }

            String numStr = src.substring(start, pos);
            try {
                if (!isFloat) {
                    return Long.parseLong(numStr);
                }
                return Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                // 超出 Long 范围的大整数降级为 Double 表示
                if (!isFloat) {
                    return Double.parseDouble(numStr);
                }
                throw new JsonParseException("Invalid number format: " + numStr);
            }
        }

        /**
         * 连续消费当前位置起的全部十进制数字字符。
         */
        private void consumeDigits() {
            while (!isAtEnd() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
        }

        /**
         * 匹配字面量关键字（true / false / null）并推进游标。
         */
        private Object parseLiteral(String literal, Object value) {
            if (!src.startsWith(literal, pos)) {
                throw new JsonParseException("Unexpected token at index " + pos);
            }
            pos += literal.length();
            return value;
        }

        /**
         * 预判当前游标字符是否为期望字符（不移动游标）。
         */
        private boolean peek(char expected) {
            return pos < src.length() && src.charAt(pos) == expected;
        }

        /**
         * 断言并消费期望字符，不匹配时抛出解析异常。
         */
        private void expect(char expected) {
            if (isAtEnd() || src.charAt(pos) != expected) {
                throw new JsonParseException("Expected '" + expected + "' at index " + pos);
            }
            pos++;
        }
    }
}
