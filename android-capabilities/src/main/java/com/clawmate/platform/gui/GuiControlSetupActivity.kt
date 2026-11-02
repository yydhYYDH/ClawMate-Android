package com.clawmate.platform.gui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** System accessibility "Settings" entry; never accepts a target/action intent extra. */
class GuiControlSetupActivity : Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var status:TextView
    private val update=object:Runnable {
        override fun run(){status.text=(if(GuiControlService.connected!=null)"无障碍服务已连接" else "无障碍服务未连接")+"\n"+GuiControlService.lastStatus;handler.postDelayed(this,500)}
    }
    override fun onCreate(state:Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(state)
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,48,24,24)}
        layout.addView(TextView(this).apply{text="ClawMate 按次界面控制";textSize=22f})
        layout.addView(TextView(this).apply{text="仅Android 14及以上。每次选择一个应用，120秒/最多20次动作；每次操作另行确认。截图仅本机预览，不上传。模型建议不自动执行。";textSize=16f})
        fun button(label:String,action:()->Unit){layout.addView(Button(this).apply{text=label;setOnClickListener{action()}})}
        button("无障碍权限说明"){GuiControlEntry.settings(this)}
        button("选择应用并开启手动控制"){GuiControlEntry.open(this)}
        button("停止控制会话"){GuiControlService.connected?.stop()}
        status=TextView(this).apply{textSize=15f;contentDescription="ClawMate control status"};layout.addView(status)
        button("返回"){finish()};setContentView(layout)
    }
    override fun onResume(){super.onResume();handler.post(update)}
    override fun onPause(){handler.removeCallbacks(update);super.onPause()}
    override fun onDestroy(){handler.removeCallbacks(update);super.onDestroy()}
}
