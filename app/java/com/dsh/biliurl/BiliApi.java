package com.dsh.biliurl;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** B 站接口封装：解析链接、取视频信息、取播放地址、下载码流。 */
public class BiliApi {

    // 用 Safari 的 UA：B 站风控会按 UA 封禁，Chrome UA 容易 412
    static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";
    static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private String cookie;
    public String lastError = "";

    /** 给 C++ 下载器用：让 native 侧拿到同一份 Cookie */
    public String cookieValue() {
        return cookie;
    }
    public String lastNavBody = "";
    public String lastWebFetch = "";
    public String lastUa = "";
    private String mixinKey;

    private final WebFetch webFetch;

    public BiliApi(String input) {
        this(input, null);
    }

    public BiliApi(String input, WebFetch fetch) {
        this.webFetch = fetch;
        String v = input == null ? "" : input.trim();
        if (v.isEmpty()) {
            cookie = "";
        } else if (v.contains("SESSDATA=") || v.contains(";")) {
            // 用户粘的是完整 Cookie 串
            cookie = v.endsWith(";") ? v : v + ";";
        } else {
            cookie = "SESSDATA=" + v + ";";
        }
    }

    /** 不再主动补 buvid：实测与登录态不匹配会直接 412；Cookie 自带的一律沿用 */
    private void ensureBuvid() {
        return;
    }

    /** 旧的实现保留在下方注释里，方便以后排查 */
    private void ensureBuvidLegacy() {
        if (!cookie.contains("SESSDATA=")) return;
        if (cookie.contains("buvid3=")) return;
        try {
            JSONObject r = new JSONObject(get("https://api.bilibili.com/x/frontend/finger/spi"));
            if (r.optInt("code") == 0) {
                JSONObject d = r.getJSONObject("data");
                String b3 = d.optString("b_3", "");
                String b4 = d.optString("b_4", "");
                if (!b3.isEmpty()) cookie += "buvid3=" + b3 + ";";
                if (!b4.isEmpty()) cookie += "buvid4=" + b4 + ";";
            }
        } catch (Exception ignored) { }
    }

    /** 去掉 buvid3/buvid4（412 时回退用） */
    private void stripBuvid() {
        StringBuilder sb = new StringBuilder();
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.startsWith("buvid3=") || p.startsWith("buvid4=")) continue;
            sb.append(p).append(";");
        }
        cookie = sb.toString();
    }

    /** 登录状态；未登录返回 null */
    public JSONObject nav() {
        try {
            String raw = get("https://api.bilibili.com/x/web-interface/nav");
            lastNavBody = raw.length() > 220 ? raw.substring(0, 220) : raw;
            JSONObject r = new JSONObject(raw);
            JSONObject d = r.optJSONObject("data");
            if (d == null) return null;
            // 顺便缓存 wbi key
            JSONObject wi = d.optJSONObject("wbi_img");
            if (wi != null) {
                mixinKey = Wbi.mixinKey(Wbi.keyFromUrl(wi.optString("img_url")),
                        Wbi.keyFromUrl(wi.optString("sub_url")));
            }
            // 注意：B 站返回的 isLogin 是布尔 true/false，不是数字
            Object loginFlag = d.opt("isLogin");
            boolean logged = (loginFlag instanceof Boolean)
                    ? (Boolean) loginFlag
                    : (loginFlag instanceof Number && ((Number) loginFlag).intValue() == 1);
            return logged ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 基础请求

    private HttpURLConnection open(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Referer", "https://www.bilibili.com/");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        return c;
    }

    private String get(String url) throws Exception {
        // 先试 WebView（Chromium 网络栈，不会被 412）
        if (webFetch != null && url.contains("api.bilibili.com")) {
            String body = webFetch.get(url, 30);
            lastWebFetch = webFetch.lastStatus;
            if (body != null && !body.isEmpty() && !body.startsWith("{")) {
                body = null;   // 不是 JSON，当失败
            }
            if (body != null && body.contains("\"code\"")) {
                Log.i("biliurl", "webfetch ok " + body.length() + " chars");
                return body;
            }
            Log.w("biliurl", "webfetch 失败，回退 HttpURLConnection");
        }
        Exception last = null;
        Log.i("biliurl", "GET " + url);
        for (String ua : new String[]{UA, MOBILE_UA}) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    String body = getOnce(url, ua);
                    lastUa = ua.substring(0, Math.min(24, ua.length()));
                    Log.i("biliurl", "  ok " + body.length() + " chars");
                    return body;
                } catch (Exception e) {
                    last = e;
                    Log.w("biliurl", "  fail attempt " + attempt + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
                    String msg = String.valueOf(e.getMessage());
                    if (msg.contains("412")) {
                        // 412 是风控，重试只会更慢：立刻交给调用方换通道（WebView / 镜像）
                        Log.w("biliurl", "  412 -> 立即放弃直连");
                        lastError = msg;
                        throw new Exception(msg);
                    }
                    try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                }
            }
        }
        String msg = "请求失败：" + (last == null ? "未知" : last.getClass().getSimpleName() + " " + last.getMessage());
        lastError = msg;
        throw new Exception(msg);
    }

    private String getOnce(String url, String ua) throws Exception {
        HttpURLConnection c = open(url, ua);
        try {
            int code = c.getResponseCode();
            if (code == 412) throw new Exception("412 风控");
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------ 链接解析

    public static class Target {
        public String bvid;
        public long aid;
        public int page = 1;

        public String query() {
            return bvid != null ? "bvid=" + bvid : "aid=" + aid;
        }
    }

    public static Target parse(String text) throws Exception {
        String s = text.trim().replace("\"", "").replace("'", "");
        if (s.contains("b23.tv") || s.contains("bili2233")) {
            HttpURLConnection c = (HttpURLConnection) new URL(s).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            try {
                c.getResponseCode();
                s = c.getURL().toString();
            } finally {
                c.disconnect();
            }
        }
        Target t = new Target();
        Matcher p = Pattern.compile("[?&]p=(\\d+)").matcher(s);
        if (p.find()) t.page = Integer.parseInt(p.group(1));

        Matcher bv = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(s);
        if (bv.find()) { t.bvid = bv.group(1); return t; }

        Matcher av = Pattern.compile("av(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (av.find()) { t.aid = Long.parseLong(av.group(1)); return t; }

        return null;
    }

    // ------------------------------------------------------------ 接口

    public JSONObject view(Target t) throws Exception {
        JSONObject r = new JSONObject(get("https://api.bilibili.com/x/web-interface/view?" + t.query()));
        if (r.optInt("code") != 0) {
            throw new Exception("取视频信息失败：" + r.optInt("code") + " " + r.optString("message"));
        }
        return r.getJSONObject("data");
    }

    public JSONObject playurl(String bvid, long cid) throws Exception {
        // 实测：带登录 Cookie 时旧接口更稳（WBI 反而容易 412），所以先旧后 WBI
        String plain = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                + "&cid=" + cid + "&qn=127&fnval=4048&fourk=1";
        try {
            JSONObject r = new JSONObject(get(plain));
            if (r.optInt("code") == 0 && r.optJSONObject("data") != null) {
                return r.getJSONObject("data");
            }
        } catch (Exception ignored) { }

        if (mixinKey == null) nav();
        if (mixinKey != null) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("bvid", bvid);
            p.put("cid", String.valueOf(cid));
            p.put("qn", "127");
            p.put("fnval", "4048");
            p.put("fnver", "0");
            p.put("fourk", "1");
            p.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
            String q = Wbi.sign(p, mixinKey);
            JSONObject r = new JSONObject(get("https://api.bilibili.com/x/player/wbi/playurl?" + q));
            if (r.optInt("code") == 0 && r.optJSONObject("data") != null) {
                return r.getJSONObject("data");
            }
            throw new Exception("取播放地址失败：" + r.optInt("code") + " " + r.optString("message"));
        }

        throw new Exception("取播放地址失败：WBI key 获取失败");
    }
    /** 流式播放用的单文件地址（durl）：音视频一体，播放器可以边下边播。 */
    public static class Progressive {
        public String url = "";
        public long size = 0;
        public int quality = 0;
        /** 主地址 + backup_url，播放前可测速挑一个快的 */
        public java.util.List<String> urls = new java.util.ArrayList<>();
    }

    /**
     * 取 durl 播放地址（fnval=0）。qn 只是上限，服务端可能给更低画质；
     * 拿不到返回 null，由调用方回退到"先下载再合并"的老路径。
     */
    public Progressive progressive(String bvid, long cid, int qn) throws Exception {
        String u = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid + "&cid=" + cid
                + "&qn=" + qn + "&fnval=0&fourk=0";
        JSONObject r = new JSONObject(get(u));
        if (r.optInt("code") != 0) return null;
        JSONObject data = r.optJSONObject("data");
        if (data == null) return null;
        JSONArray durl = data.optJSONArray("durl");
        if (durl == null || durl.length() == 0) return null;
        JSONObject first = durl.optJSONObject(0);
        if (first == null) return null;
        Progressive p = new Progressive();
        p.url = first.optString("url", "");
        p.size = first.optLong("size", 0);
        p.quality = data.optInt("quality", 0);
        if (!p.url.isEmpty()) p.urls.add(p.url);
        JSONArray backups = first.optJSONArray("backup_url");
        if (backups != null) {
            for (int i = 0; i < backups.length(); i++) {
                String b = backups.optString(i, "");
                if (!b.isEmpty() && !p.urls.contains(b)) p.urls.add(b);
            }
        }
        return p.url.isEmpty() ? null : p;
    }

    /** 搜索视频（wbi 签名） */
    public JSONObject search(String keyword, int page) throws Exception {
        if (mixinKey == null) nav();
        if (mixinKey == null) throw new Exception("没拿到 wbi key");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("keyword", keyword);
        p.put("search_type", "video");
        p.put("page", String.valueOf(Math.max(1, page)));
        p.put("page_size", "20");
        p.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
        String q = Wbi.sign(p, mixinKey);
        return new JSONObject(get("https://api.bilibili.com/x/web-interface/wbi/search/type?" + q));
    }
    /** dash 里所有可用画质，按从低到高 */
    public static java.util.List<String> qualityList(JSONObject dash) {
        java.util.TreeMap<Integer, String> map = new java.util.TreeMap<>();
        JSONArray arr = dash.optJSONArray("video");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int id = o.optInt("id");
                map.put(id, idName(id) + " " + o.optInt("width") + "x" + o.optInt("height"));
            }
        }
        return new java.util.ArrayList<>(map.values());
    }

    private static String idName(int id) {
        switch (id) {
            case 127: return "8K";
            case 126: return "杜比视界";
            case 125: return "HDR";
            case 120: return "4K";
            case 116: return "1080P60";
            case 112: return "1080P+";
            case 80: return "1080P";
            case 74: return "720P60";
            case 64: return "720P";
            case 32: return "480P";
            case 16: return "360P";
            default: return "Q" + id;
        }
    }

    /** 在 dash.video 里挑画质最高的，同画质优先 AVC。 */
    public static JSONObject bestVideo(JSONObject dash) {
        JSONArray arr = dash.optJSONArray("video");
        if (arr == null) return null;
        JSONObject best = null;
        long bestScore = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String codec = o.optString("codecs");
            long pref = codec.startsWith("avc") ? 3 : codec.startsWith("hvc") ? 2 : 1;
            long score = o.optLong("id") * 1_000_000_000L + o.optLong("bandwidth") * 10L + pref;
            if (score > bestScore) { bestScore = score; best = o; }
        }
        return best;
    }

    public static JSONObject bestAudio(JSONObject dash) {
        JSONArray arr = dash.optJSONArray("audio");
        if (arr == null) return null;
        JSONObject best = null;
        long bestBw = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long bw = o.optLong("bandwidth");
            if (bw > bestBw) { bestBw = bw; best = o; }
        }
        return best;
    }

    // ------------------------------------------------------------ 下载

    public interface Progress {
        void onProgress(long done, long total);
    }

    /** 一条码流的候选地址：baseUrl + backupUrl（B 站会给多个镜像）。 */
    public static java.util.List<String> urls(JSONObject stream) {
        java.util.List<String> all = new java.util.ArrayList<>();
        String base = stream.optString("baseUrl");
        if (!base.isEmpty()) all.add(base);
        JSONArray bak = stream.optJSONArray("backupUrl");
        if (bak != null) {
            for (int i = 0; i < bak.length(); i++) {
                String u = bak.optString(i);
                if (!u.isEmpty()) all.add(u);
            }
        }
        // 排序：官方 upos / bilivideo 优先，边缘节点(mcdn/edge)最后
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String u : all) if (u.contains("upos") || (u.contains("bilivideo.com") && !u.contains("mcdn"))) out.add(u);
        for (String u : all) if (!out.contains(u) && !u.contains("mcdn") && !u.contains("edge.")) out.add(u);
        for (String u : all) if (!out.contains(u)) out.add(u);
        return out;
    }

    /** 依次尝试所有候选地址；每个地址再试两次，UA 也换一轮；失败原因累积到异常里。 */
    public void download(java.util.List<String> urls, File dst, Progress cb) throws Exception {
        StringBuilder diag = new StringBuilder();
        for (String ua : new String[]{UA, MOBILE_UA}) {
            String uaName = ua.contains("Android") ? "mobile" : "safari";
            for (String url : urls) {
                String host = hostOf(url);
                for (int attempt = 1; attempt <= 2; attempt++) {
                    try {
                        downloadOne(url, dst, cb, ua);
                        return;
                    } catch (Exception e) {
                        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        diag.append(host).append('/').append(uaName).append(':').append(msg).append('\n');
                        try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                    }
                }
            }
        }
        throw new Exception("所有下载地址都失败：\n" + diag.toString().trim());
    }

    private static String hostOf(String url) {
        try {
            String s = url.substring(url.indexOf("//") + 2);
            int slash = s.indexOf('/');
            return slash > 0 ? s.substring(0, slash) : s;
        } catch (Exception e) {
            return "?";
        }
    }

    private void downloadOne(String url, File dst, Progress cb, String ua) throws Exception {
        HttpURLConnection c = open(url, ua);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Connection", "close");   // 避免复用被 CDN 断掉的连接
        c.setRequestProperty("Range", "bytes=0-");     // 强制全新 200/206 响应
        Log.i("biliurl", "DOWNLOAD " + url + " ua=" + (ua.contains("Android") ? "mobile" : "safari"));
        try {
            int code = c.getResponseCode();
            if (code != 200 && code != 206) throw new Exception("下载失败 HTTP " + code);
            long total = c.getContentLengthLong();
            long done = 0;
            int lastPct = -10;
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 20);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct - lastPct >= 2) {
                            lastPct = pct;
                            if (cb != null) cb.onProgress(done, total);
                        }
                    }
                }
            }
            if (done < 1024) throw new Exception("下载内容为空");
            if (cb != null) cb.onProgress(done, total > 0 ? total : done);
        } finally {
            c.disconnect();
        }
    }
}
