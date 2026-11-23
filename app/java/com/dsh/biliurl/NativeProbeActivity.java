package com.dsh.biliurl;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 调试入口：跑一遍 C++ 核心（解析 → view → playurl），把 JSON 写进 logcat 和屏幕。
 * 用 adb 启动即可，不参与正常流程：
 *
 *   adb shell am start -n com.dsh.biliurl/.NativeProbeActivity \
 *       --es url "BV1GJ411x7h7" --es cookie "SESSDATA=..."
 */
public class NativeProbeActivity extends Activity {

    private static final String TAG = "biliurl-native";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final TextView view = new TextView(this);
        view.setTextSize(11);
        view.setPadding(24, 24, 24, 24);
        view.setText("running...");
        // 下载期间别让屏幕睡：熄屏后 WiFi 进入省电，DNS/连接会被切断（实测过）
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        setContentView(scroll);

        final String url = getIntent().getStringExtra("url");
        final String cookie = getIntent().getStringExtra("cookie");
        final boolean doDownload = getIntent().getBooleanExtra("download", false);

        new Thread(() -> {
            String result;
            try {
                if (doDownload) {
                    result = NativeBridge.download(url == null ? "BV1GJ411x7h7" : url,
                            cookie == null ? "" : cookie, getCacheDir().getAbsolutePath());
                } else {
                    result = NativeBridge.probe(url == null ? "BV1GJ411x7h7" : url, cookie == null ? "" : cookie);
                }
            } catch (Throwable t) {
                result = "{\"ok\":false,\"error\":\"" + t + "\"}";
            }
            Log.i(TAG, (doDownload ? "DOWNLOAD " : "PROBE ") + result);
            final String text = result;
            runOnUiThread(() -> view.setText(text.replace(",", ",\n")));
        }, "native-probe").start();
    }
}
