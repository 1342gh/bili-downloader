// 宿主机（Linux/macOS/WSL）测试用的 HTTP 实现：直接调系统 curl 命令。
//
// 为什么这么糙：宿主侧只是为了验证业务逻辑，不值得为一个探针引入 libcurl 依赖；
// 安卓端会用 JNI 回调（WebView/HttpURLConnection）另一份实现，接口不变。
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "http.h"

namespace bili {
namespace {

std::string shellQuote(const std::string& text) {
    std::string out = "'";
    for (const char c : text) {
        if (c == '\'') out += "'\\''";
        else out.push_back(c);
    }
    out += "'";
    return out;
}

std::string readAll(const std::string& path) {
    std::string data;
    FILE* file = std::fopen(path.c_str(), "rb");
    if (!file) return data;
    char buffer[65536];
    size_t n;
    while ((n = std::fread(buffer, 1, sizeof(buffer), file)) > 0) data.append(buffer, n);
    std::fclose(file);
    return data;
}

std::string tempPath(const char* tag) {
    static int seq = 0;
    const char* dir = std::getenv("TMPDIR");
    std::string base = dir ? dir : "/tmp";
    return base + "/bili-http-" + tag + "-" + std::to_string(++seq);
}

// 执行命令并取回 stdout（-w 的输出）；返回退出码
std::string runCapture(const std::string& command, int* exitCode) {
    std::string output;
    FILE* pipe = popen(command.c_str(), "r");
    if (!pipe) {
        if (exitCode) *exitCode = -1;
        return output;
    }
    char buffer[4096];
    while (std::fgets(buffer, sizeof(buffer), pipe)) output += buffer;
    const int status = pclose(pipe);
    if (exitCode) *exitCode = status;
    return output;
}

HttpResponse run(const HttpRequest& req, const std::string& savePath) {
    HttpResponse res;

    const std::string bodyPath = savePath.empty() ? tempPath("body") : savePath;
    std::string command = "curl -sS --max-time " + std::to_string(req.readTimeoutMs / 1000);
    command += req.followRedirects ? " -L" : "";
    command += " -A " + shellQuote(req.userAgent.empty() ? "Mozilla/5.0" : req.userAgent);
    if (!req.referer.empty()) command += " -e " + shellQuote(req.referer);
    if (!req.cookie.empty()) command += " -H " + shellQuote("Cookie: " + req.cookie);
    if (!req.accept.empty()) command += " -H " + shellQuote("Accept: " + req.accept);
    if (req.rangeStart > 0) command += " -H " + shellQuote("Range: bytes=" + std::to_string(req.rangeStart) + "-");
    command += " -H " + shellQuote("Accept-Encoding: identity");
    command += " -o " + shellQuote(bodyPath);
    command += " -w " + shellQuote("%{http_code} %{size_download} %{redirect_url}");
    command += " " + shellQuote(req.url);

    int exitCode = 0;
    const std::string meta = runCapture(command, &exitCode);
    if (exitCode != 0) {
        res.error = "curl 退出码 " + std::to_string(exitCode) + "：" + meta;
        if (savePath.empty()) std::remove(bodyPath.c_str());
        return res;
    }

    // 形如 "200 12345" 或 "302 0 https://..."
    int status = 0;
    long long size = 0;
    char redirect[2048] = {};
    std::sscanf(meta.c_str(), "%d %lld %2047s", &status, &size, redirect);
    res.status = status;
    res.contentLength = size;
    if (status >= 300 && status < 400) res.location = redirect;

    if (savePath.empty()) {
        res.body = readAll(bodyPath);
        std::remove(bodyPath.c_str());
        if (res.contentLength < 0) res.contentLength = static_cast<long long>(res.body.size());
    }

    if (status == 412) res.error = "412 风控";
    return res;
}

class CurlCliClient : public HttpClient {
public:
    HttpResponse get(const HttpRequest& req) override { return run(req, std::string()); }

    HttpResponse download(const HttpRequest& req, const std::string& path,
                          const ProgressFn& progress) override {
        HttpResponse res = run(req, path);
        res.bytesWritten = res.contentLength;
        if (progress && res.error.empty()) progress(res.contentLength, res.contentLength);
        return res;
    }
};

}  // namespace

std::unique_ptr<HttpClient> makeHttpClient() { return std::make_unique<CurlCliClient>(); }

}  // namespace bili
