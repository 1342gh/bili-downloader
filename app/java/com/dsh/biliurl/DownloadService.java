package com.dsh.biliurl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 前台服务：下载/转码/插帧期间把进程提到前台，避免切后台被系统杀掉。
 * 真正的活儿仍在 MainActivity 的工作线程里，这里只负责保活 + 通知。
 */
public class DownloadService extends Service {

    private static final String CHANNEL = "bili_download";
    private static final int NOTIF_ID = 1001;

    private static PowerManager.WakeLock wakeLock;

    public static void start(Context ctx, String text) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.putExtra("text", text);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, DownloadService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? "正在处理…" : intent.getStringExtra("text");
        if (text == null || text.isEmpty()) text = "正在处理…";

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "下载任务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }

        Notification n = b.setContentTitle("Bili 下载")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();

        startForeground(NOTIF_ID, n);

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bili:download");
            wakeLock.acquire(30 * 60 * 1000L);   // 最多 30 分钟，防泄漏
        } catch (Throwable ignored) { }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) { }
        wakeLock = null;
        try {
            stopForeground(true);
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
