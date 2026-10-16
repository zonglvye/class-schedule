package io.github.zonglvye.classschedule;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 唯一的 Activity：一个全屏 WebView 装载本地网页，外挂原生提醒层。
 *
 * 关键实现说明：
 *  1. 网页资源不通过 file:// 加载，而是拦截 https://app.local/ 从 assets 取——
 *     这样页面处在**安全来源**下，localStorage 稳定可靠（file:// 下各家 WebView 行为不一致）。
 *  2. 导出走原生（WebView 里 blob 下载是静默失效的），导入靠文件选择器。
 *  3. 前台状态上报给 ReminderReceiver：App 开着时用页面内横幅，关掉时用系统通知，不重复打扰。
 */
public class MainActivity extends Activity {

    private static final String TAG = "CS.Main";
    private static final String HOST = "app.local";
    private static final String START_URL = "https://" + HOST + "/index.html";

    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIFY_PERM = 1002;
    private static final int REQ_STORAGE_PERM = 1003;

    private static volatile boolean sForeground = false;

    static boolean isForeground() {
        return sForeground;
    }

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        Notify.ensureChannels(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);            // 不跟随系统字体缩放，避免课表被撑破版
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);   // 全部是本地资源，无需联网校验
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF);  // 网页自带深色模式，别让系统再翻一次
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null || !HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.length() == 0 || "/".equals(path)) path = "/index.html";
                try {
                    InputStream in = getAssets().open("www" + path);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-cache");
                    return new WebResourceResponse(mimeOf(path), "utf-8", 200, "OK", headers, in);
                } catch (IOException e) {
                    Map<String, String> headers = new HashMap<>();
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                            headers, new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u != null && HOST.equals(u.getHost())) return false;
                openExternal(u == null ? null : u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面就绪 / 每次回到前台：通知前端可以同步闹钟了
                view.evaluateJavascript(
                        "(function(){ try { if (window.__csNativeReady) window.__csNativeReady(); } catch(e){} })()",
                        null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "选择备份文件"), REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    cb.onReceiveValue(null);
                    return false;
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "[web:" + m.messageLevel() + "] " + m.message());
                return true;
            }
        });

        web.addJavascriptInterface(new WebAppBridge(this), "CSApp");
        WebView.setWebContentsDebuggingEnabled(true);

        setContentView(web);
        web.loadUrl(START_URL);

        Notify.ensureChannels(this);
        if (Build.VERSION.SDK_INT >= 33) requestNotificationPermission();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) requestLegacyStoragePermission();
    }

    /* ------------------------- 生命周期与前台状态 ------------------------- */

    @Override
    protected void onResume() {
        super.onResume();
        sForeground = true;
        if (web != null) {
            // 回到前台时让前端重新同步一次闹钟，并领走原生已触发的 key
            web.evaluateJavascript(
                    "(function(){ try { if (window.__csNativeReady) window.__csNativeReady(); } catch(e){} })()",
                    null);
        }
    }

    @Override
    protected void onPause() {
        sForeground = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("CSApp");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            handleBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 返回键优先交给网页处理（关弹层 / 回课表页），网页说不用了才退到桌面。 */
    private void handleBack() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript(
                "(function(){ try { return window.__csNativeBack ? String(window.__csNativeBack()) : 'exit'; }"
                        + " catch(e) { return 'exit'; } })()",
                value -> {
                    if (value == null || value.indexOf("exit") >= 0) moveTaskToBack(true);
                });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(out);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_NOTIFY_PERM) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "通知已开启，到点会提醒你" : "通知被拒绝，请在系统设置里允许",
                    Toast.LENGTH_LONG).show();
            if (web != null) {
                web.evaluateJavascript(
                        "(function(){ try { if (window.__csOnPerm) window.__csOnPerm(); } catch(e){} })()", null);
            }
        }
    }

    /* --------------------------- 权限与系统设置 --------------------------- */

    void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY_PERM);
        }
    }

    /** Android 9 及以下：导出到公共「下载」目录需要存储权限，否则自动退回应用私有目录 */
    void requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
        }
    }

    boolean canNotify() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    void requestExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "当前系统无需额外授权", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ReminderScheduler.canExact(this)) {
            Toast.makeText(this, "精确闹钟已允许", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable t2) {
                Toast.makeText(this, "请到系统设置 → 应用 → 闹钟与提醒 中允许", Toast.LENGTH_LONG).show();
            }
        }
    }

    boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            openBatterySettings();
        }
    }

    void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) { }
        }
    }

    void openExternal(String url) {
        if (url == null || url.length() == 0) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    /** 本地资源按扩展名给出正确 MIME —— 否则 https 来源下脚本会因 MIME 不符被拒绝执行。 */
    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".webmanifest")) return "application/manifest+json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".txt") || p.endsWith(".ics") || p.endsWith(".csv")) return "text/plain";
        return "application/octet-stream";
    }
}
