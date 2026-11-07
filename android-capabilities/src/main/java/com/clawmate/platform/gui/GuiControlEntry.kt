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
    private fun foreground(activity:Activity) {
        check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() && !activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请返回前台并解锁后操作" }
    }
    fun settings(activity:Activity) {
        foreground(activity)
        if(Build.VERSION.SDK_INT<34){AlertDialog.Builder(activity).setMessage("跨应用窗口截图需要Android 14及以上；不必开启无障碍权限。").setPositiveButton("知道了",null).show();return}
        AlertDialog.Builder(activity).setTitle("开启按次界面控制权限？")
            .setMessage("Android无障碍权限可读取窗口并发送手势。每次需另行选择应用并确认。手动控制会话最长120秒、最多20次动作，截图仅供本机预览。采集会话最长120秒，点击采集后可预览当前页文字，确认后才保存到本机。此功能需要Android 14及以上，可随时在系统设置中关闭。")
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
            AlertDialog.Builder(activity).setTitle("本次允许：$label")
                .setMessage(if (collectOnly)
                    "仅允许读取 $pkg 当前页的可见无障碍文字，最长120秒。每页由你点击采集并预览，确认后才保存到本机。切换应用、锁屏或到期即停止。"
                else "仅允许 $pkg，最长120秒、最多20次动作。屏幕将显示停止控制入口，每个手势另行确认；切换应用、锁屏或到期即停止。截图只在本机预览，不上传、不写入文件。当前不自动执行模型建议，也不支持文字输入。")
                .setNegativeButton("取消",null).setPositiveButton("打开并开始会话"){_,_->
                    activity.window.decorView.postDelayed({
                    runCatching {
                        foreground(activity)
                        val service = checkNotNull(GuiControlService.connected) { "服务已断开" }
                        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(ComponentName(pkg, selected.activityInfo.name))
                        service.begin(pkg, label, launch, collectOnly)
                    }
                        .onFailure{AlertDialog.Builder(activity).setMessage(it.message ?: "无法开启会话").setPositiveButton("知道了",null).show()}
                    },250)
                }.show()
        }.setNegativeButton("取消",null).show()
    }

    fun showCollections(activity: Activity) {
        foreground(activity)
        runCatching { GuiCollectionHistory.show(activity) }.onFailure {
            AlertDialog.Builder(activity).setMessage("无法读取本机采集记录，请稍后重试。")
                .setPositiveButton("知道了", null).show()
        }
    }
}
