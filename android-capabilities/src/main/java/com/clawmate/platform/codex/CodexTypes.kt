package com.clawmate.platform.codex

/**
 * Validation and merging rules for the ClawMate Codex bridge protocol.
 *
 * Ported from the HarmonyOS client (`entry/src/main/ets/pages/codex/CodexTypes.ets`,
 * upstream `4e86f62`). Everything here is pure so the security-relevant decisions can be
 * unit-tested without a device or a network:
 *
 *  - an origin is `https://host[:port]` only: no path, query, credentials or trailing slash;
 *  - bridge tokens and pairing codes share one alphabet and length bound;
 *  - a pairing bundle must be fresh (already expired, or valid for more than ten minutes,
 *    means the phone clock or the file is wrong);
 *  - an incremental task update applies only onto its exact base revision and never
 *    reorders collapsed records.
 *
 * Tokens never appear in a URL. Callers must keep them out of logs and error messages.
 */
object CodexTypes {

    const val PAIRING_PREFIX: String = "CLAWMATE-CODEX-PAIR:"
    const val MAX_FILE_BYTES: Long = 8L * 1024 * 1024
    const val MAX_ARTIFACTS_PER_TURN: Int = 24
    const val MAX_ARTIFACT_TOTAL_BYTES: Long = 512L * 1024 * 1024
    const val MAX_TIMELINE_ITEMS: Int = 160
    const val MAX_CA_BYTES: Int = 2048

    private val ORIGIN = Regex("^https://(\\[[0-9a-fA-F:]+]|[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?)(?::([0-9]{1,5}))?$")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{43,128}$")
    private val TASK_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val RUN_ID = Regex("^[a-zA-Z0-9-]{32,36}$")
    private val CERTIFICATE = Regex("^-----BEGIN CERTIFICATE-----\\r?\\n[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----\\s*$")
    private val BUNDLE_EXPIRY_SLACK_MS = 10L * 60 * 1000

    /**
     * Accepts an HTTPS origin only, matching `normalizeCodexOrigin`.
     *
     * @return the lower-cased origin without a trailing slash.
     * @throws CodexValidationException when the input is not a bare HTTPS origin.
     */
    fun normalizeOrigin(input: String): String {
        val value = input.trim().trimEnd('/')
        val match = ORIGIN.matchEntire(value)
            ?: throw CodexValidationException("请填写完整 HTTPS 地址，例如 https://codex.example.com:8787，不含路径或令牌")
        val port = match.groupValues[2]
        if (port.isNotEmpty()) {
            val number = port.toIntOrNull()
            if (number == null || number < 1 || number > 65535) {
                throw CodexValidationException("请填写完整 HTTPS 地址，例如 https://codex.example.com:8787，不含路径或令牌")
            }
        }
        return value.lowercase()
    }

    fun isValidToken(value: String): Boolean = TOKEN.matches(value)

    fun isValidTaskId(value: String): Boolean = TASK_ID.matches(value)

    fun isValidRunId(value: String): Boolean = RUN_ID.matches(value)

    fun isValidCa(value: String): Boolean = value.length <= MAX_CA_BYTES && CERTIFICATE.matches(value)

    fun connectionLabel(origin: String): String = origin.removePrefix("https://")

    /**
     * Validates a scanned or imported pairing bundle before any request is made.
     *
     * @throws CodexValidationException naming the first broken rule.
     */
    fun validatePairing(bundle: CodexPairingBundle?, now: Long): CodexPairingBundle {
        if (bundle == null) throw CodexValidationException("配对信息无效或已过期，请在 PC 应用中重新生成")
        val origin = runCatching { normalizeOrigin(bundle.origin) }.getOrNull()
        val invalid = bundle.type != "clawmate-codex-pairing" ||
            bundle.version != 1 ||
            bundle.name.length > 80 ||
            origin != bundle.origin ||
            !isValidToken(bundle.code) ||
            bundle.serverId.length > 80 ||
            bundle.expiresAt < now ||
            bundle.expiresAt > now + BUNDLE_EXPIRY_SLACK_MS ||
            (bundle.caCertificate.isNotEmpty() && !isValidCa(bundle.caCertificate))
        if (invalid) throw CodexValidationException("配对信息无效或已过期，请在 PC 应用中重新生成")
        return bundle
    }

    fun taskIsBusy(status: String): Boolean =
        status in setOf("starting", "running", "waitingApproval", "stopping", "releasing")

    /**
     * Applies a full snapshot or an incremental delta onto [current].
     *
     * Mirrors `mergeCodexTask`: a delta is rejected unless it continues the exact
     * [current] revision, moves it forward, and stays within the collapsed-record bound.
     *
     * @return the next task, or `null` when the envelope carries neither a task nor a delta.
     * @throws CodexStaleDeltaException when the delta cannot be applied and a full resync is required.
     */
    fun mergeTask(current: CodexTask?, envelope: CodexEnvelope): CodexTask? {
        envelope.task?.let { return it }
        val delta = envelope.taskDelta ?: return null
        if (current == null || current.id != delta.task.id || current.revision != delta.baseRevision ||
            delta.task.revision <= current.revision ||
            delta.itemOrder.size > MAX_TIMELINE_ITEMS || delta.items.size > MAX_TIMELINE_ITEMS
        ) {
            throw CodexStaleDeltaException("会话增量已过期，需要重新同步")
        }
        val records = LinkedHashMap<String, CodexItem>()
        current.items.forEach { records[it.id] = it }
        delta.items.forEach { patch ->
            val previous = records[patch.id]
            if (patch.append && (previous == null || previous.kind != patch.kind)) {
                throw CodexStaleDeltaException("会话增量缺少原始记录")
            }
            val text = if (patch.append) (previous?.text.orEmpty()) + patch.text else patch.text
            records[patch.id] = CodexItem(patch.id, patch.kind, text, patch.turnId)
        }
        val items = ArrayList<CodexItem>(delta.itemOrder.size)
        val seen = HashSet<String>()
        delta.itemOrder.forEach { id ->
            val item = records[id]
            if (item == null || !seen.add(id)) throw CodexStaleDeltaException("会话增量顺序无效")
            items.add(item)
        }
        return delta.task.copy(items = items)
    }

    /**
     * Rejects a delivered artifact that the phone cannot safely store.
     *
     * @throws CodexValidationException on an invalid name, digest, size or timestamp.
     */
    fun validateArtifact(artifact: CodexArtifact) {
        val ok = artifact.id.matches(TASK_ID) &&
            artifact.name.isNotEmpty() &&
            artifact.name.length <= 240 &&
            artifact.name.none { it == '/' || it == '\\' || it < ' ' } &&
            artifact.name != "." && artifact.name != ".." &&
            artifact.sha256.matches(Regex("^[a-f0-9]{64}$")) &&
            artifact.size in 1..MAX_ARTIFACT_TOTAL_BYTES &&
            artifact.createdAt >= 0
        if (!ok) throw CodexValidationException("交付文件信息无效")
    }

    /** Turn-scoped delivery bound: at most 24 files and 512 MB in one turn. */
    fun validateTurnArtifacts(artifacts: List<CodexArtifact>) {
        artifacts.forEach { validateArtifact(it) }
        if (artifacts.size > MAX_ARTIFACTS_PER_TURN) {
            throw CodexValidationException("本次交付超过 24 个文件，请在电脑端检查")
        }
        val total = artifacts.sumOf { it.size }
        if (total > MAX_ARTIFACT_TOTAL_BYTES) {
            throw CodexValidationException("本次交付超过 512 MB，请在电脑端检查")
        }
    }

    fun approvalLabel(mode: String): String = when (mode) {
        "full" -> "完全访问控制"
        "auto" -> "帮我批准"
        else -> "请求批准"
    }

    fun statusLabel(status: String): String = when (status) {
        "idle" -> "等待任务"
        "starting" -> "正在提交"
        "running" -> "执行中"
        "waitingApproval" -> "等待你的确认"
        "stopping" -> "正在停止"
        "releasing" -> "正在保存并释放会话"
        "completed" -> "已完成"
        "interrupted" -> "已中断"
        "failed" -> "执行失败"
        "history" -> "电脑历史"
        "unknown" -> "等待同步状态"
        else -> "状态未知"
    }

    fun linkLabel(lastSuccess: Long, lastFailure: Long, now: Long, checking: Boolean = false): String = when {
        lastFailure > 0 && lastFailure >= lastSuccess -> if (checking) "正在重连" else "连接已中断"
        lastSuccess == 0L || now - lastSuccess > 25_000L -> "正在确认连接"
        else -> "已连接"
    }
}

/** Raised when a pairing bundle, origin, token, CA or artifact fails validation. */
class CodexValidationException(message: String) : Exception(message)

/** Raised when an incremental update cannot be applied and a full task read is required. */
class CodexStaleDeltaException(message: String) : Exception(message)
