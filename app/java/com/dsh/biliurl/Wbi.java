package com.dsh.biliurl;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** B 站 WBI 签名（2023 年后的接口要求）。 */
public class Wbi {

    private static final int[] TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    /** img_key + sub_key 重排后取前 32 位 */
    public static String mixinKey(String imgKey, String subKey) {
        String raw = imgKey + subKey;
        StringBuilder sb = new StringBuilder();
        for (int idx : TAB) {
            if (idx < raw.length()) sb.append(raw.charAt(idx));
            if (sb.length() >= 32) break;
        }
        return sb.toString();
    }

    /** 从 wbi_img 的 url 里取出 key（去目录、去后缀） */
    public static String keyFromUrl(String url) {
        if (url == null) return "";
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 排序 + 追加 wts + md5 签名，返回可直接拼到 URL 的查询串 */
    public static String sign(Map<String, String> params, String mixinKey) throws Exception {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (e.getValue() == null) continue;
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return q + "&w_rid=" + md5(q + mixinKey);
    }

    public static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
