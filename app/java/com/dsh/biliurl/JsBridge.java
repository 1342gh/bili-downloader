package com.dsh.biliurl;

import android.webkit.JavascriptInterface;

/** 前端 window.Android 对应的桥接对象（顶层类，避免 d8 处理内部类时崩溃）。 */
public class JsBridge {

    private final MainActivity act;

    public JsBridge(MainActivity a) {
        this.act = a;
    }

    @JavascriptInterface
    public String soc() {
        return MainActivity.socModel();
    }

    @JavascriptInterface
    public boolean aiOk() {
        return !Boolean.FALSE.equals(MainActivity.socAllowsAi(MainActivity.socModel()));
    }

    @JavascriptInterface
    public void onFetch(String id, String body) {
        act.onFetchResult(id, body);
    }

    @JavascriptInterface
    public String themeMode() {
        return act.themeMode();
    }

    @JavascriptInterface
    public void setThemeMode(String mode) {
        act.setThemeMode(mode);
    }

    @JavascriptInterface
    public String filterKeywords() {
        return act.filterKeywords();
    }

    @JavascriptInterface
    public String filterChannels() {
        return act.filterChannels();
    }

    @JavascriptInterface
    public void saveFilters(String keywords, String channels) {
        act.saveFilters(keywords, channels);
    }

    @JavascriptInterface
    public void openBiliLogin() {
        act.startActivity(new android.content.Intent(act, BiliLoginActivity.class));
    }

    @JavascriptInterface
    public String crashLogPath() {
        return CrashLogger.lastLogPath();
    }

    @JavascriptInterface
    public boolean installPlugin(String url) {
        return act.installPlugin(url);
    }

    @JavascriptInterface
    public boolean deletePlugin(String fileName) {
        return act.deletePlugin(fileName);
    }

    @JavascriptInterface
    public boolean createPluginTemplate() {
        return act.createPluginTemplate();
    }

    @JavascriptInterface
    public String pluginDir() {
        return act.pluginDirPath();
    }

    @JavascriptInterface
    public void search(String keyword, int page) {
        act.searchAsync(keyword, page);
    }

    @JavascriptInterface
    public String updateSource() {
        return act.updateSource();
    }

    @JavascriptInterface
    public void setUpdateSource(String url) {
        act.setUpdateSource(url);
    }

    @JavascriptInterface
    public void checkUpdate() {
        act.checkUpdateAsync();
    }

    @JavascriptInterface
    public void downloadUpdate(String url, String fileName) {
        act.downloadUpdateAsync(url, fileName);
    }

    @JavascriptInterface
    public void loadFeed(String kind, int page) {
        act.loadFeedAsync(kind, page);
    }

    @JavascriptInterface
    public void play(String url, String cookie) {
        act.playFromWeb(url, cookie);
    }

    /** 浏览列表用：带 cid + 标题，Java 侧可跳过 view 请求 */
    @JavascriptInterface
    public void play2(String url, String cookie, long cid, String title) {
        act.playFromWeb(url, cookie, cid, title);
    }

    /** 列表加载完预取第一条的播放地址 */
    @JavascriptInterface
    public void prefetchPlay(String url, String cookie, long cid) {
        act.prefetchPlay(url, cookie, cid);
    }

    @JavascriptInterface
    public void checkLogin(String cookie) {
        act.checkLoginAsync(cookie);
    }

    @JavascriptInterface
    public String plugins() {
        return act.loadPluginsJson();
    }

    @JavascriptInterface
    public void start(String url, String cookie, int content, String format,
                      int resHeight, int fps, boolean interp) {
        act.startFromWeb(url, cookie, content, format, resHeight, fps, interp);
    }
}
