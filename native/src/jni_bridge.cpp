// JNI 桥：把 C++ 核心暴露给 Java（NativeBridge.probe），并初始化 HTTP 回调。
#include <jni.h>

#include <chrono>
#include <stdexcept>
#include <string>

#include "bili.h"
#include "download.h"
#include "http.h"
#include "jni_env.h"
#include "json.hpp"

namespace {

using json = nlohmann::json;

std::string toStd(JNIEnv* env, jstring text) {
    if (!text) return {};
    const char* chars = env->GetStringUTFChars(text, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(text, chars);
    return out;
}

jstring toJava(JNIEnv* env, const std::string& text) { return env->NewStringUTF(text.c_str()); }

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    bili::jni::initJni(vm);
    return JNI_VERSION_1_6;
}

// Java: com.dsh.biliurl.NativeBridge.probe(String input, String cookie) -> String(JSON)
extern "C" JNIEXPORT jstring JNICALL Java_com_dsh_biliurl_NativeBridge_probe(JNIEnv* env, jclass, jstring input,
                                                                             jstring cookie) {
    const auto started = std::chrono::steady_clock::now();
    const std::string text = toStd(env, input);
    const std::string sessdata = toStd(env, cookie);

    json out = {{"ok", false}};
    out["input"] = text;

    try {
        auto http = bili::makeHttpClient();
        bili::BiliClient client(*http);
        if (!sessdata.empty()) client.setCookie(sessdata);

        const bili::Target target = bili::parseTarget(text, *http);
        if (!target.valid()) {
            out["error"] = "看不懂这个链接，需要 bilibili.com/video/BV... 或 BV 号";
            return toJava(env, out.dump());
        }
        out["target"] = target.query();
        out["page"] = target.page;

        std::string nickname;
        bool vip = false;
        const bool loggedIn = client.fetchNav(&nickname, &vip);
        out["loggedIn"] = loggedIn;
        if (loggedIn) {
            out["nickname"] = nickname;
            out["vip"] = vip;
        }

        const bili::BiliClient::VideoInfo info = client.view(target);
        out["bvid"] = info.bvid;
        out["title"] = info.title;
        out["owner"] = info.owner;
        out["cid"] = info.cid;
        out["pages"] = info.pages.size();

        const std::string dash = client.playurl(info.bvid, info.cid);
        out["qualities"] = bili::BiliClient::qualityList(dash);

        const bili::Stream video = bili::BiliClient::bestVideo(dash);
        const bili::Stream audio = bili::BiliClient::bestAudio(dash);
        out["video"] = {{"id", video.id},
                        {"name", bili::BiliClient::qualityName(video.id)},
                        {"codecs", video.codecs},
                        {"width", video.width},
                        {"height", video.height},
                        {"bandwidth", video.bandwidth},
                        {"urls", video.urls.size()}};
        out["audio"] = {{"id", audio.id},
                        {"codecs", audio.codecs},
                        {"bandwidth", audio.bandwidth},
                        {"urls", audio.urls.size()}};
        out["ok"] = true;
    } catch (const std::exception& e) {
        out["error"] = e.what();
    } catch (...) {
        out["error"] = "未知异常";
    }

    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                             std::chrono::steady_clock::now() - started)
                             .count();
    out["ms"] = elapsed;
    return toJava(env, out.dump());
}

// Java: com.dsh.biliurl.NativeBridge.downloadUrls(String[] urls, String path, String cookie) -> String(JSON)
// App 主流程用这个：Java 侧已经解析好候选地址，只把"怎么把码流可靠下下来"交给 C++。
extern "C" JNIEXPORT jstring JNICALL Java_com_dsh_biliurl_NativeBridge_downloadUrls(JNIEnv* env, jclass,
                                                                                   jobjectArray urls, jstring path,
                                                                                   jstring cookie) {
    const auto started = std::chrono::steady_clock::now();
    json out = {{"ok", false}};

    std::vector<std::string> candidates;
    const jsize count = urls ? env->GetArrayLength(urls) : 0;
    for (jsize i = 0; i < count; ++i) {
        jstring item = static_cast<jstring>(env->GetObjectArrayElement(urls, i));
        const std::string text = toStd(env, item);
        if (!text.empty()) candidates.push_back(text);
        if (item) env->DeleteLocalRef(item);
    }
    out["candidates"] = static_cast<int>(candidates.size());

    try {
        auto http = bili::makeHttpClient();
        const auto result = bili::downloadStream(*http, candidates, toStd(env, path), nullptr, toStd(env, cookie));
        out["ok"] = result.ok;
        out["bytes"] = result.bytes;
        if (!result.ok) out["error"] = result.error;
    } catch (const std::exception& e) {
        out["error"] = e.what();
    }

    out["ms"] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started)
                    .count();
    return toJava(env, out.dump());
}
// Java: com.dsh.biliurl.NativeBridge.download(String input, String cookie, String dir) -> String(JSON)
// 下载最高画质的视频轨 + 音频轨到 dir，返回两条流的落盘结果。
extern "C" JNIEXPORT jstring JNICALL Java_com_dsh_biliurl_NativeBridge_download(JNIEnv* env, jclass, jstring input,
                                                                                jstring cookie, jstring dir) {
    const auto started = std::chrono::steady_clock::now();
    const std::string text = toStd(env, input);
    const std::string sessdata = toStd(env, cookie);
    const std::string outDir = toStd(env, dir);

    json out = {{"ok", false}};
    try {
        auto http = bili::makeHttpClient();
        bili::BiliClient client(*http);
        if (!sessdata.empty()) client.setCookie(sessdata);

        const bili::Target target = bili::parseTarget(text, *http);
        if (!target.valid()) throw std::runtime_error("解析不出视频号");

        const bili::BiliClient::VideoInfo info = client.view(target);
        const std::string dash = client.playurl(info.bvid, info.cid);
        const bili::Stream video = bili::BiliClient::bestVideo(dash);
        const bili::Stream audio = bili::BiliClient::bestAudio(dash);
        if (video.urls.empty()) throw std::runtime_error("没有可用视频流");

        const std::string videoPath = outDir + "/native-video.m4s";
        const std::string audioPath = outDir + "/native-audio.m4s";

        const auto vres = bili::downloadStream(*http, video.urls, videoPath, nullptr);
        out["video"] = {{"path", videoPath}, {"bytes", vres.bytes}, {"ok", vres.ok}};
        if (!vres.ok) out["video"]["error"] = vres.error;

        if (!audio.urls.empty()) {
            const auto ares = bili::downloadStream(*http, audio.urls, audioPath, nullptr);
            out["audio"] = {{"path", audioPath}, {"bytes", ares.bytes}, {"ok", ares.ok}};
            if (!ares.ok) out["audio"]["error"] = ares.error;
        }

        out["ok"] = vres.ok;
        out["title"] = info.title;
        out["quality"] = bili::BiliClient::qualityName(video.id);
    } catch (const std::exception& e) {
        out["error"] = e.what();
    }

    out["ms"] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started)
                    .count();
    return toJava(env, out.dump());
}
