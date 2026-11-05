# Codex 联调测试夹具

用于在没有真实 Codex 账号的机器上验证 Android 客户端的完整协议链路。

## 组成

| 文件 | 作用 |
|---|---|
| `bridge/codex/server.mjs` | 上游桥接服务（原样复用，仅本地副本加了 `codexArgs` 读取） |
| `tests/codex/android-fixture-app-server.mjs` | App Server 替身，模拟 `initialize` / `model/list` / `thread/start` / `turn/start`，并能按指令产出**审批请求**与**交付文件** |
| `fake-app-server.mjs`（上游自带） | 只能返回固定答复，无法覆盖审批与文件交付 |

替身识别的指令：

- 消息含 `approve` 或 `审批` → 发起 `item/commandExecution/requestApproval`
- 消息含 `deliver` 或 `交付` → 在 `outputs/` 写入文件并在最终答复中给出 Markdown 链接
- 其他 → 返回固定答复「测试成功」

## 搭建步骤

1. 复制本地桥接副本并允许传入 App Server 参数（上游 `server.mjs` 只传命令名）：

   ```js
   // server.mjs 第 225 行附近
   const rpc = new CodexRpc(config.codexCommand || 'codex', config.codexArgs || undefined);
   ```

2. 生成自签证书（SAN 需包含 `127.0.0.1`）：

   ```bash
   openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=127.0.0.1" \
     -addext "subjectAltName=IP:127.0.0.1,DNS:localhost" \
     -keyout key.pem -out cert.pem
   ```

3. 写配置（`codexCommand` 指向 node，`codexArgs` 指向替身）：

   ```json
   {
     "name": "Local Test PC",
     "host": "127.0.0.1",
     "port": 8787,
     "token": "<43-128 位 base64url>",
     "codexCommand": "<path-to>/node.exe",
     "codexArgs": ["<path-to>/tests/codex/android-fixture-app-server.mjs"],
     "tls": { "cert": "<path-to>/cert.pem", "key": "<path-to>/key.pem" },
     "stateDir": "<path>/state",
     "workspaces": [{ "id": "app", "name": "Fixture Project", "path": "<path>/workspace" }]
   }
   ```

4. 启动：

   ```bash
   CLAWMATE_FIXTURE_WORKSPACE=<path>/workspace node server.mjs serve <path>/config.json
   ```

5. 运行联调测试：

   ```bash
   ./gradlew :android-capabilities:testDebugUnitTest \
     -Dclawmate.codex.bridgeUrl=https://127.0.0.1:8787 \
     -Dclawmate.codex.bridgeToken=<token> \
     -Dclawmate.codex.bridgeCaFile=<path>/cert.pem
   ```

   不传这三个属性时，所有 live 测试自动跳过，普通构建保持隔离。

## 覆盖范围

| 测试类 | 覆盖 |
|---|---|
| `CodexLiveBridgeTest` | 能力协商、单次往返、错误令牌、CA 钉扎、未钉扎拒绝 |
| `CodexBackendLiveTest` | 完整委托：连上→建任务→下发→跟随→终态 |
| `CodexBridgeSafetyTest` | 同一 `requestId` 不重复执行、冲突内容 409、中断确认、未知路由 |
| `CodexDeliveryLiveTest` | 交付文件下载落地、审批提示 `waitingApproval` 且不误交付 |

## 仍然无法覆盖

- **模型推理质量**：替身不调用任何模型。
- **真实 Codex CLI 的行为差异**：替身按协议实现，不代表官方 App Server 的全部细节。
- **真正的停止语义**：`turn/interrupt` 在替身里直接返回，不模拟执行中中断。
