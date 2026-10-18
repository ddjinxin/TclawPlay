package com.jingxin.jingxinmusic.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.scene.CoverScene;
import com.jingxin.jingxinmusic.scene.CoverSceneHelper;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.service.MusicPlayerService.MusicPlayerBinder;
import com.jingxin.jingxinmusic.util.FavoriteManager;
import com.jingxin.jingxinmusic.util.HistoryManager;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.util.LyricFetcher;
import com.jingxin.jingxinmusic.util.MusicScanner;
import com.jingxin.jingxinmusic.util.CoverColorExtractor;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.RotatingCoverView;
import com.jingxin.jingxinmusic.view.SpectrumView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.AlertDialog;

/**
 * 播放页面 Fragment
 * 旋转封面 + 歌词 + 频谱 + 进度条 + 控制按钮
 */
public class PlayerFragment extends BaseFloatFragment {

    private static final String TAG = "PlayerFragment";
    private static final int PROGRESS_UPDATE_INTERVAL = 200;

    // ===== 控制栏自动隐藏（精简模式） =====
    private boolean controlsHidden = false;      // 当前控制栏是否隐藏
    private boolean controlsHideEnabled = false; // 播放中且配置不为"不隐藏"才启用（暂停/未开始不启用）

    /** 读取自动隐藏延时配置（秒），-1=不隐藏 */
    private int getControlsHideDelayMs() {
        int sec = com.jingxin.jingxinmusic.fragment.SettingsFragment.getAutoHideControlsSeconds(requireContext());
        return sec <= 0 ? -1 : sec * 1000;
    }

    private Song song;
    private int position;
    private List<Song> allSongs;
    private String playlistMode = "all"; // "all" 或 "favorites"
    private boolean resumePlay = false; // 从 mini 播放条跳转，不重新播放
    private long resumePosition = 0; // 自动恢复时的播放进度（毫秒），仅首次 playSong 使用

    private View mRootView;

    // UI
    private ImageView blurBackground;
    private LyricView lyricView;
    private SpectrumView spectrumView;
    
    private android.widget.PopupWindow effectPickerPopup; // 音效选择浮窗
    private View overlayView;
    private View whiteOverlay;
    private View immersiveDarkOverlay;

    // 封面缓存：切歌时保持旧封面，避免「默认封面→真实封面」闪烁
    private boolean hasCoverLoaded = false; // 是否已有封面加载过

    // 白天模式渐变遮罩（浅绿→白）
    private android.graphics.drawable.GradientDrawable whiteGradientDrawable;

    // 主题
    private boolean isNightMode = true;  // 默认夜间模式
    // 数据中枢（组件化第2步）：播放页状态单一数据源，广播/手动操作同步写入，组件订阅消费
    private final com.jingxin.jingxinmusic.player.PlayerStateHub stateHub =
            com.jingxin.jingxinmusic.player.PlayerStateHub.get();

    /** 日夜变更统一入口：写中枢 + 同步本地字段（所有 Set isNightMode 的地方必须改走此项） */
    private void setNightModeSynced(boolean night) {
        if (isNightMode == night) return;
        isNightMode = night;
        stateHub.setNightMode(night);
    }
    private boolean isFavorite = false;  // 当前歌曲是否已收藏
    private boolean biliDownloadEnabled = true; // B站离线保存进行中置 false（下载键禁用）

    // 封面风格：0=经典, 1=沉浸, 2=唱片机
    private static final int COVER_STYLE_CLASSIC = 0;
    private static final int COVER_STYLE_CLASSIC_V2 = 4; // 第5风格：新经典（自持实例页）
    private static final int COVER_STYLE_IMMERSIVE_V2 = 5; // 第6风格：沉浸 V2（自持实例页）
    private static final int COVER_STYLE_RECORD_V2 = 6; // 第7风格：唱片机 V2（自持实例页）
    private static final int COVER_STYLE_CAROUSEL_V2 = 7; // 第8风格：轮播 V2（自持实例页）

    /**
     * 风格入口收口：旧四风格（0~3）已隐藏（双轨观察期，代码保留），入口只在 V2 新风格间循环。
     * 读 prefs 时旧值映射到对应新风格（0→新经典 1→沉浸新 2→唱片机新 3→轮播新）。
     */
    private static final boolean HIDE_LEGACY_STYLES = true;

    /** 风格值规范化：隐藏开关开启时旧值映射到对应 V2 新风格（幂等） */
    private static int normalizeCoverStyle(int style) {
        if (!HIDE_LEGACY_STYLES) return style;
        if (style < COVER_STYLE_CLASSIC_V2) return style + COVER_STYLE_CLASSIC_V2;
        return style > COVER_STYLE_CAROUSEL_V2 ? COVER_STYLE_CLASSIC_V2 : style;
    }
    private int coverStyle = COVER_STYLE_CLASSIC;
    private com.jingxin.jingxinmusic.view.AuroraGradientView auroraBackgroundView;
    private int bgSkinMode = 0; // 0=默认模式, 1=双色光晕
    // 背景皮肤控制器（组件化第3步）：背景渲染收口，updateThemeUI 委托分发
    private com.jingxin.jingxinmusic.bg.BackgroundSkinController bgSkinController;
    private int lastAuroraCoverColor = 0; // 光晕模式最近一次封面提取色（用于日夜切换重新映射歌词色）
    private volatile int coverColorToken = 0; // 封面取色竞态防护token，每次setCoverBitmap递增
    // （旧共享唱臂 tonearmView 已随旧四风格页下线：V2 唱机页自持唱臂，此链全删）
    private com.jingxin.jingxinmusic.view.ImmersiveOverlayView immersiveOverlay;
    private android.widget.PopupWindow spectrumPickerPopup; // 频谱选择浮窗
    private android.widget.PopupWindow lyricSearchPopup; // 歌词搜索浮窗
    private android.widget.PopupWindow historyPopup; // 播放历史浮窗

    // 横屏模式
    private boolean isLandscapeMode = false; // 宽>高*1.2 时为横屏
    private com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable coverBorderGradient; // 横屏沉浸封面边缘渐变
    private com.jingxin.jingxinmusic.view.LandscapeGradientOverlay landscapeGradientOverlay; // 横屏沉浸渐变过渡层

    // CoverScene 模式策略
    private CoverSceneHelper sceneHelper;
    // 旧 8 个 Scene 实例已随 scene/ 拆除（步骤7）：四风格全部由 StagePageAdapter 承载
    // 新架构风格页面（双轨，组件化第5/6步）：StagePageAdapter 接入 currentScene 调度
    private com.jingxin.jingxinmusic.stage.StagePageAdapter classicV2PageAdapter; // 第5风格
    private com.jingxin.jingxinmusic.stage.StagePageAdapter immersiveV2PageAdapter; // 第6风格
    private com.jingxin.jingxinmusic.stage.StagePageAdapter recordV2PageAdapter; // 第7风格
    private com.jingxin.jingxinmusic.stage.StagePageAdapter carouselV2PageAdapter; // 第8风格
    private CoverScene currentScene;

    // 播放服务
    private MusicPlayerBinder playerBinder;
    private boolean bound = false;
    private final Handler uiHandler = new Handler();
    private boolean userSeeking = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 频谱：Visualizer（主方案）或 AudioRecord（降级方案）
    private android.media.audiofx.Visualizer visualizer;
    private AudioRecord audioRecord;
    private volatile boolean spectrumRunning = false;
    private boolean useVisualizer = false; // 当前是否用 Visualizer

    // 频谱双缓冲：采集线程写 bufA/bufB 轮换，主线程读另一个，避免每帧 new float[] 且防撕裂
    private float[] spectrumBufA;
    private float[] spectrumBufB;
    private volatile boolean spectrumBufFlip = false; // true=采集线程写B读A，false=写A读B
    private int spectrumBufLen = 0;

    // 频谱仲裁广播：PlayerFragment 创建前通知 Service 释放（Service 监听此 action）
    private static final String ACTION_PLAYER_CLAIM = "com.jingxin.jingxinmusic.PLAYER_CLAIM_VISUALIZER";
    // Service 创建前通知 PlayerFragment 释放（PlayerFragment 监听此 action）
    private static final String ACTION_SERVICE_CLAIM = "com.jingxin.jingxinmusic.SERVICE_CLAIM_VISUALIZER";
    private static final int SAMPLE_RATE = 8000;
    private static final int FFT_SIZE = 256;

    // 广播接收器：监听歌曲切换和播放状态
    private BroadcastReceiver songChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MusicPlayerService.ACTION_SONG_CHANGED.equals(action)) {
                // 切歌了，更新所有 UI
                Song newSong = Song.fromIntent(intent);
                position = intent.getIntExtra(MusicPlayerService.EXTRA_SONG_INDEX, 0);
                stateHub.setSong(newSong); // 数据中枢同步（组件化第2步）

                song = newSong;
                // Hub 封面/歌词快照失效（新歌数据到达前，独立页不读到旧歌缓存）
                stateHub.invalidateCoverAndLyric();
                loadCover();
                fetchLyrics();
                checkFavoriteStatus();
                updateDownloadButtonVisibility();
                saveLastPlayed();
                // 第8风格（轮播V2）卡片位置同步（切歌即重新拉列表）
                if (coverStyle == COVER_STYLE_CAROUSEL_V2 && carouselV2PageAdapter != null) {
                    ((com.jingxin.jingxinmusic.stage.CarouselPageV2) carouselV2PageAdapter.getPage())
                            .syncPlaylistPosition();
                }
                // 切歌时重置自动隐藏计时（新歌重新计算5秒窗口）
                if (controlsHideEnabled) {
                    showControls(false);
                    resetControlsHideTimer();
                }
                // 切歌时重建频谱（sessionId 可能变化，如 ExoPlayer 重建或 MediaPlayer 兜底）
                if (com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().isFloating() || (isSpectrumStartable() && bound && playerBinder != null)) {
                    stopSpectrum();
                    startSpectrumWithPermission();
                }
            } else if (MusicPlayerService.ACTION_PLAY_STATE_CHANGED.equals(action)) {
                // 连续播放失败：服务停止自动切歌，向用户说明原因
                if (intent.getBooleanExtra("play_error", false)) {
                    String msg = intent.getStringExtra("error_message");
                    android.widget.Toast.makeText(requireContext(),
                            msg != null ? msg : "连续播放失败，请检查网络或音乐文件",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false);
                stateHub.setPlaying(playing); // 数据中枢同步
                updatePlayPauseButton(playing);
                updateControlsAutoHideState(playing);
            } else if (MusicPlayerService.ACTION_PLAY_ORDER_CHANGED.equals(action)) {
                int order = intent.getIntExtra(MusicPlayerService.EXTRA_PLAY_ORDER, 0);
                stateHub.setPlayOrder(order); // 数据中枢同步
                updatePlayOrderIcon(order);
            } else if (MusicPlayerService.ACTION_THEME_CHANGED.equals(action)) {
                // 高德导航日夜模式切换：只要高德广播状态与当前不同就跟随，
                // 不依赖 amapTriggered 标记（用户手动切主题只是临时设置，高德广播始终可接管）
                boolean amapNight = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_NIGHT, isNightMode);
                if (amapNight != isNightMode) {
                    // 高德触发的，直接同步
                    setNightModeSynced(amapNight);
                    if (lyricView != null) {
                        lyricView.setThemeMode(isNightMode ?
                                com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT :
                                com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
                    }
                        // 双色光晕模式：亮度图后重新映射歌词高亮色（QQ固定用深色）
                        if (isAuroraActive() && lyricView != null && lastAuroraCoverColor != 0) {
                            int lyricColor = CoverColorExtractor.getLyricHighlightColor(lastAuroraCoverColor, false);
                            lyricView.setHighlightColor(lyricColor);
                        }
                        updateThemeUI();
                }
            } else if (ACTION_SERVICE_CLAIM.equals(action)) {
                // MiniFloatService 要创建 Visualizer，PlayerFragment 释放自己的
                stopSpectrum();
            } else if ("com.jingxin.jingxinmusic.FLOAT_LAYOUT_REFRESH".equals(action)) {
                // 乐酷悬浮进入：按悬浮区域尺寸刷新布局
                if (!isActivityGone()) {
                    detectAndApplyLandscapeMode();
                    applyLayoutMode();
                    int w = getLayoutWidth();
                    int h = getAvailableScreenHeight();
                    currentScene.layout(w, h);
                    updateThemeUI();
                    updateLayoutForMode(lyricView.getDisplayMode());
                    lyricView.post(lyricView::refreshTextSize);
                }
            } else if ("com.jingxin.jingxinmusic.SPECTRUM_RESTART".equals(action)) {
                // 乐酷悬浮进入/退出时，重建频谱
                if (com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().isFloating() || isSpectrumStartable()) {
                    // 可见 → 重建频谱
                    stopSpectrum();
                    startSpectrumWithPermission();
                } else {
                    // 不可见 → 让出 Visualizer，MiniFloatService 接管
                    stopSpectrum();
                }
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerBinder = (MusicPlayerBinder) service;
            bound = true;

            // 读缓存或扫描歌曲列表（不触发 triggerMediaScan）
            executor.execute(() -> {
                // 同步播放模式图标（进程重启后服务已恢复持久化值，但不广播，需主动读取）
                int order = playerBinder.getPlayOrder();
                uiHandler.post(() -> {
                    // 服务绑定完成：初始状态灌入数据中枢（歌/播放态/模式，组件订阅方自动刷新）
                    stateHub.setSong(playerBinder.getCurrentSong());
                    stateHub.setPlaying(playerBinder.isPlaying());
                    stateHub.setPlayOrder(order);
                    updatePlayOrderIcon(order);
                });
                allSongs = MusicScanner.loadCache(requireContext());
                if (allSongs == null || allSongs.isEmpty()) {
                    allSongs = MusicScanner.scanMusic(requireContext());
                }
                uiHandler.post(() -> {
                    if (bound && playerBinder != null && allSongs != null) {
                        if (resumePlay && playerBinder.isPlaying()) {
                            // 从 mini 播放条跳转，音乐已在后台播放，只更新 UI
                            startSpectrumWithPermission();
                            // 通过 Scene 同步播放状态（唱片机同步唱臂等）
                            currentScene.onServiceResumed(true);
                            // 已在后台播放：启用控制栏自动隐藏
                            updateControlsAutoHideState(true);
                        } else if ("folder".equals(playlistMode)) {
                            // 目录模式：播放队列 = 该目录歌曲
                            List<String> folderPaths = getArguments() != null ? getArguments().getStringArrayList("folder_song_paths") : null;
                            if (folderPaths != null && !folderPaths.isEmpty()) {
                                List<Song> folderSongs = new ArrayList<>();
                                for (String path : folderPaths) {
                                    for (Song s : allSongs) {
                                        if (s.filePath != null && s.filePath.equals(path)) {
                                            folderSongs.add(s);
                                            break;
                                        }
                                    }
                                }
                                if (!folderSongs.isEmpty()) {
                                    playerBinder.setPlaylist(folderSongs);
                                    playSong();
                                } else {
                                    playerBinder.setPlaylist(allSongs);
                                    playSong();
                                }
                            } else {
                                playerBinder.setPlaylist(allSongs);
                                playSong();
                            }
                        } else if ("favorites".equals(playlistMode)) {
                            // 收藏模式：播放队列 = 收藏歌曲
                            File favDir = com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext());
                            List<Song> favSongs = FavoriteManager.loadFavorites(favDir);
                            if (!favSongs.isEmpty()) {
                                playerBinder.setPlaylist(favSongs);
                                // 从收藏列表中找到匹配歌曲的位置
                                int favPos = 0;
                                for (int i = 0; i < favSongs.size(); i++) {
                                    if (favSongs.get(i).filePath != null &&
                                            favSongs.get(i).filePath.equals(song.filePath)) {
                                        favPos = i;
                                        break;
                                    }
                                }
                                position = favPos;
                                playSong();
                            } else {
                                playerBinder.setPlaylist(allSongs);
                                playSong();
                            }
                        } else if ("webdav".equals(playlistMode) || "bili".equals(playlistMode) || (getArguments() != null && getArguments().getBoolean("from_webdav", false))) {
                            // WebDAV/B站模式：从SharedPreferences恢复播放列表
                            List<Song> savedSongs = loadWebDavPlaylist();
                            if (!savedSongs.isEmpty()) {
                                playerBinder.setPlaylist(savedSongs);
                                // position优先从"position"取（正常浏览点击），"song_index"是自动恢复时的备选
                                int savedIndex = getArguments() != null ? getArguments().getInt("song_index", -1) : -1;
                                if (position >= 0 && position < savedSongs.size()) {
                                    // 从"position"已取到有效值，直接用
                                } else if (savedIndex >= 0 && savedIndex < savedSongs.size()) {
                                    // 自动恢复场景
                                    position = savedIndex;
                                } else {
                                    position = 0;
                                }
                                // 用播放列表中完整字段的歌曲替代可能缺失字段的song
                                song = savedSongs.get(position);
                                playSong();
                            } else {
                                // 降级：单曲播放
                                playerBinder.setPlaylist(java.util.Collections.singletonList(song));
                                position = 0;
                                playSong();
                            }
                        } else {
                            playerBinder.setPlaylist(allSongs);
                            playSong();
                        }
                        // 第8风格（轮播V2）：列表就绪后全量灌入轮布封面
                        if (coverStyle == COVER_STYLE_CAROUSEL_V2 && carouselV2PageAdapter != null) {
                            ((com.jingxin.jingxinmusic.stage.CarouselPageV2) carouselV2PageAdapter.getPage())
                                    .syncPlaylistPosition();
                        }
                    }
                });
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playerBinder = null;
            bound = false;
        }
    };

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (bound && playerBinder != null && !userSeeking) {
                int currentPosition = playerBinder.getCurrentPosition();
                int duration = playerBinder.getDuration();
                if (duration > 0) {
                    // 数据中枢：进度唯一写入点，ProgressPanel/歌词等订阅方各自消费
                    // （旧 lyricView 直调已删：V2 页四实例均订阅 ProgressObserver）
                    stateHub.setProgress(currentPosition, duration);
                }
            }
            uiHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL);
        }
    };

    // ===== 控制栏自动隐藏（精简模式）实现 =====

    /** 重置自动隐藏计时器：每次触摸/播放/切歌后重算延时窗口 */
    private void resetControlsHideTimer() {
        uiHandler.removeCallbacks(controlsHideRunnable);
        int delay = getControlsHideDelayMs();
        // 配置为"不隐藏"(-1)则禁用
        if (delay < 0) {
            controlsHideEnabled = false;
            showControls(false);
            return;
        }
        // 仅播放中启用（暂停/未开始保持常显）
        if (controlsHideEnabled && !controlsHidden) {
            uiHandler.postDelayed(controlsHideRunnable, delay);
        }
    }

    /** 停止并清除自动隐藏计时 */
    private void stopControlsHideTimer() {
        uiHandler.removeCallbacks(controlsHideRunnable);
    }

    private final Runnable controlsHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (controlsHideEnabled && !controlsHidden) {
                hideControls();
            }
        }
    };

    /** 隐藏控制栏（精简模式）：旧共享实例已 GONE（info_panel 收纳），仅驱动 V2 页 */
    private void hideControls() {
        if (controlsHidden) return;
        controlsHidden = true;
        // 同步第5风格（V2）自持实例
        if (classicV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) classicV2PageAdapter.getPage())
                    .setControlsHiddenFromHost(true);
        }
        // 同步第7风格（唱片机 V2）自持实例
        if (recordV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) recordV2PageAdapter.getPage())
                    .setControlsHiddenFromHost(true);
        }
        // 同步第8风格（轮播 V2）自持实例
        if (carouselV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) carouselV2PageAdapter.getPage())
                    .setControlsHiddenFromHost(true);
        }
        // 同步第6风格（沉浸�V2）自持实例
        if (immersiveV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ImmersivePageV2) immersiveV2PageAdapter.getPage())
                    .setControlsHiddenFromHost(true);
        }
    }

    /** 恢复控制栏，可选重置隐藏计时 */
    private void showControls(boolean resetTimer) {
        if (!controlsHidden) {
            if (resetTimer) resetControlsHideTimer();
            return;
        }
        controlsHidden = false;
        // 同步第5风格（V2）自持实例
        if (classicV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) classicV2PageAdapter.getPage())
                    .setControlsHiddenFromHost(false);
        }
        // 同步第7风格（唱片机 V2）自持实例
        if (recordV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) recordV2PageAdapter.getPage())
                    .setControlsHiddenFromHost(false);
        }
        // 同步第8风格（轮播 V2）自持实例
        if (carouselV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) carouselV2PageAdapter.getPage())
                    .setControlsHiddenFromHost(false);
        }
        // 同步第6风格（沉浸�V2）自持实例
        if (immersiveV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ImmersivePageV2) immersiveV2PageAdapter.getPage())
                    .setControlsHiddenFromHost(false);
        }
        if (resetTimer) resetControlsHideTimer();
    }

    /** 根据播放状态更新自动隐藏开关：播放中启用，暂停/停止禁用并强制显示 */
    private void updateControlsAutoHideState(boolean playing) {
        if (playing) {
            if (!controlsHideEnabled) {
                controlsHideEnabled = true;
                // 开始播放：先显示控制栏并重置5秒计时
                showControls(false);
                resetControlsHideTimer();
            }
        } else {
            // 暂停/停止：禁用自动隐藏并强制恢复控制栏
            controlsHideEnabled = false;
            stopControlsHideTimer();
            showControls(false);
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 获取传入的歌曲信息
        Bundle args = getArguments();
        if (args != null) {
            Intent temp = new Intent();
            temp.putExtras(args);
            song = Song.fromIntent(temp);
            position = args.getInt("position", 0);
            playlistMode = args.getString("playlist_mode");
            if (playlistMode == null) playlistMode = "all";
            resumePlay = args.getBoolean("resume_play", false);
            resumePosition = args.getLong("resume_position", 0L);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_player, container, false);
        mRootView = view;

        // 初始化视图
        blurBackground = view.findViewById(R.id.blur_background);
        auroraBackgroundView = view.findViewById(R.id.aurora_background);
          // 歌名+歌手组件
        // 过渡期兼容：Scene 布局手术仍直接访问文本 View
        // 歌词数据泵：detached 实例（fetchLyrics/搜索回填写入点 → Hub 广播到 V2 页；
        // 旧 lyric_view 节点已删，V2 页各持显示实例）
        lyricView = new LyricView(requireContext());
        // 频谱真值锚：detached 实例（不挂视图树，仅承载 prefs 样式读写与采集参数；
        // 旧 spectrum_view 节点已删，V2 页各持显示实例）
        spectrumView = new SpectrumView(requireContext());
        // 进度组件（SeekBar+双时间，事件经 Listener 上抛）
        // 底部控制条组件（内部含7键：播放/上一首/下一首/模式/历史/收藏/下载）
        overlayView = view.findViewById(R.id.overlay_view);
        whiteOverlay = view.findViewById(R.id.white_overlay);
        // 初始化白天模式渐变遮罩：浅蓝(0xFFADD8E6)→白(0xFFFFFFFF)，左上到右下
        whiteGradientDrawable = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFADD8E6, 0xFFFFFFFF}
        );
        whiteOverlay.setBackground(whiteGradientDrawable);
        whiteOverlay.setAlpha(0.7f);
        immersiveDarkOverlay = view.findViewById(R.id.immersive_dark_overlay);
        immersiveOverlay = view.findViewById(R.id.immersive_overlay);

        // 初始化 CoverScene 策略
        initCoverScene();

        // 延迟检测横屏模式（等视图布局完成）
        // 用 ViewTreeObserver 监听布局变化，自动切换横竖屏布局
        isLandscapeMode = false;
        View rootView = view.findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (isActivityGone()) return;
                boolean wasLandscape = isLandscapeMode;
                detectAndApplyLandscapeMode();
                if (wasLandscape != isLandscapeMode) {
                    applyLayoutMode();
                    updateThemeUI();
                    updateLayoutForMode(lyricView.getDisplayMode());
                }
            });
        }

        // 在根 FrameLayout 上监听尺寸变化（车机调整应用宽度时触发）
        android.widget.FrameLayout rootFrameLayout = view.findViewById(R.id.root_layout);
        if (rootFrameLayout != null) {
            rootFrameLayout.addOnLayoutChangeListener(
                    (View v, int left, int top, int right, int bottom,
                     int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
                        if (isActivityGone()) return;
                        int newWidth = right - left;
                        int oldWidth = oldRight - oldLeft;
                        if (newWidth > 0 && newWidth != oldWidth) {
                            // 横竖屏切换时关闭频谱选择弹窗
                            if (spectrumPickerPopup != null && spectrumPickerPopup.isShowing()) {
                                spectrumPickerPopup.dismiss();
                            }
                            if (effectPickerPopup != null && effectPickerPopup.isShowing()) {
                                effectPickerPopup.dismiss();
                            }
                            // 重新检测横竖屏并应用完整布局
                            detectAndApplyLandscapeMode();
                            applyLayoutMode();
                            // 始终重新layout，更新频谱位置（圆环模式需要跟随封面中心）
                            int w = getLayoutWidth();
                            int h = getAvailableScreenHeight();
                            currentScene.layout(w, h);
                            updateThemeUI();
                            updateLayoutForMode(lyricView.getDisplayMode());
                            // 兕底：Scene.layout() 设置了 LyricView 新 LayoutParams，
                            // 但 onSizeChanged 在下一帧才触发，存在时序窗口。
                            // post 一次 refreshTextSize 确保字号立即重算+重绘。
                             lyricView.post(lyricView::refreshTextSize);
                         }
                     });
         }

        // 首次必须应用竖屏布局（设置封面占位等）——通过 scene layout
        syncSceneState();
        currentScene.layout(getLayoutWidth(), getAvailableScreenHeight());

        // 读取主题状态并同步所有 UI
        Context ctx = requireContext();
        isNightMode = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE).getBoolean("isNight", true);
        stateHub.setNightMode(isNightMode); // 初始值入中枢
        coverStyle = normalizeCoverStyle(ctx.getSharedPreferences("theme", Context.MODE_PRIVATE).getInt("cover_style", COVER_STYLE_CLASSIC));
        bgSkinMode = SettingsFragment.getBgSkin(ctx);
        stateHub.setBgSkinMode(bgSkinMode); // 必须在读取 bgSkinMode 之后写中枢（此前写在读取前，恒写 0=默认皮肤）
        sceneHelper.bgSkinMode = bgSkinMode; // 同步到 helper，确保 enter() 能正确判断
        sceneHelper.coverStyle = coverStyle;
        // 在初始化场景前先检测横竖屏（OnGlobalLayoutListener是异步的，会太晚）
        detectAndApplyLandscapeMode();
        ThemeColors.init(ctx);
        ThemeColors.restoreDominant(ctx); // 冷启动恢复持久化主色（与首页同步）
        updateThemeUI();
        lyricView.setThemeMode(isNightMode
                ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        immersiveOverlay.setNightMode(isNightMode);

        // 封面风格初始化：统一走 switchScene（currentScene 已在 onCreateView 由
        // initCurrentSceneHolder 建立+enter；此处按保存风格切换，保存=新经典时无需动作）
        syncSceneState();
        switchScene();

        // 显示歌曲信息
        updateDownloadButtonVisibility();


        // 控制按钮：事件已在 ControlBar.Listener 中统一接入（controlBar.setListener）

        // 歌词区域：单击切换模式（V2 页内自管联动；Fragment 仅同步旧共享实例显示模式）

        // 注册广播接收器（监听切歌和播放状态）
            IntentFilter filter = new IntentFilter();
            filter.addAction(MusicPlayerService.ACTION_SONG_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_PLAY_STATE_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_PLAY_ORDER_CHANGED);
            filter.addAction(MusicPlayerService.ACTION_THEME_CHANGED);
            filter.addAction("com.jingxin.jingxinmusic.SPECTRUM_RESTART");
            filter.addAction("com.jingxin.jingxinmusic.FLOAT_LAYOUT_REFRESH");
            filter.addAction(ACTION_SERVICE_CLAIM);
        CompatUtil.safeRegisterReceiver(requireContext(), songChangedReceiver, filter);

        // 绑定播放服务
        Intent serviceIntent = new Intent(requireContext(), MusicPlayerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(serviceIntent);
        } else {
            requireContext().startService(serviceIntent);
        }
        requireContext().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 加载封面
        loadCover();

        // 检查收藏状态
        checkFavoriteStatus();

        // 加载歌词
        fetchLyrics();

        // 开始进度更新
        uiHandler.post(progressRunnable);


        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从设置页返回时重读背景皮肤和封面风格，切换即时生效
        int savedBgSkin = SettingsFragment.getBgSkin(requireContext());
        int savedCoverStyle = normalizeCoverStyle(requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE).getInt("cover_style", COVER_STYLE_CLASSIC));
        boolean skinOrStyleChanged = savedBgSkin != bgSkinMode || savedCoverStyle != coverStyle;
        if (skinOrStyleChanged) {
            bgSkinMode = savedBgSkin;
            coverStyle = savedCoverStyle;
            sceneHelper.bgSkinMode = bgSkinMode;
            sceneHelper.coverStyle = coverStyle;
            stateHub.setBgSkinMode(bgSkinMode); // Hub 同步（V2 自持页订阅皮肤通道）
            updateThemeUI();
            // 不在「双色光晕+非沉浸」组合时，恢复自定义歌词高亮色
            if (!(isAuroraActive()) && lyricView != null) {
                lyricView.restoreHighlightColor();
            }
            loadCover();
        }
        // 从列表页返回时同步主题（列表页可能切换了日夜模式）
        boolean savedNight = requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE).getBoolean("isNight", true);
        if (savedNight != isNightMode) {
            setNightModeSynced(savedNight);
            updateThemeUI();
            lyricView.setThemeMode(isNightMode
                    ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                    : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
            // 双色光晕模式：日夜切换后重新映射歌词高亮色（QQ固定用深色）
            if (isAuroraActive() && lyricView != null && lastAuroraCoverColor != 0) {
                int lyricColor = CoverColorExtractor.getLyricHighlightColor(lastAuroraCoverColor, false);
                lyricView.setHighlightColor(lyricColor);
            }
        }
        // 频谱开关：关闭时隐藏频谱View并停止，开启时恢复
        boolean spectrumEnabled = SettingsFragment.isSpectrumEnabled(requireContext());
        if (!spectrumEnabled) {
            stopSpectrum();
        } else {
            if (bound && playerBinder != null && playerBinder.isPlaying()) {
                startSpectrumWithPermission();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // 不在 onPause 停频谱：分屏/车机系统会频繁触发 onPause/onResume 循环
    }

    @Override
    public void onStop() {
        super.onStop();
        // 不在 onStop 停频谱：车机系统会疯狂循环 onResume/onStop（间隔 20-50ms），每次停掉重建导致频谱闪一下就消失
        // 频谱只由以下场景停止：ACTION_SERVICE_CLAIM（悬浮窗抢占）、切歌重建、用户关开关、onDestroyView
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 横竖屏切换时关闭频谱选择弹窗
        if (spectrumPickerPopup != null && spectrumPickerPopup.isShowing()) {
            spectrumPickerPopup.dismiss();
        }
        if (effectPickerPopup != null && effectPickerPopup.isShowing()) {
            effectPickerPopup.dismiss();
        }
        if (lyricSearchPopup != null && lyricSearchPopup.isShowing()) {
            lyricSearchPopup.dismiss();
        }
        if (historyPopup != null && historyPopup.isShowing()) {
            historyPopup.dismiss();
        }
        // 延迟一帧再检测，确保 DisplayMetrics 已更新
        uiHandler.post(() -> {
            boolean wasLandscape = isLandscapeMode;
            detectAndApplyLandscapeMode();
            if (wasLandscape != isLandscapeMode) {
                applyLayoutMode();
                updateThemeUI();
                updateLayoutForMode(lyricView.getDisplayMode());
            }
        });
    }

    private boolean isActivityGone() {
        Activity a = getActivity();
        return a == null || a.isFinishing() || a.isDestroyed();
    }

    private void runOnUi(Runnable r) {
        Activity a = getActivity();
        if (a != null) a.runOnUiThread(r);
    }

    private void playSong() {
        if (bound && playerBinder != null && song != null) {
            // 确保 position 与实际 playlist 匹配（position 可能来自 allSongs 索引，
            // 但 setPlaylist 设置的播放列表可能更小，如 folder/收藏模式）
            List<Song> playlist = playerBinder.getPlaylist();
            if (playlist != null && position >= playlist.size()) {
                // 在新播放列表中查找当前歌曲的实际位置
                int realPos = -1;
                for (int i = 0; i < playlist.size(); i++) {
                    Song s = playlist.get(i);
                    if ((s.filePath != null && s.filePath.equals(song.filePath)) ||
                        (s.contentUri != null && s.contentUri.equals(song.contentUri))) {
                        realPos = i;
                        break;
                    }
                }
                position = realPos >= 0 ? realPos : 0;
            }
            // 恢复播放进度：仅首次调用时设置，之后清零避免切歌误 seek
            if (resumePosition > 0 && playerBinder != null) {
                playerBinder.setPendingSeekPosition(resumePosition);
                resumePosition = 0;
            }
            playerBinder.playSong(song, position);
            startSpectrumWithPermission();
            saveLastPlayed();
            // 开始播放：启用控制栏自动隐藏
            updateControlsAutoHideState(true);
        }
    }

    /**
     * 轮播模式点击侧边封面切歌
     */
    /**
     * 从轮播模式切歌，参数为偏移量（delta）
     * delta=-1=上一曲, +1=下一曲, -2/+2跳两首
     */
    private void playSongAt(int delta) {
        if (bound && playerBinder != null) {
            int curIdx = playerBinder.getCurrentIndex();
            int newIdx = curIdx + delta;
            java.util.List<Song> playlist = playerBinder.getPlaylist();
            if (playlist != null && newIdx >= 0 && newIdx < playlist.size()) {
                Song newSong = playlist.get(newIdx);
                // 同步 allSongs 中的 position
                if (allSongs != null) {
                    int allPos = allSongs.indexOf(newSong);
                    if (allPos >= 0) position = allPos;
                }
                song = newSong;
                playerBinder.playSongAtPosition(newIdx);
                    startSpectrumWithPermission();
                loadCover();
                fetchLyrics();
                checkFavoriteStatus();
                updateDownloadButtonVisibility();
                saveLastPlayed();
                // 切歌：启用自动隐藏并重置计时
                updateControlsAutoHideState(true);
            }
        }
    }

    /**
     * 初始 currentScene 持有者：新经典 V2 adapter，并立即 enter 保证 install
     * （首个 onLayoutChange 前页面必须就绪，否则页面字段全 null）。
     */
    private void initCurrentSceneHolder() {
        if (classicV2PageAdapter == null) {
            com.jingxin.jingxinmusic.stage.ClassicPageV2 v2 =
                    new com.jingxin.jingxinmusic.stage.ClassicPageV2(sceneHelper);
            v2.setActions(buildV2Actions());
            classicV2PageAdapter = new com.jingxin.jingxinmusic.stage.StagePageAdapter(v2);
        }
        currentScene = classicV2PageAdapter;
        syncSceneState();
        currentScene.enter();
        currentScene.onStyleEnter();
    }

    /**
     * 频谱可启动判断：Fragment 生命周期至少 STARTED
     * 用 STARTED 而非 RESUMED：乐酷分屏下 Activity 长时间 paused 但窗口可见可交互，
     * onServiceConnected 异步回调落在 paused 窗口时 isResumed 守卫会吞掉频谱启动
     * （fragment 1.5.4 无 isStarted()，用 Lifecycle 状态等价判断）
     */
    private boolean isSpectrumStartable() {
        return getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    /**
     * 频谱数据分发：Visualizer/AudioRecord 回调统一入口。
     * 旧共享实例始终喂（退出 V2 后仍活跃）；第5风格（V2）激活时分流其自持实例。
     */
    private void feedSpectrumData(float[] magnitudes, float maxMag) {
        if (coverStyle == COVER_STYLE_CLASSIC_V2 && classicV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) classicV2PageAdapter.getPage())
                    .feedSpectrumData(magnitudes, maxMag);
        }
        if (coverStyle == COVER_STYLE_IMMERSIVE_V2 && immersiveV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ImmersivePageV2) immersiveV2PageAdapter.getPage())
                    .feedSpectrumData(magnitudes, maxMag);
        }
        if (coverStyle == COVER_STYLE_RECORD_V2 && recordV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) recordV2PageAdapter.getPage())
                    .feedSpectrumData(magnitudes, maxMag);
        }
        if (coverStyle == COVER_STYLE_CAROUSEL_V2 && carouselV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) carouselV2PageAdapter.getPage())
                    .feedSpectrumData(magnitudes, maxMag);
        }
    }

    private void startSpectrumWithPermission() {
        if (!SettingsFragment.isSpectrumEnabled(requireContext())) return;
        // 频谱正在用 Visualizer 正常运行，不重复启动（防止 stop→start 死循环）
        if (spectrumRunning && useVisualizer) return;
        // 乐酷悬浮模式或 Fragment started 时才启动频谱
        // 注意用 isStarted 而非 isResumed：乐酷分屏下 Activity 长时间 paused 但窗口可见可交互，
        // onServiceConnected 异步回调落在 paused 窗口时 isResumed 守卫会吞掉频谱启动
        if (!com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().isFloating() && !isSpectrumStartable()) {
            // 极早期竞态保险网：延迟重试补启动，防止频谱被吞
            scheduleSpectrumResumeRetry();
            return;
        }
        // Visualizer 只需要 MODIFY_AUDIO_SETTINGS（安装即授予），不需要 RECORD_AUDIO
        // 先直接尝试 Visualizer，失败后再请求 RECORD_AUDIO 降级到 AudioRecord
        startSpectrum();
    }

    /**
     * 频谱启动竞态保险网：onServiceConnected 等异步回调落在 Fragment 未 started 时，
     * 延迟重试补启动（最多 5 次 × 200ms），onDestroyView 清理
     */
    private void scheduleSpectrumResumeRetry() {
        if (spectrumResumeRetryCount >= SPECTRUM_RESUME_MAX_RETRIES) {
            return;
        }
        spectrumResumeRetryCount++;
        uiHandler.removeCallbacks(spectrumResumeRetryRunnable);
        uiHandler.postDelayed(spectrumResumeRetryRunnable, SPECTRUM_RESUME_RETRY_INTERVAL_MS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSpectrum();
            }
        }
    }

    private void togglePlayPause() {
        if (bound && playerBinder != null) {
            playerBinder.togglePlayPause();
            updatePlayPauseButton(playerBinder.isPlaying());
            updateControlsAutoHideState(playerBinder.isPlaying());
        }
    }

    private void playPrevious() {
        if (bound && playerBinder != null) {
            playerBinder.playPrevious();
            resetControlsHideTimer();
        }
    }

    private void playNext() {
        if (bound && playerBinder != null) {
            playerBinder.playNext();
            resetControlsHideTimer();
        }
    }

    /**
     * 切换播放顺序：顺序 → 随机 → 单曲循环
     */
    private void togglePlayOrder() {
        if (bound && playerBinder != null) {
            int current = playerBinder.getPlayOrder();
            int next = (current + 1) % 3;
            playerBinder.setPlayOrder(next);
            updatePlayOrderIcon(next);
            String[] names = {"顺序播放", "随机播放", "单曲循环"};
            android.widget.Toast.makeText(requireContext(), names[next], android.widget.Toast.LENGTH_SHORT).show();
            resetControlsHideTimer();
        }
    }

    private void updatePlayOrderIcon(int order) {
    }

    /**
     * 切换收藏状态
     */
    private void toggleFavorite() {
        if (song == null) return;
        resetControlsHideTimer();
        File favDir = com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext());

        if (isFavorite) {
            FavoriteManager.removeFavorite(favDir, song);
            isFavorite = false;
            notifyActivePageControlExtras();
            android.widget.Toast.makeText(requireContext(), "取消收藏", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            FavoriteManager.addFavorite(favDir, song);
            isFavorite = true;
            notifyActivePageControlExtras();
            android.widget.Toast.makeText(requireContext(), "已收藏", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检查当前歌曲是否已收藏
     */
    private void checkFavoriteStatus() {
        if (song == null) return;
        File favDir = com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext());
        isFavorite = FavoriteManager.isFavorite(favDir, song.title, song.artist);
        notifyActivePageControlExtras();
    }

    /**
     * 保存B站歌曲到本地（离线保存）
     */
    private void saveBiliOffline() {
        if (song == null) return;

        // 只对B站音源显示下载按钮，点击时校验
        if (song.sourceType != Song.SOURCE_BILI &&
                !(song.filePath != null && song.filePath.startsWith("bili://"))) {
            android.widget.Toast.makeText(requireContext(), "仅支持B站音源离线保存",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查是否已保存
        if (com.jingxin.jingxinmusic.util.BiliOfflineSaver.isSaved(requireContext(), song)) {
            android.widget.Toast.makeText(requireContext(), "已保存过，无需重复保存",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // 禁用按钮防止重复点击
        biliDownloadEnabled = false;
        notifyActivePageControlExtras();
        android.widget.Toast.makeText(requireContext(), "开始离线保存...",
                android.widget.Toast.LENGTH_SHORT).show();

        com.jingxin.jingxinmusic.util.BiliOfflineSaver.saveAsync(requireContext(), song,
                new com.jingxin.jingxinmusic.util.BiliOfflineSaver.SaveCallback() {
                    @Override
                    public void onSuccess(java.io.File m4aFile) {
                        runOnUi(() -> {
                            biliDownloadEnabled = true; notifyActivePageControlExtras();
                            android.widget.Toast.makeText(requireContext(),
                                    "已保存到 Download/music/",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onSkipped(String reason) {
                        runOnUi(() -> {
                            biliDownloadEnabled = true; notifyActivePageControlExtras();
                            android.widget.Toast.makeText(requireContext(),
                                    reason, android.widget.Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onFailed(String error) {
                        runOnUi(() -> {
                            biliDownloadEnabled = true; notifyActivePageControlExtras();
                            android.widget.Toast.makeText(requireContext(),
                                    "保存失败: " + error,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onProgress(int percent) {
                        // 暂不显示进度
                    }
                });
    }

    /**
     * 更新下载按钮可见性：仅B站音源显示
     */
    private void updateDownloadButtonVisibility() {
        notifyActivePageControlExtras(); // V2 页经 actions.isBiliSong() 自行判显隐
    }

    /**
     * 根据歌词模式更新布局——使用 CoverScene 策略
     */
    private void updateLayoutForMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode mode) {
        syncSceneState();
        boolean isFull = mode == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL;
        currentScene.onLyricModeChanged(isFull);
    }

    /**
     * 获取窗口实际可用高度
     * 优先用根 FrameLayout 的实际测量高度，避免雷电/MuMu 模拟器 getDisplayMetrics() 返回全屏高度
     * 首次布局时 View 可能为0，回退到 getDisplayMetrics()
     */
    private int getAvailableScreenHeight() {
        return sceneHelper != null ? sceneHelper.getAvailableScreenHeight() : getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * 初始化 CoverScene 策略框架
     */
    private void initCoverScene() {
        android.widget.FrameLayout rootLayout = mRootView.findViewById(R.id.root_layout);
        sceneHelper = new CoverSceneHelper(
                rootLayout, blurBackground, spectrumView, null,
                getResources().getDisplayMetrics().density);
        sceneHelper.bgSkinMode = bgSkinMode;
        sceneHelper.coverStyle = coverStyle;

        // 背景皮肤控制器装配（组件化第3步）：视图与 Helper 均就绪，mRootView 即 root_layout
        bgSkinController = new com.jingxin.jingxinmusic.bg.BackgroundSkinController(
                new com.jingxin.jingxinmusic.bg.BackgroundSkinContext(
                        (android.widget.FrameLayout) mRootView,
                        auroraBackgroundView, blurBackground,
                        immersiveOverlay, immersiveDarkOverlay, whiteOverlay, overlayView,
                        null, spectrumView, sceneHelper));

        // 初始场景：新经典 V2（首建+立即 enter，保证首个 onLayoutChange 前页面已 install——
        // 上次 5a 教训：只赋值不 enter，switchScene 遇 target==currentScene 不进入，
        // V2 页面未 install → 频谱未建立/歌词字段 null → onLyricModeChanged NPE 崩溃）
        initCurrentSceneHolder();

        // 监听系统窗口 insets，获取状态栏实际高度（车机全屏时为0）
        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // API 30+：使用新 API，Android 16 edge-to-edge 模式下更可靠
                topInset = insets.getInsets(android.view.WindowInsets.Type.systemBars()).top;
            } else {
                // API 21-29：使用兼容 API
                topInset = insets.getSystemWindowInsetTop();
            }
            if (sceneHelper.systemTopInset != topInset) {
                sceneHelper.systemTopInset = topInset;
                // inset 变化时重新布局，让 applyButtonMargins 生效
                v.requestLayout();
            }
            return insets;
        });
    }

    /**
     * 根据当前 isLandscapeMode 和 coverStyle 切换到正确的 Scene
     * @return 是否发生了 Scene 切换
     */
    private boolean switchScene() {
        CoverScene target;
        if (coverStyle == COVER_STYLE_CLASSIC_V2) {
            // 第5风格：新经典 V2（完全自持实例，全页叠加在旧视图之上）
            if (classicV2PageAdapter == null) {
                com.jingxin.jingxinmusic.stage.ClassicPageV2 v2 =
                        new com.jingxin.jingxinmusic.stage.ClassicPageV2(sceneHelper);
                v2.setActions(buildV2Actions());
                classicV2PageAdapter = new com.jingxin.jingxinmusic.stage.StagePageAdapter(v2);
            }
            target = classicV2PageAdapter;
        } else if (coverStyle == COVER_STYLE_IMMERSIVE_V2) {
            // 第6风格：沉浸 V2（完全自持实例）
            if (immersiveV2PageAdapter == null) {
                com.jingxin.jingxinmusic.stage.ImmersivePageV2 iv2 =
                        new com.jingxin.jingxinmusic.stage.ImmersivePageV2(sceneHelper);
                iv2.setActions(buildV2Actions());
                immersiveV2PageAdapter = new com.jingxin.jingxinmusic.stage.StagePageAdapter(iv2);
            }
            target = immersiveV2PageAdapter;
        } else if (coverStyle == COVER_STYLE_RECORD_V2) {
            // 第7风格：唱片机 V2（继承新经典V2，黑胶+唱臂专属层）
            if (recordV2PageAdapter == null) {
                com.jingxin.jingxinmusic.stage.RecordPageV2 r2 =
                        new com.jingxin.jingxinmusic.stage.RecordPageV2(sceneHelper);
                r2.setActions(buildV2Actions());
                recordV2PageAdapter = new com.jingxin.jingxinmusic.stage.StagePageAdapter(r2);
            }
            target = recordV2PageAdapter;
        } else if (coverStyle == COVER_STYLE_CAROUSEL_V2) {
            // 第8风格：轮播 V2（继承新经典V2，卡片层+列表桥）
            if (carouselV2PageAdapter == null) {
                com.jingxin.jingxinmusic.stage.CarouselPageV2 c2 =
                        new com.jingxin.jingxinmusic.stage.CarouselPageV2(sceneHelper);
                c2.setActions(buildCarouselV2Actions());
                carouselV2PageAdapter = new com.jingxin.jingxinmusic.stage.StagePageAdapter(c2);
            }
            target = carouselV2PageAdapter;
        } else {
            // 新经典 V2（默认风格；旧四风格已下线，异常 prefs 值也回落到此）
            if (classicV2PageAdapter == null) {
                com.jingxin.jingxinmusic.stage.ClassicPageV2 v2 =
                        new com.jingxin.jingxinmusic.stage.ClassicPageV2(sceneHelper);
                v2.setActions(buildV2Actions());
                classicV2PageAdapter = new com.jingxin.jingxinmusic.stage.StagePageAdapter(v2);
            }
            target = classicV2PageAdapter;
        }
        if (target != currentScene) {
            currentScene.onStyleExit();
            currentScene.exit();
            currentScene = target;
            // 进入沉浸模式（含V2）：不支持全屏歌词，若当前为全屏则降级为多行
            if (coverStyle == COVER_STYLE_IMMERSIVE_V2 && lyricView != null
                    && lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL) {
                lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE);
            }
            currentScene.enter();
            currentScene.onStyleEnter();
            // 非光晕或沉浸模式：隐藏光晕 View，避免遮挡其他模式
            if (!(isAuroraActive()) && auroraBackgroundView != null) {
                auroraBackgroundView.setVisibility(View.GONE);
            }
            return true;
        }
        return false;
    }

    /**
     * 同步 sceneHelper 状态（在调用 scene 方法前调用）
     */
    private void syncSceneState() {
        sceneHelper.isNightMode = isNightMode;
        sceneHelper.isPlaying = bound && playerBinder != null && playerBinder.isPlaying();
        sceneHelper.isLandscapeMode = isLandscapeMode;
        sceneHelper.playerBinder = playerBinder;
        sceneHelper.executor = executor;
        sceneHelper.bgSkinMode = bgSkinMode;
        sceneHelper.coverStyle = coverStyle;
        // 双向同步：helper可能由scene创建了实例，不能被activity的null覆盖
        if (coverBorderGradient != null) {
            sceneHelper.coverBorderGradient = coverBorderGradient;
        } else if (sceneHelper.coverBorderGradient != null) {
            coverBorderGradient = sceneHelper.coverBorderGradient;
        }
        // 背景皮肤上下文同步（组件化第3步）
        if (bgSkinController != null) {
            bgSkinController.syncCoverBorderGradient(coverBorderGradient);
        }
    }

     /**
       * 检测并应用横屏布局模式
       * 触发条件：实际宽度 > 高度 * 1.1
      * 横屏布局：左65%信息区 + 右35%封面区
      */
    private void detectAndApplyLandscapeMode() {
        int width = getLayoutWidth();
        int height = getAvailableScreenHeight();
        boolean newLandscape = (width > height * 1.1f);
        isLandscapeMode = newLandscape;
    }

    /**
     * 应用当前布局模式（横屏/竖屏）——使用 CoverScene 策略
     */
    private void applyLayoutMode() {
        syncSceneState();
        switchScene();
        int width = getLayoutWidth();
        int height = getAvailableScreenHeight();
        currentScene.layout(width, height);
        immersiveOverlay.setLandscapeMode(isLandscapeMode);
        // 切换后需要重新加载封面的模式
        if (currentScene.needsReloadCover()) {
            loadCover();
        }
    }

    /**
     * 获取根 FrameLayout 的实际宽度，首次布局时可能为0则回退到 screenWidth
     */
    private int getLayoutWidth() {
        return sceneHelper != null ? sceneHelper.getLayoutWidth() : getResources().getDisplayMetrics().widthPixels;
    }

    private void updatePlayPauseButton(boolean isPlaying) {
        if (isPlaying) {
            currentScene.onPlayingStateChanged(true);
            // 恢复播放时重启频谱数据采集（暂停期间 Visualizer 可能已失效）
            if (SettingsFragment.isSpectrumEnabled(requireContext())) {
                startSpectrumWithPermission();
            }
        } else {
            currentScene.onPlayingStateChanged(false);
        }
    }

    /**
     * 在线获取歌词
     */
    private void fetchLyrics() {
        if (song == null || lyricView == null) return;
        lyricView.clearLyric();

        String title = Song.cleanSongTitle(song.title, song.artist);
        String artist = song.artist;
        if ("<unknown>".equals(artist)) artist = "";

        File lyricsDir = new File(requireContext().getExternalFilesDir(null), "lyrics");

        LyricFetcher.loadLyric(title, artist, song.filePath, lyricsDir, new LyricFetcher.LyricCallback() {
            @Override
            public void onLyricFetched(KrcParser.LyricData lyricData) {
                if (lyricData != null && lyricData.lines != null && !lyricData.lines.isEmpty()) {
                    // Hub 歌词通道广播（独立页订阅；同一 LyricData 对象引用转发，零重复解析）
                    stateHub.setLyricData(lyricData); // 广播 V2 页（双写旧实例已删）
                } else {
                }
            }

            @Override
            public void onError(String errorMessage) {
            }
        }, requireContext(), song.title, SettingsFragment.isLocalLyricPriority(requireContext()));
    }

    /**
     * 加载封面：先本地文件，没有则在线获取并保存
     */
    private void notifyMetadataUpdate() {
        Intent intent = new Intent(requireContext(), com.jingxin.jingxinmusic.service.MusicPlayerService.class);
        intent.setAction(com.jingxin.jingxinmusic.service.MusicPlayerService.ACTION_UPDATE_METADATA);
        requireContext().startService(intent);
    }

    private void loadCover() {
        // 切歌时保持旧封面，避免闪烁——只有首次进入（无旧封面）才设默认封面
        if (!hasCoverLoaded) {
            applyDefaultCoverBlur();
        }

        boolean preferLocal = SettingsFragment.isLocalCoverPriority(requireContext());
        com.jingxin.jingxinmusic.util.CoverLoader.load(requireContext(), song, 600, 600,
                true, preferLocal, executor, new com.jingxin.jingxinmusic.util.CoverLoader.CoverCallback() {
            @Override
            public void onCoverLoaded(Bitmap bitmap) {
                if (isActivityGone()) return;
                setCoverBitmap(bitmap);
            }

            @Override
            public void onCoverFailed() {
                if (isActivityGone()) return;
                // 所有封面来源都失败，用默认封面图标生成模糊背景
                applyDefaultCoverBlur();
            }
        });
    }

    /**
     * 将默认封面矢量图(ic_music_icon)转为Bitmap并设置模糊背景
     * 确保无在线封面时播放页面也有模糊背景效果
     */
    private void applyDefaultCoverBlur() {
        if (isActivityGone()) return;
        executor.execute(() -> {
            try {
                Drawable drawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_music_icon);
                if (drawable == null) return;
                // 矢量图需要指定尺寸转为Bitmap
                int size = 256;
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
                if (!isActivityGone()) {
                    uiHandler.post(() -> setCoverBitmap(bitmap));
                }
            } catch (Exception e) {
                Log.w(TAG, "默认封面模糊背景生成失败", e);
            }
        });
    }

    private void setCoverBitmap(Bitmap bitmap) {
        if (bitmap == null || isActivityGone()) return;
        hasCoverLoaded = true;
        syncSceneState();
        currentScene.setCover(bitmap);
        // Hub 封面通道广播（独立页订阅；旧页直调路径不变，双轨互不影响）
        stateHub.setCover(bitmap);
        // 通知 Service 更新 MediaSession metadata（含封面）
        notifyMetadataUpdate();
        // 双色提取（无条件执行：双色广播给独立页随时可切光晕；旧路径仅光晕激活时消费）
        {
            final int token = ++coverColorToken; // 每次取色递增token，回调时校验防竞态
            executor.execute(() -> {
                try {
                    CoverColorExtractor.ColorPair colors = CoverColorExtractor.extract(bitmap);
                    // token不匹配说明期间已切歌，丢弃本次结果
                    if (token != coverColorToken) return;
                    // 按封面第二色色相映射出歌词高亮色（QQ固定用深色，不分日夜）
                    int lyricColor = CoverColorExtractor.getLyricHighlightColor(colors.bottom, false);
                    lastAuroraCoverColor = colors.bottom;
                    if (!isActivityGone()) {
                        uiHandler.post(() -> {
                            // 主线程再次校验token，确保最终应用的是最新歌曲的颜色
                            if (token != coverColorToken) return;
                            // Hub 双色通道广播（独立页光晕/歌词高亮消费）
                            stateHub.setCoverColors(colors.top, colors.bottom);
                            if (auroraBackgroundView != null) {
                                auroraBackgroundView.setColors(colors.top, colors.bottom);
                            }
                            // 同步静态主色（首页 DynamicBgView 渐变依赖）：
                            // 光晕变色不写 ThemeColors 会导致返回首页后仍显示旧封面渐变色
                            ThemeColors.setRawDominantColor(colors.bottom);
                            ThemeColors.setDominantColor(com.jingxin.jingxinmusic.util.MagicColor.mapColor(
                                    colors.bottom, !isNightMode));
                            // 频谱颜色跟随封面双色（双色光晕模式下，竖条/圆点/波浪线/圆环生效）
                            // 二次校验模式：异步取色期间用户可能已退出光晕，clearTint后不可再注入
                            // 再次检查当前仍为光晕+非沉浸模式，防止异步期间用户切换了模式
                            if (lyricView != null && isAuroraActive()) {
                                lyricView.setHighlightColor(lyricColor);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.w(TAG, "封面取色失败", e);
                }
            });
        }
    }

    /**
     * 从封面提取主色调（简单采样法）
     * 将图片缩小后采样中心区域像素，计算加权平均色
     * @param bitmap 封面图片
     * @return 主色调（ARGB）
     */
    private int extractDominantColor(Bitmap bitmap) {
        // 缩小到 50x50 采样
        int sampleSize = Math.max(1, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 50);
        int w = bitmap.getWidth() / sampleSize;
        int h = bitmap.getHeight() / sampleSize;
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        Bitmap sampled = Bitmap.createScaledBitmap(bitmap, w, h, true);
        
        int[] pixels = new int[w * h];
        sampled.getPixels(pixels, 0, w, 0, 0, w, h);
        sampled.recycle();
        
        // 只采样中心 60% 区域，避免边缘黑边影响
        int startX = w / 5;
        int endX = w * 4 / 5;
        int startY = h / 5;
        int endY = h * 4 / 5;
        
        long rSum = 0, gSum = 0, bSum = 0, count = 0;
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int pixel = pixels[y * w + x];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                // 忽略过暗和过亮的像素
                if (r + g + b > 60 && r + g + b < 720) {
                    rSum += r;
                    gSum += g;
                    bSum += b;
                    count++;
                }
            }
        }
        
        if (count == 0) return ThemeColors.dominantColorFallback();
        
        int r = (int) (rSum / count);
        int g = (int) (gSum / count);
        int b = (int) (bSum / count);
        return Color.rgb(r, g, b);
    }

    /**
     * 显示播放历史列表（自定义浮窗，随页面分辨率缩放）
     */
    private void showHistoryDialog() {
        executor.execute(() -> {
            File historyDir = new File(requireContext().getExternalFilesDir(null), "history");
            List<HistoryManager.HistoryItem> history = HistoryManager.loadHistory(historyDir);

            if (history.isEmpty()) {
                uiHandler.post(() -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("播放历史")
                            .setMessage("暂无播放记录")
                            .setPositiveButton("确定", null)
                            .show();
                });
                return;
            }

            uiHandler.post(() -> {
                float density = getResources().getDisplayMetrics().density;
                Context ctx = requireContext();

                // 缩放系数：小屏手机保持原样(sf=1)，高分辨率车机按屏高等比放大（横竖屏自适应）
                // 基准：16sp，竖屏锚点屏高×1.88%，横屏锚点屏高×3%（与频谱/歌词弹窗一致）
                int viewH = getAvailableScreenHeight();
                if (viewH <= 0) viewH = getResources().getDisplayMetrics().heightPixels;
                float basePx = android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
                boolean pickerLandscape = getLayoutWidth() > viewH * 1.2f;
                float sf = Math.max(1f, viewH * (pickerLandscape ? 0.03f : 0.0188f) / basePx);

                // 外层容器
                android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
                container.setOrientation(android.widget.LinearLayout.VERTICAL);
                container.setPadding(
                        (int)(12 * density * sf), (int)(12 * density * sf),
                        (int)(12 * density * sf), (int)(12 * density * sf));
                // 半透明深色背景
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                float r = 16 * density * sf;
                if (isLandscapeMode) {
                    bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
                } else {
                    bg.setCornerRadius(r);
                }
                bg.setColor(Color.argb(102, 30, 30, 30));  // 透明度40%
                container.setBackground(bg);

                // 标题
                android.widget.TextView title = new android.widget.TextView(ctx);
                title.setText("播放历史");
                title.setTextColor(ThemeColors.sparkColor(isNightMode));
                title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16 * density * sf);
                title.setGravity(android.view.Gravity.CENTER);
                title.setPadding(0, 0, 0, (int)(10 * density * sf));
                container.addView(title);

                // 列表容器（ScrollView + LinearLayout）
                android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
                android.widget.LinearLayout listContainer = new android.widget.LinearLayout(ctx);
                listContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
                scroll.addView(listContainer);
                container.addView(scroll);

                for (int idx = 0; idx < history.size(); idx++) {
                    final HistoryManager.HistoryItem item = history.get(idx);
                    android.widget.TextView itemView = new android.widget.TextView(ctx);
                    itemView.setText(item.getDisplayName());
                    itemView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13 * density * sf);
                    itemView.setTextColor(0xFFDDDDDD);
                    itemView.setSingleLine(true);
                    itemView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    itemView.setPadding(
                            (int)(12 * density * sf), (int)(10 * density * sf),
                            (int)(12 * density * sf), (int)(10 * density * sf));

                    android.graphics.drawable.GradientDrawable itemBg = new android.graphics.drawable.GradientDrawable();
                    itemBg.setColor(Color.argb(30, 68, 68, 68));
                    itemBg.setCornerRadius(8 * density * sf);
                    itemView.setBackground(itemBg);
                    android.widget.LinearLayout.LayoutParams itemLp = new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    itemLp.setMargins(0, (int)(3 * density * sf), 0, (int)(3 * density * sf));
                    itemView.setLayoutParams(itemLp);

                    itemView.setOnClickListener(v -> {
                        if (historyPopup != null) historyPopup.dismiss();
                        Song s = item.song;
                        song = s;
                        loadCover();
                        fetchLyrics();

                        if (bound && playerBinder != null) {
                            List<Song> historySongs = new ArrayList<>();
                            for (HistoryManager.HistoryItem h : history) {
                                historySongs.add(h.song);
                            }
                            playerBinder.setPlaylist(historySongs);
                            playerBinder.playSong(s, history.indexOf(item));
                            startSpectrumWithPermission();
                        }
                    });

                    listContainer.addView(itemView);
                }

                // 清空历史按钮
                android.widget.TextView clearBtn = new android.widget.TextView(ctx);
                clearBtn.setText("清空历史");
                clearBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14 * density * sf);
                clearBtn.setTextColor(ThemeColors.sparkColor(isNightMode));
                clearBtn.setGravity(android.view.Gravity.CENTER);
                clearBtn.setPadding(0, (int)(8 * density * sf), 0, 0);
                clearBtn.setOnClickListener(v -> {
                    if (historyPopup != null) historyPopup.dismiss();
                    HistoryManager.clearHistory(historyDir);
                });
                container.addView(clearBtn);

                // 弹窗尺寸：竖屏宽度80%，横屏宽度65%（与歌词搜索一致）
                int screenWidth = mRootView.getWidth();
                float widthRatio = isLandscapeMode ? 0.65f : 0.8f;
                int popupWidth = (int)(screenWidth * widthRatio);
                int popupHeight = (int)(280 * density * sf);  // 高度固定比例兜底
                if (popupHeight > viewH - (int)(100 * density * sf)) {
                    popupHeight = viewH - (int)(100 * density * sf);
                }

                historyPopup = new android.widget.PopupWindow(container, popupWidth, popupHeight, true);
                historyPopup.setOutsideTouchable(true);
                if (Build.VERSION.SDK_INT <= 22) {
                    // 5.1：background为null时不创建PopupViewContainer，点外部无法dismiss，设透明背景触发容器创建
                    historyPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                }
                historyPopup.setElevation(8 * density * sf);
                historyPopup.showAtLocation(mRootView,
                        isLandscapeMode
                                ? (android.view.Gravity.TOP | android.view.Gravity.START)
                                : (android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL),
                        0, (int)(60 * density * sf));
            });
        });
    }

    /**
     * 循环切换封面风格：经典 → 沉浸 → 唱片机 → 轮播 → 经典
     */
    /** 第8风格（轮播V2）动作桥：新经典桥 + 播放列表三方法（引用型数据走桥不走Hub） */
    private com.jingxin.jingxinmusic.stage.CarouselPageV2.Actions buildCarouselV2Actions() {
        com.jingxin.jingxinmusic.stage.ClassicPageV2.Actions base = buildV2Actions();
        return new com.jingxin.jingxinmusic.stage.CarouselPageV2.Actions() {
            @Override public void togglePlayPause() { base.togglePlayPause(); }
            @Override public void playPrevious() { base.playPrevious(); }
            @Override public void playNext() { base.playNext(); }
            @Override public void togglePlayOrder() { base.togglePlayOrder(); }
            @Override public void showHistoryDialog() { base.showHistoryDialog(); }
            @Override public void toggleFavorite() { base.toggleFavorite(); }
            @Override public void saveBiliOffline() { base.saveBiliOffline(); }
            @Override public void showSpectrumPicker(View anchor) { base.showSpectrumPicker(anchor); }
            @Override public void showEffectPickerPopup(View anchor) { base.showEffectPickerPopup(anchor); }
            @Override public void showLyricSearchPopup(View anchor) { base.showLyricSearchPopup(anchor); }
            @Override public boolean isFavorite() { return base.isFavorite(); }
            @Override public boolean isBiliSong() { return base.isBiliSong(); }
            @Override public boolean isBiliDownloadEnabled() { return base.isBiliDownloadEnabled(); }
            @Override public void exitPlayerPage() { base.exitPlayerPage(); }
            @Override public void switchOutfitStyle() { base.switchOutfitStyle(); }
            @Override public void onSeekStart() { base.onSeekStart(); }
            @Override public void onSeekTo(int positionMs) { base.onSeekTo(positionMs); }
            @Override public void onSeekStop() { base.onSeekStop(); }
            @Override public boolean isControlsHidden() { return base.isControlsHidden(); }
            @Override public void onV2ControlsTouched() { base.onV2ControlsTouched(); }
            @Override public java.util.List<Song> getPlaylist() {
                return (bound && playerBinder != null) ? playerBinder.getPlaylist() : null;
            }
            @Override public int getCurrentIndex() {
                return (bound && playerBinder != null) ? playerBinder.getCurrentIndex() : 0;
            }
            @Override public void playSongAt(int delta) { PlayerFragment.this.playSongAt(delta); }
        };
    }

    /** 第5风格（新经典V2）的动作桥：数据与播放逻辑共享，视图独立 */
    private com.jingxin.jingxinmusic.stage.ClassicPageV2.Actions buildV2Actions() {
        return new com.jingxin.jingxinmusic.stage.ClassicPageV2.Actions() {
            @Override public void togglePlayPause() { PlayerFragment.this.togglePlayPause(); }
            @Override public void playPrevious() { PlayerFragment.this.playPrevious(); }
            @Override public void playNext() { PlayerFragment.this.playNext(); }
            @Override public void togglePlayOrder() { PlayerFragment.this.togglePlayOrder(); }
            @Override public void showHistoryDialog() { PlayerFragment.this.showHistoryDialog(); }
            @Override public void toggleFavorite() { PlayerFragment.this.toggleFavorite(); }
            @Override public void saveBiliOffline() { PlayerFragment.this.saveBiliOffline(); }
            @Override public void showSpectrumPicker(View anchor) { PlayerFragment.this.showSpectrumPicker(); }
            @Override public void showEffectPickerPopup(View anchor) { PlayerFragment.this.showEffectPickerPopup(); }
            @Override public void showLyricSearchPopup(View anchor) { PlayerFragment.this.showLyricSearchPopup(); }
            @Override public boolean isFavorite() { return PlayerFragment.this.isFavorite; }
            @Override public boolean isBiliSong() { return song != null && song.sourceType == Song.SOURCE_BILI; }
            @Override public boolean isBiliDownloadEnabled() { return biliDownloadEnabled; }
            @Override public void exitPlayerPage() {
                stopSpectrum();
                requireActivity().onBackPressed();
            }
            @Override public void switchOutfitStyle() { PlayerFragment.this.toggleImmersiveMode(); }
            @Override public void onSeekStart() {
                stopControlsHideTimer(); // 拖动中暂停自动隐藏
                userSeeking = true;
            }
            @Override public void onSeekTo(int positionMs) {
                if (bound && playerBinder != null) playerBinder.seekTo(positionMs);
                stateHub.setProgress(positionMs, stateHub.getDurationMs());
            }
            @Override public void onSeekStop() {
                userSeeking = false;
                if (controlsHideEnabled) {
                    showControls(false);
                    resetControlsHideTimer();
                }
            }
            @Override public boolean isControlsHidden() { return PlayerFragment.this.controlsHidden; }
            @Override public void onV2ControlsTouched() {
                // V2 内交互：等价旧页触摸活动（重置自动隐藏计时窗口）
                if (controlsHideEnabled) {
                    showControls(false);
                    resetControlsHideTimer();
                }
            }
        };
    }

    /** 收藏态/B站下载键态变化 → 通知当前活跃 V2 页刷新控制条附加态 */
    private void notifyActivePageControlExtras() {
        if (coverStyle == COVER_STYLE_CLASSIC_V2 && classicV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) classicV2PageAdapter.getPage())
                    .notifyControlExtrasChanged();
        } else if (coverStyle == COVER_STYLE_RECORD_V2 && recordV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) recordV2PageAdapter.getPage())
                    .notifyControlExtrasChanged();
        } else if (coverStyle == COVER_STYLE_CAROUSEL_V2 && carouselV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ClassicPageV2) carouselV2PageAdapter.getPage())
                    .notifyControlExtrasChanged();
        } else if (coverStyle == COVER_STYLE_IMMERSIVE_V2 && immersiveV2PageAdapter != null) {
            ((com.jingxin.jingxinmusic.stage.ImmersivePageV2) immersiveV2PageAdapter.getPage())
                    .notifyControlExtrasChanged();
        }
    }

    private void toggleImmersiveMode() {
        // 装扮循环只在 V2 新风格间切换（旧四风格已隐藏，收口入口；若内存值为旧风格异常态则先归位）
        coverStyle = HIDE_LEGACY_STYLES
                ? COVER_STYLE_CLASSIC_V2 + (Math.max(coverStyle, COVER_STYLE_CLASSIC_V2) + 1 - COVER_STYLE_CLASSIC_V2) % 4
                : (coverStyle + 1) % 8;
        requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE).edit().putInt("cover_style", coverStyle).apply();

        // 目标风格不适配环类频谱时（轮播无圆形封面可挂）：真值直接归位第一个频谱（竖条）。
        // 写 prefs + 全实例同步：不适配的风格内显示竖条；切回唱机看到的也是竖条（明确状态），
        // 用户再选环即可。不用降级跳档（跳到哪档不可控→"样式自己变"），不静默压制
        // （环/底实例判定链会回置→"频谱消失"）。
        syncSceneState();
        if (switchScene()) {
            int nowStyle = spectrumView.getCurrentStyle();
            if (com.jingxin.jingxinmusic.view.SpectrumView.isOverlayStyle(nowStyle)
                    && !currentScene.shouldShowSpectrumButton(nowStyle)) {
                spectrumView.setStyle(com.jingxin.jingxinmusic.view.SpectrumView.STYLE_BAR);
                if (coverStyle == COVER_STYLE_CLASSIC_V2 && classicV2PageAdapter != null) {
                    ((com.jingxin.jingxinmusic.stage.ClassicPageV2) classicV2PageAdapter.getPage())
                            .applySpectrumStyle(com.jingxin.jingxinmusic.view.SpectrumView.STYLE_BAR);
                } else if (coverStyle == COVER_STYLE_RECORD_V2 && recordV2PageAdapter != null) {
                    ((com.jingxin.jingxinmusic.stage.ClassicPageV2) recordV2PageAdapter.getPage())
                            .applySpectrumStyle(com.jingxin.jingxinmusic.view.SpectrumView.STYLE_BAR);
                } else if (coverStyle == COVER_STYLE_CAROUSEL_V2 && carouselV2PageAdapter != null) {
                    ((com.jingxin.jingxinmusic.stage.ClassicPageV2) carouselV2PageAdapter.getPage())
                            .applySpectrumStyle(com.jingxin.jingxinmusic.view.SpectrumView.STYLE_BAR);
                } else if (coverStyle == COVER_STYLE_IMMERSIVE_V2 && immersiveV2PageAdapter != null) {
                    ((com.jingxin.jingxinmusic.stage.ImmersivePageV2) immersiveV2PageAdapter.getPage())
                            .applySpectrumStyle(com.jingxin.jingxinmusic.view.SpectrumView.STYLE_BAR);
                }
                // 样式变更影响布局（环→条），重排版
                currentScene.layout(getLayoutWidth(), getAvailableScreenHeight());
            }
        }
        updateThemeUI(); // 切换封面风格后更新背景（光晕/遮罩等）
        // 切换封面风格后，若不在「双色光晕+非沉浸」组合则恢复自定义歌词高亮色
        if (!(isAuroraActive()) && lyricView != null) {
            lyricView.restoreHighlightColor();
        }
        int width = getLayoutWidth();
        int height = getAvailableScreenHeight();
        currentScene.layout(width, height);

        // 重新加载封面
        loadCover();

        String[] styleNames = {"经典模式", "沉浸模式", "唱片机模式", "轮播模式", "新经典模式", "沉浸·新", "唱片机·新", "轮播·新"};
        android.widget.Toast.makeText(requireContext(), styleNames[coverStyle],
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 弹出频谱选择浮窗面板（竖屏2列5行，横屏5列2行）
     */
    private void showSpectrumPicker() {
        if (spectrumPickerPopup != null && spectrumPickerPopup.isShowing()) {
            spectrumPickerPopup.dismiss();
            return;
        }

        String[] names = com.jingxin.jingxinmusic.view.SpectrumView.STYLE_NAMES;
        int currentStyle = spectrumView.getCurrentStyle(); // 真值=prefs（实例构造即恢复）
        float density = getResources().getDisplayMetrics().density;
        Context ctx = requireContext();

        // 缩放系数：小屏手机保持原样(sf=1)，高分辨率车机按屏高等比放大（横竖屏自适应）
        // 基准：16sp，竖屏锚点屏高×1.88%，横屏锚点屏高×3%（与 SettingsFragment 一致）
        int viewH = getAvailableScreenHeight();
        if (viewH <= 0) viewH = getResources().getDisplayMetrics().heightPixels;
        float basePx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
        boolean pickerLandscape = getLayoutWidth() > viewH * 1.2f;
        float pickerRatio = pickerLandscape ? 0.03f : 0.0188f;
        float sf = Math.max(1f, viewH * pickerRatio / basePx);

        // 外层容器
        android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(
                (int)(10 * density * sf), (int)(10 * density * sf),
                (int)(10 * density * sf), (int)(10 * density * sf));
        // 左直角右圆角背景
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        float r = 16 * density * sf;
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});  // 左上0, 右上r, 右下r, 左下0
        bg.setColor(Color.argb(51, 30, 30, 30));  // 透明度20%
        container.setBackground(bg);

        // 标题
        android.widget.TextView title = new android.widget.TextView(ctx);
        title.setText("频谱选择");
        title.setTextColor(ThemeColors.sparkColor(isNightMode));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18 * density * sf);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, (int)(12 * density * sf));
        container.addView(title);

        // 竖屏2列5行，横屏5列2行
        boolean isLandscape = pickerLandscape;
        int columns = isLandscape ? 5 : 2;

        int itemWidth = isLandscape
                ? (int)(120 * density * sf)
                : (int)(140 * density * sf);

        android.widget.GridLayout grid = new android.widget.GridLayout(ctx);
        grid.setColumnCount(columns);

        for (int i = 0; i < names.length; i++) {
            final int style = i;
            android.widget.TextView item = new android.widget.TextView(ctx);
            item.setText(names[i]);
            item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16 * density * sf);
            item.setGravity(android.view.Gravity.CENTER);
            item.setPadding(
                    (int)(20 * density * sf), (int)(14 * density * sf),
                    (int)(20 * density * sf), (int)(14 * density * sf));
            item.setFocusable(true);
            CompatUtil.setForegroundCompat(item, ctx.getResources().getDrawable(R.drawable.tv_focus_popup_item));

            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = itemWidth;
            lp.height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT;
            lp.setMargins((int)(6 * density * sf), (int)(4 * density * sf), (int)(6 * density * sf), (int)(4 * density * sf));
            item.setLayoutParams(lp);

            // 沉浸模式下圆环类灰色不可选
            boolean isOverlay = com.jingxin.jingxinmusic.view.SpectrumView.isOverlayStyle(style);
            boolean disabled = !currentScene.shouldShowSpectrumButton(style);

            if (disabled) {
                item.setTextColor(ThemeColors.SPECTRUM_POPUP_DISABLED_TEXT);
                item.setBackgroundColor(Color.argb(153, 42, 42, 42));    // 透明度60%
            } else if (i == currentStyle) {
                // 当前选中：青色发光
                item.setTextColor(ThemeColors.SPECTRUM_POPUP_SELECTED_TEXT);
                item.setBackgroundColor(Color.argb(153, 0, 230, 180));  // 透明度60%
            } else {
                item.setTextColor(ThemeColors.SPECTRUM_POPUP_NORMAL_TEXT);
                item.setBackgroundColor(Color.argb(153, 68, 68, 68));   // 透明度60%
            }

            item.setOnClickListener(v -> {
                if (disabled) return;
                boolean wasRing = spectrumView.isCoverOverlayMode();
                spectrumView.setStyle(style); // setStyle 写 prefs 持久化（真值唯一来源）
                // 第5风格（V2）激活时：样式同步到 V2 自持实例（currentScene.layout 会重新收敛可见性）
                if (coverStyle == COVER_STYLE_CLASSIC_V2 && classicV2PageAdapter != null) {
                    ((com.jingxin.jingxinmusic.stage.ClassicPageV2) classicV2PageAdapter.getPage())
                            .applySpectrumStyle(style);
                }
                if (coverStyle == COVER_STYLE_IMMERSIVE_V2 && immersiveV2PageAdapter != null) {
                    // 沉浸 V2：仅底部样式（圆环类已被禁用项过滤）
                    ((com.jingxin.jingxinmusic.stage.ImmersivePageV2) immersiveV2PageAdapter.getPage())
                            .applySpectrumStyle(style);
                }
                if (coverStyle == COVER_STYLE_RECORD_V2 && recordV2PageAdapter != null) {
                    ((com.jingxin.jingxinmusic.stage.ClassicPageV2) recordV2PageAdapter.getPage())
                            .applySpectrumStyle(style);
                }
                if (coverStyle == COVER_STYLE_CAROUSEL_V2 && carouselV2PageAdapter != null) {
                    ((com.jingxin.jingxinmusic.stage.ClassicPageV2) carouselV2PageAdapter.getPage())
                            .applySpectrumStyle(style);
                }
                boolean isRing = spectrumView.isCoverOverlayMode();
                if (wasRing != isRing) {
                    int w = getLayoutWidth();
                    int h = getAvailableScreenHeight();
                    currentScene.layout(w, h);
                }
                spectrumPickerPopup.dismiss();
            });

            grid.addView(item);
        }
        container.addView(grid);

        spectrumPickerPopup = new android.widget.PopupWindow(container,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                true);
        spectrumPickerPopup.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT <= 22) {
            // 5.1：background为null时不创建PopupViewContainer，点外部无法dismiss，设透明背景触发容器创建
            spectrumPickerPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        spectrumPickerPopup.setElevation(8 * density * sf);
        // 左边距0，垂直居中
        spectrumPickerPopup.showAtLocation(mRootView, android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL,
                0, 0);
    }

    /**
     * 弹出音效预设选择浮窗（仿频谱弹窗：半透明深底、左直角右圆角、网格布局）
     * 竖屏 2 列，横屏 4 列
     */
    private void showEffectPickerPopup() {
        if (effectPickerPopup != null && effectPickerPopup.isShowing()) {
            effectPickerPopup.dismiss();
            return;
        }

        String[] names = com.jingxin.jingxinmusic.util.AudioEffectManager.PRESET_NAMES;
        int currentPreset = SettingsFragment.getAudioEffectPreset(requireContext());
        float density = getResources().getDisplayMetrics().density;
        Context ctx = requireContext();

        // 缩放系数：小屏手机保持原样(sf=1)，高分辨率车机按屏高等比放大（横竖屏自适应）
        int viewH = getAvailableScreenHeight();
        if (viewH <= 0) viewH = getResources().getDisplayMetrics().heightPixels;
        float basePx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
        boolean pickerLandscape = getLayoutWidth() > viewH * 1.2f;
        float sf = Math.max(1f, viewH * (pickerLandscape ? 0.03f : 0.0188f) / basePx);
        // 超高自适应收缩：单列7项弹窗总高与 sf 线性相关，按内容高系数反算上限，
        // 保证弹窗完整显示（横屏矮屏下底部"全景环绕"不被裁掉）
        float heightFactor = 20f /*容器上下padding*/ + 18f * 1.33f /*标题*/ + 12f /*标题底距*/
                + names.length * (16f * 1.33f + 28f /*条目上下padding*/ + 8f /*条目上下margin*/);
        float maxSf = viewH * 0.85f / (heightFactor * density);
        if (sf > maxSf) sf = maxSf;

        // 外层容器
        android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(
                (int)(10 * density * sf), (int)(10 * density * sf),
                (int)(10 * density * sf), (int)(10 * density * sf));
        // 左直角右圆角背景（与频谱弹窗一致）
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        float r = 16 * density * sf;
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        bg.setColor(Color.argb(51, 30, 30, 30));  // 透明度20%
        container.setBackground(bg);

        // 标题
        android.widget.TextView title = new android.widget.TextView(ctx);
        title.setText("音效选择");
        title.setTextColor(ThemeColors.sparkColor(isNightMode));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18 * density * sf);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, (int)(12 * density * sf));
        container.addView(title);

        // 只显示一列
        int itemWidth = (int)(140 * density * sf);

        android.widget.GridLayout grid = new android.widget.GridLayout(ctx);
        grid.setColumnCount(1);

        for (int i = 0; i < names.length; i++) {
            final int preset = i;
            android.widget.TextView item = new android.widget.TextView(ctx);
            item.setText(names[i]);
            item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16 * density * sf);
            item.setGravity(android.view.Gravity.CENTER);
            item.setPadding(
                    (int)(20 * density * sf), (int)(14 * density * sf),
                    (int)(20 * density * sf), (int)(14 * density * sf));
            item.setFocusable(true);
            CompatUtil.setForegroundCompat(item, ctx.getResources().getDrawable(R.drawable.tv_focus_popup_item));

            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = itemWidth;
            lp.height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT;
            lp.setMargins((int)(6 * density * sf), (int)(4 * density * sf), (int)(6 * density * sf), (int)(4 * density * sf));
            item.setLayoutParams(lp);

            if (i == currentPreset) {
                // 当前选中：青色发光（与频谱弹窗一致）
                item.setTextColor(ThemeColors.SPECTRUM_POPUP_SELECTED_TEXT);
                item.setBackgroundColor(Color.argb(153, 0, 230, 180));
            } else {
                item.setTextColor(ThemeColors.SPECTRUM_POPUP_NORMAL_TEXT);
                item.setBackgroundColor(Color.argb(153, 68, 68, 68));
            }

            item.setOnClickListener(v -> {
                if (preset == currentPreset) {
                    effectPickerPopup.dismiss();
                    return;
                }
                // SP 持久化（与设置页同一 key，两处入口互通）
                ctx.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(SettingsFragment.KEY_AUDIO_EFFECT_PRESET, preset).apply();
                // startService 通知 Service 立即应用（无需重启播放）
                android.content.Intent intent = new android.content.Intent(
                        MusicPlayerService.ACTION_EFFECT_PRESET_CHANGED);
                intent.setClass(ctx, MusicPlayerService.class);
                intent.putExtra(MusicPlayerService.EXTRA_EFFECT_PRESET, preset);
                try {
                    ctx.startService(intent);
                } catch (Exception ignored) {}
                effectPickerPopup.dismiss();
                android.widget.Toast.makeText(ctx, names[preset], android.widget.Toast.LENGTH_SHORT).show();
            });

            grid.addView(item);
        }
        container.addView(grid);

        effectPickerPopup = new android.widget.PopupWindow(container,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                true);
        effectPickerPopup.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT <= 22) {
            // 5.1：background为null时不创建PopupViewContainer，点外部无法dismiss，设透明背景触发容器创建
            effectPickerPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        effectPickerPopup.setElevation(8 * density * sf);
        // 贴页面左边、垂直居中于整个屏幕（以全屏根布局为锚点，避免以顶部按钮为锚点时弹窗整体上移、
        // 内容接近满屏高度导致底部"全景环绕"被屏幕裁掉）
        if (mRootView != null) {
            effectPickerPopup.showAtLocation(mRootView, android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL,
                    0, 0);
        }
    }

    /**
     * 弹出歌词搜索浮窗：加载状态 → 候选列表 → 点击下载
     */
    private void showLyricSearchPopup() {
        if (lyricSearchPopup != null && lyricSearchPopup.isShowing()) {
            lyricSearchPopup.dismiss();
            return;
        }
        if (song == null) {
            android.widget.Toast.makeText(requireContext(), "无歌曲信息", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        Context ctx = requireContext();

        // 缩放系数：小屏手机保持原样(sf=1)，高分辨率车机按屏高等比放大（横竖屏自适应）
        // 基准：16sp，竖屏锚点屏高×1.88%，横屏锚点屏高×3%（与 SettingsFragment/频谱弹窗一致）
        int viewH = getAvailableScreenHeight();
        if (viewH <= 0) viewH = getResources().getDisplayMetrics().heightPixels;
        float basePx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
        boolean pickerLandscape = getLayoutWidth() > viewH * 1.2f;
        float sf = Math.max(1f, viewH * (pickerLandscape ? 0.03f : 0.0188f) / basePx);

        // 外层容器
        android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(
                (int)(12 * density * sf), (int)(12 * density * sf),
                (int)(12 * density * sf), (int)(12 * density * sf));
        // 半透明深色背景，竖屏四角圆角，横屏左直角右圆角
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        float r = 16 * density * sf;
        if (isLandscapeMode) {
            bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        } else {
            bg.setCornerRadius(r);
        }
        bg.setColor(Color.argb(102, 30, 30, 30));  // 透明度40%
        container.setBackground(bg);

        // 标题
        android.widget.TextView title = new android.widget.TextView(ctx);
        String cleanTitle = Song.cleanSongTitle(song.title, song.artist);
        title.setText("搜索「" + (cleanTitle.length() > 12 ? cleanTitle.substring(0, 12) + "…" : cleanTitle) + "」歌词");
        title.setTextColor(ThemeColors.sparkColor(isNightMode));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16 * density * sf);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, (int)(10 * density * sf));
        container.addView(title);

        // 加载提示
        android.widget.ProgressBar loading = new android.widget.ProgressBar(ctx);
        android.widget.LinearLayout loadingRow = new android.widget.LinearLayout(ctx);
        loadingRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        loadingRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView loadingText = new android.widget.TextView(ctx);
        loadingText.setText("搜索中…");
        loadingText.setTextColor(0xFFCCCCCC);
        loadingText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14 * density * sf);
        loadingText.setPadding((int)(8 * density * sf), 0, 0, 0);
        loadingRow.addView(loading);
        loadingRow.addView(loadingText);
        android.widget.LinearLayout.LayoutParams loadingLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        loadingLp.gravity = android.view.Gravity.CENTER;
        loadingRow.setLayoutParams(loadingLp);
        container.addView(loadingRow);

        // 候选列表容器（ScrollView + LinearLayout）
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout listContainer = new android.widget.LinearLayout(ctx);
        listContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        container.addView(scroll);

        // 空结果提示（默认隐藏）
        android.widget.TextView emptyText = new android.widget.TextView(ctx);
        emptyText.setText("未找到歌词候选");
        emptyText.setTextColor(0xFFCCCCCC);
        emptyText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14 * density * sf);
        emptyText.setGravity(android.view.Gravity.CENTER);
        emptyText.setPadding(0, (int)(16 * density * sf), 0, (int)(16 * density * sf));
        emptyText.setVisibility(android.view.View.GONE);
        container.addView(emptyText);

        // 创建弹窗：竖屏宽度80%水平居中，横屏宽度65%左对齐
        // 旧 song_title_bar/spectrum_view 节点已删；弹窗几何改屏高比例估算
        int spectrumBottom = (int) (getAvailableScreenHeight() * 0.62f);
        int popupTop = (int) (getAvailableScreenHeight() * 0.12f);
        int popupHeight = spectrumBottom - popupTop;
        if (popupHeight < (int)(200 * density * sf)) popupHeight = (int)(200 * density * sf);
        int screenWidth = mRootView.getWidth();
        float widthRatio = isLandscapeMode ? 0.65f : 0.8f;
        int popupWidth = (int)(screenWidth * widthRatio);

        lyricSearchPopup = new android.widget.PopupWindow(container,
                popupWidth,
                popupHeight,
                true);
        lyricSearchPopup.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT <= 22) {
            // 5.1：background为null时不创建PopupViewContainer，点外部无法dismiss，设透明背景触发容器创建
            lyricSearchPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        lyricSearchPopup.setElevation(8 * density * sf);
        lyricSearchPopup.showAtLocation(mRootView,
                isLandscapeMode
                        ? (android.view.Gravity.TOP | android.view.Gravity.START)
                        : (android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL),
                0, popupTop);

        // 异步搜索（传入当前歌曲时长用于排序，优先用ExoPlayer真实时长）
        final String searchTitle = cleanTitle;
        final float scaleFactor = sf;   // 供异步线程中候选条目尺寸使用
        long realDuration = 0;
        if (bound && playerBinder != null) {
            int dur = playerBinder.getDuration();
            if (dur > 0) realDuration = dur;
        }
        if (realDuration == 0 && song != null) realDuration = song.duration;
        final long songDuration = realDuration;
        new Thread(() -> {
            java.util.List<LyricFetcher.LyricCandidate> candidates =
                    LyricFetcher.searchLyricCandidates(searchTitle, songDuration);
            uiHandler.post(() -> {
                if (lyricSearchPopup == null || !lyricSearchPopup.isShowing()) return;

                // 移除加载提示
                container.removeView(loadingRow);

                if (candidates.isEmpty()) {
                    emptyText.setVisibility(android.view.View.VISIBLE);
                    scroll.setVisibility(android.view.View.GONE);
                    return;
                }

                scroll.setVisibility(android.view.View.VISIBLE);

                // 找到时长最接近的候选索引（已排序，第一项即最接近）
                long bestDiff = Long.MAX_VALUE;
                int bestIdx = -1;
                if (songDuration > 0) {
                    for (int i = 0; i < candidates.size(); i++) {
                        long dur = candidates.get(i).durationMs;
                        if (dur > 0) {
                            bestDiff = Math.abs(dur - songDuration);
                            bestIdx = i;
                            break;
                        }
                    }
                }

                for (int idx = 0; idx < candidates.size(); idx++) {
                    final LyricFetcher.LyricCandidate cand = candidates.get(idx);
                    android.widget.TextView item = new android.widget.TextView(ctx);
                    String displayName = cand.title;
                    if (cand.artist != null && !cand.artist.isEmpty()) {
                        displayName += " - " + cand.artist;
                    }
                    String sourceTag = "kugou".equals(cand.source) ? "酷狗" : "网易云";
                    // 拼接时长
                    String durationStr = "";
                    if (cand.durationMs > 0) {
                        int totalSec = (int)(cand.durationMs / 1000);
                        durationStr = String.format("%d:%02d", totalSec / 60, totalSec % 60);
                    }
                    // 第一项且时长差<5秒，标记"最佳"
                    boolean isBestMatch = (idx == bestIdx && bestDiff < 5000);
                    String suffix = "  [" + sourceTag + "]";
                    if (!durationStr.isEmpty()) suffix += "  " + durationStr;
                    if (isBestMatch) suffix += "  ★";
                    item.setText(displayName + suffix);
                    item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13 * density * scaleFactor);
                    item.setTextColor(isBestMatch ? ThemeColors.sparkColor(isNightMode) : 0xFFDDDDDD);
                    item.setSingleLine(true);
                    item.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    item.setPadding(
                            (int)(12 * density * scaleFactor), (int)(10 * density * scaleFactor),
                            (int)(12 * density * scaleFactor), (int)(10 * density * scaleFactor));

                    android.graphics.drawable.GradientDrawable itemBg = new android.graphics.drawable.GradientDrawable();
                    if (isBestMatch) {
                        itemBg.setColor(Color.argb(51, 0, 230, 180));  // 最佳匹配背景高亮
                    } else {
                        itemBg.setColor(Color.argb(30, 68, 68, 68));
                    }
                    itemBg.setCornerRadius(8 * density * scaleFactor);
                    item.setBackground(itemBg);
                    android.widget.LinearLayout.LayoutParams itemLp = new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    itemLp.setMargins(0, (int)(3 * density * scaleFactor), 0, (int)(3 * density * scaleFactor));
                    item.setLayoutParams(itemLp);

                    item.setOnClickListener(v -> {
                        lyricSearchPopup.dismiss();
                        android.widget.Toast.makeText(requireContext(),
                                "下载歌词中…", android.widget.Toast.LENGTH_SHORT).show();

                        File lyricsDir = new File(requireContext().getExternalFilesDir(null), "lyrics");
                        LyricFetcher.downloadLyricByCandidate(cand, lyricsDir, song.title,
                            new LyricFetcher.LyricCallback() {
                                @Override
                                public void onLyricFetched(KrcParser.LyricData lyricData) {
                                    uiHandler.post(() -> {
                                        if (lyricData != null && lyricData.lines != null
                                                && !lyricData.lines.isEmpty()) {
                                            // Hub 歌词通道广播（歌词搜索下载成功路径）
                                            stateHub.setLyricData(lyricData);
                                            if (lyricView != null) {
                                                lyricView.setLyricData(lyricData);
                                            }
                                            android.widget.Toast.makeText(requireContext(),
                                                    "歌词已更新", android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                                @Override
                                public void onError(String errorMessage) {
                                    uiHandler.post(() ->
                                        android.widget.Toast.makeText(requireContext(),
                                                "歌词下载失败: " + errorMessage,
                                                android.widget.Toast.LENGTH_SHORT).show());
                                }
                            }, requireContext());
                    });

                    listContainer.addView(item);
                }
            });
        }, "LyricSearch").start();
    }

    private void toggleTheme() {
        setNightModeSynced(!isNightMode);
        requireContext().getSharedPreferences("theme", Context.MODE_PRIVATE).edit()
                .putBoolean("isNight", isNightMode)
                .putBoolean("amapTriggered", false)  // 手动切换，暂停高德同步
                .apply();
        updateThemeUI();
        if (lyricView != null) {
            lyricView.setThemeMode(isNightMode
                    ? com.jingxin.jingxinmusic.view.LyricView.ThemeMode.NIGHT
                    : com.jingxin.jingxinmusic.view.LyricView.ThemeMode.DAY);
        }
        // 双色光晕模式：日夜切换后按新主题重新映射歌词高亮色（QQ固定用深色）
        if (isAuroraActive() && lyricView != null && lastAuroraCoverColor != 0) {
            int lyricColor = CoverColorExtractor.getLyricHighlightColor(lastAuroraCoverColor, false);
            lyricView.setHighlightColor(lyricColor);
        }
        android.widget.Toast.makeText(requireContext(), isNightMode ? "夜间模式" : "白天模式", android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 双色光晕是否生效（所有封面模式均生效，包括沉浸模式）
     */
    private boolean isAuroraActive() {
        return bgSkinMode == 1;
    }

    private void updateThemeUI() {
        // 同步日夜模式到 sceneHelper
        if (sceneHelper != null) sceneHelper.isNightMode = isNightMode;

        // ===== 背景：皮肤收口（组件化第3步）=====
        // 原光晕/默认两分支（137行层级+遮罩逻辑）整体移入 bg/ 皮肤包：
        // DefaultBackgroundSkin（遮罩/日夜）与 AuroraBackgroundSkin（双色光晕+沉浸变体+层级手术）。
        // 原各叶子分支末尾均调用 applyTextTheme/applyButtonTheme，等价外提为统一收尾。
        if (bgSkinController != null) {
            bgSkinController.apply(bgSkinMode, coverStyle, isLandscapeMode, isNightMode);
        }
        applyTextTheme(isNightMode);
        applyButtonTheme(isNightMode);
    }

    /**
     * 统一设置文字主题颜色
     */
    private void applyTextTheme(boolean isNight) {
        int textColor = isNight ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
    }

    /**
     * 统一设置按钮主题颜色
     * ControlBar 内部按钮由组件自染；顶部栏按钮仍由 Fragment 染色
     */
    private void applyButtonTheme(boolean isNight) {
        // 光晕模式下用专用进度条配色，非光晕模式用原逻辑
    }


    /**
     * 保存当前播放歌曲信息到 SharedPreferences，下次启动时自动恢复
     * 同时保存播放队列模式，恢复时能继续在原队列（全部/收藏/目录）中播放
     */
    private void saveLastPlayed() {
        if (song == null) return;
        android.content.SharedPreferences.Editor editor = requireContext().getSharedPreferences("last_played", Context.MODE_PRIVATE).edit();
        song.saveToPrefs(editor);
        Bundle args = getArguments();
        String biliNavUrl = args != null ? args.getString("bili_nav_url", "") : "";
        editor.putInt("position", position)
                .putBoolean(com.jingxin.jingxinmusic.model.Song.KEY_HAS_LAST, true)
                .putString("playlist_mode", playlistMode != null ? playlistMode : "all")
                // B站导航上下文：返回时恢复到歌曲所在的列表页面
                .putString("bili_nav_url", biliNavUrl != null ? biliNavUrl : "");
        // 目录模式：保存该目录下所有歌曲路径
        if ("folder".equals(playlistMode)) {
            List<String> folderPaths = args != null ? args.getStringArrayList("folder_song_paths") : null;
            if (folderPaths != null && !folderPaths.isEmpty()) {
                editor.putStringSet("folder_song_paths", new java.util.HashSet<>(folderPaths));
            }
        } else if ("webdav".equals(playlistMode) || "bili".equals(playlistMode)) {
            // WebDAV/B站模式：保存标记，播放列表已在webdav_playlist SharedPreferences中
            editor.putBoolean("from_webdav", true);
        } else {
            // 其他模式清除旧数据
            editor.remove("folder_song_paths");
            editor.remove("from_webdav");
        }
        editor.apply();
    }

    /**
     * 启动频谱：优先 Visualizer（直接读取音频输出），失败则降级到 AudioRecord（麦克风采集）
     */
    private static final int SPECTRUM_RETRY_INTERVAL = 300; // ms
    private static final int SPECTRUM_MAX_RETRIES = 10; // 共 3 秒
    private int spectrumRetryCount = 0;
    // 频谱启动竞态保险网：Fragment 未 started 时延迟重试（乐酷时序竞态）
    private static final int SPECTRUM_RESUME_MAX_RETRIES = 5;
    private static final long SPECTRUM_RESUME_RETRY_INTERVAL_MS = 200;
    private int spectrumResumeRetryCount = 0;
    private final Runnable spectrumResumeRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            startSpectrumWithPermission();
        }
    };

    private void startSpectrum() {
        // 先停后启：确保每次调用都能得到全新的 Visualizer（避免旧实例回调静默失效后标志卡死）
        if (spectrumRunning) {
            stopSpectrum();
        }
        // 通知 MiniFloatService 释放 Visualizer（抢占式仲裁：创建前先释放对方）
        Intent releaseIntent = new Intent(ACTION_PLAYER_CLAIM);
        releaseIntent.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(releaseIntent);
        spectrumRunning = true;
        spectrumRetryCount = 0;
        spectrumResumeRetryCount = 0;
        tryStartVisualizer();
    }

    /**
     * 尝试用 Visualizer 启动频谱，sessionId=0 时延迟重试
     */
    private void tryStartVisualizer() {
        if (!spectrumRunning) {
            return;
        }
        if (bound && playerBinder != null) {
            try {
                int sessionId = playerBinder.getAudioSessionId();
                if (sessionId != -1 && sessionId != 0) {
                    visualizer = new Visualizer(sessionId);
                    visualizer.setEnabled(false);
                    int[] range = visualizer.getCaptureSizeRange();
                    if (range == null || range.length < 2) {
                        throw new RuntimeException("getCaptureSizeRange returned invalid range");
                    }
                    visualizer.setCaptureSize(range[1]);
                    visualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);

                    // rate=Visualizer.getMaxCaptureRate(), 不捕获波形, 捕获FFT
                    visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                        @Override
                        public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                        }

                        @Override
                        public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                            if (!spectrumRunning || spectrumView == null) return;

                            // BD 方式：FFT 1:1 取幅度，竖条模式只取半数频段（会镜像展开）
                            int count = spectrumView.getBarInputCount();
                            float[] magnitudes = acquireSpectrumBuf(count);
                            float maxMag = 0;
                            for (int i = 0; i < count; i++) {
                                int idx = (i + 1) * 2; // 跳过 DC 分量
                                if (idx + 1 < fft.length) {
                                    byte real = fft[idx];
                                    byte imaginary = fft[idx + 1];
                                    float mag = (float) Math.sqrt(real * real + imaginary * imaginary);
                                    magnitudes[i] = mag;
                                    if (mag > maxMag) maxMag = mag;
                                }
                            }
                            float finalMax = maxMag;
                            mRootView.post(() -> feedSpectrumData(magnitudes, finalMax));
                        }
                    }, Visualizer.getMaxCaptureRate(), false, true);

                    visualizer.setEnabled(true);
                    useVisualizer = true;
                    return; // 成功，不需要 AudioRecord
                } else {
                    // sessionId 还没准备好（ExoPlayer 正在异步 prepare），延迟重试
                    if (spectrumRetryCount < SPECTRUM_MAX_RETRIES) {
                        spectrumRetryCount++;
                        uiHandler.postDelayed(this::tryStartVisualizer, SPECTRUM_RETRY_INTERVAL);
                        return;
                    }
                    // 超过重试次数，降级到 AudioRecord
                }
            } catch (Exception e) {
                Log.w(TAG, "Visualizer 初始化失败，降级到 AudioRecord: " + e.getMessage());
                if (visualizer != null) {
                    try { visualizer.release(); } catch (Exception ignored) {}
                    visualizer = null;
                }
            }
        }

        // 降级：AudioRecord + DFT（和酷狗替身参数一致）
        useVisualizer = false;
        startSpectrumAudioRecord();
    }

    /**
     * AudioRecord 降级方案：通过麦克风采集音频 + FFT 计算频谱
     */
    private void startSpectrumAudioRecord() {
        // AudioRecord 降级方案需要 RECORD_AUDIO 权限，Visualizer 不需要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.RECORD_AUDIO}, 100);
            spectrumRunning = false;
            return;
        }
        new Thread(() -> {
            try {
                int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord getMinBufferSize 失败");
                    spectrumRunning = false;
                    return;
                }

                audioRecord = null;
                int[] sources = {
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.UNPROCESSED
                };
                boolean created = false;
                // 用局部变量持有实例：并发启动多个采集线程时，成员变量 audioRecord
                // 会被互相覆盖导致部分实例泄漏（Finalizer 超时崩溃），局部变量保证每个实例必然释放
                AudioRecord ar = null;
                for (int source : sources) {
                    try {
                        ar = new AudioRecord(
                                source,
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize);
                        if (ar.getState() == AudioRecord.STATE_INITIALIZED) {
                            created = true;
                            break;
                        } else {
                            ar.release();
                            ar = null;
                        }
                    } catch (Exception e) {
                        if (ar != null) {
                            ar.release();
                            ar = null;
                        }
                    }
                }

                if (!created) {
                    Log.e(TAG, "AudioRecord 所有音频源初始化失败");
                    spectrumRunning = false;
                    return;
                }
                audioRecord = ar; // 成功后赋给成员变量，供 stopSpectrum 统一释放

                audioRecord.startRecording();
                Thread.sleep(500);

                // FFT 补零到 1024（2^10），800 采样点零填充
                final int FFT_SIZE = 1024;
                short[] readBuffer = new short[800];
                float[] fftReal = new float[FFT_SIZE];
                float[] fftImag = new float[FFT_SIZE];
                // 预计算 bit-reverse 表
                int[] bitRevTable = new int[FFT_SIZE];
                int bits = 10; // log2(1024)
                for (int i = 0; i < FFT_SIZE; i++) {
                    int rev = 0;
                    int val = i;
                    for (int b = 0; b < bits; b++) {
                        rev = (rev << 1) | (val & 1);
                        val >>= 1;
                    }
                    bitRevTable[i] = rev;
                }

                while (spectrumRunning) {
                    int totalRead = audioRecord.read(readBuffer, 0, 800);
                    if (totalRead <= 0) continue;

                    // 零填充到 FFT_SIZE 并加窗（Hann 窗减少频谱泄漏）
                    for (int i = 0; i < FFT_SIZE; i++) {
                        if (i < totalRead) {
                            double window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (totalRead - 1)));
                            fftReal[i] = readBuffer[i] * (float) window;
                        } else {
                            fftReal[i] = 0;
                        }
                        fftImag[i] = 0;
                    }

                    // Cooley-Tukey 原地 FFT
                    for (int i = 0; i < FFT_SIZE; i++) {
                        int j = bitRevTable[i];
                        if (j > i) {
                            float tmp = fftReal[i]; fftReal[i] = fftReal[j]; fftReal[j] = tmp;
                            tmp = fftImag[i]; fftImag[i] = fftImag[j]; fftImag[j] = tmp;
                        }
                    }
                    for (int len = 2; len <= FFT_SIZE; len <<= 1) {
                        int halfLen = len >> 1;
                        double angleStep = -2.0 * Math.PI / len;
                        for (int i = 0; i < FFT_SIZE; i += len) {
                            for (int j = 0; j < halfLen; j++) {
                                double angle = angleStep * j;
                                float wr = (float) Math.cos(angle);
                                float wi = (float) Math.sin(angle);
                                int idx1 = i + j;
                                int idx2 = i + j + halfLen;
                                float tr = wr * fftReal[idx2] - wi * fftImag[idx2];
                                float ti = wr * fftImag[idx2] + wi * fftReal[idx2];
                                fftReal[idx2] = fftReal[idx1] - tr;
                                fftImag[idx2] = fftImag[idx1] - ti;
                                fftReal[idx1] += tr;
                                fftImag[idx1] += ti;
                            }
                        }
                    }

                    // 从 FFT 结果提取频谱幅度
                    int count = spectrumView != null ? spectrumView.getBarInputCount() : 65;
                    float[] magnitudes = acquireSpectrumBuf(count);
                    float maxMag = 0;
                    for (int bar = 0; bar < count; bar++) {
                        int k = bar + 1; // 跳过 DC 分量
                        if (k >= FFT_SIZE / 2) k = FFT_SIZE / 2 - 1;
                        float re = fftReal[k];
                        float im = fftImag[k];
                        float mag = (float) Math.sqrt(re * re + im * im) / FFT_SIZE;
                        magnitudes[bar] = mag;
                        if (mag > maxMag) maxMag = mag;
                    }

                    float finalMax = maxMag;
                    if (spectrumView != null) {
                        mRootView.post(() -> feedSpectrumData(magnitudes, finalMax));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "频谱采集异常: " + e.getMessage());
            } finally {
                stopSpectrum();
            }
        }, "SpectrumThread").start();
    }

    /**
     * 双缓冲轮换：返回当前"写槽"（采集线程写），主线程读另一个槽。
     * count 变化时自动扩容；flip 标志保证采集与主线程读不共用同一数组。
     */
    private float[] acquireSpectrumBuf(int count) {
        if (count <= 0) count = 1;
        if (spectrumBufLen < count) {
            spectrumBufA = new float[count];
            spectrumBufB = new float[count];
            spectrumBufLen = count;
            spectrumBufFlip = false;
        }
        spectrumBufFlip = !spectrumBufFlip;
        // 清零：上一次的残留值会影响本帧（只填部分 idx 时）
        float[] buf = spectrumBufFlip ? spectrumBufA : spectrumBufB;
        java.util.Arrays.fill(buf, 0, count, 0f);
        return buf;
    }

    private void stopSpectrum() {
        spectrumRunning = false;
        // 释放 Visualizer：先断回调，再延迟 release，避免 native CaptureThread 与 release 并发导致 CFI 崩溃
        if (visualizer != null) {
            final android.media.audiofx.Visualizer v = visualizer;
            visualizer = null;
            try {
                v.setEnabled(false);
                v.setDataCaptureListener(null, 0, false, false);
            } catch (Exception ignored) {}
            // 延迟释放：给 native CaptureThread 退出时间（Android 8.1 立即 release 会 use-after-free）
            try {
                uiHandler.postDelayed(v::release, 300);
            } catch (Exception ignored) {}
        }
        // 释放 AudioRecord
        // 注意：stop()/release() 在音频服务异常设备（5.1 模拟器 AudioFlinger Binder 卡死）上
        // 会长时间阻塞；且本方法可能被主线程（点击播放）与 SpectrumThread（采集结束 finally）
        // 并发调用，直接在主线程执行会互相抢锁导致 ANR。
        // 因此：先原子取走引用（防止并发双释放），stop/release 移到子线程执行。
        final android.media.AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            new Thread(() -> {
                try {
                    if (ar.getState() == AudioRecord.STATE_INITIALIZED) {
                        ar.stop();
                    }
                } catch (Exception ignored) {}
                try {
                    ar.release();
                } catch (Exception ignored) {}
            }, "AudioRecordRelease").start();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopSpectrum();
        stopControlsHideTimer();
        uiHandler.removeCallbacks(progressRunnable);
        uiHandler.removeCallbacks(spectrumResumeRetryRunnable);
        try {
            requireContext().unregisterReceiver(songChangedReceiver);
        } catch (Exception ignored) {}
        if (bound) {
            requireContext().unbindService(serviceConnection);
            bound = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /**
     * 从SharedPreferences加载WebDAV播放列表
     */
    private List<Song> loadWebDavPlaylist() {
        List<Song> songs = new ArrayList<>();
        try {
            String json = requireContext().getSharedPreferences("webdav_playlist", Context.MODE_PRIVATE)
                    .getString("playlist", null);
            if (json != null) {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    Song s = Song.fromJson(arr.getJSONObject(i));
                    if (s != null) songs.add(s);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载WebDAV播放列表失败: " + e.getMessage());
        }
        return songs;
    }
}