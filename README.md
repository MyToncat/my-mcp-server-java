# mcp-server-java

纯手工编写的**零运行期第三方依赖** Java MCP (Model Context Protocol) 服务端，基于 JDK 21 从零实现 JSON 解析/序列化、JSON-RPC 2.0 协议分发以及 Stdio 传输层。

## 目录结构

```text
mcp-server-java/
├── pom.xml                                                 # Maven 构建配置（运行期零外部依赖，仅测试依赖 JUnit 5）
├── README.md
└── src/
    ├── main/java/com/example/mcp/
    │   ├── Main.java                                       # 服务端入口，注册 Tools / Resources / Prompts
    │   ├── json/JsonCodec.java                             # 手写 RFC 8259 JSON 解析器与序列化器
    │   ├── protocol/JsonRpc.java                           # JSON-RPC 2.0 常量、错误码与响应构造
    │   ├── server/
    │   │   ├── McpServer.java                              # MCP 生命周期、Stdio 传输循环与请求分发
    │   │   ├── McpTool.java                                # Tool 抽象接口
    │   │   ├── McpResource.java                            # Resource 抽象接口
    │   │   └── McpPrompt.java                              # Prompt 抽象接口
    │   └── tools/BuiltinTools.java                         # 内置示例工具集
    └── test/java/com/example/mcp/
        └── McpServerTest.java                              # 单元测试与 Stdio 端到端测试
```

## 已实现的 MCP 能力

### 1. 协议生命周期与传输层
- **Stdio 传输**：按行读取 UTF-8 JSON-RPC 2.0 消息；所有响应写往 `stdout`，日志统一输出到 `stderr`。
- **方法支持**：
  - `initialize`（支持 `2024-11-05` / `2025-03-26` / `2025-06-18` 版本协商与 Capabilities 声明）
  - `notifications/initialized`、`notifications/cancelled`
  - `ping`
  - `tools/list`、`tools/call`
  - `resources/list`、`resources/read`
  - `prompts/list`、`prompts/get`

### 2. 内置 Tools
- `get_current_time`：获取指定 IANA 时区（如 `Asia/Shanghai`、`UTC`）的当前时间。
- `calculate`：基于手写递归下降解析器的安全数学计算器，支持 `+ - * / % ^`、括号、常量(`pi`, `e`)及函数(`sqrt`, `abs`, `pow`, `max`, `min`)。
- `text_stats`：统计文本字符数、非空白字符数、词数、行数与 UTF-8 字节长度。

### 3. 内置 Resources & Prompts
- **Resource**: `info://server/status` — 返回当前 JVM 运行时信息与内存状态（JSON）。
- **Prompt**: `code_review` — 生成代码审查提示词模板。

## 构建与运行

```powershell
# 编译、运行单元测试并打包
mvn test package

# 直接启动 MCP Stdio 服务（等待 stdin 输入 JSON-RPC 消息）
java "-Dfile.encoding=UTF-8" -jar target/mcp-server-java-1.0.0.jar
```

## 在 MCP 客户端中配置

可在支持 Stdio MCP 的客户端（如 Claude Desktop、Cursor 或其他 MCP Host）的 `mcp_config.json` 中添加：

```json
{
  "mcpServers": {
    "java-manual-mcp": {
      "command": "java",
      "args": [
        "-Dfile.encoding=UTF-8",
        "-jar",
        "G:/ai-project/mcp-server-java/target/mcp-server-java-1.0.0.jar"
      ]
    }
  }
}
```
