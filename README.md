# mcp-server-java

纯手工编写的**零运行期第三方依赖** Java MCP (Model Context Protocol) 服务端，基于 JDK 21 从零实现 JSON 解析/序列化、JSON-RPC 2.0 协议分发，并同时支持 **Stdio** 与 **MCP Streamable HTTP** 两种传输层。

## 目录结构

```text
mcp-server-java/
├── pom.xml                                                 # Maven 构建配置（运行期零外部依赖，仅测试依赖 JUnit 5）
├── README.md
└── src/
    ├── main/java/com/example/mcp/
    │   ├── Main.java                                       # 服务端入口，支持同时启动 Stdio 与 Streamable HTTP
    │   ├── json/JsonCodec.java                             # 手写 RFC 8259 JSON 解析器与序列化器
    │   ├── protocol/JsonRpc.java                           # JSON-RPC 2.0 常量、错误码与响应构造
    │   ├── server/
    │   │   ├── McpServer.java                              # MCP 生命周期、多会话调度与 Stdio 循环
    │   │   ├── StreamableHttpTransport.java                # 基于 JDK HttpServer 的 Streamable HTTP 传输层
    │   │   ├── McpTool.java                                # Tool 抽象接口
    │   │   ├── McpResource.java                            # Resource 抽象接口
    │   │   └── McpPrompt.java                              # Prompt 抽象接口
    │   └── tools/BuiltinTools.java                         # 内置示例工具集
    └── test/java/com/example/mcp/
        └── McpServerTest.java                              # 单元测试与 Stdio / HTTP 端到端测试
```

## 已实现的 MCP 能力

### 1. 协议生命周期与传输层
- **Stdio 传输**：按行读取 UTF-8 JSON-RPC 2.0 消息；所有响应写往 `stdout`，日志统一输出到 `stderr`。
- **Streamable HTTP 传输**：基于 JDK 21 内置 `HttpServer` + 虚拟线程实现单端点 `/mcp`：
  - `POST /mcp`：接收 JSON-RPC 请求/通知/批处理，返回 `application/json`（或按 `Accept: text/event-stream` 返回 SSE），自动分配并校验 `Mcp-Session-Id`，仅通知时返回 `202 Accepted`。
  - `GET /mcp`：建立 SSE 长连接通道（用于保活/推送）。
  - `DELETE /mcp`：注销指定 `Mcp-Session-Id` 会话。
  - `GET /health`：返回服务端健康状态。
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
# 编译、运行单元测试与 HTTP 集成测试并打包
mvn test package

# 默认模式（both）：同时启动 Stdio 与 Streamable HTTP（http://0.0.0.0:8080/mcp）
java "-Dfile.encoding=UTF-8" -jar target/mcp-server-java-1.0.0.jar

# 仅启动远程 HTTP 模式（无 stdin 依赖，适合后台/容器部署）
java "-Dfile.encoding=UTF-8" -jar target/mcp-server-java-1.0.0.jar --transport http --host 0.0.0.0 --port 8080

# 仅启动 Stdio 模式（当需要与本地客户端独立运行且不占用端口时）
java "-Dfile.encoding=UTF-8" -jar target/mcp-server-java-1.0.0.jar --transport stdio
```

支持的参数与环境变量（命令行优先级更高）：
- `--transport <both|stdio|http>` / `MCP_TRANSPORT`（默认 `both`）
- `--host <addr>` / `MCP_HOST`（默认 `0.0.0.0`）
- `--port <port>` / `MCP_PORT`（默认 `8080`）
- `--path <path>` / `MCP_PATH`（默认 `/mcp`）

## 手工调用示例

无论使用 **Stdio**、**HTTP JSON** 还是 **SSE**，MCP 消息体本身始终是 **JSON-RPC 2.0** 格式，传输层仅负责消息的拆帧与传递：
- **Stdio**：按行（`\n`）分隔单条 JSON-RPC 2.0 消息
- **Streamable HTTP (`POST /mcp`)**：请求体为 JSON-RPC 2.0；响应为 `application/json` 或 `text/event-stream`（`event: message` + `data: <JSON-RPC 2.0>`）

### 1. 手工 Stdio 调用（PowerShell）

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

@'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"ps-cli","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"calculate","arguments":{"expression":"(12 + 8) * 3.5 / 2"}}}
'@ | java "-Dfile.encoding=UTF-8" -jar target/mcp-server-java-1.0.0.jar --transport stdio
```

### 2. 手工远程 HTTP 调用（curl）

```bash
# 健康检查
curl http://127.0.0.1:8080/health

# 1. 初始化并获取 Mcp-Session-Id 响应头
curl -i -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

# 2. 发送 initialized 通知（替换 <SESSION_ID>，返回 202 Accepted）
curl -i -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: <SESSION_ID>" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3. 调用工具（也可以省略 Mcp-Session-Id 直接无状态调用）
curl -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: <SESSION_ID>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"calculate","arguments":{"expression":"(12 + 8) * 3.5 / 2"}}}'

# 4. 以 SSE 格式接收响应（Accept 仅指定 text/event-stream）
curl -N -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"jsonrpc":"2.0","id":3,"method":"ping"}'

# 5. 建立 GET SSE 长连接通道 / 注销会话
curl -N -H "Accept: text/event-stream" -H "Mcp-Session-Id: <SESSION_ID>" http://127.0.0.1:8080/mcp
curl -i -X DELETE -H "Mcp-Session-Id: <SESSION_ID>" http://127.0.0.1:8080/mcp
```

## 在 MCP 客户端中配置

### 1. 本地 Stdio 客户端配置
若在客户端中通过 Stdio 独立拉起多个进程，建议加上 `--transport stdio` 以避免端口冲突：

```json
{
  "mcpServers": {
    "java-manual-mcp-stdio": {
      "command": "java",
      "args": [
        "-Dfile.encoding=UTF-8",
        "-jar",
        "G:/ai-project/mcp-server-java/target/mcp-server-java-1.0.0.jar",
        "--transport",
        "stdio"
      ]
    }
  }
}
```

### 2. 远程 Streamable HTTP 客户端配置
服务端启动后（默认或 `--transport http`），可在支持 Streamable HTTP 的客户端中直接配置远程 URL：

```json
{
  "mcpServers": {
    "java-manual-mcp-http": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```
