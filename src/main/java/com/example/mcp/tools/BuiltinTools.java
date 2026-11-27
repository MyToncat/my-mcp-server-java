package com.example.mcp.tools;

import com.example.mcp.json.JsonCodec;
import com.example.mcp.server.McpTool;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 内置 MCP 示例工具集：时间查询、安全数学计算、文本统计。
 */
public final class BuiltinTools {

    /** 工具集合类私有构造器，禁止实例化 */
    private BuiltinTools() {
    }

    /**
     * 返回全部内置 MCP 工具实例列表，供服务端启动时统一注册。
     */
    public static List<McpTool> all() {
        return List.of(
                new GetCurrentTimeTool(),
                new CalculateTool(),
                new TextStatsTool()
        );
    }

    /**
     * 获取当前时间（支持指定 IANA 时区）。
     */
    public static final class GetCurrentTimeTool implements McpTool {

        /** 人类可读的本地化日期时间格式 */
        private static final DateTimeFormatter READABLE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

        @Override
        public String name() {
            return "get_current_time";
        }

        @Override
        public String description() {
            return "获取指定时区的当前日期与时间（默认使用系统时区）。";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return JsonCodec.objectOf(
                    "type", "object",
                    "properties", JsonCodec.objectOf(
                            "timezone", JsonCodec.objectOf(
                                    "type", "string",
                                    "description", "IANA 时区名称，例如 Asia/Shanghai、UTC、America/New_York"
                            )
                    ),
                    "required", List.of()
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            String tz = JsonCodec.asString(arguments.get("timezone"));
            ZoneId zoneId;
            try {
                // 未传入 timezone 时回退到操作系统默认时区
                zoneId = (tz == null || tz.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(tz.trim());
            } catch (Exception e) {
                return ToolResult.error("无效的时区名称: " + tz);
            }
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            Map<String, Object> payload = JsonCodec.objectOf(
                    "timezone", zoneId.getId(),
                    "iso8601", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "readable", now.format(READABLE_FMT),
                    "epochSeconds", now.toEpochSecond()
            );
            return ToolResult.text(JsonCodec.stringify(payload));
        }
    }

    /**
     * 纯手工编写的安全算术表达式求值器（递归下降解析，无脚本注入风险）。
     */
    public static final class CalculateTool implements McpTool {

        @Override
        public String name() {
            return "calculate";
        }

        @Override
        public String description() {
            return "安全计算数学表达式，支持 + - * / % ^、括号、常量(pi, e)及函数(sqrt, abs, pow, max, min)。";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return JsonCodec.objectOf(
                    "type", "object",
                    "properties", JsonCodec.objectOf(
                            "expression", JsonCodec.objectOf(
                                    "type", "string",
                                    "description", "待计算的数学表达式，例如 (12 + 8) * 3.5 / 2 或 sqrt(16) + pow(2, 3)"
                            )
                    ),
                    "required", List.of("expression")
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            String expr = JsonCodec.asString(arguments.get("expression"));
            if (expr == null || expr.isBlank()) {
                return ToolResult.error("参数 'expression' 不能为空");
            }
            double result = ExpressionParser.evaluate(expr);
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                return ToolResult.error("计算结果超出有效实数范围");
            }
            String formatted = formatNumber(result);
            return ToolResult.text(expr.trim() + " = " + formatted);
        }

        /**
         * 格式化数值结果：整数值去除小数点，小数去除末尾多余零并避免科学计数法。
         */
        private static String formatNumber(double value) {
            if (value == Math.rint(value) && Math.abs(value) <= Long.MAX_VALUE) {
                return Long.toString((long) value);
            }
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
    }

    /**
     * 统计文本的字符数、非空白字符数、词数、行数及 UTF-8 字节数。
     */
    public static final class TextStatsTool implements McpTool {

        @Override
        public String name() {
            return "text_stats";
        }

        @Override
        public String description() {
            return "统计给定文本的字符数、非空白字符数、单词数、行数与 UTF-8 字节长度。";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return JsonCodec.objectOf(
                    "type", "object",
                    "properties", JsonCodec.objectOf(
                            "text", JsonCodec.objectOf(
                                    "type", "string",
                                    "description", "需要统计分析的文本内容"
                            )
                    ),
                    "required", List.of("text")
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            Object rawText = arguments.get("text");
            if (!(rawText instanceof String text)) {
                return ToolResult.error("参数 'text' 必须为字符串");
            }
            int charCount = text.length();
            long nonWhitespaceCount = text.chars().filter(ch -> !Character.isWhitespace(ch)).count();
            int wordCount = text.isBlank() ? 0 : text.trim().split("\\s+").length;
            // limit=-1 保留末尾空行，与常见编辑器行统计口径一致
            int lineCount = text.isEmpty() ? 0 : text.split("\r?\n", -1).length;
            int utf8Bytes = text.getBytes(StandardCharsets.UTF_8).length;

            Map<String, Object> stats = JsonCodec.objectOf(
                    "characters", charCount,
                    "nonWhitespaceCharacters", nonWhitespaceCount,
                    "words", wordCount,
                    "lines", lineCount,
                    "utf8Bytes", utf8Bytes
            );
            return ToolResult.text(JsonCodec.stringify(stats));
        }
    }

    /**
     * 递归下降数学表达式解析器（按运算符优先级逐层下降：加减 -> 乘除模 -> 幂 -> 一元正负 -> 括号/字面量/函数）。
     */
    static final class ExpressionParser {
        /** 待求值表达式原始文本 */
        private final String input;
        /** 当前字符扫描游标 */
        private int pos = 0;

        /**
         * 构造内部表达式解析器实例。
         */
        private ExpressionParser(String input) {
            this.input = input;
        }

        /**
         * 解析并计算给定数学表达式，若存在未消费的尾随字符则抛出异常。
         */
        static double evaluate(String expression) {
            ExpressionParser parser = new ExpressionParser(expression);
            double value = parser.parseExpression();
            parser.skipWhitespace();
            if (parser.pos < parser.input.length()) {
                throw new IllegalArgumentException("无法识别的表达式内容，位置: " + parser.pos);
            }
            return value;
        }

        /**
         * 跳过当前游标处的全部空白字符。
         */
        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        /**
         * 跳过空白后尝试匹配并消费指定字符。
         */
        private boolean eat(char ch) {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ch) {
                pos++;
                return true;
            }
            return false;
        }

        /**
         * 解析加减法层（最低优先级）。
         */
        private double parseExpression() {
            double x = parseTerm();
            while (true) {
                if (eat('+')) {
                    x += parseTerm();
                } else if (eat('-')) {
                    x -= parseTerm();
                } else {
                    return x;
                }
            }
        }

        /**
         * 解析乘、除、取模运算层（校验除数与模数非零）。
         */
        private double parseTerm() {
            double x = parsePower();
            while (true) {
                if (eat('*')) {
                    x *= parsePower();
                } else if (eat('/')) {
                    double divisor = parsePower();
                    if (divisor == 0.0) {
                        throw new IllegalArgumentException("除数不能为 0");
                    }
                    x /= divisor;
                } else if (eat('%')) {
                    double divisor = parsePower();
                    if (divisor == 0.0) {
                        throw new IllegalArgumentException("取模除数不能为 0");
                    }
                    x %= divisor;
                } else {
                    return x;
                }
            }
        }

        /**
         * 解析右结合幂运算（^），指数部分递归调用 parsePower 实现右结合（如 2^3^2 = 2^(3^2)）。
         */
        private double parsePower() {
            double base = parseUnary();
            if (eat('^')) {
                return Math.pow(base, parsePower());
            }
            return base;
        }

        /**
         * 解析一元正负号（+x / -x）。
         */
        private double parseUnary() {
            if (eat('+')) {
                return parseUnary();
            }
            if (eat('-')) {
                return -parseUnary();
            }
            return parsePrimary();
        }

        /**
         * 解析基础项：括号子表达式、数字字面量、内置函数调用或数学常量（pi / e）。
         */
        private double parsePrimary() {
            skipWhitespace();
            if (eat('(')) {
                double x = parseExpression();
                if (!eat(')')) {
                    throw new IllegalArgumentException("缺少右括号 ')'");
                }
                return x;
            }
            int start = pos;
            if (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                    pos++;
                }
                return Double.parseDouble(input.substring(start, pos));
            }
            if (pos < input.length() && Character.isLetter(input.charAt(pos))) {
                while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                    pos++;
                }
                String ident = input.substring(start, pos).toLowerCase();
                // 标识符后紧跟 '(' 时按白名单函数调用处理，否则按常量解析
                if (eat('(')) {
                    double firstArg = parseExpression();
                    double result = switch (ident) {
                        case "sqrt" -> {
                            if (firstArg < 0) {
                                throw new IllegalArgumentException("sqrt 参数不能为负数");
                            }
                            yield Math.sqrt(firstArg);
                        }
                        case "abs" -> Math.abs(firstArg);
                        case "pow" -> {
                            if (!eat(',')) {
                                throw new IllegalArgumentException("pow 函数需要 2 个参数");
                            }
                            yield Math.pow(firstArg, parseExpression());
                        }
                        case "max" -> {
                            if (!eat(',')) {
                                throw new IllegalArgumentException("max 函数需要 2 个参数");
                            }
                            yield Math.max(firstArg, parseExpression());
                        }
                        case "min" -> {
                            if (!eat(',')) {
                                throw new IllegalArgumentException("min 函数需要 2 个参数");
                            }
                            yield Math.min(firstArg, parseExpression());
                        }
                        default -> throw new IllegalArgumentException("不支持的函数: " + ident);
                    };
                    if (!eat(')')) {
                        throw new IllegalArgumentException("函数 '" + ident + "' 缺少右括号 ')'");
                    }
                    return result;
                }
                return switch (ident) {
                    case "pi" -> Math.PI;
                    case "e" -> Math.E;
                    default -> throw new IllegalArgumentException("未知标识符: " + ident);
                };
            }
            throw new IllegalArgumentException("表达式语法错误，位置: " + pos);
        }
    }
}
