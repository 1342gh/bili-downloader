#include "ffmpeg_cmd.h"

namespace bili {

std::string extOf(const std::string& format) {
    if (format == "MKV") return ".mkv";
    if (format == "MOV") return ".mov";
    if (format == "M4A") return ".m4a";
    if (format == "MP3") return ".mp3";
    if (format == "FLAC") return ".flac";
    if (format == "WAV") return ".wav";
    return ".mp4";
}

std::string mimeOf(const std::string& format) {
    if (format == "MKV") return "video/x-matroska";
    if (format == "MOV") return "video/quicktime";
    if (format == "M4A") return "audio/mp4";
    if (format == "MP3") return "audio/mpeg";
    if (format == "FLAC") return "audio/flac";
    if (format == "WAV") return "audio/wav";
    return "video/mp4";
}

bool isCopySafe(const std::string& format) {
    return format == "MP4" || format == "MKV" || format == "MOV";
}

std::string audioCodec(const std::string& format) {
    if (format == "MP3") return "libmp3lame";
    if (format == "FLAC") return "flac";
    if (format == "WAV") return "pcm_s16le";
    return "copy";
}

std::string audioBitrate(const std::string& format) { return format == "MP3" ? "192k" : std::string(); }

std::string videoFilters(int resHeight, int fps) {
    std::string filters;
    if (resHeight > 0) filters += "scale=-2:" + std::to_string(resHeight) + ":flags=lanczos";
    if (fps > 0) {
        if (!filters.empty()) filters += ",";
        filters += "fps=" + std::to_string(fps);
    }
    return filters;
}

std::string describe(const std::string& format, int resHeight, int fps) {
    std::string text = "→ " + format;
    if (resHeight > 0) text += " / " + std::to_string(resHeight) + "p";
    if (fps > 0) text += " / " + std::to_string(fps) + "fps";
    return text;
}

std::vector<std::string> buildMergeArgs(const std::string& videoPath, const std::string& audioPath,
                                        const std::string& outPath, bool needVideo, bool needAudio) {
    std::vector<std::string> args{"-y", "-loglevel", "error"};
    if (needVideo && !videoPath.empty()) args.insert(args.end(), {"-i", videoPath});
    if (needAudio && !audioPath.empty()) args.insert(args.end(), {"-i", audioPath});

    args.insert(args.end(), {"-map", "0:v:0"});
    if (needAudio) {
        const std::string audioInput = needVideo ? "1:a:0" : "0:a:0";
        args.insert(args.end(), {"-map", audioInput});
    }
    args.insert(args.end(), {"-c", "copy", "-movflags", "+faststart", outPath});
    return args;
}

std::vector<std::string> buildTranscodeArgs(const std::string& inPath, const std::string& outPath,
                                            const TranscodeOptions& options) {
    std::vector<std::string> args{"-y", "-loglevel", "error", "-i", inPath};

    if (!options.needVideo) {
        args.insert(args.end(), {"-vn", "-c:a", audioCodec(options.format)});
        const std::string bitrate = audioBitrate(options.format);
        if (!bitrate.empty()) args.insert(args.end(), {"-b:a", bitrate});
    } else {
        const std::string filters = videoFilters(options.resHeight, options.fps);
        if (!filters.empty()) args.insert(args.end(), {"-vf", filters});

        if (filters.empty() && isCopySafe(options.format)) {
            args.insert(args.end(), {"-c:v", "copy"});
        } else {
            args.insert(args.end(), {"-c:v", "libx264", "-preset", "veryfast", "-crf", "20"});
        }

        if (options.needAudio) {
            args.insert(args.end(), {"-c:a", "copy"});
        } else {
            args.insert(args.end(), {"-an"});
        }
    }

    args.push_back(outPath);
    return args;
}

std::vector<std::string> buildMuxAudioArgs(const std::string& videoPath, const std::string& audioPath,
                                           const std::string& outPath) {
    return {"-y", "-loglevel", "error", "-i", videoPath, "-i", audioPath, "-map", "0:v:0", "-map", "1:a:0",
            "-c", "copy", "-movflags", "+faststart", outPath};
}

}  // namespace bili
