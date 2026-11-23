// B 站接口层：链接解析、视频信息、取流地址、画质挑选。
//
// 这一层是纯逻辑 + 一次 HTTP 调用，不含任何平台代码，宿主机和安卓共用。
#pragma once

#include <string>
#include <vector>

#include "http.h"

namespace bili {

// 解析出来的目标：BV 号或 av 号，外加分 P
struct Target {
    std::string bvid;
    long long aid = 0;
    int page = 1;

    bool valid() const { return !bvid.empty() || aid > 0; }
    std::string query() const {
        return !bvid.empty() ? "bvid=" + bvid : "aid=" + std::to_string(aid);
    }
};

// 一条码流（dash.video[] 或 dash.audio[] 里的一项）挑出的必要字段
struct Stream {
    int id = 0;
    std::string codecs;
    int width = 0;
    int height = 0;
    long long bandwidth = 0;
    std::vector<std::string> urls;  // 官方节点优先，边缘节点最后
};

// 解析用户输入：完整链接 / 手机分享文案 / BV 号 / av 号 / b23.tv 短链
// 解析不出时返回 valid()==false 的 Target。短链需要发一次请求取跳转目标。
Target parseTarget(const std::string& text, HttpClient& http);

class BiliClient {
public:
    explicit BiliClient(HttpClient& http);

    void setCookie(const std::string& cookie);
    const std::string& cookie() const { return cookie_; }

    // 取 nav：顺便缓存 WBI 的 mixin key。返回是否已登录（未登录也能拿到 key）。
    bool fetchNav(std::string* nickname = nullptr, bool* vip = nullptr);

    // 视频基本信息；失败抛 std::runtime_error（message 带 B 站返回的 code/message）
    struct VideoInfo {
        std::string bvid;
        long long aid = 0;
        std::string title;
        std::string owner;
        long long cid = 0;
        std::string cover;
        std::vector<std::pair<long long, std::string>> pages;  // (cid, part 标题)
    };
    VideoInfo view(const Target& target);

    // dash 数据（playurl 的 data 字段）。先试不带签名的旧接口，失败再走 WBI。
    std::string playurl(const std::string& bvid, long long cid);

    // dash.video 里的画质清单，按 id 升序，形如 "1080P 1920x1080"
    static std::vector<std::string> qualityList(const std::string& dashJson);

    // dash 里挑最高画质（同画质优先 AVC）和最高码率音轨
    static Stream bestVideo(const std::string& dashJson);
    static Stream bestAudio(const std::string& dashJson);

    // 单独抽一路码流的候选地址（baseUrl + backupUrl，按节点质量排序）
    static std::vector<std::string> streamUrls(const std::string& streamJson);

    static std::string qualityName(int id);

private:
    std::string get(const std::string& url);

    HttpClient& http_;
    std::string cookie_;
    std::string mixinKey_;
};

}  // namespace bili
