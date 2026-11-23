// Windows / 宿主机实现：WinHTTP（系统自带，不引第三方 TLS 库）。
//
// 一个细节：WinHTTP 默认会做证书吊销检查，离线环境下会报
// CRYPT_E_REVOCATION_OFFLINE 直接握手失败。这里把 SECURITY_FLAG_IGNORE_REVOCATION
// 打开（不影响链路加密，只是跳过 CRL/OCSP 查询）。
#include <windows.h>
#include <winhttp.h>

#include <cstdio>
#include <string>

#include "http.h"

namespace bili {
namespace {

std::wstring toWide(const std::string& utf8) {
    if (utf8.empty()) return {};
    const int need = MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), nullptr, 0);
    std::wstring out(static_cast<size_t>(need), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), out.data(), need);
    return out;
}

std::string lastErrorMessage(const char* stage, DWORD code) {
    wchar_t* text = nullptr;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
                       FORMAT_MESSAGE_IGNORE_INSERTS,
                   nullptr, code, 0, reinterpret_cast<LPWSTR>(&text), 0, nullptr);
    std::string message;
    if (text) {
        const int need = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
        message.resize(static_cast<size_t>(need > 0 ? need - 1 : 0));
        if (need > 0) WideCharToMultiByte(CP_UTF8, 0, text, -1, message.data(), need, nullptr, nullptr);
        LocalFree(text);
    }
    char prefix[128];
    std::snprintf(prefix, sizeof(prefix), "%s failed (err %lu): ", stage, code);
    return std::string(prefix) + message;
}

struct UrlParts {
    std::wstring host;
    std::wstring path;
    INTERNET_PORT port = 0;
    bool secure = false;
    bool valid = false;
};

UrlParts parseUrl(const std::string& url) {
    UrlParts parts;
    URL_COMPONENTS uc{};
    uc.dwStructSize = sizeof(uc);
    uc.dwSchemeLength = static_cast<DWORD>(-1);
    uc.dwHostNameLength = static_cast<DWORD>(-1);
    uc.dwUrlPathLength = static_cast<DWORD>(-1);
    uc.dwExtraInfoLength = static_cast<DWORD>(-1);

    const std::wstring wide = toWide(url);
    if (!WinHttpCrackUrl(wide.c_str(), static_cast<DWORD>(wide.size()), 0, &uc)) return parts;

    parts.secure = uc.nScheme == INTERNET_SCHEME_HTTPS;
    parts.port = uc.nPort;
    parts.host.assign(uc.lpszHostName, uc.dwHostNameLength);

    std::wstring path(uc.lpszUrlPath, uc.dwUrlPathLength);
    if (uc.dwExtraInfoLength > 0) path.append(uc.lpszExtraInfo, uc.dwExtraInfoLength);
    if (path.empty()) path = L"/";
    parts.path = path;
    parts.valid = true;
    return parts;
}

// 管一个 HINTERNET 的生命周期
struct Handle {
    HINTERNET h = nullptr;
    ~Handle() {
        if (h) WinHttpCloseHandle(h);
    }
};

HttpResponse run(const HttpRequest& req, const std::string& savePath, const ProgressFn& progress) {
    HttpResponse res;

    const UrlParts url = parseUrl(req.url);
    if (!url.valid) {
        res.error = "URL 解析失败: " + req.url;
        return res;
    }

    Handle session{WinHttpOpen(toWide(req.userAgent.empty() ? "Mozilla/5.0" : req.userAgent).c_str(),
                               WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, WINHTTP_NO_PROXY_NAME,
                               WINHTTP_NO_PROXY_BYPASS, 0)};
    if (!session.h) {
        res.error = lastErrorMessage("WinHttpOpen", GetLastError());
        return res;
    }
    WinHttpSetTimeouts(session.h, req.connectTimeoutMs, req.connectTimeoutMs, req.readTimeoutMs,
                       req.readTimeoutMs);

    DWORD redirectPolicy = req.followRedirects ? WINHTTP_OPTION_REDIRECT_POLICY_ALWAYS
                                               : WINHTTP_OPTION_REDIRECT_POLICY_NEVER;
    WinHttpSetOption(session.h, WINHTTP_OPTION_REDIRECT_POLICY, &redirectPolicy, sizeof(redirectPolicy));

    // 离线环境不做吊销检查，否则握手直接失败
    DWORD securityFlags = SECURITY_FLAG_IGNORE_REVOCATION;
    WinHttpSetOption(session.h, WINHTTP_OPTION_SECURITY_FLAGS, &securityFlags, sizeof(securityFlags));

    Handle connect{WinHttpConnect(session.h, url.host.c_str(), url.port, 0)};
    if (!connect.h) {
        res.error = lastErrorMessage("WinHttpConnect", GetLastError());
        return res;
    }

    Handle request{WinHttpOpenRequest(connect.h, L"GET", url.path.c_str(), nullptr, WINHTTP_NO_REFERER,
                                      WINHTTP_DEFAULT_ACCEPT_TYPES,
                                      url.secure ? WINHTTP_FLAG_SECURE : 0)};
    if (!request.h) {
        res.error = lastErrorMessage("WinHttpOpenRequest", GetLastError());
        return res;
    }

    std::wstring headers;
    auto addHeader = [&headers](const wchar_t* name, const std::string& value) {
        if (value.empty()) return;
        headers.append(name);
        headers.append(L": ");
        headers.append(toWide(value));
        headers.append(L"\r\n");
    };
    addHeader(L"Referer", req.referer);
    addHeader(L"Cookie", req.cookie);
    addHeader(L"Accept", req.accept);
    if (req.rangeStart > 0) addHeader(L"Range", "bytes=" + std::to_string(req.rangeStart) + "-");
    addHeader(L"Accept-Encoding", "identity");  // 自己解压太麻烦，要求明文

    if (!WinHttpSendRequest(request.h, headers.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : headers.c_str(),
                            static_cast<DWORD>(headers.empty() ? 0 : headers.size()),
                            WINHTTP_NO_REQUEST_DATA, 0, 0, 0)) {
        res.error = lastErrorMessage("WinHttpSendRequest", GetLastError());
        return res;
    }
    if (!WinHttpReceiveResponse(request.h, nullptr)) {
        res.error = lastErrorMessage("WinHttpReceiveResponse", GetLastError());
        return res;
    }

    DWORD status = 0;
    DWORD statusSize = sizeof(status);
    WinHttpQueryHeaders(request.h, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize, WINHTTP_NO_HEADER_INDEX);
    res.status = static_cast<int>(status);

    if (status >= 300 && status < 400) {
        wchar_t location[2048] = {};
        DWORD locationSize = sizeof(location);
        if (WinHttpQueryHeaders(request.h, WINHTTP_QUERY_LOCATION, WINHTTP_HEADER_NAME_BY_INDEX,
                                location, &locationSize, WINHTTP_NO_HEADER_INDEX)) {
            const int need = WideCharToMultiByte(CP_UTF8, 0, location, -1, nullptr, 0, nullptr, nullptr);
            if (need > 1) {
                std::string text(static_cast<size_t>(need - 1), '\0');
                WideCharToMultiByte(CP_UTF8, 0, location, -1, text.data(), need, nullptr, nullptr);
                res.location = text;
            }
        }
    }

    wchar_t lengthText[32] = {};
    DWORD lengthSize = sizeof(lengthText);
    if (WinHttpQueryHeaders(request.h, WINHTTP_QUERY_CONTENT_LENGTH, WINHTTP_HEADER_NAME_BY_INDEX,
                            lengthText, &lengthSize, WINHTTP_NO_HEADER_INDEX)) {
        res.contentLength = _wtoi64(lengthText);
    }

    FILE* file = nullptr;
    if (!savePath.empty()) {
        file = _wfopen(toWide(savePath).c_str(), L"wb");
        if (!file) {
            res.error = "无法写入文件: " + savePath;
            return res;
        }
    }

    long long done = 0;
    char buffer[256 * 1024];
    for (;;) {
        DWORD read = 0;
        if (!WinHttpReadData(request.h, buffer, sizeof(buffer), &read)) {
            res.error = lastErrorMessage("WinHttpReadData", GetLastError());
            break;
        }
        if (read == 0) break;
        if (file) {
            if (std::fwrite(buffer, 1, read, file) != read) {
                res.error = "写文件失败: " + savePath;
                break;
            }
        } else {
            res.body.append(buffer, read);
        }
        done += read;
        if (progress) progress(done, res.contentLength);
    }
    if (file) std::fclose(file);
    res.bytesWritten = done;

    return res;
}

class WinHttpClient : public HttpClient {
public:
    HttpResponse get(const HttpRequest& req) override { return run(req, std::string(), nullptr); }

    HttpResponse download(const HttpRequest& req, const std::string& path,
                          const ProgressFn& progress) override {
        return run(req, path, progress);
    }
};

}  // namespace

std::unique_ptr<HttpClient> makeHttpClient() { return std::make_unique<WinHttpClient>(); }

}  // namespace bili
