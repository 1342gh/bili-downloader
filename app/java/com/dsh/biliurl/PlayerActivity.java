package com.dsh.biliurl;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.graphics.Matrix;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.DefaultLoadControl;

import java.util.HashMap;
import java.util.Map;

/**
 * 全屏播放器（ExoPlayer / media3）。
 *
 * 相比系统 MediaPlayer：自带缓冲策略和断流重连，起播更稳、拖动更快；
 * setVideoScalingMode 支持裁剪填充 —— 横屏铺满，竖屏等比完整显示。
 *
 * 入参：url（本地缓冲代理或 CDN 直链）/ path（本地文件）/ fallback / ua / cookie / title
 */
public class PlayerActivity extends Activity implements SeekBar.OnSeekBarChangeListener {

    private TextureView textureView;
    private ExoPlayer player;
    private TextView hint;
    private TextView closeBtn;
    private TextView rotateBtn;
    private View controlBar;
    private SeekBar seekBar;
    private TextView timeText;
    private TextView playPause;

    private String pendingUrl;
    private String pendingPath;
    private String pendingUa;
    private String pendingCookie;
    private String fallbackUrl;
    private boolean usedFallback;
    private boolean controlsVisible = true;
    private int rotateMode;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 进度刷新：用方法而不是匿名 Runnable（d8 对匿名类容易内部崩溃） */
    private void tick() {
        updateProgress();
        handler.postDelayed(this::tick, 500);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        pendingPath = getIntent() == null ? null : getIntent().getStringExtra("path");
        pendingUrl = getIntent() == null ? null : getIntent().getStringExtra("url");
        pendingUa = getIntent() == null ? null : getIntent().getStringExtra("ua");
        pendingCookie = getIntent() == null ? null : getIntent().getStringExtra("cookie");
        fallbackUrl = getIntent() == null ? null : getIntent().getStringExtra("fallback");
        String title = getIntent() == null ? "" : getIntent().getStringExtra("title");

        buildUi(title);
        hideSystemBars();
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void buildUi(String title) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(this);
        root.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText("缓冲中…");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        controlBar = buildControlBar();
        root.addView(controlBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM));

        closeBtn = makeRoundButton("✕");
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(44), dp(36), Gravity.TOP | Gravity.END);
        cp.topMargin = dp(10);
        cp.rightMargin = dp(10);
        root.addView(closeBtn, cp);
        closeBtn.setOnClickListener(v -> finish());

        rotateBtn = makeRoundButton("⟳");
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(44), dp(36), Gravity.TOP | Gravity.END);
        rp.topMargin = dp(10);
        rp.rightMargin = dp(64);
        root.addView(rotateBtn, rp);
        rotateBtn.setOnClickListener(v -> cycleOrientation());

        textureView.setOnClickListener(v -> toggleControls());

        setContentView(root);
        setTitle(title == null || title.isEmpty() ? "播放" : title);
    }

    private View buildControlBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(0x99000000);

        playPause = new TextView(this);
        playPause.setText("⏸");
        playPause.setTextSize(20);
        playPause.setTextColor(Color.WHITE);
        playPause.setGravity(Gravity.CENTER);
        bar.addView(playPause, new FrameLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT));
        playPause.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause();
            else player.play();
            updatePlayPause();
        });

        timeText = new TextView(this);
        timeText.setTextSize(12);
        timeText.setTextColor(Color.WHITE);
        timeText.setText("00:00 / 00:00");
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.MATCH_PARENT);
        tp.leftMargin = dp(48);
        bar.addView(timeText, tp);

        seekBar = new SeekBar(this);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32), Gravity.CENTER_VERTICAL);
        sp.leftMargin = dp(148);
        sp.rightMargin = dp(12);
        bar.addView(seekBar, sp);
        seekBar.setOnSeekBarChangeListener(this);

        return bar;
    }

    private TextView makeRoundButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(18);
        v.setTextColor(0xE6FFFFFF);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(0x66000000);
        return v;
    }

    // ------------------------------------------------------------ 播放

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player == null) startPlayback();
        handler.post(this::tick);
    }

    private void startPlayback() {
        String source = pendingUrl;
        if (source == null || source.isEmpty()) source = pendingPath;
        if (source == null || source.isEmpty()) {
            hint.setText("没有可播放的地址");
            return;
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", pendingUa == null || pendingUa.isEmpty()
                ? "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                : pendingUa);
        headers.put("Referer", "https://www.bilibili.com/");
        if (pendingCookie != null && !pendingCookie.isEmpty()) headers.put("Cookie", pendingCookie);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(headers.get("User-Agent"))
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);

        // 起播门槛调低：默认要缓冲 2.5 秒才播，本地代理其实几十毫秒就能出数据
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 40000, 700, 1200)
                .build();

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .setLoadControl(loadControl)
                .build();
        player.setVideoTextureView(textureView);

        player.setMediaItem(MediaItem.fromUri(Uri.parse(source)));
        player.setPlayWhenReady(true);
        player.prepare();
        android.util.Log.i("biliurl-player", "exo start: " + source);
    }

    /** 代理走不通时改用 CDN 直链（只换一次） */
    private boolean switchToFallback() {
        if (usedFallback || fallbackUrl == null || fallbackUrl.isEmpty() || player == null) return false;
        usedFallback = true;
        player.setMediaItem(MediaItem.fromUri(Uri.parse(fallbackUrl)));
        player.prepare();
        android.util.Log.i("biliurl-player", "exo fallback: " + fallbackUrl);
        return true;
    }

    // ------------------------------------------------------------ 进度 / 控制条

    private void updateProgress() {
        if (player == null) return;
        // 不用 Player.Listener（d8 对新增匿名类不友好），改成轮询状态
        if (player.getPlaybackState() == Player.STATE_READY) {
            if (hint.getVisibility() == View.VISIBLE) hint.setVisibility(View.GONE);
            androidx.media3.common.Format fmt = player.getVideoFormat();
            if (fmt != null && fmt.width > 0) applyCropFill(fmt.width, fmt.height);
        }
        PlaybackException err = player.getPlayerError();
        if (err != null && !usedFallback) {
            android.util.Log.w("biliurl-player", "exo error: " + err.getErrorCodeName());
            if (!switchToFallback()) {
                hint.setText("播放失败：" + (err == null ? "?" : err.getErrorCodeName()));
                hint.setVisibility(View.VISIBLE);
            }
        }
        long pos = Math.max(0, player.getCurrentPosition());
        long dur = Math.max(0, player.getDuration());
        if (dur > 0) {
            seekBar.setProgress((int) (pos * 1000 / dur));
            timeText.setText(fmt(pos) + " / " + fmt(dur));
        }
        updatePlayPause();
    }

    private void updatePlayPause() {
        if (playPause == null) return;
        boolean playing = player != null && player.isPlaying();
        playPause.setText(playing ? "⏸" : "▶");
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void toggleControls() {
        controlsVisible = !controlsVisible;
        int vis = controlsVisible ? View.VISIBLE : View.GONE;
        controlBar.setVisibility(vis);
        closeBtn.setVisibility(vis);
        rotateBtn.setVisibility(vis);
        if (controlsVisible) {
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(autoHide, 3000);
        }
    }

    private final Runnable autoHide = () -> {
        if (!controlsVisible) return;
        controlsVisible = false;
        controlBar.setVisibility(View.GONE);
        closeBtn.setVisibility(View.GONE);
        rotateBtn.setVisibility(View.GONE);
    };

    // ------------------------------------------------------------ 画面比例

    /**
     * 画面比例：横屏裁剪铺满，竖屏等比完整显示。
     * TextureView 默认会把 buffer 拉满整个 view，所以这里算的是"相对默认拉伸"的修正系数。
     */
    private int lastVw, lastVh, lastViewW, lastViewH;

    private void applyCropFill(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || textureView == null) return;
        int viewW = textureView.getWidth();
        int viewH = textureView.getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        // 尺寸没变就别重复设矩阵（tick 每 500ms 会调一次）
        if (vw == lastVw && vh == lastVh && viewW == lastViewW && viewH == lastViewH) return;
        lastVw = vw; lastVh = vh; lastViewW = viewW; lastViewH = viewH;
        boolean portrait = viewH > viewW;
        float target = portrait
                ? Math.min((float) viewW / vw, (float) viewH / vh)
                : Math.max((float) viewW / vw, (float) viewH / vh);
        float sx = target * vw / viewW;
        float sy = target * vh / viewH;
        if (Math.abs(sx - 1f) < 0.001f && Math.abs(sy - 1f) < 0.001f) return;
        Matrix m = new Matrix();
        m.setScale(sx, sy, viewW / 2f, viewH / 2f);
        textureView.setTransform(m);
        android.util.Log.i("biliurl-player", "fit " + vw + "x" + vh + " view " + viewW + "x" + viewH
                + " sx " + sx + " sy " + sy);
    }

    // SeekBar 回调：实现接口而不是匿名类
    @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
        if (fromUser && player != null && player.getDuration() > 0) {
            player.seekTo(player.getDuration() * progress / 1000L);
        }
    }
    @Override public void onStartTrackingTouch(SeekBar sb) { }
    @Override public void onStopTrackingTouch(SeekBar sb) { }

    private void cycleOrientation() {
        int mode = rotateMode;
        if (mode == 0) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        } else if (mode == 1) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
        }
        rotateMode = (mode + 1) % 3;
    }

    // ------------------------------------------------------------ 沉浸式 / 生命周期

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onBackPressed() {
        if (controlsVisible) {
            controlsVisible = false;
            controlBar.setVisibility(View.GONE);
            closeBtn.setVisibility(View.GONE);
            rotateBtn.setVisibility(View.GONE);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        try { StreamProxy.stopCurrent(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
