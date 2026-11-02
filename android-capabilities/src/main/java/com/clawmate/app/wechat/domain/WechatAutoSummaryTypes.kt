package com.clawmate.app.wechat.domain

/**
 * Pure WeChat auto-summary constants, request model, normalizers and validator,
 * ported faithfully from HarmonyOS
 * `entry/src/main/ets/utils/wechat/WechatAutoSummaryTypes.ets` (reference only).
 *
 * Every function here is a pure total function: it never throws and returns a
 * value inside the documented range / form, so a corrupt or empty stored value
 * degrades to a safe default rather than breaking the UI. The validation
 * function returns an empty string when valid, otherwise a human-readable
 * Chinese message suited for direct display.
 *
 * Collection itself is intentionally NOT automated here — see
 * [WechatCollectionGate] for the typed PC/HDC collection boundary.
 */

// ------------------------------------------------------------------ keys
object WechatAutoSummaryKeys {
    const val ENABLED = "wechatAutoSummaryEnabled"
    const val DEFAULT_DAYS = "wechatAutoSummaryDefaultDays"
    const val DEFAULT_MAX_CONTACTS = "wechatAutoSummaryDefaultMaxContacts"
    const val SWIPE_SPEED = "wechatAutoSummarySwipeSpeed"
    const val HISTORY_SWIPE_RATIO = "wechatAutoSummaryHistorySwipeRatio"
    const val STABLE_SWIPES = "wechatAutoSummaryStableSwipes"
    const val MAX_HISTORY_SWIPES = "wechatAutoSummaryMaxHistorySwipes"
    const val WAIT = "wechatAutoSummaryWait"
    const val COLLECTION_MODE = "wechatAutoSummaryCollectionMode"
    const val COLLECTION_DAYS_INPUT = "wechatAutoSummaryCollectionDaysInput"
    const val COLLECTION_MAX_CONTACTS_INPUT = "wechatAutoSummaryCollectionMaxContactsInput"
    const val COLLECTION_TARGET_CONTACT_INPUT = "wechatAutoSummaryCollectionTargetContactInput"
}

// --------------------------------------------------------------- defaults
object WechatAutoSummaryDefaults {
    const val ENABLED = "false"
    const val DAYS = "7"
    const val MAX_CONTACTS = "10"
    const val SWIPE_SPEED = "2000"
    const val HISTORY_SWIPE_RATIO = "0.4"
    const val STABLE_SWIPES = "3"
    const val MAX_HISTORY_SWIPES = "80"
    const val WAIT = "1.0"
}

// ------------------------------------------------------------------ modes
object WechatAutoSummaryModes {
    const val RECENT = "recent_contacts"
    const val TARGET = "target_contact"
}

// ------------------------------------------------------------------ model

/**
 * WeChat auto-summary request parameters, aligning with the PC-side collection
 * service's snake_case JSON fields.
 */
data class WechatAutoSummaryRequest(
    val mode: String,
    val days: Int,
    val maxContacts: Int,
    val targetContact: String,
    val maxListSwipes: Int? = null,
    val swipeSpeed: Int,
    val historySwipeRatio: Double,
    val stableSwipes: Int,
    val maxHistorySwipes: Int,
    val wait: Double,
)

/** A single chat message; [sender] is present only when the server identifies it. */
data class WechatAutoSummaryMessage(
    val kind: String,
    val text: String?,
    val sender: String? = null,
)

/** Server-annotated coverage semantics; visible_page_only means current page only. */
data class WechatAutoSummaryTimeRange(
    val start: String? = null,
    val end: String? = null,
    val mode: String? = null,
)

data class WechatAutoSummaryConversation(
    val title: String,
    val messages: List<WechatAutoSummaryMessage>,
    val timeRange: WechatAutoSummaryTimeRange? = null,
    val historyMode: String? = null,
)

/** Typed service envelope; all fields degrade to safe defaults on parse. */
data class WechatAutoSummaryResponse(
    val status: String,
    val message: String? = null,
    val runId: String? = null,
    val mode: String? = null,
    val days: Int? = null,
    val targetContact: String? = null,
    val contactsRequested: Int? = null,
    val contactsCollected: Int? = null,
    val dailyLogEntries: List<String>? = null,
    val conversations: List<WechatAutoSummaryConversation>? = null,
)

// ------------------------------------------------------------- normalizers

private fun isInvalidNumber(value: Double): Boolean = value.isNaN() || !value.isFinite()

private fun clampIntegerSetting(value: String, fallback: Int, min: Int, max: Int): Int {
    val text = value.trim()
    if (text.isEmpty()) return fallback
    val parsed = text.toDoubleOrNull() ?: return fallback
    if (isInvalidNumber(parsed)) return fallback
    // Matches the source: truncate negative toward zero (ceil) else floor,
    // then clamp into [min, max].
    val truncated = if (parsed < 0) Math.ceil(parsed).toInt() else parsed.toInt()
    return truncated.coerceIn(min, max)
}

private fun clampFloatSetting(value: String, fallback: Double, min: Double, max: Double): Double {
    val text = value.trim()
    if (text.isEmpty()) return fallback
    val parsed = text.toDoubleOrNull() ?: return fallback
    if (isInvalidNumber(parsed)) return fallback
    return parsed.coerceIn(min, max)
}

/**
 * Bounded [Int] days (1..90, default 7). Matches the source
 * `normalizeWechatAutoSummaryDays`.
 */
fun normalizeWechatAutoSummaryDays(value: String): Int = clampIntegerSetting(value, 7, 1, 90)

/** Bounded max contacts (1..50, default 10). */
fun normalizeWechatAutoSummaryMaxContacts(value: String): Int = clampIntegerSetting(value, 10, 1, 50)

/** Bounded swipe speed (0..20000, default 2000). */
fun normalizeWechatAutoSummarySwipeSpeed(value: String): Int = clampIntegerSetting(value, 2000, 0, 20000)

/** Bounded history swipe ratio (0.1..0.95, default 0.4). */
fun normalizeWechatAutoSummaryHistorySwipeRatio(value: String): Double =
    clampFloatSetting(value, 0.4, 0.1, 0.95)

/** Bounded stable swipes (1..10, default 3). */
fun normalizeWechatAutoSummaryStableSwipes(value: String): Int = clampIntegerSetting(value, 3, 1, 10)

/** Bounded max history swipes (1..300, default 80). */
fun normalizeWechatAutoSummaryMaxHistorySwipes(value: String): Int = clampIntegerSetting(value, 80, 1, 300)

/** Bounded wait seconds (0..10, default 1.0). */
fun normalizeWechatAutoSummaryWait(value: String): Double = clampFloatSetting(value, 1.0, 0.0, 10.0)

/** Normalize a mode; anything other than target is the safe recent mode. */
fun normalizeWechatAutoSummaryMode(value: String): String =
    if (value == WechatAutoSummaryModes.TARGET) WechatAutoSummaryModes.TARGET else WechatAutoSummaryModes.RECENT

// --------------------------------------------------------------- validator

/**
 * Returns an empty string when [request] is valid, otherwise a human-readable
 * Chinese message (matching the source). Total function: never throws.
 */
fun validateWechatAutoSummaryRequest(request: WechatAutoSummaryRequest): String {
    if (request.mode != WechatAutoSummaryModes.RECENT && request.mode != WechatAutoSummaryModes.TARGET) {
        return "微信汇总采集模式无效"
    }
    if (isInvalidNumber(request.days.toDouble()) || request.days < 1 || request.days > 90) {
        return "汇总天数需在 1 到 90 天之间"
    }
    if (isInvalidNumber(request.maxContacts.toDouble()) || request.maxContacts < 1 || request.maxContacts > 50) {
        return "联系人数量需在 1 到 50 个之间"
    }
    if (isInvalidNumber(request.swipeSpeed.toDouble()) || request.swipeSpeed < 0 || request.swipeSpeed > 20000) {
        return "滑动速度需在 0 到 20000 之间"
    }
    if (isInvalidNumber(request.historySwipeRatio) ||
        request.historySwipeRatio < 0.1 || request.historySwipeRatio > 0.95
    ) {
        return "历史滑动距离比例需在 0.1 到 0.95 之间"
    }
    if (isInvalidNumber(request.stableSwipes.toDouble()) || request.stableSwipes < 1 || request.stableSwipes > 10) {
        return "稳定判定次数需在 1 到 10 次之间"
    }
    if (isInvalidNumber(request.maxHistorySwipes.toDouble()) ||
        request.maxHistorySwipes < 1 || request.maxHistorySwipes > 300
    ) {
        return "历史页最大滑动次数需在 1 到 300 次之间"
    }
    if (isInvalidNumber(request.wait) || request.wait < 0 || request.wait > 10) {
        return "页面等待时间需在 0 到 10 秒之间"
    }
    val maxListSwipes = request.maxListSwipes
    if (maxListSwipes != null &&
        (isInvalidNumber(maxListSwipes.toDouble()) || maxListSwipes < 0 || maxListSwipes > 100)
    ) {
        return "联系人列表最大滑动次数需在 0 到 100 次之间"
    }
    if (request.mode == WechatAutoSummaryModes.TARGET && request.targetContact.trim().isEmpty()) {
        return "指定联系人模式需要填写联系人名称"
    }
    return ""
}
