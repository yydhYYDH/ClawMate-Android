package com.clawmate.platform.gui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.app.Dialog
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.assembly.collectPages
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.uistate.ScreenPort
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.platform.display.BackgroundDisplay
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Exact-app sessions keep observations and gesture execution inside the native service. */
class GuiControlService : AccessibilityService() {
    companion object {
        @Volatile var connected: GuiControlService? = null
            private set
        @Volatile var lastStatus: String = "尚未开启控制会话"
            private set
    }
    private val handler=Handler(Looper.getMainLooper())
    private var grant: GuiControlSession?=null
    private var panel: LinearLayout?=null
    private var dialog: Dialog?=null
    private var preview: Bitmap?=null
    private val overlayOwnership=GuiOverlayOwnership()
    private var busy=false
    private var epoch=0
    private var armedUntil=0L
    private var captureAt=0L
    private var pendingStart: GuiControlRequest? = null
    private var activeRequest: GuiControlRequest? = null
    private val targetLabel: String get() = activeRequest?.label.orEmpty().take(40)
    private val collectOnly: Boolean get() = activeRequest?.task != GuiControlTask.ManualControl && mikaRun == null
    private val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectionJob: Job? = null
    private val weiboRun get() = (activeRequest?.task as? GuiControlTask.Weibo)?.run
    private val xhsRun get() = (activeRequest?.task as? GuiControlTask.Xhs)?.run
    private val pageRun get() = (activeRequest?.task as? GuiControlTask.Pages)?.run
    private val mikaRun get() = (activeRequest?.task as? GuiControlTask.Mika)?.run
    private var backgroundDisplay: BackgroundDisplay? = null
    private var xhsCandidate: Pair<Int, Rect>? = null
    private var xhsCandidateSince = 0L
    private val watchdog=object:Runnable {
        override fun run() {
            if(grant==null)return
            try {
                check(grant!!.available(SystemClock.elapsedRealtime())) { "控制会话已到期或达到动作上限" }
                check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "手机已锁屏，控制已停止" }
                check(backgroundDisplay?.connected != false) { "后台屏助手已断开" }
                if (panel != null) currentWindow(allowInputMethod = mikaRun != null || pageRun?.awaitingPage == true && !busy) else {
                    check(SystemClock.elapsedRealtime() <= armedUntil) {
                        when {
                            backgroundDisplay != null && windows.any {
                                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == grant?.target
                            } -> "目标应用打开到了主屏，后台屏任务已停止"
                            backgroundDisplay != null -> "后台屏未在时限内打开已确认的页面"
                            weiboRun != null -> "微博榜单未在时限内打开"
                            else -> "目标应用未进入前台"
                        }
                    }
                    showPanelIfReady()
                }
            } catch(failure:Exception){stop(failure.message ?: "控制会话已停止");return}
            handler.postDelayed(this,250)
        }
    }
    override fun onServiceConnected(){connected=this;lastStatus="服务已连接；尚未授权任何应用"}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (grant == null || panel != null) return
        handler.post { showPanelIfReady() }
    }
    private fun showPanelIfReady() {
        val target = grant?.target ?: return
        if (panel != null) return
        // Opening can temporarily show a system confirmation or the app's splash screen.
        val weibo = weiboRun
        val xhs = xhsRun
        if ((weibo != null || xhs != null) && Build.VERSION.SDK_INT >= 33) clearCache()
        val visible = try {
            sessionWindows()
        } catch (failure: IllegalStateException) {
            stop(failure.message ?: "采集窗口已断开")
            return
        }
        val candidate = visible.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.singleOrNull()
        val root = candidate?.root
        if (candidate == null || root == null || root.packageName?.toString() != target) {
            weibo?.windowReady(null, SystemClock.elapsedRealtime())
            xhsCandidate = null
            return
        }
        try {
            if (weibo != null && Build.VERSION.SDK_INT >= 34) {
                val bounds = Rect().also(candidate::getBoundsInScreen)
                val marker = WeiboGuiWorkflow.hotTab(root, safeBounds(bounds))
                // A warm task can briefly expose its old rank window before the link creates a new one.
                if (!weibo.windowReady(if (marker == null) null else candidate.id to bounds, SystemClock.elapsedRealtime())) return
            }
            if (xhs != null && Build.VERSION.SDK_INT >= 34) {
                val bounds = Rect().also(candidate::getBoundsInScreen)
                val page = XhsGuiWorkflow.page(root, safeBounds(bounds), xhs.keyword)
                val next = if (page?.excerpts.isNullOrEmpty()) null else candidate.id to bounds
                val now = SystemClock.elapsedRealtime()
                if (next == null || next != xhsCandidate) {
                    xhsCandidate = next
                    xhsCandidateSince = now
                    return
                }
                if (now - xhsCandidateSince < 900) return
            }
            currentWindow(allowInputMethod = pageRun != null || mikaRun != null)
            showPanel()
        } catch (_: Exception) { /* short launch grace only */ }
    }
    override fun onInterrupt(){stop("系统中断控制会话")}
    override fun onUnbind(intent:Intent?):Boolean{stop("无障碍服务已断开");if(connected===this)connected=null;return super.onUnbind(intent)}
    override fun onDestroy(){stop("无障碍服务已销毁");collectionScope.cancel();if(connected===this)connected=null;super.onDestroy()}
    /** Used by all authorized collection and control entry points on the main thread. */
    internal suspend fun startSession(
        request: GuiControlRequest,
        useBackground: Boolean,
        checkForeground: () -> Unit,
    ) {
        check(Build.VERSION.SDK_INT >= 34) { "跨应用窗口截图需要 Android 14 及以上" }
        check(activeRequest == null && pendingStart == null) { "已有手机控制会话，请先停止" }
        check(!useBackground || request.supportsBackground) { "此流程需要在主屏手动打开要采集的页面" }
        check(connected === this) { "无障碍服务已断开" }
        checkForeground()
        pendingStart = request
        var display: BackgroundDisplay? = null
        try {
            if (useBackground) {
                requireTargetOffMainScreen(request.target)
                withContext(Dispatchers.IO + NonCancellable) { display = BackgroundDisplay.open(request.launch) }
                check(connected === this && pendingStart === request) { "采集已停止或无障碍服务已断开" }
                checkForeground()
                requireTargetOffMainScreen(request.target)
            }
            val now = SystemClock.elapsedRealtime()
            grant = GuiControlSession(request.target, now)
            activeRequest = request
            pendingStart = null
            backgroundDisplay = display
            display = null
            weiboRun?.let { it.current = it.steps.first() }
            armedUntil = now + if (collectOnly || mikaRun != null) 20_000 else 5_000
            lastStatus = if (useBackground) "等待后台屏打开：$targetLabel" else "等待目标应用进入前台：$targetLabel"
            if (!useBackground) startActivity(Intent(request.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            handler.post(watchdog)
        } catch (failure: Exception) {
            stopSession(request, failure.message ?: "无法打开目标应用")
            throw failure
        } finally {
            display?.close()
        }
    }

    internal fun startManual(request: GuiControlRequest, checkForeground: () -> Unit, onError: (Exception) -> Unit) {
        collectionScope.launch {
            try {
                startSession(request, useBackground = false, checkForeground)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onError(failure)
            }
        }
    }

    internal fun stopSession(request: GuiControlRequest, reason: String) {
        if (activeRequest === request || pendingStart === request) stop(reason)
    }
    fun stop(reason:String="用户停止控制会话") {
        val active=grant!=null
        overlayOwnership.retain(emptySet())
        epoch++;grant?.stop();grant=null;handler.removeCallbacks(watchdog);busy=false
        collectionJob?.cancel()
        collectionJob = null
        val interruptedWeibo = weiboRun
        val interruptedXhs = xhsRun
        val interruptedPages = pageRun
        val interruptedMika = mikaRun
        activeRequest = null
        pendingStart = null
        backgroundDisplay?.close()
        backgroundDisplay = null
        xhsCandidate = null
        dialog?.dismiss();dialog=null;preview?.recycle();preview=null
        panel?.let{runCatching{getSystemService(WindowManager::class.java).removeView(it)}};panel=null
        lastStatus=reason
        if(active)android.widget.Toast.makeText(this,reason,android.widget.Toast.LENGTH_LONG).show()
        interruptedWeibo?.finish(RunStatus.CANCELLED, reason)
        interruptedXhs?.finish(RunStatus.CANCELLED, reason)
        interruptedPages?.finish(RunStatus.CANCELLED, reason)
        interruptedMika?.complete("cancelled", reason)
    }
    private fun sessionWindows(): List<AccessibilityWindowInfo> {
        val display = backgroundDisplay ?: return windows
        check(display.connected) { "后台屏助手已断开" }
        return windowsOnAllDisplays.get(display.id).orEmpty()
    }

    private fun currentWindow(allowInputMethod: Boolean = false):AccessibilityWindowInfo {
        val visibleWindows=sessionWindows()
        overlayOwnership.retain(visibleWindows.filter{it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY}.map{it.id}.toSet())
        val apps=visibleWindows.filter{it.type==AccessibilityWindowInfo.TYPE_APPLICATION}
        val window=apps.singleOrNull() ?: error("多窗口或目标窗口不可见，控制已停止")
        val root=window.root ?: error("无法识别目标应用窗口")
        val pkg=root.packageName?.toString().orEmpty()
        grant?.requireTarget(SystemClock.elapsedRealtime(),pkg,!getSystemService(KeyguardManager::class.java).isKeyguardLocked,apps.size) ?: error("会话已结束")
        check(window.displayId == (backgroundDisplay?.id ?: 0)) { "目标窗口不在本次授权的屏幕" }
        if (backgroundDisplay != null) requireTargetOffMainScreen(pkg)
        val safe=safeBounds(Rect().also{window.getBoundsInScreen(it)})
        for(other in visibleWindows) {
            if(other.id==window.id)continue
            if (allowInputMethod && other.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            if(other.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                overlayOwnership.isOwned(other.id,other.root?.packageName?.toString(),packageName))continue
            val covered=Rect().also{other.getBoundsInScreen(it)}
            // 别的无障碍服务的浮动按钮（例如 Mika 的胶囊）只占一角时不算遮挡目标，
            // 否则两台助手同装时一次采集都开不起来；遮住大半个内容区仍按遮挡处理。
            if (other.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                val overlap = Rect(safe).apply { if (!intersect(covered)) setEmpty() }
                val area = safe.width().toLong() * safe.height()
                val hit = overlap.width().toLong() * overlap.height()
                if (area > 0 && hit * 2 < area) continue
            }
            check(!Rect.intersects(safe,covered)) { "输入法、系统面板或其他浮层遮挡目标，控制已停止" }
        }
        return window
    }
    private fun safeBounds(window:Rect):Rect {
        if (backgroundDisplay != null) {
            check(!window.isEmpty) { "无法取得后台屏的应用内容区域" }
            return Rect(window)
        }
        val metrics=getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val safe=Rect(metrics.bounds).apply{left+=insets.left;top+=insets.top;right-=insets.right;bottom-=insets.bottom}
        check(safe.intersect(window) && !safe.isEmpty) { "无法取得安全的应用内容区域" };return safe
    }
    private fun overlayDialog(builder:AlertDialog.Builder, secure:Boolean=false):AlertDialog {
        val value=builder.create();value.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        if (secure) value.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        value.setOnCancelListener{busy=false};dialog=value;value.show();return value
    }
    private fun showPanel() {
        if(panel!=null)return
        val weibo = weiboRun
        val xhs = xhsRun
        val pages = pageRun
        val mika = mikaRun
        val style = if (weibo == null && xhs == null && pages == null && mika == null) null else GuiCollectionStyle(this)
        val layout = style?.card(compact = true) ?: LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(8,8,8,8);setBackgroundColor(0xee18304f.toInt())}
        val status = style?.label("准备采集…", 12, true) ?: TextView(this).apply{
            text=if (collectOnly) "ClawMate · $targetLabel\n本机采集 / 120秒" else "ClawMate · $targetLabel\n120秒 / 最多20次动作"
            setTextColor(-1);textSize=11f
        }
        style?.let { layout.addView(it.badge(if (backgroundDisplay != null) "后台屏 · $targetLabel" else "ClawMate · $targetLabel")) }
        layout.addView(status)
        fun button(label:String,action:()->Unit){layout.addView(Button(this).apply{text=label;textSize=12f;minHeight=0;setOnClickListener{if(!busy)runCatching{currentWindow();action()}.onFailure{stop(it.message ?: "目标应用已变化")}}})}
        if (weibo != null || xhs != null) {
            status.text = "准备采集"
        } else if (pages != null) {
            status.text = "请打开要采集的页面"
            layout.addView(Button(this).apply {
                text = if (pages.plan.prompt == null) "开始读取" else "开始模型读屏"
                textSize = 12f
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    if (sessionWindows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                        status.text = "请收起键盘再开始读取"
                        return@setOnClickListener
                    }
                    runCatching { collectPages(pages.plan.pages, pages.plan.direction, pages, status) }
                        .onFailure { stop(it.message ?: "无法读取页面") }
                }
            })
            layout.addView(Button(this).apply {
                text = "Mika 采集（最多 ${pages.plan.pages} 页）"
                textSize = 12f
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    if (sessionWindows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                        status.text = "请收起键盘再开始读取"
                        return@setOnClickListener
                    }
                    runCatching { collectWithMika(pages.plan.pages, pages.plan.direction, pages, status) }
                        .onFailure { stop(it.message ?: "Mika 无法读取页面") }
                }
            })
        } else if (mika != null) {
            status.text = "正在启动 Mika 手机控制"
            status.maxLines = 2
            val display = backgroundDisplay
            if (display != null) {
                addMikaPreview(layout, checkNotNull(style), display)
            } else {
                layout.addView(requireNotNull(style).action("人工接管") { stop("已交给你操作，Mika 已停止") })
            }
        } else if (collectOnly) {
            button("采集当前页"){collectPages(1)}
            button("采集最多${GUI_COLLECTION_PAGE_LIMIT}页"){collectPages(GUI_COLLECTION_PAGE_LIMIT)}
            button("Mika 采集最多${GUI_COLLECTION_PAGE_LIMIT}页"){collectWithMika(GUI_COLLECTION_PAGE_LIMIT, CollectionDirection.DOWN, null, status)}
        } else {
            button("截图 / 点按"){capturePreview()}
            button("向上滑动"){confirmGesture("向上滑动",null)}
            button("向下滑动"){confirmGesture("向下滑动",null)}
            button("返回"){confirmGesture("返回",null)}
        }
        val stopButton = style?.action(if (mika == null) "停止采集" else "停止控制") {
            stop(if (mika == null) "用户停止采集" else "用户停止手机控制")
        } ?: Button(this).apply{
            text=if (collectOnly) "停止采集" else "停止控制"
            setOnClickListener{if (collectOnly) stop("用户停止采集") else stop()}
        }
        layout.addView(stopButton, LinearLayout.LayoutParams(-1, -2).apply { if (style != null) topMargin = style.dp(8) })
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT)
        params.gravity=Gravity.TOP or Gravity.END;params.x=8;params.y=80
        params.width=minOf((150*resources.displayMetrics.density).toInt(),resources.displayMetrics.widthPixels/3)
        if (style != null) { params.width = minOf(style.dp(172), resources.displayMetrics.widthPixels / 2); params.x = style.dp(12); params.y = style.dp(12) }
        getSystemService(WindowManager::class.java).addView(layout,params);panel=layout
        pages?.advance(pages.plan.read, "已打开目标应用，等待手动定位页面")
        lastStatus=if (collectOnly) "本机采集会话进行中：$targetLabel" else "控制会话进行中：$targetLabel；全部操作由用户逐次确认"
        if (weibo != null && Build.VERSION.SDK_INT >= 34) startWeiboSteps(weibo, status)
        if (xhs != null && Build.VERSION.SDK_INT >= 34) startXhsSteps(xhs, status)
        if (mika != null && Build.VERSION.SDK_INT >= 34) {
            collectionJob = collectionScope.launch {
                mika.execute(MikaScreenPort(this@GuiControlService, mika, checkNotNull(grant).target, targetLabel)) {
                    status.text = it
                }
                if (mikaRun === mika) stop("Mika 手机任务已结束")
            }
        }
    }

    private fun addMikaPreview(layout: LinearLayout, style: GuiCollectionStyle, display: BackgroundDisplay) {
        val source = getSystemService(DisplayManager::class.java).getDisplay(display.id) ?: return
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        source.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return
        val widthLimit = minOf(style.dp(148), resources.displayMetrics.widthPixels / 2 - style.dp(24))
        val scale = minOf(widthLimit.toFloat() / metrics.widthPixels, style.dp(228).toFloat() / metrics.heightPixels)
        if (scale <= 0) return
        val preview = SurfaceView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            holder.setFixedSize(metrics.widthPixels, metrics.heightPixels)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = display.showOn(holder.surface)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = display.showOn(null)
            })
        }
        layout.addView(preview, LinearLayout.LayoutParams(
            (metrics.widthPixels * scale).toInt(), (metrics.heightPixels * scale).toInt(),
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = style.dp(8) })
    }

    internal fun mikaWindow(run: MikaPhoneRun): AccessibilityWindowInfo {
        check(mikaRun === run && panel != null) { "Mika 手机控制会话已结束" }
        return currentWindow(allowInputMethod = true)
    }

    internal fun mikaBounds(window: AccessibilityWindowInfo): Rect = safeBounds(Rect().also(window::getBoundsInScreen))

    internal fun mikaAction(run: MikaPhoneRun, windowId: Int, node: AccessibilityNodeInfo?, action: Int,
        arguments: Bundle? = null,
    ): ActionResult {
        val window = mikaWindow(run)
        if (node == null && backgroundDisplay != null) return ActionResult.Failed("use_visible_back_control")
        if (window.id != windowId || node != null &&
            (!node.refresh() || node.windowId != windowId || node.packageName?.toString() != grant?.target ||
                !node.isVisibleToUser || !node.isEnabled || node.isPassword || node.isAccessibilityDataSensitive)
        ) return ActionResult.Failed("stale_snapshot")
        val session = checkNotNull(grant)
        session.consume(SystemClock.elapsedRealtime(), session.target,
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked, 1)
        val accepted = if (node == null) performGlobalAction(action) else node.performAction(action, arguments)
        return if (accepted) ActionResult.Ok() else ActionResult.Failed("action_rejected")
    }

    /**
     * Mika 看图用的这一屏截图：只截目标窗口（不含浮窗），尺寸与窗口边界一致。
     * 受保护页面、尺寸不符或系统拒绝时返回 null，由核心按「读不到画面」处理，不伪造空白图。
     */
    @RequiresApi(34)
    internal suspend fun mikaScreenshot(run: MikaPhoneRun): ByteArray? = withContext(Dispatchers.Main.immediate) {
        val window = mikaWindow(run)
        val bounds = mikaBounds(window)
        if (bounds.width() <= 0 || bounds.height() <= 0) return@withContext null
        val bytes = CompletableDeferred<ByteArray?>()
        val windowId = window.id
        takeScreenshotOfWindow(windowId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val png = runCatching { encodeWindow(result, bounds, windowId) }.getOrNull()
                if (bytes.isActive) bytes.complete(png)
            }

            override fun onFailure(@Suppress("UNUSED_PARAMETER") errorCode: Int) {
                if (bytes.isActive) bytes.complete(null)
            }
        })
        withTimeoutOrNull(6_000) { bytes.await() }
    }

    private fun encodeWindow(result: ScreenshotResult, bounds: Rect, windowId: Int): ByteArray? {
        val buffer = result.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            try {
                val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                try {
                    if (software.width != bounds.width() || software.height != bounds.height()) return null
                    if (currentWindowIdOrNull() != windowId) return null
                    return encodePng(software)
                } finally {
                    software.recycle()
                }
            } finally {
                hardware.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    private fun currentWindowIdOrNull(): Int? = runCatching { currentWindow().id }.getOrNull()

    private fun encodePng(bitmap: Bitmap): ByteArray? = try {
        java.io.ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Mika 的坐标手势：点是目标窗口内的像素（与读屏快照同一坐标系），这里加上窗口原点并夹在可操作区域内。
     * 与手动控制共用会话额度（一次手势算一次动作）；窗口变了、点被浮窗盖住或不在内容区域时不执行。
     */
    @RequiresApi(34)
    internal suspend fun mikaGesture(run: MikaPhoneRun, expectedWindow: Int?, paths: List<List<Pair<Int, Int>>>,
        durationMs: Long,
    ): ActionResult = withContext(Dispatchers.Main.immediate) {
        val window = mikaWindow(run)
        if (expectedWindow != null && window.id != expectedWindow) return@withContext ActionResult.Failed("stale_snapshot")
        val bounds = mikaBounds(window)
        if (bounds.width() <= 0 || bounds.height() <= 0) return@withContext ActionResult.Failed("no_window")
        val safe = safeBounds(bounds)
        val covered = Rect()
        if (panel != null && window.displayId == android.view.Display.DEFAULT_DISPLAY) {
            val position = IntArray(2)
            panel?.getLocationOnScreen(position)
            covered.set(position[0], position[1], position[0] + (panel?.width ?: 0), position[1] + (panel?.height ?: 0))
        }
        val strokes = ArrayList<GestureDescription.StrokeDescription>(paths.size)
        for (points in paths) {
            if (points.size < 2 || points.size > 32) return@withContext ActionResult.Failed("invalid_gesture")
            val pixels = points.map { (rawX, rawY) ->
                val x = (bounds.left + rawX).coerceIn(bounds.left, bounds.right - 1)
                val y = (bounds.top + rawY).coerceIn(bounds.top, bounds.bottom - 1)
                if (!safe.contains(x, y)) return@withContext ActionResult.Failed("outside_app_area")
                if (!covered.isEmpty && covered.contains(x, y)) return@withContext ActionResult.Failed("covered_by_panel")
                x.toFloat() to y.toFloat()
            }
            val path = Path().apply {
                moveTo(pixels.first().first, pixels.first().second)
                if (pixels.size == 2 && pixels[0] == pixels[1]) {
                    // 单点点按也要有一段路径，系统才认这是手势。
                    lineTo(pixels.first().first + 0.5f, pixels.first().second + 0.5f)
                } else {
                    pixels.drop(1).forEach { (x, y) -> lineTo(x, y) }
                }
            }
            strokes += GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(40, 5_000))
        }
        if (strokes.isEmpty()) return@withContext ActionResult.Failed("invalid_gesture")
        val session = checkNotNull(grant)
        session.consume(SystemClock.elapsedRealtime(), session.target,
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked, 1)
        val finished = CompletableDeferred<Boolean>()
        val gesture = GestureDescription.Builder().also { builder -> strokes.forEach(builder::addStroke) }.build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (finished.isActive) finished.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (finished.isActive) finished.complete(false)
            }
        }, null)
        if (!accepted) return@withContext ActionResult.Failed("gesture_rejected")
        when (withTimeoutOrNull(durationMs.coerceIn(40, 5_000) + 3_000) { finished.await() }) {
            true -> ActionResult.Ok()
            false -> ActionResult.Failed("gesture_cancelled")
            null -> ActionResult.Failed("gesture_timeout")
        }
    }

    private fun requireTargetOffMainScreen(target: String) {
        check(windows.none { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == target }) {
            "目标应用正在主屏使用，请先离开该应用"
        }
    }

    fun xhsModelWindowReady(): Boolean = xhsRun != null && panel != null && runCatching {
        currentWindow().root?.packageName?.toString() == XhsGuiWorkflow.PACKAGE
    }.getOrDefault(false)

    fun screenModelWindowReady(): Boolean {
        val (id, bounds) = pageRun?.modelWindow ?: return false
        return panel != null && runCatching { collectionWindow(id, bounds); true }.getOrDefault(false)
    }

    @RequiresApi(34)
    private fun startXhsSteps(run: XhsGuiRun, status: TextView) {
        val turn = epoch
        val job = collectionScope.launch(start = CoroutineStart.LAZY) {
            try {
                fun observe(): Triple<Int, Rect, XhsPublicPage>? {
                    clearCache()
                    val window = currentWindow()
                    val bounds = Rect().also(window::getBoundsInScreen)
                    val root = window.root ?: return null
                    val page = XhsGuiWorkflow.page(root, safeBounds(bounds), run.keyword) ?: return null
                    return Triple(window.id, bounds, page)
                }
                var page = withTimeoutOrNull(10_000) {
                    var seen = observe()
                    while (seen == null || seen.third.excerpts.isEmpty()) {
                        delay(300)
                        seen = observe()
                    }
                    seen
                } ?: error("未识别到小红书公开搜索结果，请检查登录状态、网络或客户端版本")
                val excerpts = linkedSetOf<String>()
                var pages = 0
                var scrolls = 0
                repeat(8) {
                    val (id, bounds, visible) = page
                    val choices = linkedSetOf<String>()
                    if (pages < XhsGuiWorkflow.MAX_PAGES && excerpts.size < XhsGuiWorkflow.MAX_ENTRIES &&
                        visible.excerpts.any { it !in excerpts }) choices += "read_visible"
                    if (visible.list.isScrollable && scrolls < XhsGuiWorkflow.MAX_PAGES - 1 && excerpts.size < XhsGuiWorkflow.MAX_ENTRIES) {
                        choices += "scroll_forward"
                    }
                    if (excerpts.isNotEmpty()) choices += "finish"
                    check(choices.isNotEmpty()) { "没有可读取的小红书公开笔记" }
                    status.text = "等待模型选择操作\n已读 $pages 页 · ${excerpts.size} 条"
                    val request = XhsGuiWorkflow.request(run.prompt, choices, pages, excerpts.size)
                    val answer = withTimeoutOrNull(22_000) { run.model.evaluate(request) }
                        ?: error("模型选择操作超时")
                    val action = XhsGuiWorkflow.choice(answer, choices)
                    val fresh = checkNotNull(observe()) { "小红书已离开当前公开搜索页" }
                    check(fresh.first == id && fresh.second == bounds && fresh.third.excerpts == visible.excerpts) {
                        "模型决策期间页面已变化，请重新采集"
                    }
                    when (action) {
                        "read_visible" -> {
                            excerpts.addAll(fresh.third.excerpts.filterNot(excerpts::contains).take(XhsGuiWorkflow.MAX_ENTRIES - excerpts.size))
                            pages++
                        }
                        "scroll_forward" -> {
                            status.text = "正在翻页\n已读 $pages 页 · ${excerpts.size} 条"
                            val list = fresh.third.list
                            check(performCollectionAction(list, id, bounds, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                                it.isScrollable
                            }) {
                                "搜索结果无法继续翻页"
                            }
                            scrolls++
                            delay(700)
                        }
                        "finish" -> {
                            val preview = CompletableDeferred<Boolean>()
                            val record = GuiCollection(XhsGuiWorkflow.PACKAGE, "${XhsGuiWorkflow.LABEL} · ${run.keyword}",
                                System.currentTimeMillis(), excerpts.toList(), partial = true, pages = pages)
                            status.text = "等待核对与保存"
                            showCollectionPreview(record, "搜索关键词：${run.keyword}", turn, id, bounds,
                                brandedTitle = "小红书公开笔记预览") { preview.complete(it) }
                            if (preview.await()) run.finish(RunStatus.SUCCESS, "已保存 ${excerpts.size} 条小红书公开笔记摘录")
                            else run.finish(RunStatus.CANCELLED, "已取消预览，未保存小红书记录")
                            stop("小红书 AI 采集已结束")
                            return@launch
                        }
                    }
                    page = checkNotNull(observe()) { "小红书已离开当前公开搜索页" }
                }
                error("达到模型决策轮数上限")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (turn == epoch) {
                    val message = failure.message ?: "小红书 AI 采集失败"
                    run.finish(RunStatus.FAILED, message)
                    stop(message)
                }
            }
        }
        collectionJob = job
        job.start()
    }

    @RequiresApi(34)
    private fun startWeiboSteps(run: WeiboGuiRun, status: TextView) {
        val turn = epoch
        val window = currentWindow()
        val bounds = Rect().also(window::getBoundsInScreen)
        val id = window.id
        val area = safeBounds(bounds)
        val job = collectionScope.launch(start = CoroutineStart.LAZY) {
            var record: GuiCollection? = null
            try {
                for (step in run.steps) {
                    run.current = step
                    status.text = when (step.action) {
                        WeiboGuiWorkflow.OPEN -> "正在打开微博榜单"
                        WeiboGuiWorkflow.SELECT -> "正在切换到公开热搜"
                        WeiboGuiWorkflow.READ -> "正在读取公开主榜"
                        WeiboGuiWorkflow.PREVIEW -> "等待核对与保存"
                        else -> "等待页面加载"
                    }
                    collectionWindow(id, bounds)
                    val note = when (step.action) {
                        WeiboGuiWorkflow.OPEN -> {
                            awaitHotTab(id, bounds, area)
                            "已打开微博榜单"
                        }
                        WeiboGuiWorkflow.SELECT -> {
                            selectWeiboHotTab(id, bounds, area)
                            awaitHotTab(id, bounds, area, selected = true)
                            "已进入公开热搜标签"
                        }
                        WeiboGuiWorkflow.READ -> {
                            val collected = readWeiboBoard(id, bounds, area, run.limit, status)
                            record = collected
                            "读取 ${collected.lines.size} 条微博热搜"
                        }
                        WeiboGuiWorkflow.PREVIEW -> {
                            val preview = CompletableDeferred<Boolean>()
                            showCollectionPreview(checkNotNull(record), "热度按微博显示保存。缺失或有冲突的名次留空。",
                                turn, id, bounds) { preview.complete(it) }
                            if (!preview.await()) {
                                run.finish(RunStatus.CANCELLED, "已取消预览，未保存微博采集记录")
                                stop("已取消微博采集")
                                return@launch
                            }
                            "已确认并保存到本机"
                        }
                        else -> {
                            delay(checkNotNull(step.seconds) * 1000)
                            "等待完成"
                        }
                    }
                    run.record(step, note)
                }
                val saved = checkNotNull(record)
                val coverage = if (saved.partial) "部分榜单" else "完整主榜"
                run.finish(RunStatus.SUCCESS, "已保存 $coverage · ${saved.lines.size} 条微博热搜，见本机采集记录")
                stop("微博热搜已保存到本机")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (turn == epoch) {
                    val message = failure.message ?: "微博热搜工作流失败"
                    run.finish(RunStatus.FAILED, message)
                    stop(message)
                }
            }
        }
        collectionJob = job
        job.start()
    }

    @RequiresApi(34)
    private suspend fun readWeiboBoard(id: Int, bounds: Rect, area: Rect, limit: Int, status: TextView): GuiCollection {
        val capturedAt = System.currentTimeMillis()
        val timezone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(capturedAt).atZone(timezone).toLocalDate().toString()
        val board = WeiboBoardAccumulator()
        val deadline = SystemClock.elapsedRealtime() + 65_000
        var pages = 0
        var reason = if (limit == 0) "达到翻页上限" else "按工作流设置读取部分条目"
        val reader = withContext(Dispatchers.IO) { runCatching { WeiboRankReader() }.getOrNull() }
        try {
            while (pages < 14 && SystemClock.elapsedRealtime() < deadline) {
                status.text = "正在扫描第 ${pages + 1} 页\n已读取 ${board.size} 条"
                val page = readStableWeiboPage(id, bounds, area, reader)
                collectionWindow(id, bounds)
                if ((page == null || page.second.isEmpty()) && board.size == 0 && SystemClock.elapsedRealtime() < deadline - 55_000) continue
                if (page == null) {
                    reason = "无法识别公开热搜列表"
                    break
                }
                pages++
                val advanced = board.append(if (limit == 0) page.second else page.second.take(limit))
                if (limit != 0) break
                if (board.reachedLastRank || page.first.sectionEnded) {
                    reason = "已到主榜边界，部分名次未能核对"
                    break
                }
                if (!advanced) {
                    reason = "翻页后未发现新词条"
                    break
                }
                val list = page.first.list ?: break
                if (!scrollWeibo(id, bounds, list)) {
                    reason = "微博未接受继续滚动"
                    break
                }
                delay(650)
            }
        } finally { reader?.close() }
        if (SystemClock.elapsedRealtime() >= deadline) reason = "达到扫描时限"
        val complete = limit == 0 && board.complete
        val entries = board.entries().let { rows -> if (complete) rows.filter { it.rank != null }.sortedBy { it.rank } else rows }
        check(entries.isNotEmpty()) { "未读到公开热搜词条，请检查微博页面或网络" }
        val snapshot = WeiboSnapshot(date, timezone.id, entries, complete, if (complete) "已核对全部主榜名次" else reason)
        return GuiCollection(WeiboGuiWorkflow.PACKAGE, "微博热搜", capturedAt, entries.map(WeiboTopic::display),
            partial = !complete, pages = pages.coerceAtLeast(1), weibo = snapshot)
    }

    @RequiresApi(34)
    private suspend fun readStableWeiboPage(id: Int, bounds: Rect, area: Rect, reader: WeiboRankReader?):
        Pair<WeiboVisiblePage, List<WeiboTopic>>? {
        suspend fun readTree(): WeiboVisiblePage? {
            clearCache()
            val root = checkNotNull(collectionWindow(id, bounds).root) { "无法读取微博榜单" }
            return withContext(Dispatchers.IO) { WeiboGuiWorkflow.read(root, area) }
        }
        repeat(3) {
            val before = readTree()
            if (before == null || before.rows.isEmpty()) {
                delay(250)
                return@repeat
            }
            val frame = captureCollectionFrame(id, bounds)
            try {
                val after = readTree()
                if (before == after) return withContext(Dispatchers.IO) {
                    val ranks = runCatching { reader?.ranks(frame, bounds, before.rows).orEmpty() }.getOrDefault(emptyMap())
                    before to before.rows.mapIndexed { index, row -> WeiboTopic(row.title, ranks[index], row.heat) }
                }
            } finally { frame.recycle() }
        }
        return null
    }

    @RequiresApi(34)
    private suspend fun scrollWeibo(id: Int, bounds: Rect, list: Rect): Boolean {
        val height = list.height()
        if (height < 200) return false
        val start = list.bottom - height * .22f
        val end = list.top + height * .34f
        val stroke = Path().apply { moveTo(list.centerX().toFloat(), start); lineTo(list.centerX().toFloat(), end) }
        return dispatchCollectionGesture(id, bounds, stroke, 830)
    }

    @RequiresApi(34)
    private suspend fun selectWeiboHotTab(id: Int, bounds: Rect, area: Rect) {
        val tab = awaitHotTab(id, bounds, area)
        if (tab.isSelected) return
        val button = WeiboGuiWorkflow.hotTabButton(tab, area)
        if (button != null && performCollectionAction(button, id, bounds, AccessibilityNodeInfo.ACTION_CLICK)) return

        // Some displays expose the label without its clickable ancestor.
        val fresh = awaitHotTab(id, bounds, area)
        if (fresh.isSelected) return
        val target = checkNotNull(WeiboGuiWorkflow.hotTabBounds(fresh, area)) { "无法识别热搜标签位置" }
        val tap = Path().apply { moveTo(target.exactCenterX(), target.exactCenterY()) }
        check(dispatchCollectionGesture(id, bounds, tap, 70)) { "微博未接受热搜标签操作" }
    }

    @RequiresApi(34)
    private suspend fun dispatchCollectionGesture(id: Int, bounds: Rect, stroke: Path, duration: Long): Boolean {
        val display = authorizeCollectionAction(id, bounds).displayId
        return suspendCancellableCoroutine { ready ->
            val gesture = GestureDescription.Builder().setDisplayId(display)
                .addStroke(GestureDescription.StrokeDescription(stroke, 0, duration)).build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) { if (ready.isActive) ready.resume(true) }
                override fun onCancelled(description: GestureDescription?) { if (ready.isActive) ready.resume(false) }
            }
            if (!dispatchGesture(gesture, callback, handler) && ready.isActive) ready.resume(false)
        }
    }

    @RequiresApi(34)
    private suspend fun awaitHotTab(id: Int, bounds: Rect, area: Rect, selected: Boolean = false): AccessibilityNodeInfo {
        repeat(25) {
            clearCache()
            val root = collectionWindow(id, bounds).root
            val tab = root?.let { WeiboGuiWorkflow.hotTab(it, area) }
            if (tab != null && (!selected || tab.isSelected)) return tab
            delay(350)
        }
        error("无法识别公开热搜标签，请检查微博版本或关闭弹窗后重试")
    }
    private fun collectPages(
        maxPages: Int,
        direction: CollectionDirection = CollectionDirection.DOWN,
        run: PageGuiRun? = null,
        status: TextView? = null,
    ) {
        check(Build.VERSION.SDK_INT >= 34 && collectOnly)
        val window = currentWindow()
        val windowId = window.id
        val bounds = Rect().also(window::getBoundsInScreen)
        val target = checkNotNull(grant).target
        val label = targetLabel
        val turn = epoch
        busy = true
        status?.text = "正在读取…"
        val job = collectionScope.launch(start = CoroutineStart.LAZY) {
            try {
                val (record, end) = if (run?.plan?.prompt != null) {
                    readCollectionWithModel(run, windowId, bounds, target, label, status)
                } else {
                    withTimeout(15_000) {
                        readCollection(windowId, bounds, target, label, maxPages, direction) { pages ->
                            status?.text = "已读 $pages 页"
                        }
                    }
                }
                if (turn != epoch) return@launch
                if (record.lines.isEmpty()) {
                    busy = false
                    lastStatus = if (run?.plan?.prompt != null) "模型未从截图中提取到符合提示词的文字" else "当前页没有可采集的无障碍文字"
                    status?.text = "$lastStatus，请换一页"
                    Toast.makeText(this@GuiControlService, lastStatus, Toast.LENGTH_LONG).show()
                    return@launch
                }
                run?.readyToPreview("已读取 ${record.pages} 页、${record.lines.size} 条可见文字")
                val title = if (run?.plan?.prompt != null) "模型采集预览" else null
                showCollectionPreview(record, end, turn, windowId, bounds, brandedTitle = title) { saved ->
                    if (run != null && turn == epoch) {
                        val message = if (saved) "已保存 ${record.lines.size} 条文字到本机采集记录" else "已取消预览，未保存采集内容"
                        run.finish(if (saved) RunStatus.SUCCESS else RunStatus.CANCELLED, message)
                        stop(message)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (turn == epoch) stop("采集超时，未保存采集内容")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (turn == epoch) stop(failure.message ?: "采集已停止，请在目标页面重试")
            } finally {
                if (turn == epoch) collectionJob = null
            }
        }
        collectionJob = job
        job.start()
    }

    /**
     * 任务页的本机采集端口：读当前会话窗口，只开放读屏与滚动。
     * 采集不点、不输入、不返回，避免“采集”变成看不见的界面操作。
     */
    @RequiresApi(34)
    private inner class CollectionScreenPort(private val target: String) : ScreenPort {
        private var sequence = 0
        private var previous: MikaFrame? = null

        private fun readFrame(): MikaFrame {
            clearCache()
            val window = currentWindow(allowInputMethod = true)
            val bounds = Rect().also(window::getBoundsInScreen)
            val root = checkNotNull(window.root) { "无法读取目标窗口" }
            return readMikaFrame(root, bounds, target, "p" + ++sequence)
        }

        override suspend fun snapshot() = readFrame().also { previous = it }.screen
        override suspend fun peek() = readFrame().screen
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun listApps() = listOf(target to targetLabel)

        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
            val frame = previous ?: readFrame()
            if (snapshotId != null && snapshotId != frame.screen.snapshotId) return ActionResult.Failed("stale_snapshot")
            val scrollable = frame.nodes.filter { it.description.scrollable }
            val node = (if (ref != null) scrollable.firstOrNull { it.description.ref == ref } else scrollable.firstOrNull())
                ?: return ActionResult.Failed("no_scrollable")
            val window = currentWindow(allowInputMethod = true)
            if (window.id != frame.window) return ActionResult.Failed("stale_snapshot")
            val bounds = Rect().also(window::getBoundsInScreen)
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            return if (performCollectionAction(node.node, window.id, bounds, action)) ActionResult.Ok()
            else ActionResult.Failed("scroll_rejected")
        }

        override suspend fun waitStable(timeoutMs: Long): Boolean {
            val first = peek()
            delay(timeoutMs.coerceIn(0, 260))
            return first.elements == peek().elements
        }

        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Failed("collect_only")
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("collect_only")
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Failed("collect_only")
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("collect_only")
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("collect_only")
        override suspend fun back() = ActionResult.Failed("collect_only")
        override suspend fun pressKey(keyCode: Int) = ActionResult.Failed("collect_only")
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("collect_only")
    }

    /**
     * 用 Mika 内核的 `phone_collect` 读取当前页及后续页：
     * 同一套工具、同一套去重与停止条件，结果先在本机预览，确认后写入本机采集记录。
     */
    private fun collectWithMika(maxPages: Int, direction: CollectionDirection, run: PageGuiRun?, status: TextView?) {
        check(Build.VERSION.SDK_INT >= 34 && collectOnly)
        val window = currentWindow()
        val windowId = window.id
        val bounds = Rect().also(window::getBoundsInScreen)
        val target = checkNotNull(grant).target
        val label = targetLabel
        val turn = epoch
        busy = true
        status?.text = "Mika 正在读取…"
        val job = collectionScope.launch(start = CoroutineStart.LAZY) {
            try {
                val collected = withTimeout(45_000) {
                    toolDispatcher(CollectionScreenPort(target)).collectPages(
                        direction = direction.name.lowercase(),
                        pages = maxPages,
                        maxItems = 300,
                    )
                }
                if (turn != epoch) return@launch
                val lines = MikaCollect.lines(collected.text)
                if (lines.isEmpty()) {
                    busy = false
                    lastStatus = "Mika 没有读到可采集的文字（${collected.stoppedBecause}）"
                    status?.text = lastStatus
                    Toast.makeText(this@GuiControlService, lastStatus, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val partial = collected.truncated || collected.stoppedBecause !in setOf("page_limit", "item_limit")
                val record = GuiCollection(
                    target, "$label · Mika", System.currentTimeMillis(), lines,
                    partial = partial, pages = collected.pages.coerceAtLeast(1),
                )
                status?.text = "Mika 已读 ${record.pages} 页 · ${lines.size} 条"
                run?.readyToPreview("已读 ${record.pages} 页、${lines.size} 条")
                showCollectionPreview(record, "Mika 采集 · ${collected.stoppedBecause}", turn, windowId, bounds) { saved ->
                    if (run != null && turn == epoch) {
                        run.finish(if (saved) RunStatus.SUCCESS else RunStatus.CANCELLED,
                            if (saved) "已保存 ${lines.size} 条到本机（Mika）" else "已取消预览，未保存采集内容")
                        stop(if (saved) "Mika 采集已结束" else "已取消 Mika 采集")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (turn == epoch) {
                    stop("Mika 采集超时，未保存采集内容")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (turn == epoch) {
                    stop(failure.message ?: "Mika 采集已停止，请在目标页面重试")
                }
            } finally {
                if (turn == epoch) {
                    collectionJob = null
                    busy = false
                }
            }
        }
        collectionJob = job
        job.start()
    }

    @RequiresApi(34)
    private suspend fun readCollectionWithModel(
        run: PageGuiRun, id: Int, bounds: Rect, target: String, label: String, status: TextView?,
    ): Pair<GuiCollection, String> {
        val model = checkNotNull(run.model)
        val prompt = checkNotNull(run.plan.prompt)
        val direction = run.plan.direction
        val original = GuiCollection(target, label, System.currentTimeMillis(), emptyList(), partial = true)
        val accumulator = GuiCollectionAccumulator()
        for (pageNumber in 1..run.plan.pages) {
            status?.text = "模型正在读取第 $pageNumber 页"
            val image = screenModelImage(id, bounds)
            val page = inspectCollectionPage(id, bounds, target, label, direction)
            val scrollNode = page.scrollNode
            val canContinue = scrollNode != null && pageNumber < run.plan.pages
            val request = GuiScreenModel.request(prompt, image, pageNumber, accumulator.entryCount, canContinue)
            run.modelWindow = id to Rect(bounds)
            val raw = try {
                withTimeoutOrNull(22_000) { model.evaluate(request) } ?: error("等待模型读屏超时")
            } finally {
                run.modelWindow = null
            }
            collectionWindow(id, bounds)
            val answer = GuiScreenModel.decision(raw, canContinue)
            accumulator.append(original.copy(lines = answer.lines, partial = false))
            val record = accumulator.record(original, incomplete = true).copy(pages = pageNumber)
            if (answer.action == "finish" || accumulator.truncated || !accumulator.hasCapacity) {
                return record to "请核对模型从截图中提取的文字。"
            }
            check(screenModelImage(id, bounds) == image) { "等待模型时画面已变化，请重新开始读屏" }
            check(scrollCollection(checkNotNull(scrollNode), id, bounds, target, direction)) { "无法继续滚动目标页面" }
            delay(650)
        }
        error("已达到模型读屏页数上限")
    }

    @RequiresApi(34)
    private suspend fun screenModelImage(id: Int, bounds: Rect): String {
        val area = safeBounds(bounds).apply { offset(-bounds.left, -bounds.top) }
        val frame = captureCollectionFrame(id, bounds)
        return try {
            withContext(Dispatchers.IO) { GuiScreenModel.encode(frame, area) }
        } finally {
            frame.recycle()
        }
    }

    @RequiresApi(34)
    private suspend fun readCollection(
        windowId: Int, bounds: Rect, target: String, label: String, maxPages: Int,
        direction: CollectionDirection, onPage: (Int) -> Unit,
    ): Pair<GuiCollection, String?> {
        var page = readCollectionPage(windowId, bounds, target, label, direction.takeIf { maxPages > 1 })
        val first = page.record
        if (first.lines.isNotEmpty()) onPage(1)
        if (maxPages == 1 || first.lines.isEmpty()) return first to null

        val accumulator = GuiCollectionAccumulator()
        accumulator.append(first)
        val initialScrollNode = page.scrollNode
        var end = "已达到本次采集的 $maxPages 页上限"
        while (accumulator.pages < maxPages) {
            if (accumulator.truncated || !accumulator.hasCapacity) {
                end = "已达到本次文字读取上限"
                break
            }
            val node = page.scrollNode
            if (node == null || node != initialScrollNode || !scrollCollection(node, windowId, bounds, target, direction)) {
                end = "没有可继续滚动的唯一目标区域"
                break
            }
            delay(1_050)
            page = readCollectionPage(windowId, bounds, target, label, direction)
            if (!accumulator.append(page.record)) {
                end = if (accumulator.truncated) "已达到本次文字读取上限" else "翻页后没有新的可采集文字"
                break
            }
            onPage(accumulator.pages)
        }
        return accumulator.record(first, incomplete = true) to end
    }

    @RequiresApi(34)
    private suspend fun readCollectionPage(
        windowId: Int, bounds: Rect, target: String, label: String, scroll: CollectionDirection?,
    ): GuiCollectionPage {
        checkCollectionCapture(windowId, bounds)
        return inspectCollectionPage(windowId, bounds, target, label, scroll)
    }

    @RequiresApi(34)
    private suspend fun inspectCollectionPage(
        windowId: Int, bounds: Rect, target: String, label: String, scroll: CollectionDirection?,
    ): GuiCollectionPage {
        // A scroll may change only child nodes while leaving the active window ID unchanged.
        clearCache()
        val root = checkNotNull(collectionWindow(windowId, bounds).root) { "无法读取目标页面" }
        val area = safeBounds(bounds)
        val page = withContext(Dispatchers.IO) {
            GuiCollection.read(this, root, area, target, label, scroll)
        }
        collectionWindow(windowId, bounds)
        return page
    }

    @RequiresApi(34)
    private suspend fun checkCollectionCapture(windowId: Int, bounds: Rect) {
        captureCollectionFrame(windowId, bounds).recycle()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresApi(34)
    private suspend fun captureCollectionFrame(windowId: Int, bounds: Rect): Bitmap {
        delay((captureAt + 1_050 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        collectionWindow(windowId, bounds)
        return suspendCancellableCoroutine { ready ->
            screenshot { image, capturedBounds, capturedWindow ->
                if (capturedWindow != windowId || capturedBounds != bounds) {
                    image.recycle()
                    error("采集前窗口已变化")
                }
                if (ready.isActive) ready.resume(image) { image.recycle() } else image.recycle()
            }
        }
    }

    private fun collectionWindow(windowId: Int, bounds: Rect): AccessibilityWindowInfo =
        currentWindow().also { window ->
            check(window.id == windowId && Rect().also(window::getBoundsInScreen) == bounds) {
                "采集期间窗口已变化，请重新采集"
            }
        }

    private fun authorizeCollectionAction(windowId: Int, bounds: Rect): AccessibilityWindowInfo {
        val window = collectionWindow(windowId, bounds)
        val session = checkNotNull(grant)
        session.consume(SystemClock.elapsedRealtime(), session.target,
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked, 1)
        return window
    }

    @RequiresApi(34)
    private fun performCollectionAction(
        node: AccessibilityNodeInfo, windowId: Int, bounds: Rect, action: Int,
        eligible: (AccessibilityNodeInfo) -> Boolean = { true },
    ): Boolean {
        if (node.windowId != windowId || !node.refresh() || !node.isVisibleToUser || !node.isEnabled ||
            node.isPassword || node.isAccessibilityDataSensitive ||
            node.packageName?.toString() != grant?.target || !eligible(node)) return false
        authorizeCollectionAction(windowId, bounds)
        return node.performAction(action)
    }

    @RequiresApi(34)
    private fun scrollCollection(
        node: AccessibilityNodeInfo, windowId: Int, bounds: Rect, target: String, direction: CollectionDirection,
    ): Boolean {
        collectionWindow(windowId, bounds)
        return performCollectionAction(node, windowId, bounds, direction.action.id) {
            it.canScrollCollection(target, safeBounds(bounds), direction)
        }
    }

    private fun showCollectionPreview(record: GuiCollection, end: String?, turn: Int, windowId: Int, bounds: Rect,
        brandedTitle: String? = if (record.weibo == null) null else "微博热搜预览",
        onDismiss: (Boolean) -> Unit = {},
    ) {
        var saved = false
        val message = listOfNotNull(
            record.description(), end, "保存后仅存于本机，可在采集记录中查看或删除。",
        ).joinToString("\n")
        val save = {
            if (turn == epoch) runCatching {
                collectionWindow(windowId, bounds)
                GuiCollectionHistory.save(this, record)
                saved = true
                lastStatus = "已保存到本机采集记录：${record.sourceLabel}"
                Toast.makeText(this, lastStatus, Toast.LENGTH_LONG).show()
            }.onFailure { stop(it.message ?: "未保存采集记录，请重新采集") }
            Unit
        }
        val previewDialog = if (brandedTitle != null) {
            val style = GuiCollectionStyle(this)
            style.sheet(brandedTitle, message, "保存到本机", content = style.recordContent(record),
                overlay = true, secure = true, confirm = save).also { dialog = it }
        } else {
            overlayDialog(AlertDialog.Builder(this).setTitle("采集预览：${record.sourceLabel}")
                .setMessage(message).setView(record.preview(this)).setNegativeButton("取消", null)
                .setPositiveButton("保存到本机") { _, _ -> save() }, secure = true)
        }
        previewDialog.setOnDismissListener {
            if (dialog === previewDialog) dialog = null
            if (turn == epoch) busy = false
            onDismiss(saved)
        }
    }
    private fun screenshot(receive:(Bitmap,Rect,Int)->Unit) {
        check(Build.VERSION.SDK_INT>=34)
        check(SystemClock.elapsedRealtime()-captureAt>=1000) { "截图过于频繁，请稍后重试" }
        captureAt=SystemClock.elapsedRealtime()
        val window=currentWindow();val bounds=Rect().also{window.getBoundsInScreen(it)};val turn=epoch;val windowId=window.id
        check(bounds.width()>0 && bounds.height()>0 && bounds.width().toLong()*bounds.height()<=8000000)
        takeScreenshotOfWindow(windowId,mainExecutor,object:TakeScreenshotCallback {
            override fun onSuccess(result:ScreenshotResult) {
                var image:Bitmap?=null
                try {
                    val buffer=result.hardwareBuffer
                    try{val hardware=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace) ?: error("截图内容不可读");try{image=hardware.copy(Bitmap.Config.ARGB_8888,false)}finally{hardware.recycle()}}finally{buffer.close()}
                    check(turn==epoch && currentWindow().id==windowId) { "截图期间窗口已变化" }
                    val latest=Rect().also{currentWindow().getBoundsInScreen(it)}
                    check(latest==bounds && image!!.width==bounds.width() && image!!.height==bounds.height()) { "窗口尺寸已变化或坐标不匹配" }
                    receive(checkNotNull(image),bounds,windowId);image=null
                }catch(failure:Exception){if(turn==epoch)stop(failure.message ?: "截图失败，控制已停止")}
                finally{image?.recycle()}
            }
            override fun onFailure(errorCode:Int){if(turn==epoch)stop("窗口截图被系统拒绝（$errorCode），请检查是否为受保护页面")}
        })
    }
    private fun fingerprint(bitmap:Bitmap):String {
        val digest=MessageDigest.getInstance("SHA-256");val row=IntArray(bitmap.width);val bytes=ByteArray(bitmap.width*4)
        for(y in 0 until bitmap.height){bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            row.forEachIndexed{x,pixel->for(i in 0..3)bytes[x*4+i]=(pixel ushr (i*8)).toByte()};digest.update(bytes)}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun capturePreview() {
        busy=true
        screenshot { image,bounds,_ ->
            val originalHash=fingerprint(image)
            preview?.recycle();preview=image
            val scale=minOf(1.0,1280.0/maxOf(image.width,image.height))
            val display=Bitmap.createScaledBitmap(image,maxOf(1,(image.width*scale).toInt()),maxOf(1,(image.height*scale).toInt()),true)
            val view=ImageView(this).apply{setImageBitmap(display);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;minimumHeight=300;contentDescription="跨应用本机截图预览"}
            val dialogValue=overlayDialog(AlertDialog.Builder(this).setTitle("本机截图：$targetLabel").setMessage("截图不上传、不保存。可点按预览选择位置，再逐次确认。")
                .setView(view).setNegativeButton("关闭"){_,_->busy=false})
            dialogValue.setOnDismissListener{if(display!==image && !display.isRecycled)display.recycle();if(preview===image){preview=null;if(!image.isRecycled)image.recycle()};busy=false}
            view.setOnTouchListener { v,event ->
                if(event.action==MotionEvent.ACTION_UP) {
                    runCatching {
                        val point=GuiControlSession.mapPoint(event.x,event.y,v.width,v.height,image.width,image.height)
                        val click=point.first+bounds.left to point.second+bounds.top
                        val turn=epoch;dialogValue.dismiss();busy=true
                        handler.postDelayed({if(turn==epoch)runCatching{confirmGesture("点按 (${click.first.toInt()}, ${click.second.toInt()})",click,originalHash)}.onFailure{stop(it.message ?: "点按已取消")}},1100)
                    }.onFailure{android.widget.Toast.makeText(this,it.message ?: "请选择截图中的位置",android.widget.Toast.LENGTH_SHORT).show()}
                };true
            }
        }
    }
    private fun confirmGesture(action:String,point:Pair<Float,Float>?,expected:String?=null) {
        busy=true
        screenshot { image,bounds,windowId ->
            val hash=fingerprint(image);image.recycle();val turn=epoch
            if(expected!=null && hash!=expected){stop("预览后画面已变化，未执行点按；请重新开启会话");return@screenshot}
            overlayDialog(AlertDialog.Builder(this).setTitle("确认本次操作？").setMessage("仅对 $targetLabel 执行：$action。请确认当前页面和位置。切换应用或画面变化将拒绝执行。")
                .setNegativeButton("取消"){_,_->busy=false}.setPositiveButton("确认执行"){_,_->
                    // Allow screenshot interval to elapse; never reuse a confirmation
                    // after a session/window change. The fresh image excludes overlays.
                    handler.postDelayed({
                        if(turn!=epoch)return@postDelayed
                        runCatching { screenshot freshShot@ { fresh,current,id ->
                            val matches=id==windowId && current==bounds && fingerprint(fresh)==hash;fresh.recycle()
                            if(!matches){stop("确认期间画面已变化，未执行动作；请重新开启会话");return@freshShot}
                            dispatch(action,point,bounds)
                        }}.onFailure{stop(it.message ?: "操作已取消")}
                    },1100)
                })
        }
    }
    private fun dispatch(action:String,point:Pair<Float,Float>?,bounds:Rect) {
        val window=currentWindow();val current=Rect().also{window.getBoundsInScreen(it)};check(current==bounds)
        val safe=safeBounds(bounds)
        val session=checkNotNull(grant)
        if(action=="返回"){session.consume(SystemClock.elapsedRealtime(),session.target,true,1);check(performGlobalAction(GLOBAL_ACTION_BACK)){"系统拒绝返回"};busy=false;lastStatus="已请求返回；不代表业务任务成功";return}
        val overlay=Rect();panel?.let{val position=IntArray(2);it.getLocationOnScreen(position);overlay.set(position[0],position[1],position[0]+it.width,position[1]+it.height)}
        val path=Path()
        if(point!=null){check(safe.contains(point.first.toInt(),point.second.toInt())){"目标位置不在应用内容区域，未执行动作"};check(!overlay.contains(point.first.toInt(),point.second.toInt())){"目标位置被控制面板遮挡，未执行动作"};path.moveTo(point.first,point.second)}
        else {val x=bounds.exactCenterX();val top=bounds.top+bounds.height()*0.3f;val bottom=bounds.top+bounds.height()*0.7f
            check(safe.contains(x.toInt(),top.toInt()) && safe.contains(x.toInt(),bottom.toInt())){"滑动路径不在应用内容区域"}
            check(!(x>=overlay.left && x<overlay.right && top<overlay.bottom && bottom>overlay.top)){"滑动路径被控制面板遮挡，未执行动作"}
            if(action=="向上滑动"){path.moveTo(x,bottom);path.lineTo(x,top)}else{check(action=="向下滑动");path.moveTo(x,top);path.lineTo(x,bottom)}}
        session.consume(SystemClock.elapsedRealtime(),session.target,true,1)
        busy=true
        val turn=epoch
        check(dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,if(point==null)400 else 80)).build(),object:GestureResultCallback(){
            override fun onCompleted(gestureDescription:GestureDescription?){if(turn==epoch){busy=false;lastStatus="系统确认手势完成；不代表业务任务成功"}}
            override fun onCancelled(gestureDescription:GestureDescription?){if(turn==epoch)stop("系统取消了手势")}
        },handler)){"系统拒绝手势"}
    }
}
