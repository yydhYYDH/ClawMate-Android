package com.clawmate.reuseprobe;

import android.app.Activity;
import android.os.Build;
import android.os.Looper;
import android.graphics.Rect;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import ohos.ace.adapter.capability.bridge.BridgePlugin;
import ohos.stage.ability.adapter.StageActivity;

/** Native adapter for this isolated experiment. No credentials or production services. */
public final class ProbeBridge extends BridgePlugin {
    private final Activity activity;
    private ProbeFixture fixture;

    public ProbeBridge(StageActivity activity) {
        super(activity, "ClawMateProbe", activity.getBridgeManager());
        this.activity = activity;
    }

    public String configuration() throws Exception {
        JSONObject config = new JSONObject();
        if (fixture == null) fixture = new ProbeFixture();
        config.put("fixtureBaseUrl", fixture.baseUrl());
        return config.toString();
    }

    public void closeFixture() throws Exception {
        if (fixture != null) fixture.close();
    }

    public String platformInfo() {
        return "Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT;
    }

    public String echo(String value) { return value; }

    public boolean clearFocus() throws Exception {
        FutureTask<Boolean> work = new FutureTask<>(() -> {
            View focused = activity.getCurrentFocus();
            if (focused == null) return false;
            focused.clearFocus();
            return true;
        });
        if (Looper.myLooper() == Looper.getMainLooper()) work.run();
        else activity.runOnUiThread(work);
        return work.get(2, TimeUnit.SECONDS);
    }

    public int keyboardHeight() {
        Rect frame = new Rect();
        View root = activity.getWindow().getDecorView();
        root.getWindowVisibleDisplayFrame(frame);
        int difference = root.getRootView().getHeight() - frame.bottom;
        return difference > root.getRootView().getHeight() / 5 ? difference : 0;
    }

    public boolean setClipboard(String text) {
        ClipboardManager manager = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (manager == null) return false;
        manager.setPrimaryClip(ClipData.newPlainText("ClawMate probe", text));
        return true;
    }

    public boolean testClipboard() {
        ClipboardManager manager = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (manager == null) return false;
        ClipData previous = manager.getPrimaryClip();
        try {
            setClipboard("本地剪贴板夹具🙂");
            ClipData actual = manager.getPrimaryClip();
            return actual != null && actual.getItemCount() == 1 &&
                "本地剪贴板夹具🙂".contentEquals(actual.getItemAt(0).coerceToText(activity));
        } finally {
            if (previous != null) manager.setPrimaryClip(previous);
            else if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip();
            else manager.setPrimaryClip(ClipData.newPlainText("", ""));
        }
    }

    public boolean hideKeyboard() throws Exception {
        FutureTask<Boolean> work = new FutureTask<>(() -> {
            View focused = activity.getCurrentFocus();
            if (focused == null) return false;
            InputMethodManager manager = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            return manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        });
        if (Looper.myLooper() == Looper.getMainLooper()) work.run();
        else activity.runOnUiThread(work);
        return work.get(2, TimeUnit.SECONDS);
    }

    public boolean report(String json) throws Exception {
        if (json.length() > 32000) throw new IllegalArgumentException("Probe report too large");
        new JSONObject(json);
        Files.write(new File(activity.getFilesDir(), "probe-result.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
        return true;
    }
}
