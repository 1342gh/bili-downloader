// ffmpeg 命令行拼装：把原来 Java 里散落的字符串逻辑收敛成纯函数，便于单测。
//
// 打包的 ffmpeg 是独立二进制（libffmpeg.so），不是库；所以这里只产出参数表，
// 真正执行由平台侧负责（宿主 fork/exec，安卓 posix_spawn 或 JNI 调 ProcessBuilder）。
#pragma once

#include <string>
#include <vector>

namespace bili {

struct TranscodeOptions {
    std::string format = "MP4";  // MP4 / MKV / MOV / M4A / MP3 / FLAC / WAV
    int resHeight = 0;           // 0 = 原始分辨率，否则按高度重采样（宽按比例）
    int fps = 0;                 // 0 = 原始帧率，否则复制帧到目标帧率
    bool needVideo = true;
    bool needAudio = true;
};

std::string extOf(const std::string& format);
std::string mimeOf(const std::string& format);
bool isCopySafe(const std::string& format);
std::string audioCodec(const std::string& format);
std::string audioBitrate(const std::string& format);  // 空串表示不指定
std::string videoFilters(int resHeight, int fps);     // 空串表示不加滤镜
std::string describe(const std::string& format, int resHeight, int fps);

// 音视频两条 m4s → 一个容器，纯搬运不重编码（等价 ffmpeg -c copy）
// needVideo/needAudio 决定取哪些轨。
std::vector<std::string> buildMergeArgs(const std::string& videoPath, const std::string& audioPath,
                                        const std::string& outPath, bool needVideo, bool needAudio);

// 已经合并好的文件 → 目标格式（转码/缩放/改帧率）
std::vector<std::string> buildTranscodeArgs(const std::string& inPath, const std::string& outPath,
                                            const TranscodeOptions& options);

// AI 插帧后的视频（无音轨）重新贴回原音轨
std::vector<std::string> buildMuxAudioArgs(const std::string& videoPath, const std::string& audioPath,
                                           const std::string& outPath);

}  // namespace bili
