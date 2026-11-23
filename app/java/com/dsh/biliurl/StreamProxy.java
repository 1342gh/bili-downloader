package com.dsh.biliurl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * 本地播放缓冲代理。
 *
 * 为什么要它：播放器直连 B 站 CDN 时，起播速度完全取决于 CDN 什么时候给首包，
 * 网络一抖就是几秒黑屏。这里换成：
 *   1. App 自己按 Range 断点续传把码流拉到本地临时文件（失败能换节点、能续传）；
 *   2. 播放器只请求 http://127.0.0.1:port/v.m4s，服务端一边下载一边把已到的字节喂给它。
 * 播放器看到的是"本地文件"，起播只要等我们缓冲到第一小块，之后卡顿也由我们的重试兜住。
 */
public final class StreamProxy {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";

    private static StreamProxy current;

    private final File file;
    private final List<String> urls;
    private final String cookie;
    private final Object lock = new Object();
    private volatile long available;
    private volatile boolean finished;
    private volatile String error;
    private long total = -1;
    private ServerSocket server;
    private Thread downloader;
    private volatile Socket client;
    private volatile boolean stopped;

    private StreamProxy(File file, List<String> urls, String cookie) {
        this.file = file;
        this.urls = urls;
        this.cookie = cookie;
    }

    /** 起一个代理，返回播放器该用的本地地址；失败返回 null */
    public static String start(List<String> urls, String cookie, File cacheDir) {
        stopCurrent();
        try {
            File dir = new File(cacheDir, "stream");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, "playing.m4s");
            if (file.exists() && !file.delete()) return null;
            if (!file.createNewFile()) return null;

            StreamProxy proxy = new StreamProxy(file, urls, cookie);
            proxy.server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            proxy.startDownloader();
            current = proxy;
            int port = proxy.server.getLocalPort();
            android.util.Log.i("biliurl-stream", "proxy up: 127.0.0.1:" + port + " <- " + urls.size() + " urls");
            return "http://127.0.0.1:" + port + "/v.m4s";
        } catch (Exception e) {
            android.util.Log.w("biliurl-stream", "start failed: " + e);
            return null;
        }
    }

    public static void stopCurrent() {
        StreamProxy p = current;
        current = null;
        if (p != null) p.stop();
    }

    private void stop() {
        stopped = true;
        try { if (client != null) client.close(); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        if (downloader != null) downloader.interrupt();
        synchronized (lock) { lock.notifyAll(); }
        try { if (file != null && file.exists()) file.delete(); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ 下载侧

    private void startDownloader() {
        downloader = new Thread(this::downloadLoop, "stream-dl");
        downloader.setDaemon(true);
        downloader.start();

        Thread acceptor = new Thread(() -> {
            while (!stopped) {
                try {
                    final Socket s = server.accept();
                    new Thread(() -> serve(s), "stream-serve").start();
                } catch (Exception e) {
                    break;
                }
            }
        }, "stream-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** 三条候选地址同时试，谁先真的吐数据就用谁（首包快慢差别很大） */
    private String raceFastest() {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, urls.size()));
        final java.util.concurrent.CompletableFuture<String> first = new java.util.concurrent.CompletableFuture<>();
        for (final String u : urls) {
            pool.submit(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(2500);
                    c.setRequestProperty("User-Agent", UA);
                    c.setRequestProperty("Referer", "https://www.bilibili.com/");
                    c.setRequestProperty("Range", "bytes=0-8191");
                    c.setRequestProperty("Connection", "close");
                    if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
                    int code = c.getResponseCode();
                    if (code == 200 || code == 206) {
                        InputStream in = c.getInputStream();
                        byte[] b = new byte[8192];
                        long got = 0;
                        long deadline = System.currentTimeMillis() + 1500;
                        // 不光看"有没有首包"，要在 2 秒内吃到 32KB 才算这个节点可用
                        while (got < 16384 && System.currentTimeMillis() < deadline) {
                            int n = in.read(b);
                            if (n <= 0) break;
                            got += n;
                        }
                        if (got >= 16384) first.complete(u);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }
        String winner = null;
        try {
            winner = first.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        android.util.Log.i("biliurl-stream", "race -> " + (winner == null ? "none" : winner.substring(0, Math.min(60, winner.length()))));
        return winner;
    }

    private volatile String fastest;

    private void downloadLoop() {
        // 抢跑不阻塞：立刻用第一个地址开下，谁快谁慢留给后面的重试排序用
        Thread racer = new Thread(() -> fastest = raceFastest(), "stream-race");
        racer.setDaemon(true);
        racer.start();

        final java.util.List<String> order = new java.util.ArrayList<>(urls);
        long done = 0;
        long deadline = System.currentTimeMillis() + 120000;   // 最多磨 2 分钟
        int stall = 0;
        long lastDone = -1;

        while (!stopped && System.currentTimeMillis() < deadline) {
            if (fastest != null && order.size() > 1) {
                order.remove(fastest);
                order.add(0, fastest);
            }
            for (String url : order) {
                if (stopped) return;
                try {
                    done = fetch(url, done);
                    if (total > 0 && done >= total) {
                        finish();
                        return;
                    }
                } catch (Exception e) {
                    android.util.Log.w("biliurl-stream", "download failed @" + done + ": " + e);
                }
            }
            // 连续几轮没有进展才放弃，否则一直续传（播放器那侧会等）
            if (done == lastDone) {
                stall++;
                if (stall >= 4) break;
            } else {
                stall = 0;
                lastDone = done;
            }
            try { Thread.sleep(600L + stall * 400L); } catch (InterruptedException ignored) { }
        }
        finish();
    }

    /** 从 url 续传，返回累计字节数 */
    private long fetch(String url, long done) throws IOException {
        HttpURLConnection c = null;
        RandomAccessFile out = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", "https://www.bilibili.com/");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Connection", "close");
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            if (done > 0) c.setRequestProperty("Range", "bytes=" + done + "-");

            int code = c.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            boolean append = code == 206 && done > 0;
            if (!append) done = 0;

            long len = c.getContentLengthLong();
            if (len > 0) total = append ? done + len : len;

            out = new RandomAccessFile(file, "rw");
            out.seek(done);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0 && !stopped) {
                out.write(buf, 0, n);
                done += n;
                synchronized (lock) {
                    available = done;
                    lock.notifyAll();
                }
            }
            return done;
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            if (c != null) c.disconnect();
        }
    }

    private void finish() {
        synchronized (lock) {
            finished = true;
            lock.notifyAll();
        }
        android.util.Log.i("biliurl-stream", "download finished: " + available
                + (total > 0 ? "/" + total : "") + " bytes");
    }

    // ------------------------------------------------------------ 服务侧

    private void serve(Socket s) {
        if (s == null) return;
        try {
            InputStream in = s.getInputStream();
            String head = readHead(in);
            String[] lines = head.split("\r\n");
            String requestLine = lines.length > 0 ? lines[0] : "";
            boolean isHead = requestLine.startsWith("HEAD");
            long start = 0;
            for (String line : lines) {
                if (line.toLowerCase(Locale.ROOT).startsWith("range:")) {
                    String v = line.substring(6).trim();
                    int dash = v.indexOf("bytes=");
                    if (dash >= 0) {
                        String spec = v.substring(dash + 6);
                        int hyphen = spec.indexOf('-');
                        if (hyphen > 0) start = Long.parseLong(spec.substring(0, hyphen).trim());
                    }
                }
            }

            OutputStream out = s.getOutputStream();
            StringBuilder h = new StringBuilder();
            h.append(start > 0 ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: video/mp4\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("Connection: close\r\n");
            if (start > 0) h.append("Content-Range: bytes ").append(start).append("-*/").append(total > 0 ? total : "*").append("\r\n");
            h.append("\r\n");
            out.write(h.toString().getBytes("UTF-8"));
            out.flush();
            if (isHead) {
                s.close();
                return;
            }

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            long pos = start;
            byte[] buf = new byte[64 * 1024];
            try {
                while (!stopped) {
                    long avail;
                    boolean done;
                    synchronized (lock) {
                        while (available <= pos && !finished && !stopped) {
                            lock.wait(2000);
                        }
                        avail = available;
                        done = finished;
                    }
                    if (pos < avail) {
                        long want = Math.min(avail - pos, buf.length);
                        raf.seek(pos);
                        int n = raf.read(buf, 0, (int) want);
                        if (n <= 0) break;
                        out.write(buf, 0, n);
                        out.flush();
                        pos += n;
                        continue;
                    }
                    if (done) break;
                }
            } finally {
                try { raf.close(); } catch (Exception ignored) { }
                try { s.close(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            android.util.Log.w("biliurl-stream", "serve end: " + e);
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cur;
        while ((cur = in.read()) >= 0) {
            sb.append((char) cur);
            int n = sb.length();
            if (n >= 4 && sb.charAt(n - 1) == '\n' && sb.charAt(n - 2) == '\r'
                    && sb.charAt(n - 3) == '\n' && sb.charAt(n - 4) == '\r') break;
            if (n > 8192) break;
        }
        return sb.toString();
    }
}
