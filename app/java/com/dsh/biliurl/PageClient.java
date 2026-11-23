package com.dsh.biliurl;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/**
 * 加载 https://www.bilibili.com/dshapp/ 时由本地提供页面，
 * 这样文档源是 www.bilibili.com：可以带 Cookie 请求 api 域，也过 CORS。
 */
public class PageClient extends WebViewClient {

    private final MainActivity act;

    public PageClient(MainActivity a) {
        act = a;
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        act.onPageDone();
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl() == null ? "" : request.getUrl().toString();
        if (url.startsWith("https://www.bilibili.com/dshapp/")) {
            Log.i("biliurl", "serve local ui: " + url);
            String html = act.buildUiHtml();
            Map<String, String> headers = new HashMap<>();
            // 必须禁缓存，否则会拿到旧页面
            headers.put("Cache-Control", "no-store, no-cache, must-revalidate");
            headers.put("Pragma", "no-cache");
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers,
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        }
        return null;
    }
}
