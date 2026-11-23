package com.dsh.biliurl;

import android.webkit.CookieManager;
import android.webkit.WebView;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 借 WebView（Chromium）发 API 请求。
 * Java 的 HttpURLConnection 走 B 站接口会被 412（TLS 指纹被识别），
 * 而 WebView 的 fetch 与浏览器一致，不会被拦。
 */
public class WebFetch {

    public interface Result {
        void onBody(String body);
    }

    public String lastStatus = "";
    private final WebView web;
    private final AtomicInteger seq = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Object[]> pending = new ConcurrentHashMap<>();

    public WebFetch(WebView web) {
        this.web = web;
    }

    /** 把 Cookie 写进 WebView 的 Cookie 存储，fetch 时会自动带上 */
    public void setCookie(String rawCookie) {
        if (rawCookie == null || rawCookie.isEmpty()) return;
        final String raw = rawCookie;
        Runnable task = () -> {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            int n = 0;
            // 用户可能只贴了 SESSDATA 的值，没有 name=
            String payload = raw.contains("=") ? raw : ("SESSDATA=" + raw);
            for (String part : payload.split(";")) {
                String p = part.trim();
                if (p.isEmpty() || !p.contains("=")) continue;
                cm.setCookie("https://api.bilibili.com", p);
                cm.setCookie("https://www.bilibili.com", p);
                n++;
            }
            cm.flush();
            String back = cm.getCookie("https://api.bilibili.com");
            android.util.Log.i("biliurl", "setCookie " + n + " 条 -> 回读 "
                    + (back == null ? "(空)" : back.length() + " 字符"));
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        }
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
    }

    /** 读回当前 WebView 里 api 域的 Cookie，用于诊断 */
    public String cookieNow() {
        try {
            String c = CookieManager.getInstance().getCookie("https://api.bilibili.com");
            String r = c == null ? "(空)" : (c.length() + " 字符: "
                    + c.substring(0, Math.min(40, c.length())));
            android.util.Log.i("biliurl", "cookieNow " + r);
            return r;
        } catch (Exception e) {
            return "读取失败 " + e;
        }
    }

    /** 同步请求：阻塞等 WebView 返回，最多 30 秒 */
    public String get(String url, long timeoutSeconds) {
        final String id = "f" + seq.incrementAndGet();
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] holder = new String[]{null};
        pending.put(id, new Object[]{latch, holder});

        final String js = "bili.fetchJson(" + org.json.JSONObject.quote(id) + ","
                + org.json.JSONObject.quote(url) + ")";
        web.post(() -> web.evaluateJavascript(js, null));

        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                pending.remove(id);
                lastStatus = "timeout";
                return null;
            }
        } catch (InterruptedException e) {
            pending.remove(id);
            return null;
        }
        pending.remove(id);
        String body = holder[0];
        lastStatus = body == null ? "null" : ("ok " + body.length() + " chars: "
                + body.substring(0, Math.min(60, body.length())));
        return body;
    }

    /** 供 JsBridge 调用 */
    public void complete(String id, String body) {
        Object[] slot = pending.get(id);
        if (slot == null) return;
        // slot[1] 是 String[] holder，要写进数组里，不能覆盖
        ((String[]) slot[1])[0] = body;
        ((CountDownLatch) slot[0]).countDown();
    }
}
