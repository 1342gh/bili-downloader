package com.dsh.biliurl;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：把堆栈写到用户能直接看到的位置
 *   /sdcard/Download/Bili下载/crash-时间.log
 * 写不进公共目录时回落到 App 外部私有目录。
 */
public class CrashLogger {

    private static final String TAG = "biliurl";
    private static String lastLogPath;

    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                lastLogPath = write(ctx, thread, e);
            } catch (Throwable ignored) { }
            if (def != null) def.uncaughtException(thread, e);
        });
    }

    public static String lastLogPath() {
        return lastLogPath == null ? "" : lastLogPath;
    }

    private static String write(Context ctx, Thread thread, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("==== Bili下载 崩溃日志 ====");
        pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("版本: " + versionName(ctx));
        pw.println("设备: " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("系统: Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        pw.println("SoC:  " + MainActivity.socModel());
        pw.println("线程: " + (thread == null ? "?" : thread.getName()));
        pw.println();
        e.printStackTrace(pw);
        pw.println();
        pw.println("---- 线程栈 ----");
        for (StackTraceElement el : (thread == null ? new StackTraceElement[0] : thread.getStackTrace())) {
            pw.println("  at " + el);
        }
        pw.flush();
        String text = sw.toString();

        String name = "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log";

        // 首选：公共 Download/Bili下载（文件管理器直接能看到）
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Bili下载");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        out.write(text.getBytes("UTF-8"));
                        Log.i(TAG, "crash log -> Download/Bili下载/" + name);
                        return "/sdcard/Download/Bili下载/" + name;
                    }
                }
            }
        } catch (Throwable ignored) { }

        // 回落：App 外部私有目录
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(text.getBytes("UTF-8"));
            }
            Log.i(TAG, "crash log -> " + f.getAbsolutePath());
            return f.getAbsolutePath();
        } catch (Throwable ignored) { }

        return "";
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
