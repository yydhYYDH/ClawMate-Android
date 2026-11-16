# 设备文件工具（C4）实施计划

目标：让 Mika 任务在用户明确授权的目录里「只读」文件，作为第二档的最后一项。
当前状态：**未实现**；本页是落地方案，避免下一轮从零摸索。

## 1. 端口契约（内核已有）

`core/src/main/kotlin/.../cap/devicefiles/DeviceFilesPort.kt`：

```kotlin
interface DeviceFilesPort {
    suspend fun list(path: String?, cursor: String?, limit: Int): DeviceFilePage
    suspend fun importFile(path: String, workspace: String): DeviceFileImport
    suspend fun search(query: DeviceFileSearchQuery): DeviceFileSearchResult
    suspend fun listDirectoryHints(): List<DeviceDirectoryHint>   // 可抛 unsupported
}
```

- `DeviceFilesGroup.tools` 里的工具只有在 `toolDispatcher(deviceFiles = port)` 传入端口时才可见；
- `importFile` 需要工作区沙箱（本仓库未接），首版应抛 `SandboxError("unsupported", …)`，
  由模型看到明确失败，而不是假装导入成功。

## 2. Android 侧实现（只读）

- 授权：`ACTION_OPEN_DOCUMENT_TREE`（`SharedActivity` 的 `startActivityForResult`），
  成功后 `takePersistableUriPermission`，把 tree URI 存到 DataStore/SharedPreferences；
  设置页提供「授权文件夹 / 撤销」两行状态。
- `list/search`：`DocumentsContract.buildChildDocumentsUriUsingTree` 查询
  `COLUMN_DOCUMENT_ID`、`DISPLAY_NAME`、`MIME_TYPE`、`SIZE`、`LAST_MODIFIED`；
  用 `LIMIT` + `cursor`（下一页的 documentId 或 offset）分页，单页上限 100。
- 读取：仅通过工具返回元数据与文本内容；二进制/超限文件明确拒绝。
- `importFile`：沙箱未接前直接 `unsupported`。

## 3. 接线与边界

- 只有用户在设置里授权后，`MikaPhoneRun` 才把 `deviceFiles = AndroidDeviceFiles(context)` 传给
  `toolDispatcher`；未授权时端口为 `null`，工具不出现（与本仓库的 NOT_CONFIGURED 约定一致）。
- 文件内容属于外部不可信数据：工具结果按 `<untrusted>` 处理，不得据其改变任务授权范围。
- 不申请 `MANAGE_EXTERNAL_STORAGE`；不使用 `READ_SMS`/通话记录等敏感目录。

## 4. 验证

1. 纯函数单测：分页游标、名称搜索过滤、路径规范化、未授权时端口不可用；
2. 真机：在 OPPO 上授权「下载」目录，让 Mika 任务列出前 20 项并读取一个文本文件，
   核对回执与真实文件一致；撤销授权后工具立即消失；
3. 回归：未授权时手机任务、采集、聊天均不应出现设备文件工具。

## 5. 估算

- Kotlin 端口 + SAF + 设置入口：约 1–1.5 天；
- 单测与真机：约 0.5 天；
- 风险：`DocumentsProvider` 行为差异（厂商文件管理器）、大目录分页性能。

> 本页只做方案，不代表已实现；实现完成后应更新 `MIGRATION_STATUS.md` 与待办勾选。
