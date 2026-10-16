package io.github.zonglvye.classschedule;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 网页 ↔ 原生 的唯一通道，暴露为 JS 的 window.CSApp。
 *
 * 分工原则：前端负责「算」（什么时候提醒什么），原生负责「响」（到点弹通知）。
 * 前端那套单双周 / 补课 / 作业 DDL 算法一行都不用重写，两边永远不会算出两套结果。
 *
 * 注意：这些方法由 WebView 在**子线程**调用，任何碰 UI 的操作都要 post 回主线程。
 */
public class WebAppBridge {

    private static final String TAG = "CS.Bridge";

    private final MainActivity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    WebAppBridge(MainActivity activity) {
        this.act = activity;
    }

    /** 原生能力自检：前端据此决定显示「浏览器通知」还是「系统闹钟」。 */
    @JavascriptInterface
    public String capabilities() {
        JSONObject o = new JSONObject();
        try {
            o.put("native", true);
            o.put("exact", ReminderScheduler.canExact(act));
            o.put("notify", act.canNotify());
            o.put("battery", act.isIgnoringBatteryOptimizations());
            o.put("sdk", android.os.Build.VERSION.SDK_INT);
            o.put("version", act.versionName());
            o.put("pending", ReminderScheduler.pendingCount(act));
        } catch (Throwable t) {
            Log.e(TAG, "capabilities failed", t);
        }
        return o.toString();
    }

    /**
     * 全量同步提醒点。入参是前端算好的 JSON 数组：
     * [{key,kind,title,body,trig,ring,vibrate}, ...]
     */
    @JavascriptInterface
    public String syncReminders(String json) {
        JSONObject res = new JSONObject();
        try {
            List<Reminders.Item> items = new ArrayList<>();
            if (json != null && json.length() > 0) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    Reminders.Item it = Reminders.Item.fromJson(arr.optJSONObject(i));
                    if (it != null) items.add(it);
                }
            }
            int n = ReminderScheduler.sync(act, items);
            res.put("ok", true);
            res.put("received", items.size());
            res.put("scheduled", n);
            res.put("exact", ReminderScheduler.canExact(act));
            Log.i(TAG, "syncReminders: in=" + items.size() + " out=" + n);
        } catch (Throwable t) {
            Log.e(TAG, "syncReminders failed", t);
            try {
                res.put("ok", false);
                res.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) { }
        }
        return res.toString();
    }

    /** 取消所有闹钟（用户关掉提醒开关时用）。 */
    @JavascriptInterface
    public String clearReminders() {
        JSONObject res = new JSONObject();
        try {
            ReminderScheduler.cancel(act, Reminders.load(act));
            Reminders.clear(act);
            res.put("ok", true);
            res.put("scheduled", 0);
        } catch (Throwable t) {
            try {
                res.put("ok", false);
            } catch (Throwable ignored) { }
        }
        return res.toString();
    }

    /** 取走「原生已触发」的 key，前端合并进自己那份去重表，避免同一节课弹两次。 */
    @JavascriptInterface
    public String takeFiredKeys() {
        try {
            return Reminders.takeFiredJson(act);
        } catch (Throwable t) {
            Log.e(TAG, "takeFiredKeys failed", t);
            return "[]";
        }
    }

    /**
     * 排一条 N 秒后的测试闹钟，返回 {ok, at}。
     * 让用户自己验证「关掉 App 也会准时响」——这是整个 APK 存在的理由。
     */
    @JavascriptInterface
    public String scheduleTestAlarm(int seconds, boolean ring, boolean vibrate) {
        JSONObject res = new JSONObject();
        try {
            long at = ReminderScheduler.scheduleTest(act, seconds, ring, vibrate);
            res.put("ok", at > 0);
            if (at > 0) res.put("at", at);
            else res.put("error", "系统拒绝了闹钟排程，请检查「闹钟与提醒」权限");
        } catch (Throwable t) {
            Log.e(TAG, "scheduleTestAlarm failed", t);
            try {
                res.put("ok", false);
                res.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) { }
        }
        return res.toString();
    }

    /** APK 模式下的导出：写进系统「下载/我的课表/」，返回 {ok,path}。 */
    @JavascriptInterface
    public String saveFile(String name, String mime, String content) {
        JSONObject res = new JSONObject();
        try {
            String path = FileExporter.save(act, name, mime, content);
            res.put("ok", path != null);
            if (path != null) res.put("path", path);
            else res.put("error", "写入失败，请检查存储空间");
        } catch (Throwable t) {
            Log.e(TAG, "saveFile failed", t);
            try {
                res.put("ok", false);
                res.put("error", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) { }
        }
        return res.toString();
    }

    @JavascriptInterface
    public void requestNotificationPermission() {
        ui.post(act::requestNotificationPermission);
    }

    @JavascriptInterface
    public void requestExactAlarm() {
        ui.post(act::requestExactAlarm);
    }

    @JavascriptInterface
    public void requestIgnoreBattery() {
        ui.post(act::requestIgnoreBattery);
    }

    @JavascriptInterface
    public void openBatterySettings() {
        ui.post(act::openBatterySettings);
    }

    @JavascriptInterface
    public void toast(final String msg) {
        ui.post(() -> {
            try {
                Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) { }
        });
    }

    @JavascriptInterface
    public void openExternal(final String url) {
        ui.post(() -> act.openExternal(url));
    }

    @JavascriptInterface
    public String appVersion() {
        return act.versionName();
    }
}
