package io.github.zonglvye.classschedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowWebView;

import java.lang.reflect.Method;

/**
 * JS 桥接与 Activity 的契约：前端调过来的每个方法都必须真的干活。
 * 这是网页与原生之间唯一的接口，坏一个就是功能残废。
 */
@RunWith(RobolectricTestRunner.class)
public class WebAppBridgeTest {

    private static final String ENTRY_URL = "https://app.local/index.html";

    private Context ctx;
    private MainActivity activity;
    private WebAppBridge bridge;
    private ShadowAlarmManager alarms;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        Reminders.clear(ctx);
        alarms = Shadows.shadowOf((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE));
        alarms.setCanScheduleExactAlarms(true);

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create();
        activity = controller.get();
        bridge = new WebAppBridge(activity);
    }

    /** setContentView 会把 WebView 包进 android.R.id.content 这个 FrameLayout 里 */
    private WebView webView() {
        View root = activity.findViewById(android.R.id.content);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i) instanceof WebView) return (WebView) g.getChildAt(i);
            }
        }
        return null;
    }

    private String payload(int n) {
        long base = System.currentTimeMillis() + 3600_000L;
        JSONArray arr = new JSONArray();
        for (int i = 0; i < n; i++) {
            JSONObject o = new JSONObject();
            try {
                o.put("key", "cls|p" + i + "|1|1|2026-10-19");
                o.put("kind", "cls");
                o.put("title", "还有 15 分钟上课 · 课" + i);
                o.put("body", "08:00–08:45 第 1 节");
                o.put("trig", base + i * 60000L);
                o.put("ring", true);
                o.put("vibrate", true);
            } catch (Exception ignored) { }
            arr.put(o);
        }
        return arr.toString();
    }

    @Test
    public void activity_loads_the_bundled_web_app_over_its_local_origin() {
        WebView web = webView();
        assertNotNull("MainActivity 应把 WebView 设为内容视图", web);

        ShadowWebView shadow = Shadows.shadowOf(web);
        // 必须走 https://app.local/ 而不是 file://：安全来源下 localStorage 才稳定可靠
        assertEquals(ENTRY_URL, shadow.getLastLoadedUrl());
    }

    @Test
    public void webview_is_configured_for_the_app_to_work() {
        WebView web = webView();
        assertNotNull(web);
        assertTrue("必须开启 JS", web.getSettings().getJavaScriptEnabled());
        assertTrue("必须开启 DOM storage，否则 localStorage 直接不可用", web.getSettings().getDomStorageEnabled());
    }

    @Test
    public void capabilities_reports_a_usable_json_object() throws Exception {
        JSONObject c = new JSONObject(bridge.capabilities());
        assertTrue(c.getBoolean("native"));
        assertTrue(c.has("exact"));
        assertTrue(c.has("notify"));
        assertTrue(c.has("battery"));
        assertTrue(c.getInt("sdk") >= 24);
        assertFalse(c.getString("version").isEmpty());
        assertTrue(c.has("pending"));
    }

    @Test
    public void sync_reminders_actually_schedules_alarms() throws Exception {
        JSONObject res = new JSONObject(bridge.syncReminders(payload(5)));
        assertTrue(res.getBoolean("ok"));
        assertEquals(5, res.getInt("received"));
        assertEquals(5, res.getInt("scheduled"));
        assertEquals("排上的闹钟必须真的进了系统", 5, alarms.getScheduledAlarms().size());
        assertEquals(5, Reminders.load(ctx).size());
    }

    @Test
    public void sync_reminders_with_empty_array_clears_alarms() {
        bridge.syncReminders(payload(4));
        assertEquals(4, alarms.getScheduledAlarms().size());

        bridge.syncReminders("[]");
        assertEquals(0, alarms.getScheduledAlarms().size());
    }

    @Test
    public void sync_reminders_survives_garbage_input() throws Exception {
        JSONObject res = new JSONObject(bridge.syncReminders("{这不是数组"));
        assertFalse("不能把异常抛回 JS，否则前端会整个卡死", res.getBoolean("ok"));

        JSONObject res2 = new JSONObject(bridge.syncReminders(null));
        assertTrue(res2.getBoolean("ok"));
        assertEquals(0, res2.getInt("received"));
    }

    @Test
    public void take_fired_keys_round_trip_and_clears() throws Exception {
        assertEquals(0, new JSONArray(bridge.takeFiredKeys()).length());

        Reminders.markFired(ctx, "cls|zzz|1|1|2026-10-19");
        JSONArray first = new JSONArray(bridge.takeFiredKeys());
        assertEquals(1, first.length());
        assertEquals("cls|zzz|1|1|2026-10-19", first.getString(0));
        assertEquals("取走即清空", 0, new JSONArray(bridge.takeFiredKeys()).length());
    }

    @Test
    public void save_file_reports_back_a_path() throws Exception {
        JSONObject res = new JSONObject(bridge.saveFile("备份.json", "application/json", "{\"v\":1}"));
        assertTrue(res.getBoolean("ok"));
        assertTrue(res.getString("path").contains("备份.json"));
    }

    @Test
    public void clear_reminders_wipes_both_alarms_and_index() throws Exception {
        bridge.syncReminders(payload(3));
        assertEquals(3, alarms.getScheduledAlarms().size());

        JSONObject res = new JSONObject(bridge.clearReminders());
        assertTrue(res.getBoolean("ok"));
        assertEquals(0, alarms.getScheduledAlarms().size());
        assertEquals(0, Reminders.load(ctx).size());
    }

    @Test
    public void asset_mime_types_are_executable_by_the_webview() throws Exception {
        // 关键：页面来源是 https，脚本若以 octet-stream 下发会被 WebView 拒绝执行 → 白屏
        Method m = MainActivity.class.getDeclaredMethod("mimeOf", String.class);
        m.setAccessible(true);
        assertEquals("text/html", m.invoke(null, "/index.html"));
        assertEquals("application/javascript", m.invoke(null, "/assets/app.js"));
        assertEquals("text/css", m.invoke(null, "/assets/style.css"));
        assertEquals("image/png", m.invoke(null, "/assets/icon-192.png"));
        assertEquals("application/json", m.invoke(null, "/manifest.json"));
        assertEquals("text/plain", m.invoke(null, "/notes.txt"));
    }

    @Test
    public void schedule_test_alarm_returns_the_fire_time() throws Exception {
        long t0 = System.currentTimeMillis();
        JSONObject res = new JSONObject(bridge.scheduleTestAlarm(60, true, true));

        assertTrue(res.getBoolean("ok"));
        long at = res.getLong("at");
        assertTrue("应报告约 60 秒后的触发时刻", at > t0 + 50_000L && at < t0 + 70_000L);
        assertEquals(1, alarms.getScheduledAlarms().size());
    }

    @Test
    public void back_key_is_delegated_to_the_page_first() {
        // 契约：原生把返回键转发给网页，网页返回 'exit' 才退到桌面。
        // 这里验证原生确实走了这条通道（而不是直接 finish）。
        activity.onKeyDown(android.view.KeyEvent.KEYCODE_BACK,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));

        WebView web = webView();
        assertNotNull(web);
        String js = String.valueOf(Shadows.shadowOf(web).getLastEvaluatedJavascript());
        assertTrue("返回键必须交给网页先处理", js.contains("__csNativeBack"));
    }

    @Test
    public void page_ready_callback_is_invoked_so_the_page_can_resync() {
        // onResume / onPageFinished 都会回调 __csNativeReady，前端据此重排闹钟并领走已触发记录
        activity.onResume();
        WebView web = webView();
        assertNotNull(web);
        String js = String.valueOf(Shadows.shadowOf(web).getLastEvaluatedJavascript());
        assertTrue("回到前台必须通知网页重排闹钟", js.contains("__csNativeReady"));
    }
}
