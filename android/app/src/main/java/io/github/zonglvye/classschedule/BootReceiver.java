package io.github.zonglvye.classschedule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 应用更新后重排闹钟。
 *
 * 没有这一步，「重启一次提醒全丢」——这是绝大多数自制课表 App 的通病。
 * 这里不需要前端参与：提醒点连同时间早就持久化好了。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "CS.Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        Log.i(TAG, "boot event: " + action);
        Context ctx = context.getApplicationContext();
        try {
            Notify.ensureChannels(ctx);
            int n = ReminderScheduler.reschedule(ctx);
            Log.i(TAG, "rescheduled " + n + " alarms");
        } catch (Throwable t) {
            Log.e(TAG, "reschedule failed", t);
        }
    }
}
