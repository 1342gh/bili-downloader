// 宿主机调试/自测工具：不依赖安卓，直接跑真实接口，用来验证 C++ 核心逻辑。
//
//   bili_probe --selftest
//   bili_probe BV1GJ411x7h7
//   bili_probe "https://www.bilibili.com/video/BV1GJ411x7h7?p=2"
//   bili_probe BV1GJ411x7h7 --cookie "SESSDATA=xxx" --download out.m4s
#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>

#include "bili.h"
#include "download.h"
#include "ffmpeg_cmd.h"
#include "md5.h"
#include "wbi.h"

namespace {

int selfTest() {
    int failures = 0;
    auto check = [&failures](const char* label, const std::string& actual, const std::string& expected) {
        const bool ok = actual == expected;
        std::printf("  %-28s %s\n", label, ok ? "ok" : ("FAIL\n    got      " + actual + "\n    expected " + expected).c_str());
        if (!ok) ++failures;
    };

    std::printf("[md5]\n");
    check("md5(\"\")", bili::md5Hex(""), "d41d8cd98f00b204e9800998ecf8427e");
    check("md5(\"abc\")", bili::md5Hex("abc"), "900150983cd24fb0d6963f7d28e17f72");
    check("md5(\"消息摘要\")", bili::md5Hex("消息摘要"), "323070dd4582eda3825fec99ee0887db");

    std::printf("[wbi]\n");
    check("keyFromUrl", bili::wbi::keyFromUrl("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"),
          "7cd084941338484aae1ad9425b84077c");
    check("mixinKey", bili::wbi::mixinKey("7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45"),
          "ea1db124af3c7062474693fa704f4ff8");
    check("urlEncode 中文", bili::wbi::urlEncode("测试 a-b_c.d*e"), "%E6%B5%8B%E8%AF%95+a-b_c.d*e");

    std::printf("[target]\n");
    // parseTarget 需要 HttpClient，这里只测纯文本部分（短链不涉及）
    return failures;
}

}  // namespace

int main(int argc, char** argv) {
    std::ios::sync_with_stdio(false);

    if (argc < 2) {
        std::printf("用法: bili_probe <链接|BV号> [--cookie SESSDATA=...] [--download 文件]\n"
                    "      bili_probe --selftest\n");
        return 2;
    }
    if (std::strcmp(argv[1], "--selftest") == 0) {
        const int failures = selfTest();
        std::printf(failures == 0 ? "\n全部通过\n" : "\n%d 项失败\n", failures);
        return failures == 0 ? 0 : 1;
    }

    std::string input = argv[1];
    std::string cookie;
    std::string downloadPath;
    for (int i = 2; i < argc; ++i) {
        if (std::strcmp(argv[i], "--cookie") == 0 && i + 1 < argc) cookie = argv[++i];
        else if (std::strcmp(argv[i], "--download") == 0 && i + 1 < argc) downloadPath = argv[++i];
    }

    auto http = bili::makeHttpClient();
    bili::BiliClient client(*http);
    if (!cookie.empty()) client.setCookie(cookie);

    try {
        const bili::Target target = bili::parseTarget(input, *http);
        if (!target.valid()) {
            std::printf("解析不出视频号：%s\n", input.c_str());
            return 1;
        }
        std::printf("目标: %s  p=%d\n", target.query().c_str(), target.page);

        std::string nickname;
        bool vip = false;
        const bool loggedIn = client.fetchNav(&nickname, &vip);
        std::printf("登录: %s\n", loggedIn ? (nickname + (vip ? "（大会员）" : "")).c_str() : "未登录（最高 480P）");

        const bili::BiliClient::VideoInfo info = client.view(target);
        std::printf("标题: %s\nUP  : %s\ncid : %lld  pages=%zu\n", info.title.c_str(), info.owner.c_str(), info.cid,
                    info.pages.size());

        const std::string dash = client.playurl(info.bvid, info.cid);
        std::printf("可用画质:\n");
        for (const auto& line : bili::BiliClient::qualityList(dash)) std::printf("  - %s\n", line.c_str());

        const bili::Stream video = bili::BiliClient::bestVideo(dash);
        const bili::Stream audio = bili::BiliClient::bestAudio(dash);
        std::printf("选中视频: %s %dx%d %s  %.1f Mbps  候选 %zu 条\n", bili::BiliClient::qualityName(video.id).c_str(),
                    video.width, video.height, video.codecs.c_str(), video.bandwidth / 1e6, video.urls.size());
        std::printf("选中音频: id=%d %s  %.0f kbps  候选 %zu 条\n", audio.id, audio.codecs.c_str(),
                    audio.bandwidth / 1e3, audio.urls.size());
        for (size_t i = 0; i < video.urls.size(); ++i) std::printf("视频地址[%zu]: %s\n", i, video.urls[i].c_str());
        if (!audio.urls.empty()) std::printf("音频地址[0]: %s\n", audio.urls.front().c_str());

        // 演示 ffmpeg 参数拼装（真正执行交给平台侧）
        auto show = [](const char* label, const std::vector<std::string>& args) {
            std::printf("%s\n  ffmpeg", label);
            for (const auto& arg : args) std::printf(" %s", arg.c_str());
            std::printf("\n");
        };
        show("合并（搬运不重编码）:",
             bili::buildMergeArgs("video.m4s", "audio.m4s", "merged.mp4", true, true));
        bili::TranscodeOptions opts;
        opts.format = "MP3";
        opts.needVideo = false;
        show("只要音频 → MP3:", bili::buildTranscodeArgs("merged.m4a", "out.mp3", opts));

        if (!downloadPath.empty() && !video.urls.empty()) {
            const auto result = bili::downloadStream(*http, video.urls, downloadPath,
                                                     [](long long done, long long total) {
                                                         if (total > 0) {
                                                             std::printf("\r  下载 %lld/%lld KB (%lld%%)", done / 1024, total / 1024,
                                                                         done * 100 / total);
                                                         } else {
                                                             std::printf("\r  下载 %lld KB", done / 1024);
                                                         }
                                                         std::fflush(stdout);
                                                     });
            std::printf("\n%s\n", result.ok ? ("完成: " + downloadPath).c_str() : result.error.c_str());
            if (!result.ok) return 1;
        }
    } catch (const std::exception& e) {
        std::printf("失败: %s\n", e.what());
        return 1;
    }
    return 0;
}
