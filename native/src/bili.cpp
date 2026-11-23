#include "bili.h"

#include <algorithm>
#include <chrono>
#include <regex>
#include <stdexcept>
#include <thread>
#include <utility>

#include "json.hpp"
#include "wbi.h"

namespace bili {
namespace {

using json = nlohmann::json;

// B 站风控按 UA 放行，Safari 比 Chrome 稳；失败再换移动端 UA
constexpr const char* kSafariUa =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";
constexpr const char* kMobileUa =
    "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

constexpr const char* kNavUrl = "https://api.bilibili.com/x/web-interface/nav";
constexpr const char* kViewUrl = "https://api.bilibili.com/x/web-interface/view";

std::string trim(const std::string& text) {
    const auto begin = text.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) return {};
    const auto end = text.find_last_not_of(" \t\r\n");
    return text.substr(begin, end - begin + 1);
}

// playurl 返回的是 data（里面还套一层 dash）；也允许直接传 dash 本身
json dashOf(const json& root) {
    if (root.is_object() && root.contains("dash") && root["dash"].is_object()) return root["dash"];
    return root;
}

// b23.tv / bili2233 短链只回 302，把 Location 拿出来当真实地址
std::string resolveShortLink(HttpClient& http, const std::string& url) {
    HttpRequest req;
    req.url = url;
    req.userAgent = kSafariUa;
    req.followRedirects = false;
    const HttpResponse res = http.get(req);
    if (!res.location.empty()) return res.location;
    return url;
}

}  // namespace

Target parseTarget(const std::string& text, HttpClient& http) {
    Target target;

    std::string s = text;
    // 去掉引号和各种空白（分享文案里常带换行）
    s.erase(std::remove_if(s.begin(), s.end(), [](char c) { return c == '"' || c == '\'' || c == '\n' || c == '\r' || c == '\t'; }), s.end());
    s = trim(s);
    if (s.empty()) return target;

    if (s.find("b23.tv") != std::string::npos || s.find("bili2233") != std::string::npos) {
        s = resolveShortLink(http, s);
    }

    static const std::regex pageRe(R"([?&]p=(\d+))");
    std::smatch match;
    if (std::regex_search(s, match, pageRe)) target.page = std::stoi(match[1].str());

    static const std::regex bvRe(R"(BV[0-9A-Za-z]{10})");
    if (std::regex_search(s, match, bvRe)) {
        target.bvid = match.str();
        return target;
    }

    static const std::regex avRe(R"(av(\d+))", std::regex::icase);
    if (std::regex_search(s, match, avRe)) {
        target.aid = std::stoll(match[1].str());
        return target;
    }

    return target;
}

BiliClient::BiliClient(HttpClient& http) : http_(http) {}

void BiliClient::setCookie(const std::string& cookie) { cookie_ = cookie; }

std::string BiliClient::get(const std::string& url) {
    std::string lastError;
    for (const char* ua : {kSafariUa, kMobileUa}) {
        for (int attempt = 0; attempt < 2; ++attempt) {
            HttpRequest req;
            req.url = url;
            req.userAgent = ua;
            req.cookie = cookie_;

            const HttpResponse res = http_.get(req);
            if (res.error.empty() && res.status == 200 && !res.body.empty()) return res.body;

            if (!res.error.empty()) {
                lastError = res.error;
            } else if (res.status == 412) {
                lastError = "412 风控";
            } else {
                lastError = "HTTP " + std::to_string(res.status);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(800));
        }
    }
    throw std::runtime_error("请求失败：" + lastError);
}

bool BiliClient::fetchNav(std::string* nickname, bool* vip) {
    const json root = json::parse(get(kNavUrl), nullptr, false);
    if (root.is_discarded() || !root.contains("data") || !root["data"].is_object()) return false;

    const json& data = root["data"];
    if (data.contains("wbi_img") && data["wbi_img"].is_object()) {
        const std::string img = data["wbi_img"].value("img_url", "");
        const std::string sub = data["wbi_img"].value("sub_url", "");
        if (!img.empty() && !sub.empty()) {
            mixinKey_ = wbi::mixinKey(wbi::keyFromUrl(img), wbi::keyFromUrl(sub));
        }
    }

    // 注意：isLogin 是布尔 true/false，不是 1/0（Java 版踩过这个坑）
    const json& flag = data.contains("isLogin") ? data["isLogin"] : json(false);
    const bool loggedIn = flag.is_boolean() ? flag.get<bool>() : (flag.is_number() && flag.get<int>() == 1);
    if (!loggedIn) return false;

    if (nickname) *nickname = data.value("uname", "");
    if (vip) *vip = data.contains("vipStatus") && data["vipStatus"].is_number() && data["vipStatus"].get<int>() == 1;
    return true;
}

BiliClient::VideoInfo BiliClient::view(const Target& target) {
    std::string url = std::string(kViewUrl) + "?" + target.query();
    if (target.page > 1) url += "&p=" + std::to_string(target.page);

    const json root = json::parse(get(url), nullptr, false);
    if (root.is_discarded()) throw std::runtime_error("视频信息不是合法 JSON");

    const int code = root.value("code", -1);
    if (code != 0) {
        throw std::runtime_error("取视频信息失败：" + std::to_string(code) + " " + root.value("message", ""));
    }

    const json& data = root.at("data");
    VideoInfo info;
    info.bvid = data.value("bvid", target.bvid);
    info.aid = data.value("aid", 0LL);
    info.title = data.value("title", "");
    info.cid = data.value("cid", 0LL);
    info.cover = data.value("pic", "");
    if (data.contains("owner") && data["owner"].is_object()) info.owner = data["owner"].value("name", "");

    if (data.contains("pages") && data["pages"].is_array()) {
        for (const json& page : data["pages"]) {
            info.pages.emplace_back(page.value("cid", 0LL), page.value("part", ""));
        }
    }

    // 指定了分 P 就用那一 P 的 cid；越界则退回第一 P
    if (target.page > 1) {
        const size_t index = static_cast<size_t>(target.page - 1);
        if (index < info.pages.size() && info.pages[index].first > 0) info.cid = info.pages[index].first;
    }
    if (info.cid == 0 && !info.pages.empty()) info.cid = info.pages.front().first;

    return info;
}

std::string BiliClient::playurl(const std::string& bvid, long long cid) {
    // 1) 先试不带签名的旧接口：实测未登录/普通登录都更稳
    const std::string plain = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid +
                              "&cid=" + std::to_string(cid) + "&qn=127&fnval=4048&fourk=1";
    {
        const json root = json::parse(get(plain), nullptr, false);
        if (!root.is_discarded() && root.value("code", -1) == 0 && root.contains("data") &&
            root["data"].is_object()) {
            return root["data"].dump();
        }
    }

    // 2) 回退到 WBI 签名版本
    if (mixinKey_.empty()) fetchNav();
    if (mixinKey_.empty()) throw std::runtime_error("取播放地址失败：WBI key 获取失败");

    const long long now = std::chrono::duration_cast<std::chrono::seconds>(
                              std::chrono::system_clock::now().time_since_epoch())
                              .count();
    std::map<std::string, std::string> params{
        {"bvid", bvid},
        {"cid", std::to_string(cid)},
        {"qn", "127"},
        {"fnval", "4048"},
        {"fnver", "0"},
        {"fourk", "1"},
        {"wts", std::to_string(now)},
    };

    const std::string url = "https://api.bilibili.com/x/player/wbi/playurl?" + wbi::sign(params, mixinKey_);
    const json root = json::parse(get(url), nullptr, false);
    if (root.is_discarded()) throw std::runtime_error("取播放地址失败：响应不是 JSON");
    if (root.value("code", -1) != 0 || !root.contains("data") || !root["data"].is_object()) {
        throw std::runtime_error("取播放地址失败：" + std::to_string(root.value("code", -1)) + " " +
                                 root.value("message", ""));
    }
    return root["data"].dump();
}

std::string BiliClient::qualityName(int id) {
    switch (id) {
        case 127: return "8K";
        case 126: return "杜比视界";
        case 125: return "HDR";
        case 120: return "4K";
        case 116: return "1080P60";
        case 112: return "1080P+";
        case 80: return "1080P";
        case 74: return "720P60";
        case 64: return "720P";
        case 32: return "480P";
        case 16: return "360P";
        default: return "Q" + std::to_string(id);
    }
}

std::vector<std::string> BiliClient::qualityList(const std::string& dashJson) {
    const json dash = dashOf(json::parse(dashJson, nullptr, false));
    if (dash.is_discarded() || !dash.contains("video") || !dash["video"].is_array()) return {};

    std::vector<std::pair<int, std::string>> rows;
    for (const json& item : dash["video"]) {
        const int id = item.value("id", 0);
        const int w = item.value("width", 0);
        const int h = item.value("height", 0);
        rows.emplace_back(id, qualityName(id) + " " + std::to_string(w) + "x" + std::to_string(h));
    }
    std::sort(rows.begin(), rows.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    // 同一画质可能有多条（不同编码），去重后展示
    std::vector<std::string> out;
    for (const auto& [id, label] : rows) {
        if (out.empty() || out.back() != label) out.push_back(label);
    }
    return out;
}

Stream BiliClient::bestVideo(const std::string& dashJson) {
    const json dash = dashOf(json::parse(dashJson, nullptr, false));
    Stream best;
    if (dash.is_discarded() || !dash.contains("video") || !dash["video"].is_array()) return best;

    long long bestScore = -1;
    for (const json& item : dash["video"]) {
        const std::string codecs = item.value("codecs", "");
        long long pref = 1;
        if (codecs.rfind("avc", 0) == 0) pref = 3;
        else if (codecs.rfind("hvc", 0) == 0) pref = 2;

        const long long score = item.value("id", 0) * 1000000000LL + item.value("bandwidth", 0LL) * 10 + pref;
        if (score <= bestScore) continue;

        bestScore = score;
        best.id = item.value("id", 0);
        best.codecs = codecs;
        best.width = item.value("width", 0);
        best.height = item.value("height", 0);
        best.bandwidth = item.value("bandwidth", 0LL);
        best.urls = streamUrls(item.dump());
    }
    return best;
}

Stream BiliClient::bestAudio(const std::string& dashJson) {
    const json dash = dashOf(json::parse(dashJson, nullptr, false));
    Stream best;
    if (dash.is_discarded() || !dash.contains("audio") || !dash["audio"].is_array()) return best;

    long long bestBandwidth = -1;
    for (const json& item : dash["audio"]) {
        const long long bandwidth = item.value("bandwidth", 0LL);
        if (bandwidth <= bestBandwidth) continue;
        bestBandwidth = bandwidth;
        best.id = item.value("id", 0);
        best.codecs = item.value("codecs", "");
        best.bandwidth = bandwidth;
        best.urls = streamUrls(item.dump());
    }
    return best;
}

std::vector<std::string> BiliClient::streamUrls(const std::string& streamJson) {
    const json stream = json::parse(streamJson, nullptr, false);
    if (stream.is_discarded()) return {};

    std::vector<std::string> all;
    const std::string base = stream.value("baseUrl", "");
    if (!base.empty()) all.push_back(base);
    if (stream.contains("backupUrl") && stream["backupUrl"].is_array()) {
        for (const json& item : stream["backupUrl"]) {
            if (item.is_string() && !item.get<std::string>().empty()) all.push_back(item.get<std::string>());
        }
    }

    // 排序：upos / bilivideo 直连优先，mcdn、edge 这类边缘节点最后（容易断）
    auto isGood = [](const std::string& url) {
        return url.find("upos") != std::string::npos ||
               (url.find("bilivideo.com") != std::string::npos && url.find("mcdn") == std::string::npos);
    };
    auto isBad = [](const std::string& url) {
        return url.find("mcdn") != std::string::npos || url.find("edge.") != std::string::npos;
    };

    std::vector<std::string> out;
    auto add = [&out](const std::string& url) {
        if (std::find(out.begin(), out.end(), url) == out.end()) out.push_back(url);
    };
    for (const auto& url : all) {
        if (isGood(url)) add(url);
    }
    for (const auto& url : all) {
        if (!isBad(url)) add(url);
    }
    for (const auto& url : all) add(url);
    return out;
}

}  // namespace bili
