package io.github.zonglvye.classschedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 导出落盘。APK 里「导出备份」不能走网页那套（WebView 里 blob 下载是静默失效的），
 * 必须真的写到磁盘上 —— 否则用户点导出毫无反应、还以为备份好了。
 */
@RunWith(RobolectricTestRunner.class)
public class FileExporterTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
    }

    @Test
    public void sanitize_strips_path_separators_and_illegal_chars() {
        String dirty = "a/b\\c:d*e?f\"g<h>i|j.json";
        String clean = FileExporter.sanitize(dirty);
        for (char bad : new char[]{'/', '\\', ':', '*', '?', '"', '<', '>', '|'}) {
            assertFalse("不该残留 " + bad + " → " + clean, clean.indexOf(bad) >= 0);
        }
        assertTrue(clean.endsWith(".json"));
    }

    @Test
    public void sanitize_handles_empty_and_overlong_names() {
        assertEquals("backup.json", FileExporter.sanitize(""));
        assertEquals("backup.json", FileExporter.sanitize("   "));
        assertEquals("backup.json", FileExporter.sanitize(null));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("我的课表备份-");
        String out = FileExporter.sanitize(sb.toString() + ".json");
        assertTrue("文件名过长会写入失败，必须截断", out.length() <= 120);
    }

    @Test
    public void save_returns_a_path_that_names_the_file() {
        String path = FileExporter.save(ctx, "我的课表备份-2026-10-16.json", "application/json", "{\"v\":1}");
        assertNotNull("导出必须返回落盘位置，否则用户无从找起", path);
        assertTrue(path, path.contains("我的课表备份-2026-10-16.json"));
    }

    @Test
    public void save_writes_real_bytes_when_it_lands_on_a_real_path() throws Exception {
        String content = "{\"v\":1,\"courses\":[{\"name\":\"示例课程\"}]}";
        String path = FileExporter.save(ctx, "readback-test.json", "application/json", content);
        assertNotNull(path);

        File f = new File(path);
        if (f.isAbsolute()) {
            assertTrue("文件应当真的存在：" + path, f.exists());
            String got = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertEquals("内容要被完整写入（含中文）", content, got);
        } else {
            // 走了 MediaStore 通道（Android 10+ 的常规路径）：这里只验证没抛异常且给了位置
            System.out.println("（本次走 MediaStore 通道，返回：" + path + "）");
        }
    }

    @Test
    public void save_defaults_mime_when_not_provided() {
        String path = FileExporter.save(ctx, "x.json", "", "{}");
        assertNotNull(path);
        assertTrue(path.endsWith("x.json"));
    }

    @Test
    public void save_does_not_throw_on_weird_input() {
        assertNotNull(FileExporter.save(ctx, "../../逃逸.json", "application/json", "{}"));
        assertNotNull(FileExporter.save(ctx, null, null, ""));
    }
}
