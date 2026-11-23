package com.dsh.biliurl;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 用系统自带的 MediaExtractor + MediaMuxer 把两条 m4s 码流合成一个 MP4。
 * 不重编码，纯搬运（等价于 ffmpeg -c copy）。
 */
public class Merger {

    public static void merge(File video, File audio, File out,
                             boolean needVideo, boolean needAudio) throws IOException {

        MediaExtractor ve = null, ae = null;
        MediaMuxer mux = null;
        try {
            int vTrackIn = -1, aTrackIn = -1;
            MediaFormat vFmt = null, aFmt = null;

            if (needVideo && video != null) {
                ve = new MediaExtractor();
                ve.setDataSource(video.getAbsolutePath());
                vTrackIn = findTrack(ve, "video/");
                if (vTrackIn < 0) throw new IOException("视频文件里没有视频轨");
                vFmt = ve.getTrackFormat(vTrackIn);
                ve.selectTrack(vTrackIn);
            }

            if (needAudio && audio != null) {
                ae = new MediaExtractor();
                ae.setDataSource(audio.getAbsolutePath());
                aTrackIn = findTrack(ae, "audio/");
                if (aTrackIn < 0) throw new IOException("音频文件里没有音频轨");
                aFmt = ae.getTrackFormat(aTrackIn);
                ae.selectTrack(aTrackIn);
            }

            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = vFmt != null ? mux.addTrack(vFmt) : -1;
            int aOut = aFmt != null ? mux.addTrack(aFmt) : -1;
            mux.start();

            if (vOut >= 0) copyTrack(ve, mux, vOut);
            if (aOut >= 0) copyTrack(ae, mux, aOut);

            mux.stop();
        } finally {
            if (ve != null) ve.release();
            if (ae != null) ae.release();
            if (mux != null) {
                try { mux.release(); } catch (Exception ignored) { }
            }
        }
    }

    private static int findTrack(MediaExtractor ex, String mimePrefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private static void copyTrack(MediaExtractor ex, MediaMuxer mux, int outTrack) {
        int maxSize = 1024 * 1024;
        ByteBuffer buf = ByteBuffer.allocateDirect(maxSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            mux.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }
}
