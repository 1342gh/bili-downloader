// 码流下载：候选地址轮询 + UA 轮换 + 断点续传 + 全局时间预算。
//
// 实测（Xperia XZ3 / Android 15）：
//   - B 站 CDN 单条连接会在任意位置被 reset，必须记字节数用 Range 续传；
//   - 某些节点在特定网络下会"连上但零数据"，所以要有总预算，超时就让 Java 侧回退。
#include "download.h"

#include <chrono>
#include <sstream>
#include <thread>

namespace bili {
namespace {

using Clock = std::chrono::steady_clock;

constexpr const char* kSafariUa =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";
constexpr const char* kMobileUa =
    "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

std::string hostOf(const std::string& url) {
    const auto scheme = url.find("//");
    if (scheme == std::string::npos) return "?";
    const auto slash = url.find('/', scheme + 2);
    return url.substr(scheme + 2, slash == std::string::npos ? std::string::npos : slash - scheme - 2);
}

struct AttemptResult {
    bool ok = false;
    long long bytes = 0;
    std::string error;
};

// 单条地址：反复续传直到拿满、预算用尽或重试次数用完
AttemptResult fetchWithResume(HttpClient& http, const std::string& url, const std::string& path,
                               const std::string& ua, const std::string& cookie, Clock::time_point deadline,
                               int maxRounds) {
    AttemptResult result;
    long long done = 0;
    long long total = -1;

    for (int round = 0; round < maxRounds; ++round) {
        if (Clock::now() > deadline) {
            result.error = "超出时间预算";
            break;
        }

        HttpRequest req;
        req.url = url;
        req.userAgent = ua;
        req.cookie = cookie;
        req.accept = "*/*";
        req.rangeStart = done;
        req.connectTimeoutMs = 15000;
        req.readTimeoutMs = 20000;

        const HttpResponse res = http.download(req, path, nullptr);
        if (!res.error.empty()) {
            result.error = res.error;
            break;
        }
        if (res.status != 200 && res.status != 206) {
            result.error = "HTTP " + std::to_string(res.status);
            break;
        }

        const long long start = done;
        done += res.bytesWritten;
        // 206 的 Content-Length 是"本次剩余"，200 的是"整个文件"
        if (res.contentLength > 0) {
            total = res.status == 206 ? start + res.contentLength : res.contentLength;
        }

        if (total > 0 && done >= total) {
            result.ok = true;
            result.bytes = done;
            return result;
        }
        if (res.bytesWritten == 0) {
            result.error = "没有收到数据";
            break;
        }
    }

    result.bytes = done;
    if (!result.ok) {
        if (result.error.empty()) result.error = "多轮续传后仍未拿全";
        std::ostringstream text;
        text << result.error << "（已下 " << done;
        if (total > 0) text << "/" << total;
        text << " 字节）";
        result.error = text.str();
    }
    return result;
}

}  // namespace

DownloadResult downloadStream(HttpClient& http, const std::vector<std::string>& urls, const std::string& path,
                               const ProgressFn& progress, const std::string& cookie) {
    DownloadResult result;
    if (urls.empty()) {
        result.error = "没有可用地址";
        return result;
    }

    const auto deadline = Clock::now() + std::chrono::seconds(30);
    std::ostringstream diag;

    for (const char* ua : {kSafariUa, kMobileUa}) {
        const bool mobile = std::string(ua).find("Android") != std::string::npos;
        for (const std::string& url : urls) {
            if (Clock::now() > deadline) {
                result.error = "超过时间预算（30s），放弃 C++ 下载\n" + diag.str();
                return result;
            }
            const AttemptResult attempt = fetchWithResume(http, url, path, ua, cookie, deadline, 6);
            if (progress && attempt.bytes > 0) progress(attempt.bytes, attempt.bytes);
            if (attempt.ok) {
                result.ok = true;
                result.bytes = attempt.bytes;
                return result;
            }
            diag << hostOf(url) << '/' << (mobile ? "mobile" : "safari") << ":" << attempt.error << '\n';
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
        }
    }

    result.error = "所有下载地址都失败：\n" + diag.str();
    return result;
}

}  // namespace bili
