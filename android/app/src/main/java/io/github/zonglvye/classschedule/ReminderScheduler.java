package io.github.zonglvye.classschedule;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟排程层：把前端算好的提醒点交给系统 AlarmManager。
 *
 * 关键点：
 *  1. 用 setExactAndAllowWhileIdle —— 息屏 / 打盹模式下也能准时响，这是 APK 存在的意义。
 *  2. 每条提醒的请求码由 key 稳定推导，重复同步是「覆盖」而不是「堆叠」。
 *  3. 前端一次交来整个学期的提醒点（上限 {@link #MAX_ALARMS} 条），
 *     所以即使用户几周不开 App，闹钟也不会断档。
 *  4. Android 12+ 若无精确闹钟权限，自动降级为不精确闹钟并如实上报状态，绝不假装成功。
 */
final class ReminderScheduler {

    private static final String TAG = "CS.Alarm";

    /** AlarmManager 对单应用挂起闹钟数量有上限（约 500），留出余量。 */
    static final int MAX_ALARMS = 430;

    private ReminderScheduler() { }

    static AlarmManager am(Context c) {
        return (AlarmManager) c.getApplicationContext().getSystemService(Context.ALARM_SERVICE);
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        try {
            return am(c).canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    private static PendingIntent pending(Context c, Reminders.Item it) {
        Intent i = new Intent(c.getApplicationContext(), ReminderReceiver.class);
        i.setAction(ReminderReceiver.ACTION_FIRE);
        i.putExtra(ReminderReceiver.EXTRA_ITEM, it.toJsonString());
        i.putExtra(ReminderReceiver.EXTRA_CODE, it.requestCode());
        return PendingIntent.getBroadcast(
                c.getApplicationContext(), it.requestCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 取消一批提醒对应的闹钟。 */
    static void cancel(Context c, List<Reminders.Item> items) {
        AlarmManager m = am(c);
        if (m == null) return;
        for (Reminders.Item it : items) {
            try {
                m.cancel(pending(c, it));
            } catch (Throwable t) {
                Log.w(TAG, "cancel failed: " + it.key, t);
            }
        }
    }

    /** 单条取消（作业被标记完成、或提醒点已过期时用）。 */
    static void cancel(Context c, Reminders.Item it) {
        List<Reminders.Item> one = new ArrayList<>(1);
        one.add(it);
        cancel(c, one);
    }

    /** 排一条闹钟，返回是否用上了精确闹钟。 */
    static boolean schedule(Context c, Reminders.Item it) {
        AlarmManager m = am(c);
        if (m == null) return false;
        boolean exact = canExact(c);
        PendingIntent pi = pending(c, it);
        try {
            if (exact) {
                m.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.trig, pi);
            } else {
                m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.trig, pi);
            }
            return exact;
        } catch (SecurityException se) {
            // 权限被用户在系统设置里收回：降级重试一次，别让整批同步失败
            Log.w(TAG, "exact alarm denied, falling back", se);
            try {
                m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.trig, pi);
            } catch (Throwable t) {
                Log.e(TAG, "schedule failed: " + it.key, t);
            }
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "schedule failed: " + it.key, t);
            return false;
        }
    }

    /**
     * 全量同步：先撤掉旧的，再排新的。
     * 返回实际排上的条数（负数表示 AlarmManager 不可用）。
     */
    static int sync(Context c, List<Reminders.Item> next) {
        List<Reminders.Item> old = Reminders.load(c);
        cancel(c, old);

        long now = System.currentTimeMillis();
        List<Reminders.Item> future = new ArrayList<>();
        for (Reminders.Item it : next) {
            if (it.trig > now - 60_000L) future.add(it);
        }
        future.sort((a, b) -> Long.compare(a.trig, b.trig));
        if (future.size() > MAX_ALARMS) future = new ArrayList<>(future.subList(0, MAX_ALARMS));

        int ok = 0;
        for (Reminders.Item it : future) {
            schedule(c, it);
            ok++;
        }
        Reminders.save(c, future);
        Log.i(TAG, "synced " + ok + " alarms (dropped " + (next.size() - ok) + ")");
        return ok;
    }

    /** 开机 / 应用更新后按持久化索引重排，不需要前端参与。 */
    static int reschedule(Context c) {
        AlarmManager m = am(c);
        if (m == null) return 0;
        long now = System.currentTimeMillis();
        List<Reminders.Item> all = Reminders.load(c);
        List<Reminders.Item> keep = new ArrayList<>();
        for (Reminders.Item it : all) {
            if (it.trig > now) {
                schedule(c, it);
                keep.add(it);
            } else if (it.trig > now - 6 * 3600_000L) {
                // 关机期间错过的提醒：补一条通知，别让用户静默错过
                it.trig = now;
                schedule(c, it);
                keep.add(it);
            }
        }
        Reminders.save(c, keep);
        Log.i(TAG, "rescheduled " + keep.size() + " alarms after boot");
        return keep.size();
    }

    static int pendingCount(Context c) {
        return Reminders.load(c).size();
    }

    /**
     * 排一条一次性测试闹钟。
     *
     * 为什么需要它：用户装完最想知道的是「关掉 App 到底还响不响」，而这件事只有他自己能验证 ——
     * 点一下这个按钮，把 App 切后台或关掉，等 N 秒。响了就说明整条链路（精确闹钟 →
     * 广播接收器 → 通知渠道）全都通了。
     *
     * kind 用 "test" 而不是 "cls"：测试闹钟即使在 App 前台也要弹系统通知，
     * 否则用户开着 App 干等 60 秒什么也看不到，会误判成「不响」。
     *
     * 故意不写进持久化索引：它不该在重启后被重排，也不该混进正常提醒的统计里。
     */
    static long scheduleTest(Context c, int seconds, boolean ring, boolean vibrate) {
        int sec = Math.max(5, Math.min(3600, seconds));
        Reminders.Item it = new Reminders.Item();
        it.key = "test|" + System.currentTimeMillis();
        it.kind = "test";
        it.title = "测试提醒 · 我的课表";
        it.body = "看到这条说明关掉 App、息屏也会准时提醒 ✅";
        it.trig = System.currentTimeMillis() + sec * 1000L;
        it.ring = ring;
        it.vibrate = vibrate;
        // 注意：schedule() 的返回值是「是否用上了精确闹钟」，不是「是否排成功」。
        // 没有精确闹钟权限时它会降级为不精确闹钟并返回 false —— 那种情况依然算排上了。
        schedule(c, it);
        return it.trig;
    }
}
