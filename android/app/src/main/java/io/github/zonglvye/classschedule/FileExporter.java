package io.github.zonglvye.classschedule;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 备份文件落盘。
 *
 * 为什么需要原生通道：Android WebView 里「Blob + a[download]」的下载是**静默失效**的，
 * 网页版的「导出 JSON / 导出 ICS」在 APK 里点下去会毫无反应。
 * 所以 APK 模式下导出改走这里，直接写进系统「下载」目录，用户能在文件管理器里找到。
 *
 * Android 10+ 用 MediaStore（免存储权限）；旧版本写公共下载目录，
 * 失败则退回应用私有目录并如实返回路径，绝不假装成功。
 */
final class FileExporter {

    private static final String TAG = "CS.Export";
    private static final String SUB = "我的课表";

    private FileExporter() { }

    static String save(Context c, String name, String mime, String content) {
        String safe = sanitize(name);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String m = (mime == null || mime.length() == 0) ? guessMime(safe) : mime;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                v.put(MediaStore.MediaColumns.MIME_TYPE, m);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + SUB);
                Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = c.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(bytes);
                        os.close();
                        return "下载/" + SUB + "/" + safe;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore save failed, falling back", t);
            }
        } else {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), SUB);
                if (dir.exists() || dir.mkdirs()) {
                    File f = new File(dir, safe);
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(bytes);
                    fo.close();
                    return f.getAbsolutePath();
                }
            } catch (Throwable t) {
                Log.w(TAG, "public Downloads save failed, falling back", t);
            }
        }

        // 兜底：应用私有目录，不需要任何权限
        try {
            File dir = new File(c.getExternalFilesDir(null), "exports");
            if (dir.exists() || dir.mkdirs()) {
                File f = new File(dir, safe);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(bytes);
                fo.close();
                return f.getAbsolutePath();
            }
        } catch (Throwable t) {
            Log.e(TAG, "private save failed", t);
        }
        return null;
    }

    static String sanitize(String name) {
        String n = (name == null || name.trim().length() == 0) ? "backup.json" : name.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (n.length() > 120) n = n.substring(0, 120);
        return n;
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".ics")) return "text/calendar";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }
}
