package com.agy.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import com.agy.shell.ShizukuBridge;
import com.agy.ui.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Notification bridge exposed to the Web UI and native agy-notify queue. */
public final class NotificationBridge {

    public static final String ALERTS_CHANNEL_ID = "agy_alerts_channel";
    public static final String AGENT_ALERTS_CHANNEL_ID = "agy_agent_alerts";
    public static final String AGENT_SILENT_CHANNEL_ID = "agy_agent_alerts_silent";
    public static final String EXTRA_NOTIFICATION_CASCADE_ID = "com.agy.extra.NOTIFICATION_CASCADE_ID";
    public static final int ALERT_NOTIFICATION_ID = 2002;
    public static final String ACTIVE_SNAPSHOT_FILE = ".notify_active.json";
    private static final String EXTRA_META_CASCADE_ID = "com.agy.meta.cascade_id";
    private static final int MAX_TITLE_LEN = 200;
    private static final int MAX_BODY_LEN = 2000;

    private final Context context;
    private final ShizukuBridge shizuku;

    public NotificationBridge(Context context, ShizukuBridge shizuku) {
        this.context = context.getApplicationContext();
        this.shizuku = shizuku;
    }

    public boolean post(String title, String message, String tag) {
        String cleanTag = (tag != null && !tag.trim().isEmpty())
                ? tag.trim()
                : "agy_" + System.currentTimeMillis();
        if (postLocal(context, title, message, cleanTag)) {
            return true;
        }
        if (shizuku != null && shizuku.isAvailable()) {
            String safeTitle = (title != null ? title : defaultAppTitle(context)).replace("'", "'\\''");
            String safeMessage = (message != null ? message : "").replace("'", "'\\''");
            String safeTag = cleanTag.replace("'", "'\\''");
            String cmd = "cmd notification post -S bigtext -t '" + safeTitle + "' '" + safeTag + "' '" + safeMessage + "'";
            shizuku.exec(cmd);
            return true;
        }
        return false;
    }

    public boolean postAgent(String title, String message, String tag, String cascadeId, boolean silent) {
        // Do NOT fall back to Shizuku `cmd notification post`:
        // 1) respects user's Android notification toggle/permission,
        // 2) preserves PendingIntent cascadeId deep-navigation.
        return postLocalAgent(context, title, message, tag, cascadeId, silent);
    }

    public boolean cancelForCascade(String cascadeId) {
        return cancelLocalForCascade(context, cascadeId);
    }

    public String list() {
        return listLocalJson(context);
    }

    private static String defaultAppTitle(Context ctx) {
        try {
            CharSequence label = ctx.getApplicationInfo().loadLabel(ctx.getPackageManager());
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Throwable ignored) {
        }
        return "Antigravity";
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    ALERTS_CHANNEL_ID,
                    "Agent Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
    }

    public static boolean postLocal(Context context, String title, String message, String tag) {
        if (context == null) return false;
        try {
            Context app = context.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            ensureChannel(nm);

            String resolvedTitle = (title != null && !title.trim().isEmpty()) ? title : defaultAppTitle(app);
            String resolvedBody = message != null ? message : "";
            String resolvedTag = (tag != null && !tag.trim().isEmpty()) ? tag : "agy_" + System.currentTimeMillis();

            int iconId = app.getResources().getIdentifier("ic_notification", "drawable", app.getPackageName());
            if (iconId == 0) {
                iconId = android.R.drawable.stat_notify_chat;
            }

            Intent launchIntent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launchIntent == null) {
                launchIntent = new Intent(app, MainActivity.class);
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(app, resolvedTag.hashCode(), launchIntent, piFlags);

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(app, ALERTS_CHANNEL_ID)
                    : new Notification.Builder(app);
            builder.setContentTitle(resolvedTitle)
                    .setContentText(resolvedBody)
                    .setStyle(new Notification.BigTextStyle().bigText(resolvedBody))
                    .setSmallIcon(iconId)
                    .setContentIntent(pi)
                    .setAutoCancel(true);

            nm.notify(resolvedTag, ALERT_NOTIFICATION_ID, builder.build());
            syncActiveSnapshot(app);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean cancelLocal(Context context, String tag) {
        if (context == null || tag == null || tag.trim().isEmpty()) return false;
        try {
            Context app = context.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            nm.cancel(tag.trim(), ALERT_NOTIFICATION_ID);
            syncActiveSnapshot(app);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String listLocalJson(Context context) {
        if (context == null) return "[]";
        try {
            Context app = context.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return "[]";
            StatusBarNotification[] active = nm.getActiveNotifications();
            JSONArray arr = new JSONArray();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (sbn == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("id", sbn.getId());
                    item.put("tag", sbn.getTag() != null ? sbn.getTag() : "");
                    if (sbn.getNotification() != null && sbn.getNotification().extras != null) {
                        CharSequence t = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                        CharSequence tx = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
                        if (t != null) item.put("title", t.toString());
                        if (tx != null) item.put("text", tx.toString());
                    }
                    arr.put(item);
                }
            }
            return arr.toString();
        } catch (Throwable ignored) {
            return "[]";
        }
    }

    public static boolean isValidCascadeId(String cascadeId) {
        if (cascadeId == null) return false;
        int len = cascadeId.length();
        if (len == 0 || len > 128) return false;
        for (int i = 0; i < len; i++) {
            char c = cascadeId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == ':';
            if (!ok) return false;
        }
        return true;
    }

    private static String clamp(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "\u2026";
    }

    public static boolean areNotificationsPermitted(Context context) {
        if (context == null) return false;
        try {
            Context app = context.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 33
                    && app.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureAgentChannels(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel loud = new NotificationChannel(
                    AGENT_ALERTS_CHANNEL_ID,
                    "Agent Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            loud.setDescription("Notifications when the agent needs permission or finishes a task.");
            nm.createNotificationChannel(loud);

            NotificationChannel silent = new NotificationChannel(
                    AGENT_SILENT_CHANNEL_ID,
                    "Agent Alerts (Silent)",
                    NotificationManager.IMPORTANCE_DEFAULT);
            silent.setDescription("Silent notifications when in-app agent sounds or voice mode are active.");
            silent.setSound(null, null);
            silent.enableVibration(false);
            nm.createNotificationChannel(silent);
        }
    }

    public static boolean postLocalAgent(Context context, String title, String message,
                                         String tag, String cascadeId, boolean silent) {
        if (context == null) return false;
        try {
            Context app = context.getApplicationContext();
            if (!areNotificationsPermitted(app)) {
                return false;
            }
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            ensureAgentChannels(nm);

            String resolvedTitle = (title != null && !title.trim().isEmpty())
                    ? clamp(title.trim(), MAX_TITLE_LEN)
                    : defaultAppTitle(app);
            String resolvedBody = clamp(message != null ? message.trim() : "", MAX_BODY_LEN);
            String resolvedTag = (tag != null && !tag.trim().isEmpty())
                    ? clamp(tag.trim(), 160)
                    : "agy_agent_" + System.currentTimeMillis();
            String safeCascade = (cascadeId != null && isValidCascadeId(cascadeId.trim()))
                    ? cascadeId.trim()
                    : "";

            int iconId = app.getResources().getIdentifier("ic_notification", "drawable", app.getPackageName());
            if (iconId == 0) {
                iconId = android.R.drawable.stat_notify_chat;
            }

            Intent launchIntent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launchIntent == null) {
                launchIntent = new Intent(app, MainActivity.class);
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (!safeCascade.isEmpty()) {
                launchIntent.putExtra(EXTRA_NOTIFICATION_CASCADE_ID, safeCascade);
            }

            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(app, resolvedTag.hashCode(), launchIntent, piFlags);

            String channelId = silent ? AGENT_SILENT_CHANNEL_ID : AGENT_ALERTS_CHANNEL_ID;
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(app, channelId)
                    : new Notification.Builder(app);

            builder.setContentTitle(resolvedTitle)
                    .setContentText(resolvedBody)
                    .setStyle(new Notification.BigTextStyle().bigText(resolvedBody))
                    .setSmallIcon(iconId)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(pi)
                    .setAutoCancel(true);

            if (!safeCascade.isEmpty()) {
                android.os.Bundle extras = new android.os.Bundle();
                extras.putString(EXTRA_META_CASCADE_ID, safeCascade);
                builder.addExtras(extras);
            }

            if (silent) {
                builder.setOnlyAlertOnce(true);
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    builder.setDefaults(0);
                    builder.setSound(null).setVibrate(null);
                    builder.setPriority(Notification.PRIORITY_DEFAULT);
                }
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setDefaults(Notification.DEFAULT_ALL);
                builder.setPriority(Notification.PRIORITY_HIGH);
            }

            nm.notify(resolvedTag, ALERT_NOTIFICATION_ID, builder.build());
            syncActiveSnapshot(app);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean cancelLocalForCascade(Context context, String cascadeId) {
        if (context == null || !isValidCascadeId(cascadeId)) return false;
        try {
            Context app = context.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            StatusBarNotification[] active = nm.getActiveNotifications();
            boolean canceledAny = false;
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (sbn == null || sbn.getId() != ALERT_NOTIFICATION_ID) continue;
                    Notification n = sbn.getNotification();
                    if (n == null || n.extras == null) continue;
                    String cid = n.extras.getString(EXTRA_META_CASCADE_ID);
                    if (cascadeId.equals(cid)) {
                        nm.cancel(sbn.getTag(), sbn.getId());
                        canceledAny = true;
                    }
                }
            }
            if (canceledAny) {
                syncActiveSnapshot(app);
            }
            return canceledAny;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void openNotificationSettings(Context context) {
        if (context == null) return;
        Context target = (MainActivity.activeActivity != null)
                ? MainActivity.activeActivity
                : context.getApplicationContext();
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, target.getPackageName());
            } else {
                intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + target.getPackageName()));
            }
            if (!(target instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            target.startActivity(intent);
        } catch (Throwable ignored) {
            try {
                Intent fallback = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallback.setData(android.net.Uri.parse("package:" + target.getPackageName()));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                target.startActivity(fallback);
            } catch (Throwable ignored2) {
            }
        }
    }

    public static void syncActiveSnapshot(Context context) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            String json = listLocalJson(app);
            File snap = new File(app.getFilesDir(), ACTIVE_SNAPSHOT_FILE);
            try (FileOutputStream fos = new FileOutputStream(snap)) {
                fos.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
