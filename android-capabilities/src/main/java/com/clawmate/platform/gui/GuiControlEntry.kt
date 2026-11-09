package com.clawmate.platform.gui

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Native user choice; no caller can supply a package, launch intent or gesture. */
object GuiControlEntry {
    private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

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
        val query=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps=activity.packageManager.queryIntentActivities(query,0).filter{it.activityInfo.packageName!=activity.packageName && it.activityInfo.enabled && it.activityInfo.exported}
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
                    beginAfterConfirmation(activity, launch, label, collectOnly)
                }.show()
        }.setNegativeButton("取消",null).show()
    }

    fun openAlipayCollection(activity: Activity) {
        foreground(activity)
        if (Build.VERSION.SDK_INT < 34) {
            AlertDialog.Builder(activity).setMessage("支付宝账单采集需要 Android 14 及以上。")
                .setPositiveButton("知道了", null).show()
            return
        }
        val launch = activity.packageManager.getLaunchIntentForPackage(ALIPAY_PACKAGE)
        if (launch == null || launch.component?.packageName != ALIPAY_PACKAGE) {
            AlertDialog.Builder(activity).setMessage("找不到可打开的支付宝，请确认已安装支付宝。")
                .setPositiveButton("知道了", null).show()
            return
        }
        if (GuiControlService.connected == null) {
            AlertDialog.Builder(activity).setTitle("开启无障碍服务后采集账单")
                .setMessage("此服务可读取窗口并执行滚动。请在 Android 无障碍设置中开启「ClawMate 按次界面控制」，然后返回任务页重新点击「采集账单」。每次采集仍需另行确认。")
                .setNegativeButton("取消", null).setPositiveButton("去系统设置") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.show()
            return
        }
        AlertDialog.Builder(activity).setTitle("本次允许采集支付宝？")
            .setMessage("打开支付宝后，请自行进入「我的 → 账单」。确认页面后，点浮层的「采集当前页」或「采集最多${GUI_COLLECTION_PAGE_LIMIT}页」。120秒内仅限支付宝，预览确认后写入本机采集记录。")
            .setNegativeButton("取消", null).setPositiveButton("打开支付宝") { _, _ ->
                beginAfterConfirmation(activity, launch, "支付宝", collectOnly = true)
            }.show()
    }

    private fun beginAfterConfirmation(activity: Activity, launch: Intent, label: String, collectOnly: Boolean) {
        activity.window.decorView.postDelayed({
            runCatching {
                foreground(activity)
                val service = checkNotNull(GuiControlService.connected) { "无障碍服务已断开" }
                service.begin(checkNotNull(launch.component).packageName, label, launch, collectOnly)
            }.onFailure {
                if (!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
                    AlertDialog.Builder(activity).setMessage(it.message ?: "无法开启会话")
                        .setPositiveButton("知道了", null).show()
                }
            }
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
