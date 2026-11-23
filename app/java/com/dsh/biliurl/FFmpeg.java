package com.dsh.biliurl;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 调用打包进 APK 的 ffmpeg（放在 lib/arm64-v8a/libffmpeg.so，运行时从 nativeLibraryDir 执行）。 */
public class FFmpeg {

    public static String path(Context ctx) {
        return ctx.getApplicationInfo().nativeLibraryDir + "/libffmpeg.so";
    }

    public static boolean available(Context ctx) {
        return new File(path(ctx)).exists();
    }

    public static void run(Context ctx, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(path(ctx));
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 6000) sb.delete(0, 3000);
            }
        }
        int rc = p.waitFor();
        if (rc != 0) {
            String tail = sb.toString().trim();
            if (tail.length() > 600) tail = tail.substring(tail.length() - 600);
            throw new Exception("ffmpeg 失败 (exit " + rc + ")\n" + tail);
        }
    }
}
