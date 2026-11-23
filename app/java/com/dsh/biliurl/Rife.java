package com.dsh.biliurl;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** RIFE AI 插帧（JNI，ncnn + Vulkan）。模型从 assets 复制到 filesDir 后加载。 */
public class Rife {

    static {
        System.loadLibrary("rifejni");
    }

    private static boolean ready = false;

    public static native boolean init(String modelDir, int threads, String logPath);

    public static native void release();

    public static native int[] interp(int[] a, int[] b, int w, int h, float t);

    /** 形如 "Adreno (TM) 630 | fp16_storage=0 ..." */
    public static native String deviceInfo();

    public static native boolean supportsFp16();

    public static native int gpuCount();

    /** 首次调用时把 assets/rife 下的模型解出来，再加载。 */
    public static synchronized boolean ensureReady(Context ctx) {
        if (ready) return true;
        try {
            File dir = new File(ctx.getFilesDir(), "rife-v4.6");
            File param = new File(dir, "flownet.param");
            File bin = new File(dir, "flownet.bin");
            if (!param.exists() || !bin.exists()) {
                if (!dir.exists() && !dir.mkdirs()) return false;
                copyAsset(ctx, "rife/flownet.param", param);
                copyAsset(ctx, "rife/flownet.bin", bin);
            }
            ready = init(dir.getAbsolutePath(), 2, logFile(ctx).getAbsolutePath());
            return ready;
        } catch (Exception e) {
            return false;
        }
    }

    static File logFile(Context ctx) {
        File d = ctx.getExternalFilesDir(null);
        if (d == null) d = ctx.getFilesDir();
        if (!d.exists()) d.mkdirs();
        return new File(d, "rife_debug.txt");
    }

    public static synchronized void shutdown() {
        if (ready) {
            release();
            ready = false;
        }
    }

    private static void copyAsset(Context ctx, String name, File dst) throws Exception {
        try (InputStream in = ctx.getAssets().open(name);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
