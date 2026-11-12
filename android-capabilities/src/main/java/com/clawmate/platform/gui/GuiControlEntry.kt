package com.clawmate.platform.gui

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.widget.Switch
import com.clawmate.app.task.model.WorkflowDefinition
import com.clawmate.app.task.model.WorkflowStep
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.WorkflowModelTool
import com.clawmate.app.task.runner.WorkflowRunResult
import com.clawmate.platform.display.BackgroundDisplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Resolves launchers locally and requires native confirmation for each session. */
object GuiControlEntry {
    suspend fun runWorkflow(activity: Activity, definition: WorkflowDefinition, model: WorkflowModelTool,
        destination: String,
    ): WorkflowRunResult {
        val steps = definition.steps
        if (steps.any { it.action == XhsGuiWorkflow.ACTION }) {
            return runXhsWorkflow(activity, steps, model, destination)
        }
        if (steps.any { it.type == WeiboGuiWorkflow.TYPE && it.action?.startsWith("weibo_") == true }) {
            return runWeiboWorkflow(activity, steps)
        }
        return runPageWorkflow(activity, definition, model, destination)
    }

    private fun launchers(activity: Activity, target: String? = null): List<ResolveInfo> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target)
        return activity.packageManager.queryIntentActivities(query, 0).filter {
            it.activityInfo.packageName != activity.packageName && it.activityInfo.enabled && it.activityInfo.exported
        }
    }

    fun collectionApps(activity: Activity): String {
        foreground(activity)
        val pm = activity.packageManager
        val apps = launchers(activity).distinctBy { it.activityInfo.packageName }
            .map { it.loadLabel(pm).toString().take(60) to it.activityInfo.packageName }
            .sortedBy { it.first }.take(200)
        return JSONArray(apps.map { (label, target) ->
            JSONObject().put("label", label).put("packageName", target)
        }).toString()
    }

    private suspend fun runPageWorkflow(activity: Activity, definition: WorkflowDefinition,
        model: WorkflowModelTool, destination: String,
    ): WorkflowRunResult {
        val plan = try {
            GuiCollectionWorkflow.validate(definition)
        } catch (failure: IllegalArgumentException) {
            return WorkflowRunResult(RunStatus.BLOCKED, failure.message ?: "采集图配置有误")
        }
        if (plan.prompt != null && destination.isBlank()) {
            return WorkflowRunResult(RunStatus.BLOCKED, "请从任务卡单独运行模型读屏并确认 Decider")
        }
        val pm = activity.packageManager
        val unavailable = "找不到已安装且可打开的应用（${plan.target}）"
        val app = launchers(activity, plan.target).firstOrNull()
            ?: return WorkflowRunResult(RunStatus.NOT_CONFIGURED, unavailable)
        val launch = Intent.makeMainActivity(ComponentName(plan.target, app.activityInfo.name))
        val label = app.loadLabel(pm).toString().take(40)
        val run = PageGuiRun(plan, model.takeIf { plan.prompt != null })
        val range = if (plan.pages == 1) "读取当前一页" else
            "最多读取 ${plan.pages} 页（${if (plan.direction == CollectionDirection.UP) "向上" else "向下"}翻页）"
        val modelExplanation = plan.prompt?.let {
            "\n点击「开始模型读屏」后，最多发送 ${plan.pages} 张目标窗口截图与提示词给 $destination。" +
                "\n提示词：$it\n模型可决定是否继续翻页。请在本机预览中核对提取的文字。"
        }.orEmpty()
        return runApprovedCollection(activity, GuiControlRequest(label, launch, GuiControlTask.Pages(run)),
            "仅允许采集 $label（${plan.target}）。\n${plan.instructions.ifBlank { "请打开要采集的页面。" }}\n" +
                "到达页面后点浮窗开始。$range，预览确认后保存到本机。$modelExplanation",
            unavailable, run.result, title = "采集 $label", scrollMessage = plan.prompt != null,
        )
    }

    private fun foreground(activity:Activity) {
        check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() && !activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请返回前台并解锁后操作" }
    }
    fun settings(activity:Activity) {
        foreground(activity)
        if(Build.VERSION.SDK_INT<34){AlertDialog.Builder(activity).setMessage("跨应用窗口截图需要Android 14及以上；不必开启无障碍权限。").setPositiveButton("知道了",null).show();return}
        AlertDialog.Builder(activity).setTitle("开启按次界面控制权限？")
            .setMessage("Android无障碍权限可读取窗口并发送手势。每次需另行选择应用并确认。手动控制会话最长120秒、最多20次动作，截图仅供本机预览。采集会话最长120秒，可采集当前页，也可主动开启最多${GUI_COLLECTION_PAGE_LIMIT}页的自动向下采集。预览后确认才保存到本机。此功能需要Android 14及以上，可随时在系统设置中关闭。")
            .setNegativeButton("取消",null).setPositiveButton("去系统设置"){_,_->
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
    }
    fun open(activity:Activity, collectOnly:Boolean=false) {
        foreground(activity)
        if(Build.VERSION.SDK_INT<34){AlertDialog.Builder(activity).setMessage("跨应用窗口截图需要Android 14及以上；仍可使用本应用截图与规划。").setPositiveButton("知道了",null).show();return}
        if(GuiControlService.connected==null){AlertDialog.Builder(activity).setTitle("尚未启用按次界面控制")
            .setMessage("请先通过权限说明入口进入Android无障碍设置，手动开启ClawMate按次界面控制。")
            .setPositiveButton("知道了",null).show();return}
        val apps=launchers(activity)
            .distinctBy{it.activityInfo.packageName}.sortedBy{it.loadLabel(activity.packageManager).toString()}.take(200)
        if(apps.isEmpty()){AlertDialog.Builder(activity).setMessage("没有可选择的启动应用").setPositiveButton("知道了",null).show();return}
        val labels=apps.map{it.loadLabel(activity.packageManager).toString().take(60)+"\n"+it.activityInfo.packageName}.toTypedArray()
        AlertDialog.Builder(activity).setTitle(if (collectOnly) "选择要采集的应用" else "仅允许控制一个应用").setItems(labels){_,index->
            val selected=apps[index];val label=selected.loadLabel(activity.packageManager).toString();val pkg=selected.activityInfo.packageName
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(pkg, selected.activityInfo.name))
            AlertDialog.Builder(activity).setTitle("本次允许：$label")
                .setMessage(if (collectOnly)
                    "仅允许读取 $pkg 的可见无障碍文字，最长120秒。到目标页面后，可主动采集当前页，或选择最多${GUI_COLLECTION_PAGE_LIMIT}页的自动向下采集。自动采集最多滚动${GUI_COLLECTION_PAGE_LIMIT - 1}次，预览确认后才保存到本机。切换应用、锁屏或到期即停止。"
                else "仅允许 $pkg，最长120秒、最多20次动作。屏幕将显示停止控制入口，每个手势另行确认；切换应用、锁屏或到期即停止。截图只在本机预览，不上传、不写入文件。当前不自动执行模型建议，也不支持文字输入。")
                .setNegativeButton("取消",null).setPositiveButton("打开并开始会话"){_,_->
                    val task = if (collectOnly) GuiControlTask.ManualCollection else GuiControlTask.ManualControl
                    beginAfterConfirmation(activity, GuiControlRequest(label, launch, task))
                }.show()
        }.setNegativeButton("取消",null).show()
    }

    suspend fun runWeiboWorkflow(activity: Activity, steps: List<WorkflowStep>): WorkflowRunResult {
        val limit = try {
            WeiboGuiWorkflow.validate(steps)
        } catch (failure: IllegalArgumentException) {
            return WorkflowRunResult(RunStatus.BLOCKED, failure.message ?: "微博采集图配置有误")
        }
        val run = WeiboGuiRun(steps, limit)
        val range = if (limit == 0) "逐页读取整个公开热搜榜" else "读取最多 $limit 条可见热搜"
        return runApprovedCollection(
            activity, GuiControlRequest("微博热搜", WeiboGuiWorkflow.launchIntent(), GuiControlTask.Weibo(run)),
            "ClawMate 将打开微博，$range。标题、日期、排名和可读取的热度会先在本机预览。",
            "找不到可打开热搜榜的微博，请确认微博已安装", run.result, title = "采集微博热搜",
        )
    }

    private suspend fun runXhsWorkflow(activity: Activity, steps: List<WorkflowStep>, model: WorkflowModelTool,
        destination: String,
    ): WorkflowRunResult {
        val (keyword, task) = try { XhsGuiWorkflow.validate(steps) }
        catch (failure: IllegalArgumentException) {
            return WorkflowRunResult(RunStatus.BLOCKED, failure.message ?: "小红书 AI 采集配置有误")
        }
        if (destination.isBlank()) return WorkflowRunResult(RunStatus.BLOCKED, "小红书 AI 采集需要单独确认 Decider，请从任务卡运行")
        val run = XhsGuiRun(steps.single(), keyword, task, model)
        return runApprovedCollection(
            activity, GuiControlRequest("小红书 AI 采集", XhsGuiWorkflow.launch(keyword), GuiControlTask.Xhs(run)),
            "公开搜索：$keyword\n提示词：$task\n\n发送提示词、可用操作和进度给 $destination。笔记摘录在本机读取，确认预览后保存。",
            "找不到可打开公开搜索页的小红书，请检查客户端版本", run.result,
        )
    }

    private suspend fun runApprovedCollection(
        activity: Activity,
        request: GuiControlRequest,
        explanation: String,
        unavailable: String,
        result: Deferred<WorkflowRunResult>,
        title: String = request.label,
        scrollMessage: Boolean = false,
    ): WorkflowRunResult = withContext(Dispatchers.Main.immediate) {
        try {
            foreground(activity)
        } catch (failure: IllegalStateException) {
            return@withContext WorkflowRunResult(RunStatus.BLOCKED, failure.message.orEmpty())
        }
        if (Build.VERSION.SDK_INT < 34) return@withContext WorkflowRunResult(RunStatus.NOT_CONFIGURED, "界面采集需要 Android 14 及以上")
        if (request.launch.resolveActivity(activity.packageManager)?.packageName != request.target) {
            return@withContext WorkflowRunResult(RunStatus.NOT_CONFIGURED, unavailable)
        }
        val service = GuiControlService.connected
        if (service == null) {
            GuiCollectionStyle(activity).sheet("开启无障碍服务", "请启用 ClawMate 按次界面控制，返回任务页后重新运行。", "去系统设置") {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return@withContext WorkflowRunResult(RunStatus.NOT_CONFIGURED, "请先开启 ClawMate 无障碍服务")
        }
        val consent = CompletableDeferred<Boolean?>()
        val backgroundAvailable = BackgroundDisplay.available
        val background = if (request.supportsBackground) Switch(activity).apply {
            text = if (backgroundAvailable) "在后台屏采集" else "后台屏助手未连接"
            isEnabled = backgroundAvailable
        } else null
        val dialog = GuiCollectionStyle(activity).sheet(title,
            "$explanation\n锁屏会停止采集。前台模式下离开目标应用也会停止。",
            "运行采集", content = background, scrollMessage = scrollMessage) { consent.complete(background?.isChecked == true) }
            .apply { setOnDismissListener { consent.complete(null) } }
        try {
            withTimeoutOrNull(105_000) {
                val useBackground = consent.await()
                    ?: return@withTimeoutOrNull WorkflowRunResult(RunStatus.CANCELLED, "已取消${request.label}")
                delay(250)
                service.startSession(request, useBackground) { foreground(activity) }
                result.await()
            } ?: WorkflowRunResult(RunStatus.CANCELLED, "${request.label}已超时")
        } catch (failure: IllegalStateException) {
            WorkflowRunResult(RunStatus.FAILED, failure.message ?: "无法开启${request.label}")
        } catch (_: android.content.ActivityNotFoundException) {
            WorkflowRunResult(RunStatus.NOT_CONFIGURED, unavailable)
        } catch (_: SecurityException) {
            WorkflowRunResult(RunStatus.NOT_CONFIGURED, "系统拒绝开启采集，请检查应用和后台屏权限")
        } catch (_: android.os.RemoteException) {
            WorkflowRunResult(RunStatus.NOT_CONFIGURED, "后台屏助手已断开，请重新连接")
        } finally {
            dialog.dismiss()
            service.stopSession(request, "${request.label}已结束")
        }
    }

    private fun beginAfterConfirmation(activity: Activity, request: GuiControlRequest) {
        activity.window.decorView.postDelayed({
            val onError: (Exception) -> Unit = {
                if (!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
                    AlertDialog.Builder(activity).setMessage(it.message ?: "无法开启会话")
                        .setPositiveButton("知道了", null).show()
                }
            }
            val service = GuiControlService.connected
            if (service == null) onError(IllegalStateException("无障碍服务已断开"))
            else service.startManual(request, { foreground(activity) }, onError)
        }, 250)
    }

    fun showCollections(activity: Activity) {
        foreground(activity)
        runCatching { GuiCollectionHistory.show(activity) }.onFailure {
            AlertDialog.Builder(activity).setMessage("无法读取本机采集记录，请稍后重试。")
                .setPositiveButton("知道了", null).show()
        }
    }
}
