package io.github.zonglvye.classschedule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟到点 / 通知按钮的落地点。
 *
 * 三种动作：
 *   FIRE   —— 闹钟响了。App 在前台就不弹系统通知（页面里已有横幅+提示音），只在后台/被杀时弹。
 *   SNOOZE —— 用户点「稍后 10 分钟」，重新排一条闹钟。
 *   DONE   —— 用户点「知道了」，关掉通知并记下 key，防止前端再弹一次。
 */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "CS.Receiver";

    static final String ACTION_FIRE = "io.github.zonglvye.classschedule.action.FIRE";
    static final String ACTION_SNOOZE = "io.github.zonglvye.classschedule.action.SNOOZE";
    static final String ACTION_DONE = "io.github.zonglvye.classschedule.action.DONE";

    static final String EXTRA_ITEM = "item";
    static final String EXTRA_CODE = "code";

    private static final long SNOOZE_MS = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Reminders.Item it = parse(intent);
        if (it == null) {
            Log.w(TAG, "no item in intent, action=" + action);
            return;
        }
        int code = intent.getIntExtra(EXTRA_CODE, it.requestCode());
        Context ctx = context.getApplicationContext();
        Notify.ensureChannels(ctx);

        if (ACTION_SNOOZE.equals(action)) {
            Notify.cancel(ctx, code);
            it.trig = System.currentTimeMillis() + SNOOZE_MS;
            upsert(ctx, it);
            ReminderScheduler.schedule(ctx, it);
            Log.i(TAG, "snoozed " + it.key);
            return;
        }

        if (ACTION_DONE.equals(action)) {
            Notify.cancel(ctx, code);
            remove(ctx, it.key);
            Reminders.markFired(ctx, it.key);
            Log.i(TAG, "dismissed " + it.key);
            return;
        }

        // 默认：闹钟触发
        remove(ctx, it.key);
        Reminders.markFired(ctx, it.key);

        if (MainActivity.isForeground() && !"test".equals(it.kind)) {
            // App 正开着：页面里的横幅 + 提示音已经提示过了，不重复打扰。
            // 测试闹钟例外 —— 用户点「测试」时 App 就是开着的，这时必须弹出来，否则无从验证。
            Log.i(TAG, "foreground, skip system notification: " + it.key);
            return;
        }
        Notify.show(ctx, it);
        Log.i(TAG, "notified " + it.key);
    }

    private static Reminders.Item parse(Intent intent) {
        String raw = intent.getStringExtra(EXTRA_ITEM);
        if (raw == null || raw.length() == 0) return null;
        try {
            return Reminders.Item.fromJson(new JSONObject(raw));
        } catch (Throwable t) {
            Log.w(TAG, "bad item json", t);
            return null;
        }
    }

    private static void remove(Context c, String key) {
        List<Reminders.Item> all = Reminders.load(c);
        List<Reminders.Item> keep = new ArrayList<>(all.size());
        for (Reminders.Item x : all) if (!x.key.equals(key)) keep.add(x);
        if (keep.size() != all.size()) Reminders.save(c, keep);
    }

    private static void upsert(Context c, Reminders.Item it) {
        List<Reminders.Item> all = Reminders.load(c);
        boolean found = false;
        for (Reminders.Item x : all) {
            if (x.key.equals(it.key)) {
                x.trig = it.trig;
                found = true;
            }
        }
        if (!found) all.add(it);
        Reminders.save(c, all);
    }
}
