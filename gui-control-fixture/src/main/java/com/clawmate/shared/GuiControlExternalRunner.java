package com.clawmate.shared;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separate SDK-only actor; no mock service, shared static state or target process restart. */
public class GuiControlExternalRunner extends Instrumentation {
    private UiAutomation ui;
    private static final String HOST="com.clawmate.shared", FIXTURE="com.clawmate.gui.fixture";
    private void progress(String stage){Log.i("ClawMateGuiActor",stage);Bundle value=new Bundle();value.putString("guiControlStage",stage);sendStatus(2,value);}
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if(args==null || !"true".equals(args.getString("guiControlGranted"))){Bundle result=new Bundle();result.putString("scenarioPassed","false");finish(Activity.RESULT_CANCELED,result);return;}
        start();
    }
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void shell(String command) {
        try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))){byte[] bytes=new byte[4096];while(input.read(bytes)!=-1){}}
        catch(Exception failure){throw new RuntimeException(failure);}
    }
    private boolean allowed(AccessibilityNodeInfo root){if(root==null)return false;String pkg=String.valueOf(root.getPackageName());return HOST.equals(pkg)||FIXTURE.equals(pkg);}
    private boolean matches(AccessibilityNodeInfo node,String text,boolean exact) {
        String label=String.valueOf(node.getText()),description=String.valueOf(node.getContentDescription());
        return node.isVisibleToUser() && (exact?(text.equals(label)||text.equals(description)):(label.contains(text)||description.contains(text)));
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root,String text,boolean exact) {
        if(root==null)return null;
        if(matches(root,text,exact))return root;
        // Ask the app to search locally instead of issuing a Binder request for every child.
        for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))
            if(matches(node,text,exact))return node;
        return null;
    }
    private AccessibilityNodeInfo owned(String text,boolean exact) {
        Log.i("ClawMateGuiActor","Reading owned node: clear cache");
        if(android.os.Build.VERSION.SDK_INT>=33)ui.clearCache();
        Log.i("ClawMateGuiActor","Reading owned node: root");
        AccessibilityNodeInfo root=ui.getRootInActiveWindow(),found=allowed(root)?find(root,text,exact):null;
        if(found!=null)return found;
        Log.i("ClawMateGuiActor","Reading owned node: windows");
        for(AccessibilityWindowInfo window:ui.getWindows()){root=window.getRoot();if(allowed(root)){found=find(root,text,exact);if(found!=null)return found;}}
        return null;
    }
    private void await(String message,BooleanSupplier ready) {
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<deadline){if(ready.getAsBoolean())return;SystemClock.sleep(300);}
        throw new AssertionError(message);
    }
    private void tap(float x,float y){check(allowed(ui.getRootInActiveWindow()),"Refusing test input outside host/fixture");shell("input tap "+(int)x+" "+(int)y);SystemClock.sleep(180);}
    private void click(String text,boolean exact){
        progress("Looking for: "+text);
        await("Missing UI: "+text,()->{
            AccessibilityNodeInfo node=owned(text,exact);
            if(node==null)return false;
            check(allowed(node),"Refusing node action outside host/fixture");
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Node refused click: "+text);
            progress("Actor node click returned: "+text);
            SystemClock.sleep(350);
            return true;
        });
    }
    private AccessibilityWindowInfo fixtureWindow() {
        for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo root=window.getRoot();if(window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION && root!=null && FIXTURE.equals(String.valueOf(root.getPackageName())))return window;}
        return null;
    }
    private String fixtureState(){AccessibilityWindowInfo window=fixtureWindow();AccessibilityNodeInfo node=window==null?null:find(window.getRoot(),"Fixture scroll offset:",false);return node==null?"":String.valueOf(node.getContentDescription());}
    private void setup(){shell("am start -W -n com.clawmate.shared/com.clawmate.platform.gui.GuiControlSetupActivity");await("Native control settings did not open",()->owned("ClawMate control status",true)!=null);}
    @Override public void onStart() {
        Thread scenario=Thread.currentThread();
        Thread diagnostic=new Thread(()->{SystemClock.sleep(25000);StringBuilder stack=new StringBuilder();for(StackTraceElement frame:scenario.getStackTrace())stack.append(frame).append('\n');Log.i("ClawMateGuiActor","25-second actor stack: "+stack);},"fixture-diagnostic");diagnostic.setDaemon(true);diagnostic.start();
        Bundle result=new Bundle();boolean passed=false;
        try {
            progress("Connecting SDK automation without suppressing the actual service");
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            progress("Connected automation; opening native settings");
            AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
            setup();await("Real service did not connect",()->{AccessibilityNodeInfo status=owned("ClawMate control status",true);return status!=null && String.valueOf(status.getText()).contains("无障碍服务已连接");});
            progress("Actual service connected; selecting only synthetic fixture");
            SystemClock.sleep(1000);click("选择应用并开启手动控制",true);
            click("ClawMate GUI Fixture\ncom.clawmate.gui.fixture",true);
            check(owned("仅允许 com.clawmate.gui.fixture，",false)!=null,"Picker did not select exact fixture package");click("打开并开始会话",true);
            await("Visible exact-app control panel missing",()->owned("停止控制",true)!=null);
            progress("Exact fixture foreground with visible control panel");
            // Let the system launch animation finish before recording screen geometry.
            SystemClock.sleep(1500);
            check(fixtureState().endsWith("count: 0"),"Synthetic fixture not in initial state");
            AccessibilityWindowInfo target=fixtureWindow();Rect frame=new Rect();target.getBoundsInScreen(frame);
            AccessibilityNodeInfo increment=find(target.getRoot(),"Increment fixture",true);check(increment!=null,"Fixture button missing");Rect button=new Rect();increment.getBoundsInScreen(button);
            click("截图 / 点按",true);await("Real window preview missing",()->owned("跨应用本机截图预览",true)!=null);
            SystemClock.sleep(1000);
            Rect preview=new Rect();owned("跨应用本机截图预览",true).getBoundsInScreen(preview);
            float scale=Math.min((float)preview.width()/frame.width(),(float)preview.height()/frame.height());
            tap(preview.left+(preview.width()-frame.width()*scale)/2+(button.exactCenterX()-frame.left)*scale,
                preview.top+(preview.height()-frame.height()*scale)/2+(button.exactCenterY()-frame.top)*scale);
            click("确认执行",true);await("Screenshot-mapped confirmed tap did not increment fixture",()->fixtureState().endsWith("count: 1"));
            progress("Real screenshot-mapped confirmed tap incremented fixture");
            SystemClock.sleep(1300);String before=fixtureState();click("向上滑动",true);click("取消",true);SystemClock.sleep(1300);check(before.equals(fixtureState()),"Cancelled swipe changed fixture");
            click("向上滑动",true);click("确认执行",true);await("Confirmed swipe did not scroll fixture",()->{Matcher matcher=Pattern.compile("offset: ([0-9]+)").matcher(fixtureState());return matcher.find() && Integer.parseInt(matcher.group(1))>0;});
            shell("input keyevent KEYCODE_HOME");await("Leaving allowed app did not remove control panel",()->owned("停止控制",true)==null);
            passed=true;result.putString("scenarioPassed","true");result.putString("guiControlFixture","Real system-bound service, exact fixture picker, window preview/mapped tap/count, swipe cancellation/completion and Home-stop; no private app/cloud/image export");sendStatus(2,result);
        } catch(Throwable failure) {
            result.putString("scenarioPassed","false");result.putString("stream",Log.getStackTraceString(failure));
            try{setup();AccessibilityNodeInfo status=owned("ClawMate control status",true);result.putString("guiControlFailure",status==null?"No host status":String.valueOf(status.getText()));}catch(Throwable ignored){}
        } finally {
            try{setup();click("停止控制会话",true);}catch(Throwable ignored){}
            finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }
    }
}
