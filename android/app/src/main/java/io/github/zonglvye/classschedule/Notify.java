package io.github.zonglvye.classschedule;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/**
 * 通知渠道与通知构建。
 *
 * 为什么是 4 个渠道：Android 8 之后「响不响 / 震不震」由渠道决定，无法逐条覆盖，
 * 所以按「上课/作业 × 响铃/静音」建 4 个固定渠道，用户设置的响铃与震动才能真正生效。
 */
final class Notify {

    static final String CH_CLS_LOUD = "cls_loud";
    static final String CH_CLS_QUIET = "cls_quiet";
    static final String CH_TASK_LOUD = "task_loud";
    static final String CH_TASK_QUIET = "task_quiet";

    private Notify() { }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build();

        nm.createNotificationChannel(loud(CH_CLS_LOUD, "上课提醒（响铃）", sound, attrs));
        nm.createNotificationChannel(loud(CH_TASK_LOUD, "作业截止提醒（响铃）", sound, attrs));
        nm.createNotificationChannel(quiet(CH_CLS_QUIET, "上课提醒（静音）"));
        nm.createNotificationChannel(quiet(CH_TASK_QUIET, "作业截止提醒（静音）"));
    }

    private static NotificationChannel loud(String id, String name, Uri sound, AudioAttributes attrs) {
        NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("到点弹通知，带提示音与震动");
        ch.enableVibration(true);
        ch.enableLights(true);
        ch.setShowBadge(true);
        ch.setSound(sound, attrs);
        return ch;
    }

    private static NotificationChannel quiet(String id, String name) {
        NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("到点弹通知，但不响铃不震动");
        ch.enableVibration(false);
        ch.setSound(null, null);
        return ch;
    }

    static String channelFor(Reminders.Item it) {
        boolean task = "task".equals(it.kind);
        boolean loud = it.ring || it.vibrate;
        if (task) return loud ? CH_TASK_LOUD : CH_TASK_QUIET;
        return loud ? CH_CLS_LOUD : CH_CLS_QUIET;
    }

    static void show(Context c, Reminders.Item it) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(c, it.requestCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent donePi = action(c, ReminderReceiver.ACTION_DONE, it, 0x0D0AE);
        PendingIntent snoozePi = action(c, ReminderReceiver.ACTION_SNOOZE, it, 0x0A0E2);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(c, channelFor(it))
                : new Notification.Builder(c);

        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(it.title)
                .setContentText(it.body)
                .setStyle(new Notification.BigTextStyle().bigText(it.body))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(it.trig)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(contentPi)
                .addAction(0, "知道了", donePi)
                .addAction(0, "稍后 10 分钟", snoozePi);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH);
            if (it.ring) {
                b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            } else {
                b.setSound(null);
            }
            if (it.vibrate) b.setVibrate(new long[]{0, 220, 90, 220});
        }

        try {
            nm.notify(it.requestCode(), b.build());
        } catch (Throwable t) {
            android.util.Log.e("CS.Notify", "notify failed", t);
        }
    }

    private static PendingIntent action(Context c, String action, Reminders.Item it, int salt) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(action);
        i.putExtra(ReminderReceiver.EXTRA_ITEM, it.toJsonString());
        i.putExtra(ReminderReceiver.EXTRA_CODE, it.requestCode());
        return PendingIntent.getBroadcast(c, it.requestCode() ^ salt, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancel(Context c, int id) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(id);
    }
}
