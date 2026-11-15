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
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.SpectrumEngine;
import com.jingxin.jingxinmusic.scene.CoverSceneHelper;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.HostActivity;
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
    private LyricView lyricView;
    private SpectrumView spectrumView;
    
    private android.widget.PopupWindow effectPickerPopup; // 音效选择浮窗
    private android.widget.PopupWindow stylePickerPopup; // 播放风格选择浮窗（装扮键）
    private android.widget.PopupWindow lyricEffectPickerPopup; // 歌词动效选择浮窗（星空/流光/时计）
    private static final String KEY_LYRIC_EFFECT_MODE = "lyric_effect_mode"; // 0=星空 1=流光 2=时计

    // 低频能量包络（时计摆轮驱动；FFT 回调里累积，主线程读取）
    private volatile float bassEnergySmoothed;
    private long bassLastUpdateMs;
    private static final long BASS_STALE_MS = 600L; // 停更后衰减到 0

    // 封面缓存：切歌时保持旧封面，避免「默认封面→真实封面」闪烁
    private boolean hasCoverLoaded = false; // 是否已有封面加载过

    // 白天模式渐变遮罩（浅绿→白）

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
    private static final int COVER_STYLE_COLLAGE_V2 = 8; // 第9风格：拼贴 V2（自研织块墙）
    private static final int COVER_STYLE_RADIO_V2 = 9;   // 电台专用：星空收音机页（动态分流，非用户可选风格）
    /** V2 风格名（下标=风格值-4，供风格弹窗与切换 Toast 共用） */
    private static final String[] STYLE_V2_NAMES = {"经典", "沉浸", "唱机", "轮播", "拼贴"};

    /**
     * 风格值规范化：旧 prefs 值 0~3 映射到 V2 新风格（4~8），越界回落新经典（幂等）。
     */
    private static int normalizeCoverStyle(int style) {
        if (style < COVER_STYLE_CLASSIC_V2) return style + COVER_STYLE_CLASSIC_V2;
        return style > COVER_STYLE_RADIO_V2 ? COVER_STYLE_CLASSIC_V2 : style;
    }
    private int coverStyle = COVER_STYLE_CLASSIC;
    private int bgSkinMode = 0; // 0=默认模式, 1=双色光晕
    // 背景皮肤控制器（组件化第3步）：背景渲染收口，updateThemeUI 委托分发
    private int lastAuroraCoverColor = 0; // 光晕模式最近一次封面提取色（用于日夜切换重新映射歌词色）
    private volatile int coverColorToken = 0; // 封面取色竞态防护token，每次setCoverBitmap递增
    // （旧共享唱臂 tonearmView 已随旧四风格页下线：V2 唱机页自持唱臂，此链全删）
    private android.widget.PopupWindow spectrumPickerPopup; // 频谱选择浮窗
    private android.widget.PopupWindow lyricSearchPopup; // 歌词搜索浮窗
    private android.widget.PopupWindow historyPopup; // 播放历史浮窗

    // 横屏模式
    private boolean isLandscapeMode = false; // 宽>高*1.2 时为横屏
    private com.jingxin.jingxinmusic.view.CoverBorderGradientDrawable coverBorderGradient; // 横屏沉浸封面边缘渐变

    // 播放场景：四风格各持一个 StagePage 实例（组件化第5/6步）
    private CoverSceneHelper sceneHelper;
    /** insets 原始顶部值（未折算，供 onResume 重算避让策略用） */
    private int lastRawTopInset = -1;
    private com.jingxin.jingxinmusic.stage.ClassicPageV2 classicV2Page; // 经典
    private com.jingxin.jingxinmusic.stage.ImmersivePageV2 immersiveV2Page; // 沉浸
    private com.jingxin.jingxinmusic.stage.RecordPageV2 recordV2Page; // 唱机
    private com.jingxin.jingxinmusic.stage.CarouselPageV2 carouselV2Page; // 轮播
    private com.jingxin.jingxinmusic.stage.CollagePageV2 collageV2Page;
    private com.jingxin.jingxinmusic.stage.RadioPageV2 radioV2Page; // 拼贴
    private com.jingxin.jingxinmusic.stage.StagePage currentScene;
    /** currentScene 本次进入时的方向形态（横竖切换时 layout 前需 exit→enter 重建，等价旧架构两 Scene 实例） */
    private boolean enteredAsLandscape = false;

    // 播放服务
    private MusicPlayerBinder playerBinder;
    private boolean bound = false;
    /** 歌词效果页管理器（顶栏歌词动效键进入·单击退出，星空等纯展示效果层） */
    private com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager lyricEffectManager;
    /** 启动直达歌词动效标记（args 只消费一次）：歌单就绪后自动进入上次记忆的效果 */
    private boolean pendingAutoEnterLyricEffect = false;
    private final Handler uiHandler = new Handler();
    private boolean userSeeking = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

                // 切歌在「电台↔普通」间翻转时重排页面
                //（进入电台→星空收音机页，离开电台→恢复用户所选风格）
                boolean wasRadio = song != null && song.sourceType == Song.SOURCE_RADIO;
                song = newSong;
                boolean nowRadio = newSong.sourceType == Song.SOURCE_RADIO;
                if (wasRadio != nowRadio) {
                    switchScene();
                }
                // Hub 封面/歌词快照失效（新歌数据到达前，独立页不读到旧歌缓存）
                stateHub.invalidateCoverAndLyric();
                loadCover();
                fetchLyrics();
                checkFavoriteStatus();
                updateDownloadButtonVisibility();
                saveLastPlayed();
            // CarouselV2/CollageV2/RadioV2 切歌后列表同步（activePage 已建则同步，未建则待 enter 时拉取）
            com.jingxin.jingxinmusic.stage.StagePage cp = activePage();
            if (cp instanceof com.jingxin.jingxinmusic.stage.CarouselPageV2) {
                ((com.jingxin.jingxinmusic.stage.CarouselPageV2) cp).syncPlaylistPosition();
            } else if (cp instanceof com.jingxin.jingxinmusic.stage.CollagePageV2) {
                ((com.jingxin.jingxinmusic.stage.CollagePageV2) cp).syncQueueData();
            } else if (cp instanceof com.jingxin.jingxinmusic.stage.RadioPageV2) {
                ((com.jingxin.jingxinmusic.stage.RadioPageV2) cp).syncQueueData();
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
            } else if ("com.jingxin.jingxinmusic.FLOAT_LAYOUT_REFRESH".equals(action)) {
                // 乐酷悬浮进入：按悬浮区域尺寸刷新布局
                if (!isActivityGone()) {
                    int w = getLayoutWidth();
                    int h = getAvailableScreenHeight();
                    detectAndApplyLandscapeMode();
                    applyLayoutMode();
                    sceneLayout(w, h);
                    updateThemeUI();
                    updateLayoutForMode(lyricView.getDisplayMode());
                    lyricView.post(lyricView::refreshTextSize);
                }
            } else if ("com.jingxin.jingxinmusic.SPECTRUM_RESTART".equals(action)) {
                // 乐酷悬浮进入/退出时，重建频谱
                if (com.jingxin.jingxinmusic.floatwindow.LecoFloatManager.getInstance().isFloating() || isSpectrumStartable()) {
                    // 可见 → 引擎幂等续跑（已在跑无感），未跑则补启
                    startSpectrumWithPermission();
                } else {
                    // 不可见 → 让出采集器，MiniFloatService 接管
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
                        // 同步队列上下文给 Service：无 UI 播放（乐酷/后台/车键切歌）时
                        // Service 靠它保存 last_played，防记忆残留旧会话（如旧 B 站歌）
                        playerBinder.setPlaylistContext(playlistMode,
                                "folder".equals(playlistMode) && getArguments() != null
                                        ? getArguments().getStringArrayList("folder_song_paths") : null);
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
                                    // position 重算：autoResume 恢复时 position 来自全库定位
                                    // （跨队列错用会让 currentIndex 指向别的歌：封面墙展开卡
                                    // 与实际播放不符——华为实测日志实锤），按歌在目录队列内找回位置
                                    if (song != null) {
                                        int folderPos = -1;
                                        for (int i = 0; i < folderSongs.size(); i++) {
                                            Song f = folderSongs.get(i);
                                            if (f == song || (f.id != 0 && f.id == song.id)) {
                                                folderPos = i;
                                                break;
                                            }
                                        }
                                        if (folderPos >= 0) position = folderPos;
                                    }
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
                        } else if ("webdav".equals(playlistMode) || "bili".equals(playlistMode) || "radio".equals(playlistMode) || (getArguments() != null && getArguments().getBoolean("from_webdav", false))) {
                            // WebDAV/B站/电台模式：从SharedPreferences恢复播放列表
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
                        // 列表就绪后：若当前为轮播/拼贴/电台页，全量灌入
                        com.jingxin.jingxinmusic.stage.StagePage cp2 = activePage();
                        if (cp2 instanceof com.jingxin.jingxinmusic.stage.CarouselPageV2) {
                            ((com.jingxin.jingxinmusic.stage.CarouselPageV2) cp2).syncPlaylistPosition();
                        } else if (cp2 instanceof com.jingxin.jingxinmusic.stage.CollagePageV2) {
                            ((com.jingxin.jingxinmusic.stage.CollagePageV2) cp2).syncQueueData();
                        } else if (cp2 instanceof com.jingxin.jingxinmusic.stage.RadioPageV2) {
                            ((com.jingxin.jingxinmusic.stage.RadioPageV2) cp2).syncQueueData();
                        }
                        // 启动直达歌词动效：歌单就绪后进入上次记忆的效果（仅冷启动首次，消费即失效）
                        if (pendingAutoEnterLyricEffect) {
                            pendingAutoEnterLyricEffect = false;
                            enterRememberedLyricEffect();
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
        syncPagesControlsHidden(true);
    }

    /** 恢复控制栏，可选重置隐藏计时 */
    private void showControls(boolean resetTimer) {
        if (!controlsHidden) {
            if (resetTimer) resetControlsHideTimer();
            return;
        }
        controlsHidden = false;
        syncPagesControlsHidden(false);
        if (resetTimer) resetControlsHideTimer();
    }

    /** 控制条隐藏态同步到全部已建页（仅活跃页可见，其余页同步后状态一致，进入时立即应用） */
    private void syncPagesControlsHidden(boolean hidden) {
        if (classicV2Page != null) classicV2Page.setControlsHiddenFromHost(hidden);
        if (recordV2Page != null) recordV2Page.setControlsHiddenFromHost(hidden);
        if (carouselV2Page != null) carouselV2Page.setControlsHiddenFromHost(hidden);
        if (immersiveV2Page != null) immersiveV2Page.setControlsHiddenFromHost(hidden);
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
            pendingAutoEnterLyricEffect = args.getBoolean("auto_enter_lyric_effect", false);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_player, container, false);
        mRootView = view;

        // 初始化视图
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

        // 初始化 CoverScene 策略
        initCoverScene();

        // 全局布局回调重算顶部避让：沉浸切换/旋转/悬浮导致窗口挪位时，
        // onResume 同步测量会读到旧窗口位置（getLocationOnScreen 竞态），布局完成后重测即真值
        mRootView.getViewTreeObserver().addOnGlobalLayoutListener(this::refreshTopAvoidance);

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

        // 歌词效果页体系：顶栏歌词动效键进入（全风格）、单击退出、无按键纯展示
        lyricEffectManager = new com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager(
                com.jingxin.jingxinmusic.player.PlayerStateHub.get(),
                rootFrameLayout,
                new com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.SongProvider() {
                    @Override
                    public java.util.List<com.jingxin.jingxinmusic.model.Song> getPlaylist() {
                        return (bound && playerBinder != null) ? playerBinder.getPlaylist() : null;
                    }

                    @Override
                    public int getCurrentIndex() {
                        return (bound && playerBinder != null) ? playerBinder.getCurrentIndex() : -1;
                    }
                },
                () -> lyricView != null ? lyricView.getCurrentHighlightColor() : 0,
                new com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.PlayerController() {
                    @Override public void playPrevious() { PlayerFragment.this.playPrevious(); }
                    @Override public void togglePlayPause() { PlayerFragment.this.togglePlayPause(); }
                    @Override public void playNext() { PlayerFragment.this.playNext(); }
                });
        // 效果页退出回调：拼贴页恢复卡内频谱（detach 时刻）
        lyricEffectManager.setOnDetachListener(() -> {
            com.jingxin.jingxinmusic.stage.StagePage cp = activePage();
            if (cp instanceof com.jingxin.jingxinmusic.stage.CollagePageV2) {
                ((com.jingxin.jingxinmusic.stage.CollagePageV2) cp).setEffectPageActive(false);
            }
        });
        // 低频能量源（时计摆轮；无频谱时返 0，机芯恒速）
        lyricEffectManager.setBassEnergyProvider(() -> {
            if (System.currentTimeMillis() - bassLastUpdateMs > BASS_STALE_MS) return 0f;
            return bassEnergySmoothed;
        });

        if (rootFrameLayout != null) {
            rootFrameLayout.addOnLayoutChangeListener(
                    (View v, int left, int top, int right, int bottom,
                     int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
                        if (isActivityGone()) return;
                        int newWidth = right - left;
                        int newHeight = bottom - top;
                        int oldWidth = oldRight - oldLeft;
                        int oldHeight = oldBottom - oldTop;
                        // 宽或高任一变化都重排（原条件只看宽：乐酷画中画纯调高度时
                        // 全部程序化布局公式按旧高度跑，封面顶距/控制条底距/字号全错位）
                        if (newWidth > 0 && (newWidth != oldWidth || newHeight != oldHeight)) {
                            // 横竖屏切换时关闭频谱选择弹窗
                            if (spectrumPickerPopup != null && spectrumPickerPopup.isShowing()) {
                                spectrumPickerPopup.dismiss();
                            }
                            if (effectPickerPopup != null && effectPickerPopup.isShowing()) {
                                effectPickerPopup.dismiss();
                            }
                            if (stylePickerPopup != null && stylePickerPopup.isShowing()) {
                                stylePickerPopup.dismiss();
                            }
                            // 重新检测横竖屏并应用完整布局
                            detectAndApplyLandscapeMode();
                            applyLayoutMode();
                            // 始终重新layout，更新频谱位置（圆环模式需要跟随封面中心）
                            int w = getLayoutWidth();
                            int h = getAvailableScreenHeight();
                            sceneLayout(w, h);
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
        sceneLayout(getLayoutWidth(), getAvailableScreenHeight());

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
        // 播放页沉浸：背景/封面顶到状态栏（沉浸透明），按钮避让由 StatusBarAvoid 公式自动接管
        if (getActivity() instanceof HostActivity) {
            ((HostActivity) getActivity()).setStatusBarImmersive(true);
        }
        // 窗口状态可能已变：立即重算一次；沉浸切换后的窗口挪位由全局布局回调兜底
        refreshTopAvoidance();
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
        // 频谱只由以下场景停止：跨端抢占（SpectrumEngine 引擎内同步仲裁）、切歌重建、用户关开关、onDestroyView
        // 但 aurora 帧链必须挂起：车机 ROM 退后台不下发窗口隐藏，isShown 仍 true，止损不触发，
        // 后台空转 28Hz（MEK-MX8Q 日志实锤）；闸门幂等且无视觉副作用，快速循环无害
        setStagePagesHostResumed(false);
    }

    @Override
    public void onStart() {
        super.onStart();
        setStagePagesHostResumed(true);
    }

    /** 前后台闸门分发给四场景页（页内转发给 aurora 竖/横双实例，未装页空桩） */
    private void setStagePagesHostResumed(boolean resumed) {
        com.jingxin.jingxinmusic.stage.StagePage[] pages =
                {classicV2Page, immersiveV2Page, recordV2Page, carouselV2Page};
        for (com.jingxin.jingxinmusic.stage.StagePage p : pages) {
            if (p != null) p.setHostResumed(resumed);
        }
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
        if (stylePickerPopup != null && stylePickerPopup.isShowing()) {
            stylePickerPopup.dismiss();
        }
        if (lyricEffectPickerPopup != null && lyricEffectPickerPopup.isShowing()) {
            lyricEffectPickerPopup.dismiss();
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
     * 拼贴墙点卡绝对定位播放（队列真实索引）
     * 不直接 binder.playSongAtPosition：走 playSongAt 同链路（allSongs position 同步/频谱/歌词/收藏全刷新）
     */
    private void playSongAtPosition(int queueIndex) {
        if (bound && playerBinder != null) {
            java.util.List<Song> pl = playerBinder.getPlaylist();
            if (pl != null && queueIndex >= 0 && queueIndex < pl.size()) {
                playSongAt(queueIndex - playerBinder.getCurrentIndex());
                // 乐观置播放态：服务 prepare→onIsPlayingChanged→广播回 Hub 有半拍延迟，
                // 点卡瞬间墙上就该亮起播放图形（广播到达后自然校正）
                stateHub.setPlaying(true);
            }
        }
    }

    /**
     * 初始 currentScene 持有者：新经典 V2 adapter，并立即 enter 保证 install
     * （首个 onLayoutChange 前页面必须就绪，否则页面字段全 null）。
     */
    /** 页面首次挂载到宿主（旧 StagePageAdapter.enter 的懒安装拆平后改由 Fragment 在建页时触发） */
    private void installV2Page(com.jingxin.jingxinmusic.stage.StagePage page) {
        page.install(android.view.LayoutInflater.from(requireContext()), (android.view.ViewGroup) sceneHelper.rootLayout);
    }

    /**
     * 场景布局统一入口：方向变化时先重入（旧架构横竖是两个 Scene 实例，合并单实例后
     * 不重入则页面停留在旧方向形态：沉浸横→竖封面不重铺/渐变层残留等）
     */
    private void sceneLayout(int width, int height) {
        if (currentScene != null && enteredAsLandscape != isLandscapeMode) {
            currentScene.exit();
            enteredAsLandscape = isLandscapeMode;
            currentScene.enter();
        }
        if (currentScene != null) currentScene.layout(width, height);
    }

    private void initCurrentSceneHolder() {
        currentScene = ensurePage(COVER_STYLE_CLASSIC_V2);
        syncSceneState();
        enteredAsLandscape = isLandscapeMode;
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
     * 频谱数据分发
     */
    private void feedSpectrumData(float[] magnitudes, float maxMag) {
        com.jingxin.jingxinmusic.stage.StagePage p = activePage();
        if (p != null) p.feedSpectrumData(magnitudes, maxMag);
    }

    /**
     * 当前风格对应的活跃页（未建返回 null）。
     * 不用 currentScene：onCreateView 早期 coverStyle 已读 prefs 但 switchScene 未跑，
     * currentScene 仍是经典页，此时不应喂新风格页。
     */
    private com.jingxin.jingxinmusic.stage.StagePage activePage() {
        switch (effectiveCoverStyle()) {
            case COVER_STYLE_IMMERSIVE_V2:  return immersiveV2Page;
            case COVER_STYLE_RECORD_V2:     return recordV2Page;
            case COVER_STYLE_CAROUSEL_V2:   return carouselV2Page;
            case COVER_STYLE_COLLAGE_V2:   return collageV2Page;
            case COVER_STYLE_RADIO_V2:     return radioV2Page;
            case COVER_STYLE_CLASSIC_V2:
            default:                        return classicV2Page;
        }
    }

    private void startSpectrumWithPermission() {
        if (!SettingsFragment.isSpectrumEnabled(requireContext())) return;
        // 引擎 request 幂等（同 owner 同 session 不重建，session 变化自动重建），防重入
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
        // 电台走专属收藏夹（radio_favorites），不与歌曲收藏混存
        //（防主收藏Tab把台名当歌名搜出错误封面）
        boolean radio = song.sourceType == Song.SOURCE_RADIO;
        File favDir = radio
                ? com.jingxin.jingxinmusic.util.RadioFavoriteManager.getRadioFavDir(requireContext())
                : com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext());

        if (isFavorite) {
            FavoriteManager.removeFavorite(favDir, song);
            // 电台历史遗留：修复前误存进歌曲收藏夹的条目一并清除
            //（removeFavorite 无匹配时为空操作，安全）
            if (radio) {
                FavoriteManager.removeFavorite(
                        com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext()), song);
            }
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
        boolean radio = song.sourceType == Song.SOURCE_RADIO;
        if (radio) {
            // 电台：专属收藏夹 + 历史遗留误存的歌曲收藏夹都算已收藏（取消时双清）
            isFavorite = FavoriteManager.isFavorite(
                            com.jingxin.jingxinmusic.util.RadioFavoriteManager.getRadioFavDir(requireContext()),
                            song.title, song.artist)
                    || FavoriteManager.isFavorite(
                            com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext()),
                            song.title, song.artist);
        } else {
            isFavorite = FavoriteManager.isFavorite(
                    com.jingxin.jingxinmusic.util.FavoriteManager.getFavoriteDir(requireContext()),
                    song.title, song.artist);
        }
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
                rootLayout, spectrumView,
                getResources().getDisplayMetrics().density);
        sceneHelper.bgSkinMode = bgSkinMode;
        sceneHelper.coverStyle = coverStyle;

        // 初始场景：新经典 V2（首建+立即 enter，保证首个 onLayoutChange 前页面已 install——
        // 上次 5a 教训：只赋值不 enter，switchScene 遇 target==currentScene 不进入，
        // V2 页面未 install → 频谱未建立/歌词字段 null → onLyricModeChanged NPE 崩溃）
        initCurrentSceneHolder();

        // 监听系统窗口 insets，获取状态栏实际高度（车机全屏时为0）
        // 存入 sceneHelper 的已是折叠后的有效避让值（普通手机窗口系统已垫下 → 0，见 StatusBarAvoid）
        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // API 30+：使用新 API，Android 16 edge-to-edge 模式下更可靠
                topInset = insets.getInsets(android.view.WindowInsets.Type.systemBars()).top;
            } else {
                // API 21-29：使用兼容 API
                topInset = insets.getSystemWindowInsetTop();
            }
            lastRawTopInset = topInset;
            int effectiveInset = com.jingxin.jingxinmusic.util.StatusBarAvoid.effectiveTopInset(v, topInset);
            if (sceneHelper.systemTopInset != effectiveInset) {
                sceneHelper.systemTopInset = effectiveInset;
                // inset 变化时重新布局，让 applyButtonMargins 生效
                v.requestLayout();
            }
            return insets;
        });
    }

    /**
     * 顶部避让重算（onResume 与全局布局回调共用）：
     * 主动从 rootWindowInsets 拉取原始 inset，不依赖回调是否触发过（部分 ROM
     * 非 edge-to-edge 窗口 insets 事件被上层消费，lastRawTopInset 停在 -1）；
     * 沉浸切换时窗口从 y=65 挪到 y=0 有布局延迟，onResume 同步测getLocationOnScreen
     * 会读到旧值，全局布局回调在窗口挪位完成后重测，竞态消除。
     */
    private void refreshTopAvoidance() {
        if (isActivityGone() || sceneHelper == null || mRootView == null) return;
        int rawInset = lastRawTopInset;
        try {
            android.view.WindowInsets wi =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                            ? mRootView.getRootWindowInsets() : null;
            if (wi != null) {
                rawInset = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                        ? wi.getInsets(android.view.WindowInsets.Type.systemBars()).top
                        : wi.getSystemWindowInsetTop();
            }
        } catch (Exception ignored) {}
        if (rawInset >= 0 && rawInset != lastRawTopInset) lastRawTopInset = rawInset;
        if (rawInset >= 0) {
            int effectiveInset = com.jingxin.jingxinmusic.util.StatusBarAvoid.effectiveTopInset(mRootView, rawInset);
            if (sceneHelper.systemTopInset != effectiveInset) {
                sceneHelper.systemTopInset = effectiveInset;
                sceneLayout(getLayoutWidth(), getAvailableScreenHeight());
            }
        }
    }

    /**
     * 风格页工厂：懒建+装机+初始化 actions。
     * 唯一建页入口（switchScene/initCurrentSceneHolder 共用）；
     * 新增风格只需补一个 case。
     */
    private com.jingxin.jingxinmusic.stage.StagePage ensurePage(int style) {
        switch (style) {
            case COVER_STYLE_IMMERSIVE_V2:
                if (immersiveV2Page == null) {
                    com.jingxin.jingxinmusic.stage.ImmersivePageV2 p =
                            new com.jingxin.jingxinmusic.stage.ImmersivePageV2(sceneHelper);
                    p.setActions(buildV2Actions());
                    immersiveV2Page = p;
                    installV2Page(p);
                }
                return immersiveV2Page;
            case COVER_STYLE_RECORD_V2:
                if (recordV2Page == null) {
                    com.jingxin.jingxinmusic.stage.RecordPageV2 p =
                            new com.jingxin.jingxinmusic.stage.RecordPageV2(sceneHelper);
                    p.setActions(buildV2Actions());
                    recordV2Page = p;
                    installV2Page(p);
                }
                return recordV2Page;
            case COVER_STYLE_CAROUSEL_V2:
                if (carouselV2Page == null) {
                    com.jingxin.jingxinmusic.stage.CarouselPageV2 p =
                            new com.jingxin.jingxinmusic.stage.CarouselPageV2(sceneHelper);
                    p.setActions(buildCarouselV2Actions()); // 轮播专属 actions（列表桥）
                    carouselV2Page = p;
                    installV2Page(p);
                }
                return carouselV2Page;
            case COVER_STYLE_COLLAGE_V2:
                if (collageV2Page == null) {
                    com.jingxin.jingxinmusic.stage.CollagePageV2 p =
                            new com.jingxin.jingxinmusic.stage.CollagePageV2(sceneHelper);
                    p.setActions(buildCarouselV2Actions()); // 拼贴复用轮播 actions（列表桥同构）
                    collageV2Page = p;
                    installV2Page(p);
                }
                return collageV2Page;
            case COVER_STYLE_RADIO_V2:
                if (radioV2Page == null) {
                    com.jingxin.jingxinmusic.stage.RadioPageV2 p =
                            new com.jingxin.jingxinmusic.stage.RadioPageV2(sceneHelper);
                    p.setActions(buildCarouselV2Actions()); // 电台页复用轮播 actions（列表桥同构）
                    radioV2Page = p;
                    installV2Page(p);
                }
                return radioV2Page;
            case COVER_STYLE_CLASSIC_V2:
            default: // 异常 prefs 值也回落新经典（旧四风格已下线）
                if (classicV2Page == null) {
                    com.jingxin.jingxinmusic.stage.ClassicPageV2 p =
                            new com.jingxin.jingxinmusic.stage.ClassicPageV2(sceneHelper);
                    p.setActions(buildV2Actions());
                    classicV2Page = p;
                    installV2Page(p);
                }
                return classicV2Page;
        }
    }

    /**
     * 根据当前 isLandscapeMode 和 coverStyle 切换到正确的 Scene
     * @return 是否发生了 Scene 切换
     */
    private boolean switchScene() {
        int style = effectiveCoverStyle();
        com.jingxin.jingxinmusic.stage.StagePage target = ensurePage(style);
        if (target != currentScene) {
            currentScene.onStyleExit();
            currentScene.exit();
            currentScene = target;
            // 进入沉浸模式（含V2）：不支持全屏歌词，若当前为全屏则降级为多行
            if (style == COVER_STYLE_IMMERSIVE_V2 && lyricView != null
                    && lyricView.getDisplayMode() == com.jingxin.jingxinmusic.view.LyricView.DisplayMode.FULL) {
                lyricView.setDisplayMode(com.jingxin.jingxinmusic.view.LyricView.DisplayMode.MULTI_LINE);
            }
            currentScene.enter();
            enteredAsLandscape = isLandscapeMode;
            currentScene.onStyleEnter();
            return true;
        }
        return false;
    }

    /**
     * 当前应展示的封面风格：电台走专用星空收音机页，漂浮收音机即选台器
     *（用户偏好仍保存在 coverStyle/prefs，离开电台自动恢复）。
     */
    private int effectiveCoverStyle() {
        return (song != null && song.sourceType == Song.SOURCE_RADIO)
                ? COVER_STYLE_RADIO_V2 : coverStyle;
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
        sceneLayout(width, height);
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

        // 电台：直播流没有歌词——不搜索（防台名误匹配到无关歌词），并清空 V2 页上一首遗留歌词
        if (song.sourceType == Song.SOURCE_RADIO) {
            stateHub.setLyricData(null);
            return;
        }

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
     * 将品牌图标(ic_launcher)转为Bitmap并设置模糊背景
     * 确保无在线封面时播放页面也有模糊背景效果
     */
    private void applyDefaultCoverBlur() {
        if (isActivityGone()) return;
        executor.execute(() -> {
            try {
                Drawable drawable = ContextCompat.getDrawable(requireContext(), R.mipmap.ic_launcher);
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
        com.jingxin.jingxinmusic.stage.StagePage.Actions base = buildV2Actions();
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
            @Override public void openLyricEffect() { base.openLyricEffect(); }
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
            @Override public void playSongAtPosition(int queueIndex) { base.playSongAtPosition(queueIndex); }
            @Override public void openLyricEffectRemembered() { base.openLyricEffectRemembered(); }
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
    private com.jingxin.jingxinmusic.stage.StagePage.Actions buildV2Actions() {
        return new com.jingxin.jingxinmusic.stage.StagePage.Actions() {
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
            @Override public void openLyricEffect() {
                PlayerFragment.this.showLyricEffectPicker();
            }
            @Override public void openLyricEffectRemembered() {
                PlayerFragment.this.enterRememberedLyricEffect();
            }
            @Override public boolean isFavorite() { return PlayerFragment.this.isFavorite; }
            @Override public boolean isBiliSong() { return song != null && song.sourceType == Song.SOURCE_BILI; }
            @Override public boolean isBiliDownloadEnabled() { return biliDownloadEnabled; }
            @Override public void exitPlayerPage() {
                stopSpectrum();
                requireActivity().onBackPressed();
            }
            @Override public void switchOutfitStyle() { PlayerFragment.this.showStylePicker(); }
            @Override public void playSongAtPosition(int queueIndex) {
                // 拼贴墙点卡绝对定位播放：内部换 delta 走 playSongAt 链（列表桥/spectrum/歌词/收藏全刷新）
                PlayerFragment.this.playSongAtPosition(queueIndex);
            }
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

    /** 收藏态/B站下载键态变化 → 通知当前活跃页刷新控制条附加态 */
    private void notifyActivePageControlExtras() {
        com.jingxin.jingxinmusic.stage.StagePage p = activePage();
        if (p != null) p.notifyControlExtrasChanged();
    }

    /**
     * 弹出播放风格选择浮窗（装扮键由循环切换改造）：样式与音效/动效弹窗一致——
     * 贴左垂直居中、左直角右圆角深底、当前风格青色发光高亮，点击即切
     */
    /**
     * 弹窗 toggle 守卫（死窗自愈）。
     * 返回 true = 弹窗真实可见（isShowing 且内容仍挂在窗口上）→ 本次点击语义为"收起"，调用方 return。
     * 返回 false = 无弹窗，或窗已被外部杀死（车机熄屏/系统弹窗夺焦后 isShowing 可能滞留 true
     * 而画面已消失）→ 已代为 dismiss 清理，调用方继续走全新创建+展示，点击立即恢复生效。
     * 隐患背景：车机上弹窗窗体被系统移除后 isShowing 不清零，旧守卫会把点击吞成
     * 无效果的 dismiss（一次事件可同时废掉全部顶栏弹窗按钮，重进页面重建 Fragment 才恢复）。
     */
    private boolean togglePopupClose(android.widget.PopupWindow popup) {
        if (popup != null && popup.isShowing()) {
            boolean alive = popup.getContentView() != null && popup.getContentView().isAttachedToWindow();
            try { popup.dismiss(); } catch (Exception ignored) {}
            return alive;
        }
        return false;
    }

    private void showStylePicker() {
        if (togglePopupClose(stylePickerPopup)) return;

        Context ctx = requireContext();
        int raw = coverStyle - COVER_STYLE_CLASSIC_V2;
        if (raw < 0 || raw >= STYLE_V2_NAMES.length) raw = 0; // 旧风格异常态归位经典
        final int current = raw;
        float sf = panelScaleFactor(STYLE_V2_NAMES.length);

        android.widget.LinearLayout container = buildPanelContainer(ctx, "播放风格", sf);
        android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < STYLE_V2_NAMES.length; i++) {
            final int pick = i;
            list.addView(buildPanelItem(ctx, STYLE_V2_NAMES[i], i == current, false, sf, v -> {
                stylePickerPopup.dismiss();
                if (pick != current) switchToCoverStyle(COVER_STYLE_CLASSIC_V2 + pick);
            }));
        }
        stylePickerPopup = new android.widget.PopupWindow();
        showPanelPopup(stylePickerPopup, ctx, container, list, sf);
    }

    /** 切换到指定播放风格（风格弹窗收口：持久化 + 场景重排 + 频谱环类归位） */
    private void switchToCoverStyle(int style) {
        coverStyle = style;
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
                com.jingxin.jingxinmusic.stage.StagePage ap = activePage();
                if (ap != null) ap.applySpectrumStyle(com.jingxin.jingxinmusic.view.SpectrumView.STYLE_BAR);
                // 样式变更影响布局（环→条），重排版
                sceneLayout(getLayoutWidth(), getAvailableScreenHeight());
            }
        }
        updateThemeUI(); // 切换封面风格后更新背景（光晕/遮罩等）
        // 切换封面风格后，若不在「双色光晕+非沉浸」组合则恢复自定义歌词高亮色
        if (!(isAuroraActive()) && lyricView != null) {
            lyricView.restoreHighlightColor();
        }
        sceneLayout(getLayoutWidth(), getAvailableScreenHeight());

        // 重新加载封面
        loadCover();

        android.widget.Toast.makeText(requireContext(), STYLE_V2_NAMES[coverStyle - COVER_STYLE_CLASSIC_V2],
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 弹出频谱选择浮窗（公共模板：单列；圆环类在沉浸模式下禁用灰显）
     */
    private void showSpectrumPicker() {
        if (togglePopupClose(spectrumPickerPopup)) return;

        String[] names = com.jingxin.jingxinmusic.view.SpectrumView.STYLE_NAMES;
        int currentStyle = spectrumView.getCurrentStyle(); // 真值=prefs（实例构造即恢复）
        Context ctx = requireContext();
        float sf = panelScaleFactor(names.length);

        // 展示顺序：渐变柱状置顶（主推样式），其余按常量原序；
        // 仅影响弹窗排列，样式常量值与 prefs 持久化语义不变（老存档不受影响）
        final int[] displayOrder = new int[names.length];
        displayOrder[0] = com.jingxin.jingxinmusic.view.SpectrumView.STYLE_GRADIENT;
        for (int i = 0, j = 1; i < names.length; i++) {
            if (i != com.jingxin.jingxinmusic.view.SpectrumView.STYLE_GRADIENT) {
                displayOrder[j++] = i;
            }
        }

        android.widget.LinearLayout container = buildPanelContainer(ctx, "频谱选择", sf);
        android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int k = 0; k < displayOrder.length; k++) {
            final int style = displayOrder[k];
            // 沉浸模式下圆环类灰色不可选
            final boolean disabled = !currentScene.shouldShowSpectrumButton(style);
            list.addView(buildPanelItem(ctx, names[style], style == currentStyle, disabled, sf, v -> {
                if (disabled) return;
                boolean wasRing = spectrumView.isCoverOverlayMode();
                spectrumView.setStyle(style); // setStyle 写 prefs 持久化（真值唯一来源）
                // 样式同步到当前活跃页自持实例（sceneLayout 会重新收敛可见性）
                com.jingxin.jingxinmusic.stage.StagePage ap = activePage();
                if (ap != null) ap.applySpectrumStyle(style);
                boolean isRing = spectrumView.isCoverOverlayMode();
                if (wasRing != isRing) {
                    int w = getLayoutWidth();
                    int h = getAvailableScreenHeight();
                    sceneLayout(w, h);
                }
                spectrumPickerPopup.dismiss();
            }));
        }
        spectrumPickerPopup = new android.widget.PopupWindow();
        showPanelPopup(spectrumPickerPopup, ctx, container, list, sf);
    }

    /**
     * 弹出音效预设选择浮窗（公共模板：单列、青色发光选中）
     */
    private void showEffectPickerPopup() {
        if (togglePopupClose(effectPickerPopup)) return;

        String[] names = com.jingxin.jingxinmusic.util.AudioEffectManager.PRESET_NAMES;
        Context ctx = requireContext();
        int currentPreset = SettingsFragment.getAudioEffectPreset(requireContext());
        float sf = panelScaleFactor(names.length);

        android.widget.LinearLayout container = buildPanelContainer(ctx, "音效选择", sf);
        android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < names.length; i++) {
            final int preset = i;
            list.addView(buildPanelItem(ctx, names[i], i == currentPreset, false, sf, v -> {
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
            }));
        }
        effectPickerPopup = new android.widget.PopupWindow();
        showPanelPopup(effectPickerPopup, ctx, container, list, sf);
    }

    /**
     * 启动直达歌词动效：读取上次记忆的效果索引（lyric_effect_mode）直接进入。
     * 仅歌单就绪后调用一次；已在效果页（isActive）防御跳过。
     */
    private void enterRememberedLyricEffect() {
        if (lyricEffectManager == null || lyricEffectManager.isActive()) return;
        // 拼贴页：动效页全屏沉浸，卡内频谱隐藏
        com.jingxin.jingxinmusic.stage.StagePage cp = activePage();
        if (cp instanceof com.jingxin.jingxinmusic.stage.CollagePageV2) {
            ((com.jingxin.jingxinmusic.stage.CollagePageV2) cp).setEffectPageActive(true);
        }
        com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect[] effects = {
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.STARFIELD,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.GLOW,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.PENDELO,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.CADENZA,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.TILT,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.PARTITA,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.FUME,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.DIORAMA,
        };
        int idx = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_LYRIC_EFFECT_MODE, 0);
        if (idx < 0 || idx >= effects.length) idx = 0;
        lyricEffectManager.attach(effects[idx]);
    }

        /**
     * 弹出歌词动效选择浮窗：星空 / 流光（样式与音效弹窗一致：贴左垂直居中、青色发光选中）
     */
    private void showLyricEffectPicker() {
        if (togglePopupClose(lyricEffectPickerPopup)) return;
        if (lyricEffectManager == null || lyricEffectManager.isActive()) return;

        String[] names = {"星空", "流光", "时计", "心象", "倾诉", "云阶", "浮名", "镜台"};
        final com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect[] effects = {
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.STARFIELD,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.GLOW,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.PENDELO,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.CADENZA,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.TILT,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.PARTITA,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.FUME,
                com.jingxin.jingxinmusic.lyriceffect.LyricEffectManager.Effect.DIORAMA,
        };

        Context ctx = requireContext();
        int current = ctx.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_LYRIC_EFFECT_MODE, 0);
        if (current < 0 || current >= names.length) current = 0;
        float sf = panelScaleFactor(names.length);

        android.widget.LinearLayout container = buildPanelContainer(ctx, "歌词动效", sf);
        android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < names.length; i++) {
            final int pick = i;
            list.addView(buildPanelItem(ctx, names[i], i == current, false, sf, v -> {
                lyricEffectPickerPopup.dismiss();
                if (lyricEffectManager != null && !lyricEffectManager.isActive()) {
                    ctx.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_LYRIC_EFFECT_MODE, pick).apply();
                    // 拼贴页：动效页全屏沉浸，卡内频谱隐藏
                    com.jingxin.jingxinmusic.stage.StagePage cpk = activePage();
                    if (cpk instanceof com.jingxin.jingxinmusic.stage.CollagePageV2) {
                        ((com.jingxin.jingxinmusic.stage.CollagePageV2) cpk).setEffectPageActive(true);
                    }
                    lyricEffectManager.attach(effects[pick]);
                }
            }));
        }
        lyricEffectPickerPopup = new android.widget.PopupWindow();
        showPanelPopup(lyricEffectPickerPopup, ctx, container, list, sf);
    }

    /**
     * 弹出歌词搜索浮窗：加载状态 → 候选列表 → 点击下载
     */
    private int titleBottomSpace(float scaledDensityPx) {
        // 标题行高 + 标题底 padding + 容器上下 padding（列表区可用高扣除项）
        return (int) ((18 * 1.6f + 12 + 20) * scaledDensityPx);
    }

    // ==================== 选择类弹窗公共模板（装扮/频谱/音效/动效四窗同构） ====================
    // 视觉规格唯一事实源：容器 pad10/20%黑/左直角右圆角 r16、标题 18sp、item 16sp/140dp宽/
    // 三态色（选中青色发光/禁用暗灰/普通灰底）/焦点态；超高屏 maxSf 收缩 + 列表区限高 80% 滚动。

    /** 缩放系数：小屏保持 1，高分辨率车机按屏高等比放大（横竖屏锚点 0.03/0.0188，基准 16sp）；超高屏按内容高反算上限收缩 */
    private float panelScaleFactor(int itemCount) {
        float density = getResources().getDisplayMetrics().density;
        int viewH = getAvailableScreenHeight();
        if (viewH <= 0) viewH = getResources().getDisplayMetrics().heightPixels;
        float basePx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
        boolean landscape = getLayoutWidth() > viewH * 1.2f;
        float sf = Math.max(1f, viewH * (landscape ? 0.03f : 0.0188f) / basePx);
        // 超高自适应收缩：单列弹窗总高与 sf 线性相关，按内容高系数反算上限，
        // 保证弹窗完整显示（源自音效弹窗防御，横屏矮屏下底部选项不被裁掉）
        float heightFactor = 20f /*容器上下padding*/ + 18f * 1.33f /*标题*/ + 12f /*标题底距*/
                + itemCount * (16f * 1.33f + 28f /*条目上下padding*/ + 8f /*条目上下margin*/);
        float maxSf = viewH * 0.85f / (heightFactor * density);
        if (sf > maxSf) sf = maxSf;
        return sf;
    }

    /** 标准面板容器：左直角右圆角 20% 黑、pad10、含标题（18sp 钉顶） */
    private android.widget.LinearLayout buildPanelContainer(Context ctx, String titleText, float sf) {
        float density = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(
                (int)(10 * density * sf), (int)(10 * density * sf),
                (int)(10 * density * sf), (int)(10 * density * sf));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        float r = 16 * density * sf;
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        bg.setColor(Color.argb(51, 30, 30, 30));
        container.setBackground(bg);

        android.widget.TextView title = new android.widget.TextView(ctx);
        title.setText(titleText);
        title.setTextColor(ThemeColors.sparkColor(isNightMode));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18 * density * sf);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, (int)(12 * density * sf));
        container.addView(title);
        return container;
    }

    /** 单列选项 item：140dp 定宽、焦点态、三态底色（disabled 优先于 selected） */
    private android.widget.TextView buildPanelItem(Context ctx, String text, boolean selected,
                                                   boolean disabled, float sf,
                                                   View.OnClickListener onClick) {
        float density = getResources().getDisplayMetrics().density;
        android.widget.TextView item = new android.widget.TextView(ctx);
        item.setText(text);
        item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16 * density * sf);
        item.setGravity(android.view.Gravity.CENTER);
        item.setPadding(
                (int)(20 * density * sf), (int)(14 * density * sf),
                (int)(20 * density * sf), (int)(14 * density * sf));
        item.setFocusable(true);
        CompatUtil.setForegroundCompat(item, ctx.getResources().getDrawable(R.drawable.tv_focus_popup_item));
        if (disabled) {
            item.setTextColor(ThemeColors.SPECTRUM_POPUP_DISABLED_TEXT);
            item.setBackgroundColor(Color.argb(153, 42, 42, 42));  // 透明度60%
        } else if (selected) {
            item.setTextColor(ThemeColors.SPECTRUM_POPUP_SELECTED_TEXT);
            item.setBackgroundColor(Color.argb(153, 0, 230, 180));  // 透明度60%
        } else {
            item.setTextColor(ThemeColors.SPECTRUM_POPUP_NORMAL_TEXT);
            item.setBackgroundColor(Color.argb(153, 68, 68, 68));  // 透明度60%
        }
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                (int)(140 * density * sf), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int)(6 * density * sf), (int)(4 * density * sf),
                (int)(6 * density * sf), (int)(4 * density * sf));
        item.setLayoutParams(lp);
        item.setOnClickListener(onClick);
        return item;
    }

    /** 统一收尾：列表区装入限高 ScrollView（80% 屏高-标题，超出滚动）→ PopupWindow 左贴边垂直居中 */
    private void showPanelPopup(android.widget.PopupWindow popup, Context ctx,
                                android.widget.LinearLayout container,
                                android.widget.LinearLayout list, float sf) {
        float density = getResources().getDisplayMetrics().density;
        int viewH = getAvailableScreenHeight();
        if (viewH <= 0) viewH = getResources().getDisplayMetrics().heightPixels;

        int maxListH = (int) (viewH * 0.80f);
        android.widget.ScrollView scroller = new android.widget.ScrollView(ctx);
        scroller.setFillViewport(false);
        scroller.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        scroller.addView(list, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        container.addView(scroller, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        // 提前量列表内容高：超出上限则把 ScrollView 裁到余量（标题不滚，弹窗 80% 上限内整体居中）
        list.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED);
        int contentH = list.getMeasuredHeight();
        int maxScrollH = maxListH - titleBottomSpace(density * sf);
        if (contentH > maxScrollH) {
            android.widget.LinearLayout.LayoutParams sLp =
                    (android.widget.LinearLayout.LayoutParams) scroller.getLayoutParams();
            sLp.height = maxScrollH;
            scroller.setLayoutParams(sLp);
        }

        popup.setContentView(container);
        popup.setWidth(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        popup.setHeight(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT <= 22) {
            // 5.1：background为null时不创建PopupViewContainer，点外部无法dismiss，设透明背景触发容器创建
            popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        popup.setElevation(8 * density * sf);
        if (mRootView != null) {
            popup.showAtLocation(mRootView,
                    android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL, 0, 0);
        }
    }

    private void showLyricSearchPopup() {
        if (togglePopupClose(lyricSearchPopup)) return;
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

    /**
     * 双色光晕是否生效（所有封面模式均生效，包括沉浸模式）
     */
    private boolean isAuroraActive() {
        return bgSkinMode == 1;
    }

    private void updateThemeUI() {
        // 同步日夜模式到 sceneHelper
        if (sceneHelper != null) sceneHelper.isNightMode = isNightMode;
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
            // 清旧 B站/WebDAV 标记：恢复分派 from_webdav 优先级高，
            // 残留会把 folder 劫持到 B 站队列（上次播放记忆错乱的实锤根因）
            editor.remove("from_webdav");
        } else if ("webdav".equals(playlistMode) || "bili".equals(playlistMode) || "radio".equals(playlistMode)) {
            // WebDAV/B站/电台模式：保存标记，播放列表已在webdav_playlist SharedPreferences中
            editor.putBoolean("from_webdav", true);
        } else {
            // 其他模式清除旧数据
            editor.remove("folder_song_paths");
            editor.remove("from_webdav");
        }
        editor.apply();
    }

    /**
     * 频谱：统一经 SpectrumEngine 采集（Visualizer 主案 / AudioRecord 麦克风降级），
     * 采集/重试/双缓冲/跨端抢占仲裁全部在引擎内完成
     */
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

    /** 引擎帧消费：低频包络（时计摆轮，仅播放流）+ 当前舞台页分发 */
    private final SpectrumEngine.FrameSink engineSink = new SpectrumEngine.FrameSink() {
        @Override
        public int getBarCount() {
            return spectrumView != null ? spectrumView.getBarInputCount() : 0;
        }

        @Override
        public void onSpectrumFrame(float[] magnitudes, float maxMag, int source) {
            // 低频包络（时计摆轮）：前 4 频段幅值均值，不依赖频谱开关，仅播放流（麦克风降级不喂）
            if (source == SpectrumEngine.SOURCE_PLAYBACK && magnitudes.length >= 4) {
                float bassSum = 0;
                for (int i = 0; i < 4; i++) bassSum += magnitudes[i];
                // 归一到 0~1（byte 幅度上限 ~127*sqrt2≈180，取 /60 软钳）
                float b = Math.min(1f, (bassSum / 4) / 60f);
                float prev = bassEnergySmoothed;
                // 快起慢落包络（attack 0.35 / release 0.15 每 20ms 采样近似）
                bassEnergySmoothed = b > prev ? prev + (b - prev) * 0.45f : prev + (b - prev) * 0.18f;
                bassLastUpdateMs = System.currentTimeMillis();
            }
            if (mRootView != null) {
                mRootView.post(() -> feedSpectrumData(magnitudes, maxMag));
            }
        }
    };

    private void startSpectrum() {
        if (!isAdded()) return;
        SpectrumEngine.Result r = SpectrumEngine.getInstance().request(
                requireContext(), SpectrumEngine.OWNER_PLAYER,
                () -> (bound && playerBinder != null) ? playerBinder.getAudioSessionId() : -1,
                engineSink, true);
        if (r == SpectrumEngine.Result.NEED_MIC_PERMISSION) {
            // AudioRecord 降级需要 RECORD_AUDIO（Visualizer 不需要），申请后授权回调重启
            androidx.core.app.ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.RECORD_AUDIO}, 100);
        }
    }

    private void stopSpectrum() {
        SpectrumEngine.getInstance().release(SpectrumEngine.OWNER_PLAYER);
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
        // 场景页/效果页 Hub 观察者注销：原缺此步，旧页（含 aurora 引用）滞留
        // PlayerStateHub 观察者列表，封面颜色广播将已销毁实例反复唤醒——幽灵复活，
        // 乐酷 resize 连续重建下多代页面对象累积泄漏（cx 日志 13:58:13 六连复活实锤）。
        // 仅注销不置 null：卸载态调用点（setCover 等）无判空，stale 安全、null 会 NPE
        com.jingxin.jingxinmusic.stage.StagePage[] pages =
                {classicV2Page, immersiveV2Page, recordV2Page, carouselV2Page};
        for (com.jingxin.jingxinmusic.stage.StagePage p : pages) if (p != null) p.exit();
        if (lyricEffectManager != null) lyricEffectManager.detach();
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