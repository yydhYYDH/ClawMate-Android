# 上游 ClawMate 的采集数据存储（mobiinfra-oh@clawmate_dev）

调研对象：<https://github.com/JKSOBBING/mobiinfra-oh> 分支 `clawmate_dev`
（本次读取的 tree `49fc716ab6775642f96a44a499b12e772a4af5d0`）。
依据仓库内文档与源码：`docs/gallery/storage-format.md`、`docs/gallery/collection-flow.md`、
`entry/src/main/ets/utils/WorkflowStorage.ets`、`utils/WorkflowTypes.ets`、
`datamanager/DataManagerStorage.ets`。

## 1. 结论速览

上游有**两套并存的采集存储**，都以应用 `filesDir` 为根，都按“日期 + 单条文件”落盘：

| 采集类型 | 根目录 | 事实源 | 派生视图 |
| --- | --- | --- | --- |
| 任务/Workflow 采集（微博、小红书、账单、订单、聊天记录等） | `filesDir/workflows/` | `runs/` 下的运行 JSON | `daily-log/` |
| 图库采集（相册图片分析） | `filesDir/gallery-log/` | `items/<date>/<id>.json` | `daily-log/<date>/<category>.md` |

另外，聊天采集（微信等）的批次与事件进入 DataManager 的记忆事件文件
（`datamanager/DataManagerStorage.ets` 管理的按前缀分文件的 JSON 存储），供后续分析与检索使用。

## 2. 任务/Workflow 采集：`filesDir/workflows/`

`WorkflowStorage.ets` 里的目录常量：

```text
workflows/
  configs/                 # 每个任务的工作流 JSON（默认任务 + 自定义任务）
  runs/                    # 每次运行的 JSON 结果
  chat-sessions/           # 与运行关联的聊天会话
  tool-runs/               # 工具调用记录
  mobile-gui/              # 手机 GUI 动作相关文件
  daily-log/               # 按天汇总的采集日志
  task-index.json          # 任务清单
  deleted-default-tasks.json
```

- 默认任务是一组随包资源（支付宝账单、饿了么订单、B 站历史、腾讯视频历史、微信聊天/今日聊天、
  大众点评订单、美团订单、微博热搜、小红书历史、携程订单），首次使用时复制成 `configs/<name>.json`。
- 每次运行产出的 `WorkflowRunResult`（`utils/WorkflowTypes.ets`）包含：
  `dailyLogPath`、`result_summary`、`result`（结构化 JSON）、`result_extraction_error`、
  `result_screenshot_path(s)`、`steps`（每步状态与输出）。
- 因此“采集到的数据”以 **运行 JSON（结构化 result）为事实源**，`daily-log/` 是按天聚合的派生文件，
  可以由 runs 重建。任务页的“采集日志/运行记录”读的就是这里。

## 3. 图库采集：`filesDir/gallery-log/`

```text
gallery-log/
  items/<date>/<id>.json          # 单图完整分析结果（事实源）
  embeddings/<date>/<id>.json     # 单图向量
  daily-log/<date>/<category>.md  # 按天、按类别的人读摘要（派生）
```

- 文件级并发写入；`category` 是大模型分析后的结果，所以 `items`/`embeddings` 的路径**不含类别**。
- 缓存键：`items/<date>/<id>.json` 已存在即跳过解码、OCR、模型与 embedding。
- item JSON 字段：`id`、`uri`、`title`、`category`、`summary`、`details[]`、`tags[]`、`ocrText`、
  `rawModelText`、`keywords[]`、`date`、`embeddingRef`、`embeddingStatus`、`embeddingDimension`、
  `error`、`latitude/longitude`、`exif{...}`、`createdAt`。
- 类别固定为：聊天、文档、餐饮、购物、出行、健康、工作、其他。
- `daily-log/<date>/<category>.md` 头部有 `<!-- DAILY_LOG_METADATA {...} -->`，正文逐条列
  `[id] title: summary` 与 `details`；不作为事实源，item 更新后整日重建。
- embedding JSON：`{id, uri, date, category, text, vector, model, mode, createdAt}`；
  `mode=remote`（SiliconFlow/bge-m3）或预留的 `huawei_local`；daily-log 不存向量。
- 写入顺序：embeddings → items → 批次结束后按日期重建 daily-log。

## 4. 聊天采集与记忆层

`datamanager/DataManagerStorage.ets` 是文件型 KV/JSON 存储（按前缀分文件、带读取缓存与
SHA-256 变更日志）。聊天采集批次（`ChatCollectionBatch`）与事件（`IndexedEvent`）经
`ChatCollectionContext` 归并，进入记忆事件流；批次日志追加写、同步前封存。

## 5. 与本仓库 Android 版的关系

Android 共享前端（`shared-frontend`）来自同一份源码，目录布局**已经一致**：
真机 `files/files/` 下同时存在 `workflows/`、`gallery-log/`、`chat-sessions/`。

差别在原生采集入口：

- 原生 `GuiControlService` / 任务页的模板采集（微博、小红书等）把结果写进
  `no_backup/gui-collections.json`（`GuiCollectionHistory`），**不是** `workflows/runs/`；
  聊天通过 `guiCollectionQuery` 读它。
- 本轮新增的 Mika 采集也写这个本机存储（`sourceLabel` 带 `· Mika`），因此能被聊天检索，
  但不会出现在上游式 `workflows/daily-log/` 里。

## 6. 建议（未实施，供决策）

1. 如果要求“任务页采集日志/运行记录”统一：让原生采集在保存时同时产出
   `workflows/runs/<runId>.json`（含 `result`、`result_summary`、`dailyLogPath`），
   再按天重建 `workflows/daily-log/`；`gui-collections.json` 保留为聊天查询的轻量索引。
2. 如果只要求“本机保存、以后分析”：当前 `gui-collections.json` + `mika-runs.jsonl` 已够用，
   但需要明确它和共享前端 `WorkflowStorage` 是两套存储，避免将来重复迁移。
3. 图库采集已与上游一致，无需改动；注意 `docs/gallery/storage-format.md` 说明旧聚合文件
   （`gallery/results.json` 等）已废弃，不应对新代码继续使用。

> 本页只做代码/文档对照；上述目录与字段均来自上游源码与文档，未在本机 Android 上逐项复测。
