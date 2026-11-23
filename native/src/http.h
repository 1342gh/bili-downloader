// HTTP 抽象层。
//
// 为什么抽象：安卓端的 API 请求有个坑 —— Java 的 HttpURLConnection 直连 B 站接口
// 会被 412（TLS 指纹），所以 App 原来绕道 WebView 发请求。C++ 这边把"怎么发"隔离成
// 接口，宿主测试用 WinHTTP，安卓端可以换成自己的 TLS 栈或走 JNI 回调，业务代码不用改。
#pragma once

#include <functional>
#include <memory>
#include <string>

namespace bili {

struct HttpRequest {
    std::string url;
    std::string userAgent;
    std::string referer = "https://www.bilibili.com/";
    std::string cookie;
    std::string accept = "application/json, text/plain, */*";
    long long rangeStart = 0;  // >0 表示从该字节续传（配合 download 使用）
    int connectTimeoutMs = 20000;
    int readTimeoutMs = 30000;
    bool followRedirects = true;
};

struct HttpResponse {
    int status = 0;
    std::string body;
    long long contentLength = -1;
    long long bytesWritten = 0;  // download() 本次实际写入的字节数
    std::string location;  // 3xx 跳转目标（followRedirects=false 时用得上，b23.tv 短链靠它）
    std::string error;     // 非空表示失败（连接失败、超时、TLS 握手失败等）

    bool ok() const { return error.empty() && status >= 200 && status < 300; }
};

// (已下载字节, 总字节；总字节未知时为 -1)
using ProgressFn = std::function<void(long long done, long long total)>;

class HttpClient {
public:
    virtual ~HttpClient() = default;

    // 一次 GET，整个响应体读进内存
    virtual HttpResponse get(const HttpRequest& req) = 0;

    // 一次 GET，边收边写文件（视频码流用它，避免几 MB 全压内存）
    virtual HttpResponse download(const HttpRequest& req, const std::string& path,
                                  const ProgressFn& progress) = 0;
};

// 当前平台实现：Windows 用 WinHTTP；安卓端另有一份实现（见 http_jni.cpp）
std::unique_ptr<HttpClient> makeHttpClient();

}  // namespace bili
