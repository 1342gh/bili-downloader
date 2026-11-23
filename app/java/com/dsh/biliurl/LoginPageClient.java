package com.dsh.biliurl;

import android.webkit.WebView;
import android.webkit.WebViewClient;

/** 登录页的 WebViewClient（顶层类，避免 d8 处理匿名内部类时崩溃）。 */
public class LoginPageClient extends WebViewClient {

    private final BiliLoginActivity act;

    public LoginPageClient(BiliLoginActivity a) {
        act = a;
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        android.util.Log.i("biliurl", "login page start: " + url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        android.util.Log.i("biliurl", "login page finish: " + url);
        act.checkCookie();
    }

    @Override
    public void onReceivedError(WebView view, android.webkit.WebResourceRequest req,
                                android.webkit.WebResourceError err) {
        android.util.Log.w("biliurl", "login page error: " + req.getUrl() + " -> " + err.getDescription());
    }

    @Override
    public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest req,
                                    android.webkit.WebResourceResponse resp) {
        android.util.Log.w("biliurl", "login http error: " + req.getUrl() + " -> " + resp.getStatusCode());
    }
}
