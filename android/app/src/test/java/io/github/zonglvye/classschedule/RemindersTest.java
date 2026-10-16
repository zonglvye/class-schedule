package io.github.zonglvye.classschedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/** 提醒点索引的持久化、去重与清理。 */
@RunWith(RobolectricTestRunner.class)
public class RemindersTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    private Reminders.Item item(String key, long trig) {
        Reminders.Item it = new Reminders.Item();
        it.key = key;
        it.kind = "cls";
        it.title = "还有 15 分钟上课 · 示例课程";
        it.body = "08:00–08:45 第 1 节 · 12501";
        it.trig = trig;
        it.ring = true;
        it.vibrate = false;
        return it;
    }

    @Test
    public void item_json_round_trip_keeps_every_field() throws Exception {
        Reminders.Item src = item("cls|p1|1|1|2026-10-19", 1792000000000L);
        Reminders.Item back = Reminders.Item.fromJson(new JSONObject(src.toJsonString()));
        assertNotNull(back);
        assertEquals(src.key, back.key);
        assertEquals(src.kind, back.kind);
        assertEquals(src.title, back.title);
        assertEquals(src.body, back.body);
        assertEquals(src.trig, back.trig);
        assertTrue(back.ring);
        assertFalse(back.vibrate);
    }

    @Test
    public void item_without_key_or_trig_is_rejected() throws Exception {
        assertNull(Reminders.Item.fromJson(new JSONObject("{\"title\":\"x\"}")));
        assertNull(Reminders.Item.fromJson(new JSONObject("{\"key\":\"k\",\"trig\":0}")));
        assertNull(Reminders.Item.fromJson(null));
    }

    @Test
    public void request_code_is_stable_for_same_key() {
        assertEquals(item("a|b|c", 1L).requestCode(), item("a|b|c", 999L).requestCode());
    }

    @Test
    public void save_and_load_round_trip() {
        Context c = ctx();
        Reminders.clear(c);
        Reminders.save(c, java.util.Arrays.asList(item("k1", 111L), item("k2", 222L)));

        List<Reminders.Item> got = Reminders.load(c);
        assertEquals(2, got.size());
        assertEquals("k1", got.get(0).key);
        assertEquals("k2", got.get(1).key);

        Reminders.clear(c);
        assertTrue(Reminders.load(c).isEmpty());
    }

    @Test
    public void load_survives_corrupted_prefs() {
        Context c = ctx();
        c.getSharedPreferences("cs_reminders", Context.MODE_PRIVATE)
                .edit().putString("items", "这不是 JSON").apply();
        assertEquals(0, Reminders.load(c).size());
    }

    @Test
    public void fired_keys_are_handed_over_once_then_cleared() throws Exception {
        Context c = ctx();
        c.getSharedPreferences("cs_reminders", Context.MODE_PRIVATE).edit().clear().apply();

        Reminders.markFired(c, "cls|x|1|1|2026-10-19");
        Reminders.markFired(c, "task|t1|60");

        JSONArray first = new JSONArray(Reminders.takeFiredJson(c));
        assertEquals(2, first.length());
        assertEquals("cls|x|1|1|2026-10-19", first.getString(0));

        // 取走即清空：否则 App 每次打开都会重复去重，甚至把新提醒也吞掉
        assertEquals(0, new JSONArray(Reminders.takeFiredJson(c)).length());
    }

    @Test
    public void fired_keys_are_pruned_to_a_bounded_size() throws Exception {
        Context c = ctx();
        c.getSharedPreferences("cs_reminders", Context.MODE_PRIVATE).edit().clear().apply();

        // 一次性灌入超过上限的记录，验证会被裁剪（否则长期使用后这个表会无限膨胀）
        for (int i = 0; i < 460; i++) Reminders.markFired(c, "k" + i);

        JSONArray left = new JSONArray(Reminders.takeFiredJson(c));
        assertTrue("裁剪后应在 400 条以内，实际 " + left.length(), left.length() <= 400);
        assertTrue("不该把最新的一条剪掉", left.toString().contains("k459"));
    }
}
