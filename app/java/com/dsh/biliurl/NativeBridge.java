package com.dsh.biliurl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;

import javax.net.ssl.SSLSocketFactory;

/**
 * C++ 核心（libbili.so）的 Java 侧门面。
 *
 * 两个方向：
 *   - Java → C++：probe() 等 native 方法；
 *   - C++ → Java：httpGet / httpDownload 由 native 回调，负责真正的网络 I/O
 *     （安卓上 TLS 栈由系统提供，C++ 不自己带 OpenSSL）。
 */
public final class NativeBridge {

    static {
        System.loadLibrary("bili");
    }

    private NativeBridge() { }

    /** 解析链接 → 取视频信息 → 取流，返回 JSON 字符串（失败时 {"ok":false,"error":...}）。 */
    public static native String probe(String input, String cookie);

    /** App 主流程用：把候选地址列表可靠地下到 path（带断点续传）。 */
    public static native String downloadUrls(String[] urls, String path, String cookie);

    /** 下载最高画质的视频轨 + 音频轨到 dir，返回 JSON。 */
    public static native String download(String input, String cookie, String dir);

    // ---------------------------------------------------------------- C++ 回调

    /** C++ 调这里发 GET，返回 "OK:<status>\n<body>" 或 "ERR:<原因>"。 */
    public static String httpGet(String url, String ua, String cookie, String range) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, ua, cookie, range);
            final int code = conn.getResponseCode();
            final InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            final String body = in == null ? "" : readAll(in);
            android.util.Log.i("biliurl-native", "httpGet " + code + " " + body.length() + "B " + url);
            return "OK:" + code + "\n" + body;
        } catch (Exception e) {
            android.util.Log.w("biliurl-native", "httpGet failed " + url + " : " + e);
            return "ERR:" + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * C++ 调这里下载码流到 path，支持断点续传。
     * start 为已下载字节数（>0 时带 Range 续传），返回 "OK:<status>:<文件字节数>:<总长度>"。
     */
    public static String httpDownload(String url, String ua, String cookie, long start, String path) {
        HttpURLConnection conn = null;
        final long began = System.currentTimeMillis();
        try {
            conn = open(url, ua, cookie, start > 0 ? ("bytes=" + start + "-") : "bytes=0-");
            final int code = conn.getResponseCode();
            if (code != 200 && code != 206) return "ERR:HTTP " + code;

            // 服务器不认 Range（返回 200）时只能从头覆盖
            final boolean append = code == 206 && start > 0;
            final long offset = append ? start : 0;
            long total = conn.getContentLengthLong();
            if (total > 0) total += offset;

            long written = 0;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(new File(path), append)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    written += n;
                }
            }
            android.util.Log.i("biliurl-native", "download " + code + " " + written + "B (offset "
                    + offset + ") in " + (System.currentTimeMillis() - began) + "ms <- " + hostOf(url));
            return "OK:" + code + ":" + (offset + written) + ":" + total;
        } catch (Exception e) {
            android.util.Log.w("biliurl-native", "download failed after "
                    + (System.currentTimeMillis() - began) + "ms <- " + hostOf(url) + " : " + e);
            return "ERR:" + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
    private static String hostOf(String url) {
        if (url == null) return "?";
        int i = url.indexOf("//");
        int slash = url.indexOf('/', i + 2);
        return slash > 0 ? url.substring(i + 2, slash) : url.substring(i + 2);
    }

    // ---- 强制走 IPv4 ----
    // 这台机器上 IPv6 到 B 站 CDN 不通（curl -6 零字节），但 JVM 默认双栈会先试 v6，
    // 结果就是每次连接都卡到超时。HttpURLConnection 不让改地址，所以给它一个
    // "解析到 IPv4 再握手" 的 Socket 工厂：SNI 仍用原域名，TLS 校验不受影响。
    private static SSLSocketFactory ipv4Factory;

    private static synchronized SSLSocketFactory ipv4Factory() {
        if (ipv4Factory != null) return ipv4Factory;
        final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
        ipv4Factory = new SSLSocketFactory() {
            @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
            @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

            @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
                return delegate.createSocket(s, host, port, autoClose);
            }

            @Override public Socket createSocket(String host, int port) throws IOException {
                return delegate.createSocket(connectAnyIpv4(host, port), host, port, true);
            }

            @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
                return delegate.createSocket(connectAnyIpv4(host, port), host, port, true);
            }

            @Override public Socket createSocket(InetAddress host, int port) throws IOException {
                return delegate.createSocket(host, port);
            }

            @Override public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
                return delegate.createSocket(address, port, local, localPort);
            }
        };
        return ipv4Factory;
    }

    /** 依次试该域名的所有 IPv4 地址（CDN 会给出多个 A 记录，其中可能有死节点），
     *  每个给 4 秒连接预算，谁先连上就用谁。 */
    private static Socket connectAnyIpv4(String host, int port) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        IOException last = null;
        for (InetAddress a : all) {
            if (!(a instanceof java.net.Inet4Address)) continue;
            Socket s = new Socket();
            try {
                s.connect(new java.net.InetSocketAddress(a, port), 4000);
                return s;
            } catch (IOException e) {
                last = e;
                try { s.close(); } catch (IOException ignored) { }
            }
        }
        // 没有 A 记录（或全失败）时退回默认行为
        if (last != null) throw last;
        return new Socket(all[0], port);
    }
    private static HttpURLConnection open(String url, String ua, String cookie, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", ua == null || ua.isEmpty()
                ? "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                : ua);
        c.setRequestProperty("Referer", "https://www.bilibili.com/");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        if (c instanceof javax.net.ssl.HttpsURLConnection) {
            ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(ipv4Factory());
        }
        if (range != null && !range.isEmpty()) {
            c.setRequestProperty("Range", range);
            // 复用到被 CDN 掐掉的连接会立刻 reset，下载时强制短连接
            c.setRequestProperty("Connection", "close");
        }
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }
}
