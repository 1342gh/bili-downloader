package com.dsh.biliurl;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 界面由 WebView 渲染，Java 只做下载 / 转码 / 插帧。 */
public class MainActivity extends Activity {

    private static final Map<Integer, String> QUALITY = new HashMap<>();

    static {
        QUALITY.put(127, "8K");
        QUALITY.put(126, "杜比视界");
        QUALITY.put(125, "HDR");
        QUALITY.put(120, "4K");
        QUALITY.put(116, "1080P60");
        QUALITY.put(112, "1080P+");
        QUALITY.put(80, "1080P");
        QUALITY.put(74, "720P60");
        QUALITY.put(64, "720P");
        QUALITY.put(32, "480P");
        QUALITY.put(16, "360P");
    }

    private WebView web;
    private WebFetch webFetch;

    private String pendingUrl;
    private boolean pendingAuto;
    private String pendingCookie;
    private String pendingPluginUrl;
    private String pendingUpdateUrl;
    private int pendingContent = -1;
    private String pendingFormat;
    private int pendingRes = -1;
    private int pendingFps = -1;
    private boolean pendingInterp;
    /** 调试/深链：带 play=true 启动时直接走播放，而不是下载 */
    private boolean pendingPlay;
    /** 每次启动只自动查一次更新 */
    private boolean autoUpdateChecked;
    /** 调试/深链：直接带上 cid 和标题，省掉 view 请求 */
    private long pendingCid;
    private String pendingTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 这台机器（Xperia XZ3 / Android 15）到 B 站 CDN 的 IPv6 是不通的：
        // 实测 curl -6 零字节、-4 正常 1 MB/s。默认双栈会优先 IPv6，下载就卡死。
        System.setProperty("java.net.preferIPv4Stack", "true");
        CrashLogger.install(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 15; Xperia XZ3) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        s.setTextZoom(100);

        BiliLoginActivity.callback = ck ->
                runOnUiThread(() -> web.evaluateJavascript(
                        "bili.setCookie(" + JSONObject.quote(ck) + ")", null));

        webFetch = new WebFetch(web);
        web.addJavascriptInterface(new JsBridge(this), "Android");
        web.setWebViewClient(new PageClient(this));

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        readIntent(getIntent());
        // 播放不依赖 WebView（API 走直连、播放器是原生），所以不用等页面加载：
        // 冷启动时直接开跑，能省掉整个页面加载时间。
        if (pendingPlay && pendingUrl != null) {
            final String u = pendingUrl;
            final String ck = pendingCookie;
            final long pc = pendingCid;
            final String pt = pendingTitle;
            pendingUrl = null;
            pendingPlay = false;
            playFromWeb(u, ck, pc, pt);
        }
        web.loadUrl("https://www.bilibili.com/dshapp/");
        setContentView(web);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || web == null) return;
        readIntent(intent);
        installPendingPlugin();
        if (pendingUrl != null) {
            String u = pendingUrl;
            boolean auto = pendingAuto;
            boolean play = pendingPlay;
            pendingUrl = null;
            pendingPlay = false;
            web.evaluateJavascript("bili.setUrl(" + JSONObject.quote(u) + ")", null);
            if (play) {
                playFromWeb(u, pendingCookie, pendingCid, pendingTitle);
            } else if (auto) {
                web.evaluateJavascript("bili.autoStart()", null);
            }
        }
    }

    private void readIntent(Intent it) {
        if (it == null) return;
        String preset = null;
        boolean auto = false;

        if (Intent.ACTION_SEND.equals(it.getAction())) {
            preset = it.getStringExtra(Intent.EXTRA_TEXT);
            auto = true;
        } else if (Intent.ACTION_VIEW.equals(it.getAction()) && it.getData() != null) {
            preset = it.getData().toString();
            auto = true;
        } else if (it.getStringExtra("url") != null) {
            preset = it.getStringExtra("url");
            auto = it.getBooleanExtra("auto", false);
        }

        if (preset != null && !preset.trim().isEmpty()) {
            String p = preset.trim();
            if (p.startsWith("http") && p.toLowerCase().contains(".js")) {
                int nl = p.indexOf('\n');
                if (nl > 0) p = p.substring(0, nl).trim();
                pendingPluginUrl = p;
            } else {
                pendingUrl = p;
                pendingAuto = auto;
            }
        }

        pendingContent = it.getIntExtra("content", -1);
        pendingFormat = it.getStringExtra("format");
        pendingRes = it.getIntExtra("res", -1);
        pendingFps = it.getIntExtra("fps", -1);
        pendingInterp = it.getBooleanExtra("interp", false);
        pendingPlay = it.getBooleanExtra("play", false);
        pendingCid = it.getLongExtra("cid", 0);
        if (pendingCid == 0) {
            String cidText = it.getStringExtra("cid");   // adb -e 传进来的是字符串
            if (cidText != null) {
                try { pendingCid = Long.parseLong(cidText.trim()); } catch (Exception ignored) { }
            }
        }
        pendingTitle = it.getStringExtra("title");
        String up = it.getStringExtra("update");
        if (up != null && !up.trim().isEmpty()) pendingUpdateUrl = up.trim();

        String ck = it.getStringExtra("cookie");
        String ckFile = it.getStringExtra("cookiefile");
        if (ckFile != null && !ckFile.isEmpty()) {
            try {
                byte[] raw = java.nio.file.Files.readAllBytes(new File(ckFile).toPath());
                ck = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) { }
        }
        if (ck != null && !ck.trim().isEmpty()) pendingCookie = ck.trim();
    }

    private void installPendingPlugin() {
        if (pendingPluginUrl == null) return;
        final String pu = pendingPluginUrl;
        pendingPluginUrl = null;
        new Thread(() -> {
            if (installPlugin(pu)) {
                runOnUiThread(() -> web.evaluateJavascript("reloadPlugins && reloadPlugins()", null));
            }
        }).start();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    String buildUiHtml() {
        String html = readAsset("web/index.html");
        String css = readAsset("web/style.css");
        String js = readAsset("web/app.js");
        html = html.replace("<link rel=\"stylesheet\" href=\"style.css\">",
                "<style>\n" + css + "\n</style>");
        html = html.replace("<script src=\"app.js\"></script>",
                "<script>\n" + js + "\n</script>");
        return html;
    }

    void onPageDone() {
        pushDeviceInfo();
        // 启动后自动查一次更新：默认源是 GitHub Release 的 latest.json，
        // 直连不通会自动换镜像；自动检查失败不打扰用户。
        if (!autoUpdateChecked) {
            autoUpdateChecked = true;
            web.postDelayed(() -> checkUpdateAsync(false), 3000);
        }
        applyTheme();
        if (pendingUpdateUrl != null) {
            setUpdateSource(pendingUpdateUrl);
            web.evaluateJavascript("var u=document.getElementById('updateUrl');if(u)u.value="
                    + JSONObject.quote(pendingUpdateUrl) + ";", null);
            pendingUpdateUrl = null;
            checkUpdateAsync();
        }
        installPendingPlugin();

        if (pendingCookie != null) {
            web.evaluateJavascript("bili.setCookie(" + JSONObject.quote(pendingCookie) + ")", null);
        }

        if (pendingContent >= 0 || pendingFormat != null || pendingRes >= 0
                || pendingFps >= 0 || pendingInterp) {
            web.evaluateJavascript("bili.setOptions("
                    + pendingContent + ","
                    + (pendingFormat == null ? "null" : JSONObject.quote(pendingFormat)) + ","
                    + pendingRes + ","
                    + pendingFps + ","
                    + pendingInterp + ")", null);
        }

        if (pendingUrl != null) {
            String u = pendingUrl;
            boolean auto = pendingAuto;
            boolean play = pendingPlay;
            pendingUrl = null;
            pendingPlay = false;
            web.evaluateJavascript("bili.setUrl(" + JSONObject.quote(u) + ")", null);
            if (play) {
                playFromWeb(u, pendingCookie, pendingCid, pendingTitle);
            } else if (auto) {
                web.evaluateJavascript("bili.autoStart()", null);
            }
        }
    }

    private void pushDeviceInfo() {
        String soc = socModel();
        boolean aiOk = !Boolean.FALSE.equals(socAllowsAi(soc));
        web.evaluateJavascript("bili.device(" + JSONObject.quote(soc) + "," + aiOk + ")", null);
    }

    void onFetchResult(String id, String body) {
        webFetch.complete(id, body);
    }

    private void jsLog(String text, String cls) {
        final String t = text == null ? "" : text;
        final String c = cls;
        runOnUiThread(() -> web.evaluateJavascript(
                "bili.log(" + JSONObject.quote(t) + ","
                        + (c == null ? "null" : JSONObject.quote(c)) + ")", null));
    }

    private void jsLog(String text) {
        jsLog(text, null);
    }

    private void jsProgress(int pct) {
        final int p = Math.max(0, Math.min(100, pct));
        runOnUiThread(() -> web.evaluateJavascript("bili.progress(" + p + ",true)", null));
    }

    private void jsRunning(boolean on) {
        final boolean v = on;
        runOnUiThread(() -> web.evaluateJavascript("bili.running(" + v + ")", null));
    }

    private void jsHideProgress() {
        runOnUiThread(() -> web.evaluateJavascript("bili.progress(0,false)", null));
    }

    // ------------------------------------------------------------ 深色模式

    String themeMode() {
        return getSharedPreferences("bili", MODE_PRIVATE).getString("theme_mode", "auto");
    }

    void setThemeMode(String mode) {
        if (mode == null) mode = "auto";
        if (!mode.equals("auto") && !mode.equals("light") && !mode.equals("dark")) mode = "auto";
        getSharedPreferences("bili", MODE_PRIVATE).edit().putString("theme_mode", mode).apply();
        applyTheme();
    }

    private boolean isNight() {
        String mode = themeMode();
        if ("dark".equals(mode)) return true;
        if ("light".equals(mode)) return false;
        int cfg = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return cfg == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyTheme() {
        runOnUiThread(() -> applyThemeUi());
    }

    private void applyThemeUi() {
        boolean dark = isNight();
        try {
            web.setBackgroundColor(dark ? 0xFF0F1115 : 0xFFF2F4F8);
        } catch (Exception ignored) { }
        try {
            web.evaluateJavascript("bili.setTheme(" + dark + ","
                    + JSONObject.quote(themeMode()) + ")", null);
        } catch (Exception ignored) { }
        try {
            getWindow().setStatusBarColor(dark ? 0xFF0F1115 : 0xFFF2F4F8);
            getWindow().setNavigationBarColor(dark ? 0xFF0F1115 : 0xFFF2F4F8);
            android.view.View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) {
                flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Exception ignored) { }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyTheme();
    }

    private void jsHook(String event, JSONObject info) {
        final String ev = event;
        final String data = (info == null ? "{}" : info.toString());
        runOnUiThread(() -> web.evaluateJavascript(
                "bili.hook(" + JSONObject.quote(ev) + "," + data + ")", null));
    }

    String filterKeywords() {
        return getSharedPreferences("bili", MODE_PRIVATE).getString("filter_keywords", "");
    }

    String filterChannels() {
        return getSharedPreferences("bili", MODE_PRIVATE).getString("filter_channels", "");
    }

    void saveFilters(String keywords, String channels) {
        getSharedPreferences("bili", MODE_PRIVATE).edit()
                .putString("filter_keywords", keywords == null ? "" : keywords)
                .putString("filter_channels", channels == null ? "" : channels)
                .apply();
    }

    private String hitFilter(String title, String channel) {
        for (String line : filterKeywords().split("\n")) {
            String k = line.trim();
            if (!k.isEmpty() && title != null
                    && title.toLowerCase().contains(k.toLowerCase())) {
                return "关键词 " + k;
            }
        }
        for (String line : filterChannels().split("\n")) {
            String c = line.trim();
            if (!c.isEmpty() && channel != null
                    && channel.toLowerCase().contains(c.toLowerCase())) {
                return "UP " + c;
            }
        }
        return null;
    }

    void checkLoginAsync(String cookie) {
        final String ck = cookie == null ? "" : cookie.trim();
        if (!ck.isEmpty()) {
            // 顺手存一份：万一 WebView 的 localStorage 被清，下次还能从这恢复
            getSharedPreferences("bili", MODE_PRIVATE).edit().putString("sessdata", ck).apply();
        }
        new Thread(() -> {
            String name = "";
            boolean vip = false, logged = false, failed = false;
            String body = null;
            try {
                if (!ck.isEmpty()) {
                    webFetch.setCookie(ck);
                    body = webFetch.get(NAV_URL, 20);
                    if (body == null || !body.contains("isLogin")) {
                        body = null;
                        // WebView 那条路取不到（跨域/未就绪）→ 直连再试一次
                        body = httpGetWithCookie(NAV_URL, ck, 12);
                    }
                }
                if (body != null) {
                    JSONObject d = new JSONObject(body).optJSONObject("data");
                    if (d != null) {
                        Object lf = d.opt("isLogin");
                        logged = (lf instanceof Boolean) ? (Boolean) lf
                                : (lf instanceof Number && ((Number) lf).intValue() == 1);
                        name = d.optString("uname", "");
                        Object vf = d.opt("vipStatus");
                        vip = (vf instanceof Boolean) ? (Boolean) vf
                                : (vf instanceof Number && ((Number) vf).intValue() == 1);
                    } else {
                        failed = true;
                    }
                } else if (!ck.isEmpty()) {
                    failed = true;   // 有 Cookie 但两条路都取不到 → 网络问题，别误报"未登录"
                }
            } catch (Exception e) {
                failed = !ck.isEmpty();
            }
            android.util.Log.i("biliurl-login", "check -> logged=" + logged + " failed=" + failed
                    + " name=" + name);
            final String n = logged ? name : "";
            final boolean v = logged && vip;
            final boolean ok = logged;
            final boolean f = failed && !logged;
            runOnUiThread(() -> web.evaluateJavascript(
                    "bili.account(" + JSONObject.quote(n) + "," + v + "," + ok + "," + f + ")", null));
        }).start();
    }

    /** 带 Cookie 的直连 GET（登录态检查的兜底） */
    private String httpGetWithCookie(String url, String cookie, int timeoutSec) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(timeoutSec * 1000);
            c.setReadTimeout(timeoutSec * 1000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", BiliApi.UA);
            c.setRequestProperty("Referer", "https://www.bilibili.com/");
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
    }
    void startFromWeb(String url, String cookie, int content, String format,
                      int resHeight, int fps, boolean interp) {
        if (url == null || url.trim().isEmpty()) {
            jsLog("先把链接粘进来。", "err");
            return;
        }
        webFetch.setCookie(cookie);

        if (interp && Boolean.FALSE.equals(socAllowsAi(socModel()))) {
            jsLog("AI 插帧需要骁龙 7+ Gen 2 及以上（当前：" + socModel() + "）", "err");
            jsLog("低端 GPU 不支持 fp16 storage，插帧会慢到不可用。", "dim");
            jsLog("可以把视频拷到电脑上用 rife_interp.py 处理。", "dim");
            return;
        }
        startWorkFromWeb(url, cookie, content, format, resHeight, fps, interp);
    }

    void startWorkFromWeb(String raw, String cookie, int content, String format,
                          int resHeight, int fps, boolean doInterp) {
        final List<String> urls = splitUrls(raw);
        if (urls.isEmpty()) {
            jsLog("没解析出链接。", "err");
            return;
        }

        final String ck = cookie;
        final int ct = content;
        final String fmt = format;
        final int rh = resHeight;
        final int fp = fps;
        final boolean interp = doInterp;

        runOnUiThread(() -> web.evaluateJavascript("bili.clear()", null));
        jsRunning(true);
        jsProgress(0);
        DownloadService.start(this, urls.size() > 1
                ? (urls.size() + " 个任务处理中") : "正在处理…");

        new Thread(() -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < urls.size(); i++) {
                final String u = urls.get(i);
                jsLog("=== [" + (i + 1) + "/" + urls.size() + "] " + u, "dim");
                try {
                    work(u, ck, ct, fmt, rh, fp, interp);
                    ok++;
                    try {
                        JSONObject info = new JSONObject();
                        info.put("url", u);
                        info.put("format", fmt);
                        jsHook("done", info);
                    } catch (Exception ignored) { }
                } catch (Exception e) {
                    fail++;
                    jsLog("错误：" + e.getMessage(), "err");
                    try {
                        JSONObject info = new JSONObject();
                        info.put("url", u);
                        info.put("message", String.valueOf(e.getMessage()));
                        jsHook("error", info);
                    } catch (Exception ignored) { }
                }
                jsProgress(0);
            }
            if (urls.size() > 1) jsLog("\n全部结束：成功 " + ok + "，失败 " + fail, "ok");
            jsRunning(false);
            jsHideProgress();
            DownloadService.stop(this);
        }).start();
    }

    private static List<String> splitUrls(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            Matcher m = Pattern.compile("(https?://\\S+|BV[0-9A-Za-z]{10}|av\\d+)").matcher(s);
            boolean found = false;
            while (m.find()) {
                out.add(m.group(1));
                found = true;
            }
            if (!found) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------ 应用内更新

    /**
     * 更新源。没配置过就用默认的 GitHub Release 清单；
     * 如果用户手动清空（保存空字符串），就视为关闭自动检查。
     */
    String updateSource() {
        String v = getSharedPreferences("bili", MODE_PRIVATE).getString("update_url", null);
        if (v == null) return DEFAULT_UPDATE_URL;
        return v.trim();
    }

    void setUpdateSource(String url) {
        getSharedPreferences("bili", MODE_PRIVATE).edit()
                .putString("update_url", url == null ? "" : url.trim()).apply();
    }

    private int currentVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 默认更新源：GitHub Release 上的 latest.json（直连不通时自动走下面的加速域名） */
    private static final String DEFAULT_UPDATE_URL =
            "https://github.com/1342gh/bili-downloader/releases/latest/download/latest.json";

    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";

    /** GitHub 直连不通时的镜像前缀（按顺序尝试） */
    private static final String[] MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghfast.top/"
    };

    /** 返回候选 URL：原始 + 镜像（只对 github 域名生效） */
    private static String[] urlCandidates(String url) {
        if (url == null) return new String[0];
        boolean gh = url.startsWith("https://github.com/")
                || url.startsWith("https://raw.githubusercontent.com/")
                || url.startsWith("https://objects.githubusercontent.com/");
        if (!gh) return new String[]{ url };
        String[] out = new String[MIRRORS.length + 1];
        out[0] = url;
        for (int i = 0; i < MIRRORS.length; i++) out[i + 1] = MIRRORS[i] + url;
        return out;
    }
    /** 直接 HTTP GET 取文本（不经过 WebView，避免跨域限制） */
    private String httpGetText(String url, int timeoutSec) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(timeoutSec * 1000);
            c.setReadTimeout(timeoutSec * 1000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) BiliDownload");
            c.setRequestProperty("Accept", "application/json,*/*");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
    }
    /** 手动检查（「保存并检查」按钮 / 调试用） */
    void checkUpdateAsync() {
        checkUpdateAsync(true);
    }

    /**
     * 检查更新。manual=false 是启动时自动查：失败不吭声，没新版本也不刷日志，
     * 只有真的发现新版本才弹横幅。
     */
    void checkUpdateAsync(final boolean manual) {
        final String src = updateSource();
        if (src.isEmpty()) {
            if (manual) {
                jsLog("没配置更新源（高级 → 更新里填一个 URL）", "err");
                jsUpdateResult("", false);
            }
            return;
        }
        new Thread(() -> {
            String body = null;
            String hit = null;
            String[] cands = urlCandidates(src);
            for (int c = 0; c < cands.length && body == null; c++) {
                for (int i = 1; i <= 2 && body == null; i++) {
                    body = httpGetText(cands[c], 8);
                    if (body != null && !body.contains("versionCode")) body = null;
                    if (body != null) hit = (c == 0 ? "direct" : "mirror" + c);
                    if (body == null && i < 2) {
                        try { Thread.sleep(800L); } catch (InterruptedException ignored) { }
                    }
                }
                if (body == null && c < cands.length - 1) {
                    jsLog("直连失败，改用镜像…", "dim");
                }
            }
            android.util.Log.i("biliurl-update", (manual ? "manual" : "auto") + " check -> "
                    + (body == null ? "failed" : (hit + ": " + body.substring(0, Math.min(80, body.length())))));
            if (body == null && !manual) return;   // 自动检查失败就静默
            jsUpdateResult(body == null ? "" : body, !manual);
        }).start();
    }

    private void jsUpdateResult(String body, boolean quiet) {
        final String b = body == null ? "" : body;
        final int code = currentVersionCode();
        final String name = currentVersionName();
        runOnUiThread(() -> web.evaluateJavascript(
                "bili.updateResult(" + JSONObject.quote(b) + "," + code + ","
                        + JSONObject.quote(name) + "," + quiet + ")", null));
    }

    /** 下载到文件：断点续传 + 自动重试，边下边更新进度 */
    private void downloadWithResume(String url, File tmp, int attempts, int connectMs) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            java.net.HttpURLConnection c = null;
            try {
                long existing = tmp.exists() ? tmp.length() : 0;
                c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(120000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) BiliDownload");
                if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");

                int code = c.getResponseCode();
                boolean append = (code == 206) && existing > 0;
                if (code != 200 && code != 206) throw new Exception("HTTP " + code);

                long total = c.getContentLengthLong();
                if (append) total += existing; else existing = 0;
                if (total <= 0) total = 0;

                java.io.InputStream in = c.getInputStream();
                FileOutputStream fos = new FileOutputStream(tmp, append);
                byte[] buf = new byte[64 * 1024];
                long done = existing;
                int lastPct = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        final int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int p = pct;
                            runOnUiThread(() -> jsProgress(p));
                        }
                    }
                }
                fos.flush();
                fos.close();
                in.close();

                if (total > 0 && tmp.length() < total) {
                    throw new Exception("下载不完整 " + tmp.length() + "/" + total);
                }
                return;
            } catch (Exception e) {
                last = e;
                jsLog("下载中断（" + e.getMessage() + "），第 " + attempt + "/3 次重试…", "dim");
                try { Thread.sleep(1500L * attempt); } catch (InterruptedException ignored) { }
            } finally {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
            }
        }
        throw new Exception(last == null ? "下载失败" : last.getMessage());
    }
    /** 下载更新包到 Download 目录，然后拉起系统安装器 */
    void downloadUpdateAsync(String url, String fileName) {
        if (url == null || url.trim().isEmpty()) return;
        final String u = url.trim();
        final String name = (fileName == null || fileName.trim().isEmpty())
                ? "BiliDownload-update.apk" : fileName.trim();
        jsLog("开始下载更新…");
        jsProgress(0);

        new Thread(() -> {
            File tmp = new File(getCacheDir(), "update.apk");
            try {
                String[] cands = urlCandidates(u);
                Exception dlErr = null;
                for (int c = 0; c < cands.length; c++) {
                    try {
                        downloadWithResume(cands[c], tmp, 1, 12000);
                        dlErr = null;
                        break;
                    } catch (Exception e) {
                        dlErr = e;
                        if (c < cands.length - 1) jsLog("下载失败，换镜像重试…", "dim");
                    }
                }
                if (dlErr != null) {
                    jsLog("全部线路失败，再试一轮…", "dim");
                    for (int c = 0; c < cands.length; c++) {
                        try {
                            downloadWithResume(cands[c], tmp, 1, 12000);
                            dlErr = null;
                            break;
                        } catch (Exception e) {
                            dlErr = e;
                        }
                    }
                }
                if (dlErr != null) throw dlErr;                jsLog("下载完成 " + String.format("%.1f MB", tmp.length() / 1048576.0), "ok");

                // 写进公共 Download 目录，拿到 content:// 再交给安装器
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Bili下载");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IOException("写入下载目录失败");
                try (InputStream fin = new FileInputStream(tmp);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("打开输出流失败");
                    byte[] b2 = new byte[256 * 1024];
                    int r;
                    while ((r = fin.read(b2)) > 0) out.write(b2, 0, r);
                }
                tmp.delete();

                // 没有「安装未知应用」权限就先引导去开
                if (android.os.Build.VERSION.SDK_INT >= 26
                        && !getPackageManager().canRequestPackageInstalls()) {
                    jsLog("需要先允许「安装未知应用」，已打开设置", "err");
                    runOnUiThread(() -> {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception ignored) { }
                    });
                    return;
                }

                final Uri apkUri = uri;
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        // 直接指定系统安装器，避免弹「用哪个应用打开」
                        boolean launched = false;
                        String[] pkgs = { "com.android.packageinstaller",
                                "com.google.android.packageinstaller" };
                        for (String p : pkgs) {
                            try {
                                Intent t = new Intent(i);
                                t.setPackage(p);
                                startActivity(t);
                                launched = true;
                                break;
                            } catch (Exception ignored) { }
                        }
                        if (!launched) startActivity(i);
                        jsLog("已调起安装器，按提示安装即可", "ok");
                    } catch (Exception e) {
                        jsLog("调起安装器失败：" + e.getMessage(), "err");
                    }
                });
            } catch (Exception e) {
                jsLog("更新下载失败：" + e.getMessage(), "err");
            } finally {
                try { jsHideProgress(); } catch (Exception ignored) { }
            }
        }).start();
    }
    // ------------------------------------------------------------ 浏览

    // ------------------------------------------------------------ 取 JSON（带重试与缓存）

    private String lastFeedBody = "";
    private String lastSearchBody = "";

    /** B 站接口：要求 code:0 */
    private String fetchJsonWithRetry(String url, String label) {
        return fetchJsonWithRetry(url, label, true);
    }

    /**
     * 请求 JSON：最多 3 次，带退避。
     * requireOk = true 时要求响应含 "code":0（B 站接口）；
     * 更新清单之类的普通 JSON 传 false。
     */
    private String fetchJsonWithRetry(String url, String label, boolean requireOk) {
        for (int i = 1; i <= 3; i++) {
            String body = null;
            try {
                body = webFetch.get(url, 12);
            } catch (Exception ignored) { }
            if (body != null && body.trim().startsWith("{")) {
                if (!requireOk || body.contains("\"code\":0")) return body;
            }
            if (i < 3) {
                jsLog(label + " 请求失败，重试 " + i + "/2…", "dim");
                try { Thread.sleep(600L * i); } catch (InterruptedException ignored) { }
            }
        }
        return null;
    }
    private int feedPage = 1;

    void searchAsync(String keyword, int page) {
        final String kw = keyword == null ? "" : keyword.trim();
        final int pn = Math.max(1, page);
        if (kw.isEmpty()) return;
        new Thread(() -> {
            String body = "";
            try {
                BiliApi api = new BiliApi("", webFetch);
                for (int i = 1; i <= 3 && body.isEmpty(); i++) {
                    JSONObject r = api.search(kw, pn);
                    if (r.optInt("code") == 0) {
                        body = r.toString();
                        lastSearchBody = body;
                    } else {
                        jsLog("搜索重试 " + i + "/3…", "dim");
                        try { Thread.sleep(600L * i); } catch (InterruptedException ignored) { }
                    }
                }
                if (body.isEmpty()) body = lastSearchBody;
            } catch (Exception e) {
                jsLog("搜索失败：" + e.getMessage(), "err");
                if (!lastSearchBody.isEmpty()) body = lastSearchBody;
            }
            final String b = body;
            runOnUiThread(() -> web.evaluateJavascript(
                    "bili.searchResult(" + JSONObject.quote(b) + ")", null));
        }).start();
    }
    void loadFeedAsync(String kind, int page) {
        final int pn = Math.max(1, page);
        new Thread(() -> {
            String url;
            if ("rank".equals(kind)) {
                url = "https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all";
            } else {
                url = "https://api.bilibili.com/x/web-interface/popular?ps=20&pn=" + pn;
            }
            String body = fetchJsonWithRetry(url, "列表");
            if (body == null) {
                body = lastFeedBody;      // 失败时用上次成功的结果兜底
            } else {
                lastFeedBody = body;
            }
            final String b = body == null ? "" : body;
            jsLog("feed 返回：" + (body == null ? "null" : (body.length() + " 字符")), "dim");
            runOnUiThread(() -> web.evaluateJavascript(
                    "bili.feed(" + JSONObject.quote(b) + ")", null));
        }).start();
    }
    // ------------------------------------------------------------ 播放

    void playFromWeb(String url, String cookie) {
        playFromWeb(url, cookie, 0, null);
    }

    /**
     * 播放入口。cidHint/titleHint 来自浏览列表（feed 接口自带 cid），
     * 有的话就能跳过 view 接口，少一次网络往返；再配合下面的播放地址缓存，
     * 点「播放」几乎是瞬时的。
     */
    void playFromWeb(final String url, final String cookie, final long cidHint, final String titleHint) {
        if (url == null || url.trim().isEmpty()) {
            jsLog("先把链接粘进来。", "err");
            return;
        }
        final long playStart = System.currentTimeMillis();
        webFetch.setCookie(cookie);
        jsRunning(true);
        jsProgress(0);
        DownloadService.start(this, "准备播放…");



        new Thread(() -> {
            try {
                BiliApi api = new BiliApi(cookie);
                BiliApi.Target t = BiliApi.parse(url.trim());
                if (t == null) throw new Exception("看不懂这个链接");

                String bvid = t.bvid;
                long cid = cidHint;
                String title = titleHint == null ? "" : titleHint;
                boolean viaWebFetch = false;

                // 没有 cid 才去请求 view（浏览列表已经带了 cid，可省一次请求）
                if (cid <= 0) {
                    JSONObject info;
                    try {
                        info = api.view(t);
                    } catch (Exception directFailed) {
                        jsLog("直连失败，改用 WebView 取数…", "dim");
                        api = new BiliApi(cookie, webFetch);
                        viaWebFetch = true;
                        info = api.view(t);
                    }
                    bvid = info.optString("bvid", bvid);
                    cid = info.optLong("cid");
                    if (title.isEmpty()) title = info.optString("title", "播放");
                }
                if (title.isEmpty()) title = bvid;
                final String playTitle = title;
                android.util.Log.i("biliurl-play", "resolve " + bvid + "/" + cid
                        + " in " + (System.currentTimeMillis() - playStart) + "ms (cidHint=" + (cidHint > 0) + ")");

                // 播放地址缓存：同一视频 5 分钟内重播直接命中
                BiliApi.Progressive stream = cachedStream(bvid, cid);
                if (stream != null) {
                    jsLog("播放地址已缓存（命中）", "dim");
                } else {
                    try {
                        stream = api.progressive(bvid, cid, 80);
                    } catch (Exception ignored) {
                    }
                    if (stream == null && !viaWebFetch) {
                        try {
                            api = new BiliApi(cookie, webFetch);
                            stream = api.progressive(bvid, cid, 80);
                        } catch (Exception ignored) {
                        }
                    }
                    if (stream != null) cacheStream(bvid, cid, stream);
                }

                if (stream != null) {
                    long probeAt = System.currentTimeMillis();
                    stream.url = pickFastestStream(stream);
                    long proxyAt = System.currentTimeMillis();
                    final String localUrl = StreamProxy.start(stream.urls, cookie, getCacheDir());
                    android.util.Log.i("biliurl-play", "url ready in " + (proxyAt - playStart) + "ms, proxy in "
                            + (System.currentTimeMillis() - proxyAt) + "ms");
                    jsLog("流式播放：" + playTitle);
                    jsLog("画质：" + QUALITY.getOrDefault(stream.quality, "?") + "，"
                            + String.format("%.1f MB", stream.size / 1048576.0)
                            + (stream.urls.size() > 1 ? "，节点 " + hostOf(stream.url) : ""), "dim");
                    jsProgress(100);
                    jsLog("开始播放 ✓", "ok");
                    jsRunning(false);
                    jsHideProgress();
                    DownloadService.stop(this);
                    final String playUrl = localUrl != null ? localUrl : stream.url;
                    final String fallback = stream.url;
                    final String ua = BiliApi.UA;
                    final String ck = cookie;
                    runOnUiThread(() -> {
                        Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                        i.putExtra("url", playUrl);
                        i.putExtra("ua", ua);
                        i.putExtra("cookie", localUrl != null ? "" : (ck == null ? "" : ck));
                        if (localUrl != null) i.putExtra("fallback", fallback);
                        i.putExtra("title", playTitle);
                        startActivity(i);
                    });
                    return;
                }

                jsLog("没有流式地址，改走下载合并…", "dim");
                File f = preparePlayback(url.trim(), cookie);
                jsProgress(100);
                jsLog("开始播放 ✓", "ok");
                jsRunning(false);
                jsHideProgress();
                DownloadService.stop(this);
                runOnUiThread(() -> {
                    Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                    i.putExtra("path", f.getAbsolutePath());
                    i.putExtra("title", f.getName());
                    startActivity(i);
                });
            } catch (Exception e) {
                jsLog("播放失败：" + e.getMessage(), "err");

                jsRunning(false);
                jsHideProgress();
                DownloadService.stop(this);
            }
        }).start();
    }

    /** 预取播放地址（列表加载完就对第一条调用），点播放时直接命中缓存 */
    void prefetchPlay(String url, String cookie, long cid) {
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            try {
                BiliApi api = new BiliApi(cookie);
                BiliApi.Target t = BiliApi.parse(url);
                if (t == null) return;
                long useCid = cid;
                String bvid = t.bvid;
                if (useCid <= 0) {
                    JSONObject info = api.view(t);
                    bvid = info.optString("bvid", bvid);
                    useCid = info.optLong("cid");
                }
                if (cachedStream(bvid, useCid) != null) return;
                BiliApi.Progressive p = api.progressive(bvid, useCid, 80);
                if (p != null) {
                    cacheStream(bvid, useCid, p);
                    android.util.Log.i("biliurl-play", "prefetch ok " + bvid + "/" + useCid);
                }
            } catch (Exception e) {
                android.util.Log.w("biliurl-play", "prefetch failed: " + e);
            }
        }, "play-prefetch").start();
    }

    // ---- 播放地址缓存：同一次使用中重播不重复请求 ----
    private static final long PLAY_CACHE_TTL = 5 * 60 * 1000L;
    private static final java.util.Map<String, BiliApi.Progressive> PLAY_CACHE =
            new java.util.LinkedHashMap<String, BiliApi.Progressive>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, BiliApi.Progressive> eldest) {
                    return size() > 8;
                }
            };
    private static final java.util.Map<String, Long> PLAY_CACHE_AT = new java.util.HashMap<>();

    private static synchronized BiliApi.Progressive cachedStream(String bvid, long cid) {
        String key = bvid + "|" + cid;
        BiliApi.Progressive p = PLAY_CACHE.get(key);
        Long at = PLAY_CACHE_AT.get(key);
        if (p == null || at == null) return null;
        if (System.currentTimeMillis() - at > PLAY_CACHE_TTL) {
            PLAY_CACHE.remove(key);
            PLAY_CACHE_AT.remove(key);
            return null;
        }
        return p;
    }

    private static synchronized void cacheStream(String bvid, long cid, BiliApi.Progressive p) {
        String key = bvid + "|" + cid;
        PLAY_CACHE.put(key, p);
        PLAY_CACHE_AT.put(key, System.currentTimeMillis());
    }
    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * 播放前给候选节点做快速测速：每个地址只读前 8KB，1.5 秒无响应就换下一个。
     * 有的节点 TCP 握手成功但不出数据（本机实测常见），必须读到字节才算通。
     */
    private static String pickFastestStream(BiliApi.Progressive p) {
        if (p.urls == null || p.urls.isEmpty()) return p.url;
        if (p.urls.size() == 1) return p.urls.get(0);
        // B 站给的 backup_url 经常是同一个 host，那就没必要白测一轮
        java.util.Set<String> hosts = new java.util.HashSet<>();
        for (String u : p.urls) hosts.add(hostOf(u));
        if (hosts.size() <= 1) return p.urls.get(0);
        for (String u : p.urls) {
            java.net.HttpURLConnection c = null;
            java.io.InputStream in = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                c.setRequestProperty("User-Agent", BiliApi.UA);
                c.setRequestProperty("Referer", "https://www.bilibili.com/");
                c.setRequestProperty("Range", "bytes=0-8191");
                c.setRequestProperty("Connection", "close");
                int code = c.getResponseCode();
                if (code == 200 || code == 206) {
                    in = c.getInputStream();
                    byte[] buf = new byte[4096];
                    if (in.read(buf) > 0) return u;
                }
            } catch (Exception ignored) {
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) { }
                if (c != null) c.disconnect();
            }
        }
        return p.urls.get(0);
    }

    /** 播放用：优先 1080P 以内（4K 下载太慢），返回最合适的流 */
    private static JSONObject pickPlaybackVideo(JSONObject dash) {
        JSONArray arr = dash.optJSONArray("video");
        if (arr == null) return null;
        JSONObject best = null;
        long bestScore = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int w = o.optInt("width");
            int h = o.optInt("height");
            int longSide = Math.max(w, h);
            if (longSide > 1920) continue;          // 超过 1080P 不要
            String codec = o.optString("codecs");
            long pref = codec.startsWith("avc") ? 3 : codec.startsWith("hvc") ? 2 : 1;
            long score = o.optLong("id") * 1_000_000_000L + o.optLong("bandwidth") * 10L + pref;
            if (score > bestScore) { bestScore = score; best = o; }
        }
        return best != null ? best : BiliApi.bestVideo(dash);
    }
    /** 下载最高画质音视频并合流，返回合并后的缓存文件 */
    private File preparePlayback(String text, String cookie) throws Exception {
        BiliApi api = new BiliApi(cookie, webFetch);
        BiliApi.Target t = BiliApi.parse(text);
        if (t == null) throw new Exception("看不懂这个链接");

        JSONObject info = api.view(t);
        String bvid = info.getString("bvid");
        String title = info.getString("title");
        long cid = info.getLong("cid");

        JSONObject play = api.playurl(bvid, cid);
        JSONObject dash = play.optJSONObject("dash");
        if (dash == null) throw new Exception("没有 dash 流");
        JSONObject v = pickPlaybackVideo(dash);
        JSONObject a = BiliApi.bestAudio(dash);
        if (v == null || a == null) throw new Exception("音视频流缺失");

        jsLog("播放：" + title);
        jsLog("画质：" + QUALITY.getOrDefault(v.optInt("id"), "?") + " "
                + v.optInt("width") + "x" + v.optInt("height"), "dim");

        File dir = new File(getCacheDir(), "play");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("缓存目录创建失败");
        File[] olds = dir.listFiles();
        if (olds != null) for (File old : olds) old.delete();

        final File vf = new File(dir, "v.m4s");
        final File af = new File(dir, "a.m4s");

        jsLog("缓存视频…");
        api.download(BiliApi.urls(v), vf, (done, total) -> {
            if (total > 0) jsProgress((int) (done * 80 / total));
        });
        jsLog("缓存音频…");
        api.download(BiliApi.urls(a), af, (done, total) -> {
            if (total > 0) jsProgress(80 + (int) (done * 15 / total));
        });

        jsLog("合并音视频…");
        File merged = new File(dir, "play.mp4");
        Merger.merge(vf, af, merged, true, true);
        jsLog("  " + String.format("%.1f MB", merged.length() / 1048576.0), "dim");
        return merged;
    }

    private void work(String text, String cookie, int content,
                      String format, int resHeight, int fps, boolean doInterp) throws Exception {

        if (text.startsWith("gen:")) {
            File dst = new File(getCacheDir(), "gen.mp4");
            FFmpeg.run(this, "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30",
                    "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p", dst.getAbsolutePath());
            jsLog("已生成测试片 " + String.format("%.0f KB", dst.length() / 1024.0));
            File outFile = new File(getCacheDir(), "interp_gen.mp4");
            interpolate(dst, outFile);
            saveToMediaStore(outFile, "interp_gen.mp4", "video/mp4", "Movies/BiliUrl");
            return;
        }

        if (text.startsWith("asset:")) {
            String name = text.substring(6);
            File dir = new File(getCacheDir(), "sample");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建样例目录");
            File dst = new File(dir, new File(name).getName());
            try (InputStream in = getAssets().open(name);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            jsLog("样例文件：" + name);
            File outFile = new File(getCacheDir(), "interp_sample.mp4");
            interpolate(dst, outFile);
            saveToMediaStore(outFile, "interp_sample.mp4", "video/mp4", "Movies/BiliUrl");
            return;
        }

        String local = text.startsWith("file://") ? text.substring(7)
                : (text.startsWith("/") ? text : null);
        if (local != null) {
            File in = new File(local);
            if (!in.exists()) throw new Exception("文件不存在：" + local);
            jsLog("本地文件：" + in.getName());
            File outFile = new File(getCacheDir(), "interp_" + in.getName());
            interpolate(in, outFile);
            saveToMediaStore(outFile, "interp_" + in.getName(), "video/mp4", "Movies/BiliUrl");
            return;
        }

        BiliApi api = new BiliApi(cookie, webFetch);

        BiliApi.Target t = BiliApi.parse(text);
        if (t == null) throw new Exception("看不懂这个链接，需要 bilibili.com/video/BV... 或 BV 号");

        JSONObject nav = api.nav();
        if (nav != null) {
            Object vf = nav.opt("vipStatus");
            boolean vip = (vf instanceof Boolean) ? (Boolean) vf
                    : (vf instanceof Number && ((Number) vf).intValue() == 1);
            jsLog("已登录：" + nav.optString("uname") + "（"
                    + (vip ? "大会员" : "普通用户") + "）", "ok");
        } else if (cookie != null && !cookie.isEmpty()) {
            jsLog("SESSDATA 无效或已过期 —— 只能用匿名画质（最高 480P）", "err");
        }

        JSONObject info = api.view(t);
        String bvid = info.getString("bvid");
        String title = info.getString("title");
        long cid = info.getLong("cid");
        int pageIdx = 1;

        JSONArray pages = info.optJSONArray("pages");
        if (pages != null && pages.length() > 1) {
            jsLog("这个视频有 " + pages.length() + " 个分P");
            if (t.page >= 1 && t.page <= pages.length()) {
                pageIdx = t.page;
            } else {
                pageIdx = 1;
                jsLog("默认取第 1 P（想下别的P，在链接后加 ?p=N）", "dim");
            }
        }

        JSONObject owner = info.optJSONObject("owner");
        String upName = owner == null ? "" : owner.optString("name");

        String hit = hitFilter(title, upName);
        if (hit != null) {
            jsLog("已按过滤规则跳过（命中 " + hit + "）：" + title, "dim");
            return;
        }

        jsLog("标题：" + title);
        if (!upName.isEmpty()) jsLog("UP：" + upName);

        JSONObject play = api.playurl(bvid, cid);

        JSONObject dash = play.optJSONObject("dash");
        JSONObject v = dash == null ? null : BiliApi.bestVideo(dash);
        JSONObject a = dash == null ? null : BiliApi.bestAudio(dash);
        if (v == null) throw new Exception("没拿到视频流（可能是付费内容）");

        jsLog("可用画质：" + String.join(" / ", BiliApi.qualityList(dash)), "dim");
        jsLog("源画质：" + QUALITY.getOrDefault(v.optInt("id"), "?")
                + "  " + v.optInt("width") + "x" + v.optInt("height"));
        if ((cookie == null || cookie.isEmpty()) && v.optInt("id") < 80) {
            jsLog("提示：没填 SESSDATA，最高只有 480P；可用下面的分辨率放大", "dim");
        }

        boolean needVideo = content != 2;
        boolean needAudio = content == 0 || content == 2;
        if (needAudio && a == null) throw new Exception("这个视频没有音频流");

        File tmp = new File(getCacheDir(), "bili");
        if (!tmp.exists() && !tmp.mkdirs()) throw new IOException("无法创建临时目录");
        File[] olds = tmp.listFiles();
        if (olds != null) for (File old : olds) old.delete();

        File vf = null, af = null;
        if (needVideo) {
            vf = new File(tmp, "video.m4s");
            jsLog("下载视频…");
            download(api, BiliApi.urls(v), vf, 0, needAudio ? 45 : 70);
        }
        if (needAudio) {
            af = new File(tmp, "audio.m4s");
            jsLog("下载音频…");
            download(api, BiliApi.urls(a), af, needVideo ? 45 : 0, 40);
        }

        jsLog("合并音视频…");
        File merged = new File(tmp, needVideo ? "merged.mp4" : "merged.m4a");
        Merger.merge(vf, af, merged, needVideo, needAudio);

        boolean needFfmpeg = needVideo
                ? (!format.equals("MP4") || resHeight > 0 || fps > 0)
                : !format.equals("M4A");
        File finalFile = merged;
        String ext = needVideo ? ".mp4" : ".m4a";

        if (doInterp && needVideo) {
            jsLog("AI 插帧中（很慢，保持屏幕常亮）…");
            File interpFile = new File(tmp, "interp.mp4");
            interpolate(merged, interpFile);
            finalFile = interpFile;
            ext = ".mp4";
        } else if (needFfmpeg) {
            if (!FFmpeg.available(this)) throw new Exception("打包的 ffmpeg 缺失，无法转码/放大");
            jsLog("转码中…" + describe(format, resHeight, fps));
            finalFile = new File(tmp, "out" + extOf(format));
            ext = extOf(format);

            List<String> args = new ArrayList<>();
            args.add("-i");
            args.add(merged.getAbsolutePath());

            if (!needVideo) {
                args.add("-vn");
                args.add("-c:a");
                args.add(audioCodec(format));
                String br = audioBitrate(format);
                if (br != null) {
                    args.add("-b:a");
                    args.add(br);
                }
            } else {
                String filters = videoFilters(resHeight, fps);
                if (filters != null) {
                    args.add("-vf");
                    args.add(filters);
                }
                if (filters == null && isCopySafe(format)) {
                    args.add("-c:v");
                    args.add("copy");
                } else {
                    args.add("-c:v");
                    args.add("libx264");
                    args.add("-preset");
                    args.add("veryfast");
                    args.add("-crf");
                    args.add("20");
                }
                if (needAudio) {
                    args.add("-c:a");
                    args.add("copy");
                } else {
                    args.add("-an");
                }
            }
            args.add(finalFile.getAbsolutePath());
            FFmpeg.run(this, args.toArray(new String[0]));
            jsLog("  ffmpeg 完成  " + String.format("%.1f MB", finalFile.length() / 1048576.0));
        }

        String safe = sanitize(pages != null && pages.length() > 1 ? title + "_P" + pageIdx : title);
        String suffix = (resHeight > 0 ? "_" + resHeight + "p" : "")
                + (fps > 0 ? "_" + fps + "fps" : "");
        String mime = mimeOf(format);
        String rel = needVideo ? "Movies/BiliUrl" : "Music/BiliUrl";
        saveToMediaStore(finalFile, safe + suffix + ext, mime, rel);

        jsProgress(100);
        jsLog("完成 ✓ " + safe + suffix + ext + "  ("
                + String.format("%.1f MB", finalFile.length() / 1048576.0) + ")", "ok");
        jsLog("目录：" + rel, "dim");
    }

    private void download(BiliApi api, List<String> urls, final File dst,
                          final int basePercent, final int weightPercent) throws Exception {
        // 优先走 C++ 核心：断点续传 + 多地址切换（CDN 会中途 reset，Java 老实现拿不全）
        try {
            final long began = System.currentTimeMillis();
            final JSONObject r = new JSONObject(NativeBridge.downloadUrls(
                    urls.toArray(new String[0]), dst.getAbsolutePath(), api.cookieValue()));
            if (!r.optBoolean("ok")) throw new Exception(r.optString("error", "下载失败"));
            jsProgress(basePercent + weightPercent);
            jsLog("  [C++] " + dst.getName() + " 完成  "
                    + String.format("%.1f MB", r.optLong("bytes") / 1048576.0)
                    + "（" + (System.currentTimeMillis() - began) + "ms）", "ok");
            return;
        } catch (Throwable e) {
            jsLog("  C++ 下载器失败，回退 Java：" + e.getMessage(), "dim");
        }
        api.download(urls, dst, (done, total) -> {
            if (total > 0) jsProgress(basePercent + (int) (done * weightPercent / total));
        });
        jsLog("  " + dst.getName() + " 完成  "
                + String.format("%.1f MB", dst.length() / 1048576.0));
    }

    private void saveToMediaStore(File src, String name, String mime, String relPath) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);

        Uri collection = mime.startsWith("audio")
                ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        Uri uri = getContentResolver().insert(collection, cv);
        if (uri == null) throw new IOException("写入媒体库失败");
        try (InputStream in = new FileInputStream(src);
             OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("打开输出流失败");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String extOf(String fmt) {
        switch (fmt) {
            case "MKV": return ".mkv";
            case "MOV": return ".mov";
            case "M4A": return ".m4a";
            case "MP3": return ".mp3";
            case "FLAC": return ".flac";
            case "WAV": return ".wav";
            default: return ".mp4";
        }
    }

    private static String mimeOf(String fmt) {
        switch (fmt) {
            case "MKV": return "video/x-matroska";
            case "MOV": return "video/quicktime";
            case "M4A": return "audio/mp4";
            case "MP3": return "audio/mpeg";
            case "FLAC": return "audio/flac";
            case "WAV": return "audio/wav";
            default: return "video/mp4";
        }
    }

    private static boolean isCopySafe(String fmt) {
        return fmt.equals("MP4") || fmt.equals("MKV") || fmt.equals("MOV");
    }

    private static String audioCodec(String fmt) {
        switch (fmt) {
            case "MP3": return "libmp3lame";
            case "FLAC": return "flac";
            case "WAV": return "pcm_s16le";
            default: return "copy";
        }
    }

    private static String audioBitrate(String fmt) {
        return fmt.equals("MP3") ? "192k" : null;
    }

    private static String videoFilters(int resHeight, int fps) {
        List<String> f = new ArrayList<>();
        if (resHeight > 0) f.add("scale=-2:" + resHeight + ":flags=lanczos");
        if (fps > 0) f.add("fps=" + fps);
        return f.isEmpty() ? null : String.join(",", f);
    }

    private static String describe(String fmt, int resHeight, int fps) {
        StringBuilder sb = new StringBuilder();
        sb.append("→ ").append(fmt);
        if (resHeight > 0) sb.append(" / ").append(resHeight).append("p");
        if (fps > 0) sb.append(" / ").append(fps).append("fps");
        return sb.toString();
    }

    private void interpolate(File input, File output) throws Exception {
        if (!Rife.ensureReady(this)) throw new Exception("RIFE 模型加载失败");
        jsLog("SoC：" + socModel());
        jsLog("GPU：" + Rife.deviceInfo());

        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(input.getAbsolutePath());
        int srcW = 0, srcH = 0;
        boolean hasAudio = false;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;
            if (mime.startsWith("video/") && srcW == 0) {
                srcW = f.getInteger(MediaFormat.KEY_WIDTH);
                srcH = f.getInteger(MediaFormat.KEY_HEIGHT);
                if (f.containsKey(MediaFormat.KEY_ROTATION)) {
                    int rot = f.getInteger(MediaFormat.KEY_ROTATION);
                    if (rot == 90 || rot == 270) {
                        int tmp = srcW;
                        srcW = srcH;
                        srcH = tmp;
                    }
                }
            } else if (mime.startsWith("audio/")) {
                hasAudio = true;
            }
        }
        ex.release();
        if (srcW <= 0 || srcH <= 0) throw new Exception("读不到视频尺寸");

        boolean fp16 = Rife.supportsFp16();
        int h = fp16 ? 720 : 480;
        int w = (int) Math.round(srcW * (h / (double) srcH));
        if (w % 2 != 0) w++;
        if (!fp16) jsLog("该 GPU 不支持 fp16 storage → 自动降到 480P 提速", "dim");

        File extDir = getExternalFilesDir(null);
        if (extDir == null) extDir = getCacheDir();

        List<String> dec = new ArrayList<>();
        dec.add(FFmpeg.path(this));
        dec.add("-v");
        dec.add("error");
        dec.add("-i");
        dec.add(input.getAbsolutePath());
        dec.add("-vf");
        dec.add("scale=" + w + ":" + h);
        dec.add("-pix_fmt");
        dec.add("rgb24");
        dec.add("-f");
        dec.add("rawvideo");
        dec.add("-");
        ProcessBuilder dpb = new ProcessBuilder(dec);
        dpb.redirectError(new File(extDir, "rife_dec.log"));
        Process decProc = dpb.start();

        List<String> enc = new ArrayList<>();
        enc.add(FFmpeg.path(this));
        enc.add("-y");
        enc.add("-v");
        enc.add("error");
        enc.add("-f");
        enc.add("rawvideo");
        enc.add("-pix_fmt");
        enc.add("rgb24");
        enc.add("-s");
        enc.add(w + "x" + h);
        enc.add("-r");
        enc.add("60");
        enc.add("-i");
        enc.add("-");
        if (hasAudio) {
            enc.add("-i");
            enc.add(input.getAbsolutePath());
            enc.add("-map");
            enc.add("0:v:0");
            enc.add("-map");
            enc.add("1:a:0");
        } else {
            enc.add("-map");
            enc.add("0:v:0");
        }
        enc.add("-c:v");
        enc.add("libx264");
        enc.add("-preset");
        enc.add("veryfast");
        enc.add("-crf");
        enc.add("20");
        enc.add("-pix_fmt");
        enc.add("yuv420p");
        if (hasAudio) {
            enc.add("-c:a");
            enc.add("copy");
            enc.add("-shortest");
        }
        enc.add(output.getAbsolutePath());
        ProcessBuilder epb = new ProcessBuilder(enc);
        epb.redirectError(new File(extDir, "rife_enc.log"));
        Process encProc = epb.start();

        DataInputStream in = new DataInputStream(
                new BufferedInputStream(decProc.getInputStream(), 1 << 20));
        BufferedOutputStream out = new BufferedOutputStream(
                encProc.getOutputStream(), 1 << 20);

        int frameBytes = w * h * 3;
        byte[] buf = new byte[frameBytes];
        int[] prev = null;
        long frames = 0;
        long started = System.currentTimeMillis();
        long interpNs = 0;

        try {
            while (true) {
                int read = 0;
                try {
                    while (read < frameBytes) {
                        int n = in.read(buf, read, frameBytes - read);
                        if (n < 0) break;
                        read += n;
                    }
                } catch (Exception e) {
                    break;
                }
                if (read < frameBytes) break;

                int[] cur = new int[w * h];
                for (int i = 0, p = 0; i < w * h; i++, p += 3) {
                    int r = buf[p] & 0xff, g = buf[p + 1] & 0xff, b = buf[p + 2] & 0xff;
                    cur[i] = 0xff000000 | (r << 16) | (g << 8) | b;
                }

                if (prev == null) {
                    prev = cur;
                    continue;
                }

                writeFrame(out, prev, w, h);
                long ti = System.nanoTime();
                int[] mid = Rife.interp(prev, cur, w, h, 0.5f);
                interpNs += System.nanoTime() - ti;
                if (mid != null) writeFrame(out, mid, w, h);
                prev = cur;

                frames++;
                if (frames % 15 == 0) {
                    long secs = (System.currentTimeMillis() - started) / 1000;
                    jsLog("  已插帧 " + frames + " 组，用时 " + secs + "s", "dim");
                }
            }
            if (prev != null) writeFrame(out, prev, w, h);
        } finally {
            try { out.flush(); out.close(); } catch (Exception ignored) { }
            try { in.close(); } catch (Exception ignored) { }
            decProc.destroy();
            encProc.waitFor();
            Rife.shutdown();
        }

        double wall = (System.currentTimeMillis() - started) / 1000.0;
        jsLog("  耗时：总 " + String.format("%.1f", wall) + "s，其中推理 "
                + String.format("%.1f", interpNs / 1e9) + "s（"
                + String.format("%.0f", interpNs / 1e6 / Math.max(1, frames)) + " ms/组）", "dim");
        jsLog("  插帧完成，共 " + frames + " 组，输出 "
                + String.format("%.1f MB", output.length() / 1048576.0), "ok");
    }

    private static void writeFrame(BufferedOutputStream out, int[] argb, int w, int h)
            throws IOException {
        byte[] row = new byte[w * 3];
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int c = argb[base + x];
                row[x * 3] = (byte) ((c >> 16) & 0xff);
                row[x * 3 + 1] = (byte) ((c >> 8) & 0xff);
                row[x * 3 + 2] = (byte) (c & 0xff);
            }
            out.write(row);
        }
    }

    String pluginDirPath() {
        File dir = new File(getExternalFilesDir(null), "plugins");
        if (!dir.exists()) dir.mkdirs();
        return dir.getAbsolutePath();
    }

    String loadPluginsJson() {
        File dir = new File(pluginDirPath());
        if (!dir.exists() && !dir.mkdirs()) return "[]";

        File[] existing = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".js"));
        if (existing == null || existing.length == 0) {
            try (InputStream in = getAssets().open("web/sample-plugin.js");
                 OutputStream out = new FileOutputStream(new File(dir, "示例插件.js"))) {
                byte[] buf = new byte[1 << 14];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception ignored) { }
            existing = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".js"));
        }

        JSONArray arr = new JSONArray();
        if (existing == null) return arr.toString();
        java.util.Arrays.sort(existing, java.util.Comparator.comparing(File::getName));
        for (File f : existing) {
            try {
                byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("code", new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                arr.put(o);
            } catch (Exception ignored) { }
        }
        return arr.toString();
    }

    boolean installPlugin(String url) {
        android.util.Log.i("biliurl", "installPlugin " + url);
        if (url == null || url.trim().isEmpty()) return false;
        String u = url.trim();
        if (!u.startsWith("http")) return false;
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 BiliDownload/2.0");
            int code = c.getResponseCode();
            android.util.Log.i("biliurl", "installPlugin http " + code);
            if (code != 200) return false;

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 512 * 1024) return false;
                    bos.write(buf, 0, n);
                }
            }
            String src = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            if (!src.contains("Bili.plugin")) {
                jsLog("插件安装失败：文件里没有 Bili.plugin(...) 声明", "err");
                return false;
            }

            String name = u.substring(u.lastIndexOf('/') + 1);
            int q = name.indexOf('?');
            if (q > 0) name = name.substring(0, q);
            if (!name.toLowerCase().endsWith(".js")) name = name + ".js";
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");

            File out = new File(pluginDirPath(), name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bos.toByteArray());
            }
            jsLog("已安装插件：" + name, "ok");
            return true;
        } catch (Exception e) {
            jsLog("插件安装失败：" + e.getMessage(), "err");
            return false;
        }
    }

    boolean deletePlugin(String fileName) {
        if (fileName == null) return false;
        File f = new File(pluginDirPath(), new File(fileName).getName());
        return f.exists() && f.delete();
    }

    boolean createPluginTemplate() {
        try {
            File f = new File(pluginDirPath(), "我的插件.js");
            if (f.exists()) return false;
            String t = "// 插件模板：改完保存，点 App 里的「重新加载」即可生效\n"
                    + "// 可用 ctx 方法：log / button / on / setUrl / setOption / options\n\n"
                    + "Bili.plugin({\n"
                    + "  name: '我的插件',\n"
                    + "  version: '1.0',\n"
                    + "  author: '你的名字',\n"
                    + "  description: '一句话说明这个插件做什么',\n\n"
                    + "  onLoad(ctx) {\n"
                    + "    ctx.log('插件已加载');\n"
                    + "    ctx.button('填入测试链接', () => {\n"
                    + "      ctx.setUrl('https://www.bilibili.com/video/BV1GJ411x7h7');\n"
                    + "    });\n"
                    + "  },\n\n"
                    + "  onStart(opts) { },\n"
                    + "  onDone(info)  { },\n"
                    + "  onError(info) { }\n"
                    + "});\n";
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            jsLog("已生成模板：我的插件.js", "ok");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String socModel() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                String s = android.os.Build.SOC_MODEL;
                if (s != null && !s.isEmpty() && !s.equals("unknown")) return s;
            }
        } catch (Throwable ignored) { }
        for (String key : new String[]{"ro.soc.model", "ro.board.platform"}) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String line = r.readLine();
                r.close();
                p.waitFor();
                if (line != null && !line.trim().isEmpty()) return line.trim();
            } catch (Exception ignored) { }
        }
        return android.os.Build.HARDWARE == null ? "" : android.os.Build.HARDWARE;
    }

    public static Boolean socAllowsAi(String soc) {
        if (soc == null) return null;
        String s = soc.toUpperCase().replace(" ", "");
        if (s.contains("SM8")) return true;
        if (s.contains("SM7475")) return true;
        if (s.contains("SM7550") || s.contains("SM7675")) return true;
        if (s.startsWith("SDM") || s.startsWith("MSM")) return false;
        if (s.contains("SM7") || s.contains("SM6") || s.contains("SM4")) return false;
        return null;
    }

    private static String sanitize(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (r.isEmpty()) r = "bili";
        return r.length() > 80 ? r.substring(0, 80) : r;
    }
}
