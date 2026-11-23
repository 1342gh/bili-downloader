// 安卓端的 HTTP 后端：不自己带 TLS，回调 Java 的 HttpURLConnection。
//
// 这样做的取舍：C++ 核心保持零平台依赖（宿主机用 WinHTTP/curl，安卓用 JNI），
// 代价是每个请求多一次 JNI 往返；对"解析 + 取流"这种低频调用完全可接受。
#include <jni.h>

#include <string>

#include "http.h"
#include "jni_env.h"

namespace bili {
namespace jni {
namespace {

JavaVM* gVm = nullptr;
jclass gBridge = nullptr;
jmethodID gHttpGet = nullptr;
jmethodID gHttpDownload = nullptr;

struct Env {
    JNIEnv* env = nullptr;
    bool attached = false;

    ~Env() {
        if (attached && gVm) gVm->DetachCurrentThread();
    }
};

Env acquire() {
    Env out;
    if (!gVm) return out;
    const jint state = gVm->GetEnv(reinterpret_cast<void**>(&out.env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (gVm->AttachCurrentThread(&out.env, nullptr) == JNI_OK) out.attached = true;
    }
    return out;
}

std::string toStd(JNIEnv* env, jstring text) {
    if (!text) return {};
    const char* chars = env->GetStringUTFChars(text, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(text, chars);
    return out;
}

jstring toJava(JNIEnv* env, const std::string& text) { return env->NewStringUTF(text.c_str()); }

// 把 "OK:200\n{...}" / "ERR:xxx" 拆开
bool splitResult(const std::string& raw, int* status, std::string* body) {
    if (raw.rfind("OK:", 0) != 0) return false;
    const size_t newline = raw.find('\n');
    if (newline == std::string::npos) return false;
    *status = std::atoi(raw.substr(3, newline - 3).c_str());
    *body = raw.substr(newline + 1);
    return true;
}

class JniHttpClient : public HttpClient {
public:
    HttpResponse get(const HttpRequest& req) override {
        HttpResponse res;
        Env guard = acquire();
        if (!guard.env) {
            res.error = "JNI 环境不可用";
            return res;
        }
        const jstring jurl = toJava(guard.env, req.url);
        const jstring jua = toJava(guard.env, req.userAgent);
        const jstring jcookie = toJava(guard.env, req.cookie);
        const jstring jrange = toJava(guard.env, std::string());
        jstring result = static_cast<jstring>(
            guard.env->CallStaticObjectMethod(gBridge, gHttpGet, jurl, jua, jcookie, jrange));
        const std::string raw = toStd(guard.env, result);
        guard.env->DeleteLocalRef(jurl);
        guard.env->DeleteLocalRef(jua);
        guard.env->DeleteLocalRef(jcookie);
        guard.env->DeleteLocalRef(jrange);
        if (result) guard.env->DeleteLocalRef(result);

        int status = 0;
        std::string body;
        if (!splitResult(raw, &status, &body)) {
            res.error = raw.empty() ? "回调返回空" : raw;
            return res;
        }
        res.status = status;
        res.body = std::move(body);
        res.contentLength = static_cast<long long>(res.body.size());
        if (status == 412) res.error = "412 风控";
        return res;
    }

    HttpResponse download(const HttpRequest& req, const std::string& path, const ProgressFn& progress) override {
        HttpResponse res;
        Env guard = acquire();
        if (!guard.env) {
            res.error = "JNI 环境不可用";
            return res;
        }
        const jstring jurl = toJava(guard.env, req.url);
        const jstring jua = toJava(guard.env, req.userAgent);
        const jstring jcookie = toJava(guard.env, req.cookie);
        const jstring jpath = toJava(guard.env, path);
        jstring result = static_cast<jstring>(guard.env->CallStaticObjectMethod(
            gBridge, gHttpDownload, jurl, jua, jcookie, static_cast<jlong>(req.rangeStart), jpath));
        const std::string raw = toStd(guard.env, result);
        guard.env->DeleteLocalRef(jurl);
        guard.env->DeleteLocalRef(jua);
        guard.env->DeleteLocalRef(jcookie);
        guard.env->DeleteLocalRef(jpath);
        if (result) guard.env->DeleteLocalRef(result);

        // 约定："OK:<status>:<written>:<total>"
        if (raw.rfind("OK:", 0) == 0) {
            int status = 0;
            long long written = 0;
            long long total = -1;
            std::sscanf(raw.c_str() + 3, "%d:%lld:%lld", &status, &written, &total);
            res.status = status;
            res.bytesWritten = written;
            res.contentLength = total;
            if (progress) progress(written, total);
        } else {
            res.error = raw.empty() ? "回调返回空" : raw;
        }
        return res;
    }
};

}  // namespace

void initJni(JavaVM* vm) {
    gVm = vm;
    Env guard = acquire();
    if (!guard.env) return;

    jclass local = guard.env->FindClass("com/dsh/biliurl/NativeBridge");
    if (!local) {
        guard.env->ExceptionDescribe();
        return;
    }
    gBridge = static_cast<jclass>(guard.env->NewGlobalRef(local));
    guard.env->DeleteLocalRef(local);

    gHttpGet = guard.env->GetStaticMethodID(
        gBridge, "httpGet",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    gHttpDownload = guard.env->GetStaticMethodID(
        gBridge, "httpDownload",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)Ljava/lang/String;");
    if (guard.env->ExceptionCheck()) guard.env->ExceptionClear();
}

}  // namespace jni

std::unique_ptr<HttpClient> makeHttpClient() { return std::make_unique<jni::JniHttpClient>(); }

}  // namespace bili
