package io.github.zonglvye.classschedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * 提醒点的持久化索引。
 *
 * 设计要点：原生侧只存「什么时候提醒什么」这一份最小数据（由前端算好后交过来），
 * 不复制课表 / 作业 / 单双周那套算法。这样网页版和 APK 永远不会算出两套结果。
 *
 * 存两份数据：
 *   items —— 待触发的提醒点（用于同步闹钟、开机后重排）
 *   fired —— 已经由原生通知触发的 key（供前端合并去重，避免重复弹两次）
 */
final class Reminders {

    private static final String PREF = "cs_reminders";
    private static final String K_ITEMS = "items";
    private static final String K_FIRED = "fired";
    private static final int FIRED_KEEP = 400;
    private static final long FIRED_TTL = 14L * 24 * 3600 * 1000;

    private Reminders() { }

    /** 一个提醒点。字段与前端 nextReminders() 产出的结构一一对应。 */
    static final class Item {
        String key = "";
        String kind = "cls";      // cls | task
        String title = "";
        String body = "";
        long trig = 0L;           // 触发时刻（epoch ms）
        boolean ring = true;
        boolean vibrate = true;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("key", key);
                o.put("kind", kind);
                o.put("title", title);
                o.put("body", body);
                o.put("trig", trig);
                o.put("ring", ring);
                o.put("vibrate", vibrate);
            } catch (JSONException ignored) { }
            return o;
        }

        String toJsonString() { return toJson().toString(); }

        static Item fromJson(JSONObject o) {
            if (o == null) return null;
            Item it = new Item();
            it.key = o.optString("key", "");
            it.kind = o.optString("kind", "cls");
            it.title = o.optString("title", "");
            it.body = o.optString("body", "");
            it.trig = o.optLong("trig", 0L);
            it.ring = o.optBoolean("ring", true);
            it.vibrate = o.optBoolean("vibrate", true);
            if (it.key.length() == 0 || it.trig <= 0L) return null;
            return it;
        }

        /** 闹钟请求码：由 key 稳定推导，重复同步即覆盖同一条，不会堆叠。 */
        int requestCode() { return key.hashCode(); }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 读已触发表。抽成方法是为了拿到 effectively final 的引用 —— lambda 里要用。 */
    private static JSONObject readFired(SharedPreferences p) {
        try {
            return new JSONObject(p.getString(K_FIRED, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        String raw = sp(c).getString(K_ITEMS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Item it = Item.fromJson(arr.optJSONObject(i));
                if (it != null) out.add(it);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    static void save(Context c, Collection<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) arr.put(it.toJson());
        sp(c).edit().putString(K_ITEMS, arr.toString()).apply();
    }

    static void clear(Context c) {
        sp(c).edit().remove(K_ITEMS).apply();
    }

    /* ---------------------------- 已触发记录 ---------------------------- */

    static void markFired(Context c, String key) {
        if (key == null || key.length() == 0) return;
        SharedPreferences p = sp(c);
        final JSONObject fired = readFired(p);
        long now = System.currentTimeMillis();
        try {
            fired.put(key, now);
        } catch (JSONException ignored) { }

        // 清理：先按时间淘汰，再按数量兜底
        List<String> keys = new ArrayList<>();
        Iterator<String> it = fired.keys();
        while (it.hasNext()) keys.add(it.next());
        if (keys.size() > FIRED_KEEP) {
            List<String> byTime = new ArrayList<>(keys);
            byTime.sort((a, b) -> Long.compare(fired.optLong(a), fired.optLong(b)));
            for (int i = 0; i < byTime.size() - FIRED_KEEP; i++) fired.remove(byTime.get(i));
            keys = byTime;
        }
        JSONObject kept = new JSONObject();
        for (String k : keys) {
            long t = fired.optLong(k);
            if (now - t <= FIRED_TTL) {
                try {
                    kept.put(k, t);
                } catch (JSONException ignored) { }
            }
        }
        p.edit().putString(K_FIRED, kept.toString()).apply();
    }

    /** 取出并清空「原生已触发」的 key 列表，前端拿去合并进 S.reminders 去重。 */
    static String takeFiredJson(Context c) {
        SharedPreferences p = sp(c);
        String raw = p.getString(K_FIRED, "{}");
        p.edit().remove(K_FIRED).apply();
        JSONArray out = new JSONArray();
        try {
            JSONObject fired = new JSONObject(raw);
            Iterator<String> it = fired.keys();
            while (it.hasNext()) out.put(it.next());
        } catch (JSONException ignored) { }
        return out.toString();
    }
}
