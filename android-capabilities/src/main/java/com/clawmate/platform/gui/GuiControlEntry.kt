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
            .setMessage("Android无障碍权限可读取窗口并发送手势。ClawMate仅在你另行选择应用并确认后开启120秒/20次动作会话；截图只在本机预览，不上传。此功能需要Android 14及以上。请在系统设置中自行决定是否启用，可随时关闭。")
            .setNegativeButton("取消",null).setPositiveButton("去系统设置"){_,_->
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
    }
    fun open(activity:Activity) {
        foreground(activity)
        if(Build.VERSION.SDK_INT<34){AlertDialog.Builder(activity).setMessage("跨应用窗口截图需要Android 14及以上；仍可使用本应用截图与规划。").setPositiveButton("知道了",null).show();return}
        if(GuiControlService.connected==null){AlertDialog.Builder(activity).setTitle("尚未启用按次界面控制")
            .setMessage("请先通过权限说明入口进入Android无障碍设置，手动开启ClawMate按次界面控制。当前未授权应用，不会执行动作。")
            .setPositiveButton("知道了",null).show();return}
        val query=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps=activity.packageManager.queryIntentActivities(query,0).filter{it.activityInfo.packageName!=activity.packageName && it.activityInfo.enabled && it.activityInfo.exported}
            .distinctBy{it.activityInfo.packageName}.sortedBy{it.loadLabel(activity.packageManager).toString()}.take(200)
        if(apps.isEmpty()){AlertDialog.Builder(activity).setMessage("没有可选择的启动应用").setPositiveButton("知道了",null).show();return}
        val labels=apps.map{it.loadLabel(activity.packageManager).toString().take(60)+"\n"+it.activityInfo.packageName}.toTypedArray()
        AlertDialog.Builder(activity).setTitle("仅允许控制一个应用").setItems(labels){_,index->
            val selected=apps[index];val label=selected.loadLabel(activity.packageManager).toString();val pkg=selected.activityInfo.packageName
            AlertDialog.Builder(activity).setTitle("本次允许：$label")
                .setMessage("仅允许 $pkg，最长120秒、最多20次动作。屏幕将显示停止控制入口，每个手势另行确认；切换应用、锁屏或到期即停止。截图只在本机预览，不上传、不写入文件。当前不自动执行模型建议，也不支持文字输入。")
                .setNegativeButton("取消",null).setPositiveButton("打开并开始会话"){_,_->
                    activity.window.decorView.postDelayed({
                    runCatching{foreground(activity);checkNotNull(GuiControlService.connected){"服务已断开"}.begin(pkg,label,Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg,selected.activityInfo.name)))}
                        .onFailure{AlertDialog.Builder(activity).setMessage(it.message ?: "无法开启会话").setPositiveButton("知道了",null).show()}
                    },250)
                }.show()
        }.setNegativeButton("取消",null).show()
    }
}
