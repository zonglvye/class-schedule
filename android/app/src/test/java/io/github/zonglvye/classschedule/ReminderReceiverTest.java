package io.github.zonglvye.classschedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm;

import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟到点后的行为：什么时候弹通知、弹在哪个渠道、三个按钮各自干什么。
 *
 * 这一块是最容易「看起来对、实际不响」的地方 —— 所以逐条钉死。
 */
@RunWith(RobolectricTestRunner.class)
public class ReminderReceiverTest {

    private Context ctx;
    private NotificationManager nm;
    private ShadowAlarmManager alarms;

    @Before
    public void setUp() throws Exception {
        ctx = RuntimeEnvironment.getApplication();
        Reminders.clear(ctx);
        Notify.ensureChannels(ctx);
        nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancelAll();
        alarms = Shadows.shadowOf((AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE));
        alarms.setCanScheduleExactAlarms(true);
        setForeground(false);
    }

    /** MainActivity.sForeground 是私有的：用反射设置，避免为了测试去改生产代码 */
    private void setForeground(boolean v) throws Exception {
        java.lang.reflect.Field f = MainActivity.class.getDeclaredField("sForeground");
        f.setAccessible(true);
        f.set(null, v);
    }

    private Reminders.Item item(String key, String kind) {
        Reminders.Item it = new Reminders.Item();
        it.key = key;
        it.kind = kind;
        it.title = "还有 15 分钟上课 · 示例课程";
        it.body = "08:00–08:45 第 1 节 · 12501";
        it.trig = System.currentTimeMillis() + 900_000L;
        it.ring = true;
        it.vibrate = true;
        return it;
    }

    private Intent intent(String action, Reminders.Item it) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction(action);
        i.putExtra(ReminderReceiver.EXTRA_ITEM, it.toJsonString());
        i.putExtra(ReminderReceiver.EXTRA_CODE, it.requestCode());
        return i;
    }

    private List<Notification> posted() {
        return Shadows.shadowOf(nm).getAllNotifications();
    }

    private void run(Intent i) {
        new ReminderReceiver().onReceive(ctx, i);
    }

    @Test
    public void fires_notification_when_app_is_not_in_foreground() throws Exception {
        Reminders.Item it = item("cls|p1|1|1|2026-10-19", "cls");
        List<Reminders.Item> store = new ArrayList<>();
        store.add(it);
        Reminders.save(ctx, store);

        setForeground(false);
        run(intent(ReminderReceiver.ACTION_FIRE, it));

        assertEquals(1, posted().size());
        Notification n = posted().get(0);
        assertEquals(it.title, Shadows.shadowOf(n).getContentTitle().toString());
        assertEquals(it.body, Shadows.shadowOf(n).getContentText().toString());
        // 上铃上震 → 必须用「响铃」渠道，否则用户设置的声音/震动等于摆设
        assertEquals(Notify.CH_CLS_LOUD, n.getChannelId());
    }

    @Test
    public void does_not_double_notify_when_app_is_open() throws Exception {
        Reminders.Item it = item("cls|p1|1|1|2026-10-19", "cls");
        Reminders.save(ctx, new ArrayList<>(java.util.Collections.singletonList(it)));

        setForeground(true);
        run(intent(ReminderReceiver.ACTION_FIRE, it));

        assertEquals("App 开着时页面里已有横幅，不该再弹系统通知", 0, posted().size());
        // 但仍要记账，否则前端稍后还会再弹一遍
        assertTrue(new JSONArray(Reminders.takeFiredJson(ctx)).toString().contains(it.key));
        assertEquals(0, Reminders.load(ctx).size());
    }

    @Test
    public void task_reminder_uses_the_task_channel() {
        Reminders.Item it = item("task|t1|60", "task");
        run(intent(ReminderReceiver.ACTION_FIRE, it));
        assertEquals(Notify.CH_TASK_LOUD, posted().get(0).getChannelId());
    }

    @Test
    public void silent_settings_use_the_quiet_channel() {
        Reminders.Item it = item("cls|p2|2|1|2026-10-20", "cls");
        it.ring = false;
        it.vibrate = false;
        run(intent(ReminderReceiver.ACTION_FIRE, it));
        assertEquals("用户关掉了响铃和震动 → 走静音渠道", Notify.CH_CLS_QUIET, posted().get(0).getChannelId());
    }

    @Test
    public void notification_carries_both_action_buttons() {
        Reminders.Item it = item("cls|p3|3|1|2026-10-21", "cls");
        run(intent(ReminderReceiver.ACTION_FIRE, it));

        Notification n = posted().get(0);
        assertNotNull(n.actions);
        assertEquals(2, n.actions.length);
        assertEquals("知道了", n.actions[0].title.toString());
        assertEquals("稍后 10 分钟", n.actions[1].title.toString());
    }

    @Test
    public void snooze_reschedules_about_ten_minutes_later() {
        Reminders.Item it = item("cls|p4|4|1|2026-10-22", "cls");
        Reminders.save(ctx, new ArrayList<>(java.util.Collections.singletonList(it)));
        int before = alarms.getScheduledAlarms().size();

        long t0 = System.currentTimeMillis();
        run(intent(ReminderReceiver.ACTION_SNOOZE, it));

        assertEquals(0, posted().size());
        assertEquals(before + 1, alarms.getScheduledAlarms().size());
        long at = alarms.getScheduledAlarms().get(alarms.getScheduledAlarms().size() - 1).getTriggerAtMs();
        long delta = at - t0;
        assertTrue("应约为 10 分钟后，实际 " + delta + "ms", delta > 9 * 60_000L && delta < 11 * 60_000L);
        // 提醒点要留在索引里，否则开机重排就找不回来了
        assertEquals(1, Reminders.load(ctx).size());
        assertTrue(Reminders.load(ctx).get(0).trig > t0);
    }

    @Test
    public void done_clears_notification_and_marks_it_fired() throws Exception {
        Reminders.Item it = item("cls|p5|5|1|2026-10-23", "cls");
        setForeground(false);
        run(intent(ReminderReceiver.ACTION_FIRE, it));
        assertEquals(1, posted().size());

        run(intent(ReminderReceiver.ACTION_DONE, it));

        assertEquals("点了「知道了」通知要消失", 0, posted().size());
        assertEquals(0, Reminders.load(ctx).size());
        assertTrue(new JSONArray(Reminders.takeFiredJson(ctx)).toString().contains(it.key));
    }

    @Test
    public void malformed_intent_is_ignored_without_crashing() {
        Intent broken = new Intent(ctx, ReminderReceiver.class);
        broken.setAction(ReminderReceiver.ACTION_FIRE);
        broken.putExtra(ReminderReceiver.EXTRA_ITEM, "{不是合法 JSON");
        run(broken);
        assertEquals(0, posted().size());

        Intent empty = new Intent(ctx, ReminderReceiver.class);
        empty.setAction(ReminderReceiver.ACTION_FIRE);
        run(empty);
        assertEquals(0, posted().size());
    }

    @Test
    public void test_alarm_notifies_even_when_the_app_is_open() throws Exception {
        // 走真实路径：scheduleTest 排一条真闹钟 → 取出它携带的 Intent → 投递给接收器。
        // 用户点「测试」时 App 必然开着；若这时也按「前台不打扰」跳过，
        // 用户干等一分钟什么都看不到，会直接判定成「提醒坏了」。
        ReminderScheduler.scheduleTest(ctx, 60, true, true);
        ShadowAlarmManager.ScheduledAlarm alarm = alarms.getNextScheduledAlarm();
        assertNotNull("测试闹钟应当真的排进系统", alarm);

        Intent fire = Shadows.shadowOf(alarm.operation).getSavedIntent();
        assertEquals(ReminderReceiver.ACTION_FIRE, fire.getAction());
        String raw = fire.getStringExtra(ReminderReceiver.EXTRA_ITEM);
        assertNotNull(raw);
        assertTrue("闹钟要带上测试标记，接收器才会破例在前台弹", raw.contains("\"kind\":\"test\""));

        setForeground(true);
        run(fire);

        assertEquals("测试闹钟在 App 前台也必须弹出来", 1, posted().size());
        assertEquals("测试提醒 · 我的课表",
                Shadows.shadowOf(posted().get(0)).getContentTitle().toString());
        assertEquals(Notify.CH_CLS_LOUD, posted().get(0).getChannelId());
    }

    @Test
    public void notification_channels_are_all_registered() {
        NotificationManager mgr = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        assertNotNull(mgr.getNotificationChannel(Notify.CH_CLS_LOUD));
        assertNotNull(mgr.getNotificationChannel(Notify.CH_CLS_QUIET));
        assertNotNull(mgr.getNotificationChannel(Notify.CH_TASK_LOUD));
        assertNotNull(mgr.getNotificationChannel(Notify.CH_TASK_QUIET));
        // 只关掉声音、没关震动的课也应算「响铃」，否则震动会失效
        Reminders.Item v = item("k", "cls");
        v.ring = false;
        v.vibrate = true;
        assertEquals(Notify.CH_CLS_LOUD, Notify.channelFor(v));
        assertFalse(Notify.channelFor(v).isEmpty());
    }
}
