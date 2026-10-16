package io.github.zonglvye.classschedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlarmManager;

import java.util.ArrayList;
import java.util.List;

/** 闹钟排程：数量、覆盖、上限、过期剔除、开机重排。这是 APK 里最不能出错的一块。 */
@RunWith(RobolectricTestRunner.class)
public class ReminderSchedulerTest {

    private Context ctx;
    private AlarmManager am;
    private ShadowAlarmManager shadow;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        Reminders.clear(ctx);
        am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        shadow = Shadows.shadowOf(am);
        shadow.setCanScheduleExactAlarms(true);
    }

    private Reminders.Item item(String key, long trig) {
        Reminders.Item it = new Reminders.Item();
        it.key = key;
        it.kind = "cls";
        it.title = "t";
        it.body = "b";
        it.trig = trig;
        return it;
    }

    private List<Reminders.Item> items(int n, long startAt) {
        List<Reminders.Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(item("k" + i, startAt + i * 60000L));
        return out;
    }

    @Test
    public void sync_schedules_every_future_item() {
        long now = System.currentTimeMillis();
        int n = ReminderScheduler.sync(ctx, items(7, now + 3600_000L));

        assertEquals(7, n);
        assertEquals(7, shadow.getScheduledAlarms().size());
        assertEquals(7, Reminders.load(ctx).size());
    }

    @Test
    public void sync_replaces_previous_alarms_instead_of_stacking() {
        long now = System.currentTimeMillis();
        ReminderScheduler.sync(ctx, items(5, now + 3600_000L));
        assertEquals(5, shadow.getScheduledAlarms().size());

        ReminderScheduler.sync(ctx, items(3, now + 7200_000L));
        assertEquals("重复同步必须覆盖而不是堆叠", 3, shadow.getScheduledAlarms().size());
        assertEquals(3, Reminders.load(ctx).size());
    }

    @Test
    public void sync_drops_items_already_in_the_past() {
        long now = System.currentTimeMillis();
        List<Reminders.Item> mixed = new ArrayList<>();
        mixed.add(item("old", now - 24 * 3600_000L));    // 昨天，早该丢
        mixed.add(item("fresh", now + 60_000L));
        mixed.add(item("future", now + 3600_000L));

        assertEquals(2, ReminderScheduler.sync(ctx, mixed));
        assertEquals(2, shadow.getScheduledAlarms().size());
    }

    @Test
    public void sync_caps_at_max_alarms_and_keeps_the_nearest_ones() {
        long now = System.currentTimeMillis();
        List<Reminders.Item> many = items(ReminderScheduler.MAX_ALARMS + 120, now + 60_000L);

        int n = ReminderScheduler.sync(ctx, many);
        assertEquals(ReminderScheduler.MAX_ALARMS, n);
        assertEquals(ReminderScheduler.MAX_ALARMS, Reminders.load(ctx).size());

        // 保留的必须是最近的那批
        List<Reminders.Item> kept = Reminders.load(ctx);
        assertEquals("k0", kept.get(0).key);
        long maxKept = kept.get(kept.size() - 1).trig;
        assertTrue("最远的那条必须被截掉", maxKept < many.get(many.size() - 1).trig);
    }

    @Test
    public void sync_with_empty_list_clears_everything() {
        long now = System.currentTimeMillis();
        ReminderScheduler.sync(ctx, items(4, now + 3600_000L));
        assertEquals(4, shadow.getScheduledAlarms().size());

        // 对应「用户关掉总开关」：必须真的把闹钟全撤掉，而不是留着继续响
        assertEquals(0, ReminderScheduler.sync(ctx, new ArrayList<Reminders.Item>()));
        assertEquals(0, shadow.getScheduledAlarms().size());
        assertEquals(0, Reminders.load(ctx).size());
    }

    @Test
    public void reschedule_after_boot_keeps_future_and_catches_up_recent_missed() {
        long now = System.currentTimeMillis();
        List<Reminders.Item> stored = new ArrayList<>();
        stored.add(item("future", now + 2 * 3600_000L));
        stored.add(item("missed_recently", now - 3600_000L));   // 关机期间错过 1 小时 → 补提醒
        stored.add(item("ancient", now - 48 * 3600_000L));      // 两天前 → 丢弃
        Reminders.save(ctx, stored);

        int n = ReminderScheduler.reschedule(ctx);

        assertEquals("未来 1 条 + 近期错过 1 条", 2, n);
        List<Reminders.Item> kept = Reminders.load(ctx);
        assertEquals(2, kept.size());
        boolean hasAncient = false;
        for (Reminders.Item it : kept) if (it.key.equals("ancient")) hasAncient = true;
        assertFalse("两天前的提醒不该再补", hasAncient);
    }

    @Test
    public void exact_alarm_falls_back_gracefully_when_not_allowed() {
        long now = System.currentTimeMillis();
        shadow.setCanScheduleExactAlarms(false);

        assertFalse(ReminderScheduler.canExact(ctx));
        // 不允许精确闹钟时不能抛异常、也不能静默不排：降级为不精确闹钟继续排
        int n = ReminderScheduler.sync(ctx, items(2, now + 3600_000L));
        assertEquals(2, n);
        assertEquals(2, shadow.getScheduledAlarms().size());
    }

    @Test
    public void pending_count_reflects_the_store() {
        long now = System.currentTimeMillis();
        ReminderScheduler.sync(ctx, items(6, now + 3600_000L));
        assertEquals(6, ReminderScheduler.pendingCount(ctx));
    }

    @Test
    public void test_alarm_is_scheduled_without_polluting_the_store() {
        long t0 = System.currentTimeMillis();
        long at = ReminderScheduler.scheduleTest(ctx, 60, true, true);

        assertTrue("应排在约 60 秒后，实际偏移 " + (at - t0) + "ms",
                at > t0 + 55_000L && at < t0 + 65_000L);
        assertEquals(1, shadow.getScheduledAlarms().size());
        assertEquals("测试闹钟不该写进待排索引（否则会被统计和开机重排带上）",
                0, Reminders.load(ctx).size());
    }

    @Test
    public void test_alarm_works_without_exact_alarm_permission() {
        // 这条是回归测试：schedule() 的返回值是「是否用上精确闹钟」，早期版本误当成「是否排成功」，
        // 于是没有精确闹钟权限时 scheduleTest 会错报失败、用户以为功能坏了。
        shadow.setCanScheduleExactAlarms(false);
        long t0 = System.currentTimeMillis();
        long at = ReminderScheduler.scheduleTest(ctx, 60, true, true);

        assertTrue("即便降级为不精确闹钟，也必须算排成功", at > t0 + 50_000L);
        assertEquals(1, shadow.getScheduledAlarms().size());
    }

    @Test
    public void test_alarm_clamps_out_of_range_seconds() {
        long t0 = System.currentTimeMillis();
        long tooSmall = ReminderScheduler.scheduleTest(ctx, 0, true, true);
        assertTrue("太短要兜到至少 5 秒", tooSmall >= t0 + 4_000L);

        long tooBig = ReminderScheduler.scheduleTest(ctx, 999999, true, true);
        assertTrue("太长要封顶到 1 小时", tooBig <= t0 + 3_601_000L);
    }
}
