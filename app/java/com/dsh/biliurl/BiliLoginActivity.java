package com.dsh.biliurl;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * B 站网页登录：用户在这里正常登录，App 从 CookieManager 里取出 Cookie 自动回填。
 *
 * 注意 B 站登录页是单页应用，登录成功后不一定触发 onPageFinished，
 * 所以这里除了页面回调，还每秒轮询一次 Cookie，拿到 SESSDATA 就返回。
 */
public class BiliLoginActivity extends Activity {

    public interface OnCookie {
        void onCookie(String cookie);
    }

    public static OnCookie callback;

    private WebView web;
    private TextView tip;
    private boolean done;
    /** 进页面时的旧 SESSDATA：只有它变了（=真的重新登录了）才算成功 */
    private String initialSessdata;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 每秒看一次 Cookie；B 站登录后不一定有页面跳转，光靠 onPageFinished 会漏 */
    private void schedulePoll() {
        handler.postDelayed(() -> {
            if (done) return;
            checkCookie();
            if (!done) schedulePoll();
        }, 1000);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        wp.topMargin = dp(44);
        root.addView(web, wp);

        tip = new TextView(this);
        tip.setText("在下方页面登录 B 站，成功后自动返回");
        tip.setTextSize(12.5f);
        tip.setTextColor(0xFF6B7280);
        tip.setGravity(Gravity.CENTER_VERTICAL);
        tip.setPadding(dp(16), 0, dp(16), 0);
        tip.setBackgroundColor(0xFFF2F4F8);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        root.addView(tip, tp);

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 15; Xperia XZ3) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        initialSessdata = valueOf(cm.getCookie("https://www.bilibili.com"), "SESSDATA");
        android.util.Log.i("biliurl-login", "login page open, existing sessdata="
                + (initialSessdata == null ? "none" : "yes"));

        web.setWebViewClient(new LoginPageClient(this));
        web.loadUrl("https://www.bilibili.com/");
        schedulePoll();
    }

    void checkCookie() {
        if (done) return;
        String ck = CookieManager.getInstance().getCookie("https://www.bilibili.com");
        if (ck == null || !ck.contains("SESSDATA=")) {
            ck = CookieManager.getInstance().getCookie("https://api.bilibili.com");
        }
        if (ck == null || !ck.contains("SESSDATA=")) return;

        // 只保留有用的几项，拼成一行 Cookie
        String[] keys = {"SESSDATA", "bili_jct", "buvid3", "DedeUserID"};
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            String value = valueOf(ck, key);
            if (value != null && !value.isEmpty()) {
                if (out.length() > 0) out.append("; ");
                out.append(key).append('=').append(value);
            }
        }
        if (out.length() == 0) return;

        // 旧的会话还在 CookieManager 里时不能算登录成功，否则点「登录」会被直接弹回去
        String sess = valueOf(ck, "SESSDATA");
        if (sess != null && sess.equals(initialSessdata)) return;

        done = true;
        handler.removeCallbacksAndMessages(null);
        if (callback != null) callback.onCookie(out.toString());
        android.widget.Toast.makeText(this, "登录成功，已自动填入", android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    private static String valueOf(String cookie, String key) {
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1);
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) checkCookie();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
