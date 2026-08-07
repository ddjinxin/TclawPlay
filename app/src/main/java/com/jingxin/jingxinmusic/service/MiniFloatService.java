package com.jingxin.jingxinmusic.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.jingxin.jingxinmusic.MainActivity;
import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.util.CoverFetcher;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.util.LyricFetcher;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.view.SpectrumView;

import java.io.File;
import java.util.List;

/**
 * 悬浮迷你播放窗服务
 * 当静心音乐退到后台时显示悬浮窗，回到前台时自动隐藏
 */
public class MiniFloatService extends Service {

    private static final String TAG = "MiniFloatService";
    private static final String CHANNEL_ID = "mini_float_channel";
    private static final int NOTIFICATION_ID = 2001;
    private static final int PROGRESS_UPDATE_INTERVAL = 200; // ms，与播放页一致

    // 悬浮窗样式模式
    private static final int MODE_CLASSIC = 0;  // 经典模式
    private static final int MODE_CAPSULE = 1;  // 胶囊（灵动岛）模式
    private static final String PREF_STYLE_MODE = "float_style_mode";

    // 胶囊模式尺寸常量（以 280 为基准，与经典模式一致）
    private static final float CAPSULE_UNIT_RATIO = 0.35f;  // 胶囊默认 unit 比例（屏宽 × 35% / 280）
    private static final float CAPSULE_UNIT_MIN = 0.15f;    // 胶囊最小 unit 比例
    private static final float CAPSULE_UNIT_MAX = 0.40f;    // 胶囊最大 unit 比例
    private static final float CAPSULE_LYRIC_SPAN_MIN = 80f;   // 歌词区最小宽度（×unit）
    private static final float CAPSULE_LYRIC_SPAN_MAX = 400f;  // 歌词区最大宽度（×unit）
    private static final float CAPSULE_LYRIC_SPAN_DEFAULT = 172f; // 默认歌词区宽度

    private WindowManager windowManager;
    private View floatView;
    private WindowManager.LayoutParams floatParams;

    // 封面加载线程池
    private java.util.concurrent.ExecutorService coverExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // 视图引用
    private ImageView coverImage;
    private TextView tvTitle;
    private TextView tvArtist;
    private TextView tvLyric;
    private ProgressBar progressBar;
    private ImageView btnPrev;
    private ImageView btnPlayPause;
    private ImageView btnNext;
    private android.view.View sizeAdjustPanel; // 尺寸调节面板
    private boolean isAdjustingSize = false;   // 是否正在调节尺寸
    private LinearLayout rootLayout;           // 悬浮窗根布局（用于更新透明度）
    private int currentBgAlpha;                // 当前背景透明度 (0-255)

    // 播放服务
    private MusicPlayerService.MusicPlayerBinder playerBinder;
    private boolean bound = false;

    // 歌词
    private volatile KrcParser.LyricData lyricData;
    private String currentLyricTitle = "";
    private String currentLyricArtist = "";

    // 封面旋转动画
    private com.jingxin.jingxinmusic.util.CoverRotationHelper coverRotationHelper = new com.jingxin.jingxinmusic.util.CoverRotationHelper();

    // 进度更新
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            uiHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL);
        }
    };

    // 拖动
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;

    // 双击关闭
    private long lastClickTime = 0;
    private Runnable pendingSingleClick;
    private static final int DOUBLE_CLICK_INTERVAL = 300; // ms

    // 广播
    private BroadcastReceiver songChangedReceiver;

    // 主题
    private boolean isNightMode = true;

    // 悬浮窗宽度（像素），按屏幕宽度比例计算，内部元素按此比例缩放
    private int floatWidthPx;
    private float unit; // 比例因子 = floatWidthPx / 280.0f
    private boolean lastIsPortrait; // 上次的屏幕方向，用于检测变化

    // 缩放比例范围
    private static final float FLOAT_SIZE_MIN = 0.20f; // 最小20%
    private static final float FLOAT_SIZE_MAX = 0.60f; // 最大60%
    private static final float FLOAT_SIZE_STEP = 0.05f; // 每次步进5%
    private static final int DEFAULT_BG_ALPHA = 204; // 0xCC，默认80%不透明度

    // ========== 胶囊模式字段 ==========
    private int floatMode = MODE_CLASSIC;     // 当前样式模式
    private float capsuleLyricSpan;           // 歌词/频谱区域宽度（像素）
    private SpectrumView capsuleSpectrum;     // 胶囊内频谱视图
    private Visualizer visualizer;            // 音频可视化器
    private boolean visualizerEnabled = false;
    private FrameLayout capsuleCenterLayout; // 歌词+频谱中间区域

    @Override
    public void onCreate() {
        super.onCreate();

        ThemeColors.init(this);
        isNightMode = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("isNight", true);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        CompatUtil.safeStartForeground(this, NOTIFICATION_ID, buildNotification());

        lastIsPortrait = isCurrentPortrait();
        floatMode = getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                .getInt(PREF_STYLE_MODE, MODE_CLASSIC);
        if (floatMode == MODE_CAPSULE) {
            floatView = buildCapsuleView();
        } else {
            floatView = buildFloatView();
        }
        addFloatView();

        // 绑定播放服务
        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 注册切歌广播
        songChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (MusicPlayerService.ACTION_SONG_CHANGED.equals(action)) {
                    onSongChanged(intent);
                } else if (MusicPlayerService.ACTION_PLAY_STATE_CHANGED.equals(action)) {
                    boolean playing = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_PLAYING, false);
                    updatePlayPauseButton(playing);
                    updateCoverRotation(playing);
                    updateSpectrumPlaying(playing);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(MusicPlayerService.ACTION_SONG_CHANGED);
        filter.addAction(MusicPlayerService.ACTION_PLAY_STATE_CHANGED);
        CompatUtil.safeRegisterReceiver(this, songChangedReceiver, filter);

        // 启动进度更新
        uiHandler.post(progressRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(progressRunnable);
        releaseVisualizer();
        coverRotationHelper.release();
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        try { unregisterReceiver(songChangedReceiver); } catch (Exception ignored) {}
        if (floatView != null && floatView.isAttachedToWindow()) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ========== 前台通知 ==========

    private void createNotificationChannel() {
        com.jingxin.jingxinmusic.util.NotificationHelper.createChannel(
                this, CHANNEL_ID, "悬浮播放窗", "悬浮迷你播放窗服务");
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("静心音乐")
                .setContentText("悬浮播放窗运行中")
                .setSmallIcon(R.drawable.ic_music_icon)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ========== 悬浮窗视图构建 ==========

    private View buildFloatView() {
        // 按屏幕方向计算悬浮窗宽度，所有内部元素按比例缩放
        android.util.DisplayMetrics screenMetrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(screenMetrics);
        boolean isPortrait = screenMetrics.widthPixels < screenMetrics.heightPixels;
        // 使用保存的缩放比例，默认竖屏40%、横屏30%
        floatWidthPx = (int) (screenMetrics.widthPixels * getSavedFloatSizeRatio());
        unit = floatWidthPx / 280.0f; // 以280dp为基准的比例因子

        // 颜色
        int bgColor = isNightMode ? ThemeColors.nightCardBg() : ThemeColors.dayCardBg();
        int bgEndColor = isNightMode ? ThemeColors.nightCardBgEnd() : ThemeColors.dayCardBgEnd();
        int textPrimary = isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        int textSecondary = isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary();
        int lyricColor = isNightMode ? ThemeColors.nightLyricCurrent() : ThemeColors.dayLyricCurrent();
        int iconColor = isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();

        // 外层圆角卡片（水平布局：左封面 + 右信息）
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.setPadding((int)(12 * unit), (int)(10 * unit), (int)(12 * unit), (int)(10 * unit));
        rootLayout.setGravity(Gravity.CENTER_VERTICAL);

        // 圆角半透明渐变背景（透明度从持久化读取）
        currentBgAlpha = getSavedBgAlpha();
        applyRootBackground(bgColor, bgEndColor, currentBgAlpha, rootLayout);

        // ===== 左侧：旋转封面 =====
        int coverSize = (int)(65 * unit);
        LinearLayout coverWrap = new LinearLayout(this);
        coverWrap.setOrientation(LinearLayout.VERTICAL);
        coverWrap.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams coverWrapParams = new LinearLayout.LayoutParams(
                coverSize, coverSize);
        coverWrapParams.setMarginEnd((int)(10 * unit));

        coverImage = new ImageView(this);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(coverSize, coverSize);
        coverImage.setLayoutParams(coverParams);
        coverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // 初始显示圆形默认封面，避免方形闪烁
        setCircularCover(BitmapFactory.decodeResource(getResources(), R.drawable.ic_music_icon));

        // 旋转动画
        coverRotationHelper.attach(coverImage);
        // 先不启动，等数据加载后再判断

        coverWrap.addView(coverImage);
        // 点击封面弹出尺寸调节面板，长按封面切换模式
        coverWrap.setOnClickListener(v -> showSizeAdjustPanel());
        coverWrap.setOnLongClickListener(v -> {
            switchFloatMode(MODE_CAPSULE);
            return true;
        });
        rootLayout.addView(coverWrap, coverWrapParams);

        // ===== 右侧：信息区域（垂直四行） =====
        LinearLayout infoLayout = new LinearLayout(this);
        infoLayout.setOrientation(LinearLayout.VERTICAL);
        infoLayout.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.weight = 1f;

        // 第一行：歌曲名 + 歌手名
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        tvTitle = new TextView(this);
        tvTitle.setTextColor(textPrimary);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13 * unit);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setMaxLines(1);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMarginEnd((int)(6 * unit));

        tvArtist = new TextView(this);
        tvArtist.setTextColor(textSecondary);
        tvArtist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 11 * unit);
        tvArtist.setMaxLines(1);
        tvArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);

        titleRow.addView(tvTitle, titleParams);
        titleRow.addView(tvArtist);
        infoLayout.addView(titleRow);

        // 第二行：当前歌词
        tvLyric = new TextView(this);
        tvLyric.setTextColor(isNightMode ? ThemeColors.nightLyricCurrent() : ThemeColors.FLOAT_LYRIC_DAY_UNPLAYED);
        tvLyric.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16 * unit);
        tvLyric.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLyric.setMaxLines(1);
        tvLyric.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lyricParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lyricParams.topMargin = (int)(2 * unit);
        infoLayout.addView(tvLyric, lyricParams);

        // 第三行：进度条
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setProgressDrawable(buildProgressDrawable(isNightMode));
        progressBar.setMax(1);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(3 * unit));
        progressParams.topMargin = (int)(4 * unit);

        infoLayout.addView(progressBar, progressParams);

        // 第四行：上一曲、播放/暂停、下一曲
        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        controlRowParams.topMargin = (int)(4 * unit);

        btnPrev = new ImageView(this);
        btnPrev.setImageResource(R.drawable.ic_previous);
        btnPrev.setColorFilter(iconColor);
        LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams((int)(24 * unit), (int)(24 * unit));
        prevParams.setMarginEnd((int)(40 * unit));
        btnPrev.setOnClickListener(v -> {
            if (bound && playerBinder != null) playerBinder.playPrevious();
        });

        btnPlayPause = new ImageView(this);
        btnPlayPause.setImageResource(R.drawable.ic_pause);
        btnPlayPause.setColorFilter(iconColor);
        LinearLayout.LayoutParams ppParams = new LinearLayout.LayoutParams((int)(28 * unit), (int)(28 * unit));
        ppParams.setMarginEnd((int)(40 * unit));
        btnPlayPause.setOnClickListener(v -> {
            if (bound && playerBinder != null) playerBinder.togglePlayPause();
        });

        btnNext = new ImageView(this);
        btnNext.setImageResource(R.drawable.ic_next);
        btnNext.setColorFilter(iconColor);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams((int)(24 * unit), (int)(24 * unit));
        btnNext.setOnClickListener(v -> {
            if (bound && playerBinder != null) playerBinder.playNext();
        });

        controlRow.addView(btnPrev, prevParams);
        controlRow.addView(btnPlayPause, ppParams);
        controlRow.addView(btnNext, nextParams);
        infoLayout.addView(controlRow, controlRowParams);

        rootLayout.addView(infoLayout, infoParams);

        // ===== 外层 FrameLayout 包裹（用于放置右上角关闭按钮） =====
        FrameLayout container = new FrameLayout(this);
        container.addView(rootLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        // 右上角关闭按钮
        ImageView btnClose = new ImageView(this);
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setColorFilter(textSecondary);
        int closeSize = (int)(20 * unit);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(closeSize, closeSize);
        closeParams.gravity = Gravity.END | Gravity.TOP;
        closeParams.setMargins((int)(4 * unit), (int)(4 * unit), (int)(4 * unit), 0);
        btnClose.setLayoutParams(closeParams);
        btnClose.setOnClickListener(v -> stopSelf());
        container.addView(btnClose);

        // ===== 尺寸调节面板（默认隐藏，点击封面弹出） =====
        sizeAdjustPanel = buildSizeAdjustPanel(textPrimary);
        sizeAdjustPanel.setVisibility(android.view.View.GONE);
        container.addView(sizeAdjustPanel);

        // ===== 单击回app / 双击关闭悬浮窗 =====
        rootLayout.setOnClickListener(v -> {
            // 点击逻辑移到 onTouch 的 ACTION_UP 中处理
        });

        // ===== 拖动 + 点击/双击 =====
        rootLayout.setOnTouchListener((v, event) -> {
            int action = event.getAction() & MotionEvent.ACTION_MASK;

            // 尺寸调节面板显示时，拦截所有触摸交给面板处理
            if (sizeAdjustPanel != null && sizeAdjustPanel.getVisibility() == android.view.View.VISIBLE) {
                return false; // 让面板自己处理
            }

            // 触摸点在封面上时，不拦截，让 coverWrap 的 onClick 触发
            if (action == MotionEvent.ACTION_DOWN) {
                if (isTouchOnCover(event)) {
                    return false;
                }
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    initialX = floatParams.x;
                    initialY = floatParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    isDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // 单指拖动
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true;
                    floatParams.x = initialX + (int) dx;
                    floatParams.y = initialY + (int) dy;
                    windowManager.updateViewLayout(floatView, floatParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        long now = System.currentTimeMillis();
                        if (now - lastClickTime < DOUBLE_CLICK_INTERVAL) {
                            // 双击：取消待执行的单击，关闭悬浮窗
                            if (pendingSingleClick != null) {
                                uiHandler.removeCallbacks(pendingSingleClick);
                                pendingSingleClick = null;
                            }
                            lastClickTime = 0;
                            stopSelf();
                        } else {
                            // 第一次点击：延迟执行单击，等双击窗口期
                            lastClickTime = now;
                            pendingSingleClick = () -> {
                                Intent mainIntent = new Intent(MiniFloatService.this, MainActivity.class);
                                mainIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(mainIntent);
                                pendingSingleClick = null;
                            };
                            uiHandler.postDelayed(pendingSingleClick, DOUBLE_CLICK_INTERVAL);
                        }
                        v.performClick();
                    } else {
                        // 拖动结束，保存位置
                        saveFloatPosition();
                    }
                    return true;
            }
            return false;
        });

        return container;
    }

    private android.graphics.drawable.Drawable buildProgressDrawable(boolean night) {
        int progressColor = night ? ThemeColors.nightTabIndicator() : ThemeColors.dayTabIndicator();
        int bgColor = night ? ThemeColors.PROGRESS_BG_NIGHT : ThemeColors.PROGRESS_BG_DAY;

        // 背景
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius((int)(2 * unit));
        background.setColor(bgColor);

        // 进度（用ClipDrawable包裹才能按比例显示）
        GradientDrawable progressShape = new GradientDrawable();
        progressShape.setCornerRadius((int)(2 * unit));
        progressShape.setColor(progressColor);
        android.graphics.drawable.ClipDrawable clipProgress =
                new android.graphics.drawable.ClipDrawable(progressShape,
                        android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);

        android.graphics.drawable.LayerDrawable layerDrawable =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{background, clipProgress});
        layerDrawable.setId(0, android.R.id.background);
        layerDrawable.setId(1, android.R.id.progress);

        return layerDrawable;
    }

    // ========== 添加/移除悬浮窗 ==========

    private void addFloatView() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.w(TAG, "无悬浮窗权限，不显示悬浮播放窗");
                return;
            }
        }

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }

        floatParams = new WindowManager.LayoutParams(
                floatMode == MODE_CAPSULE ? getCapsuleWidth() : floatWidthPx,
                floatMode == MODE_CAPSULE ? getCapsuleHeight() : WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        floatParams.gravity = Gravity.TOP | Gravity.START;
        // 恢复上次拖动位置（横屏/竖屏分开记忆）
        int[] pos = getSavedFloatPosition();
        floatParams.x = pos[0];
        floatParams.y = pos[1];

        try {
            windowManager.addView(floatView, floatParams);
        } catch (Exception e) {
            Log.e(TAG, "添加悬浮窗失败: " + e.getMessage());
        }
    }

    // ========== 服务绑定 ==========

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playerBinder = (MusicPlayerService.MusicPlayerBinder) service;
            bound = true;
            // 初始加载当前歌曲信息
            loadCurrentSongInfo();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playerBinder = null;
            bound = false;
        }
    };

    // ========== 数据同步 ==========

    private void loadCurrentSongInfo() {
        if (!bound || playerBinder == null) return;
        Song song = playerBinder.getCurrentSong();
        if (song != null) {
            updateSongUI(song);
            loadLyric(song);
            loadCover(song);
            updatePlayPauseButton(playerBinder.isPlaying());
            updateCoverRotation(playerBinder.isPlaying());
        }
        // 胶囊模式：启动频谱
        if (floatMode == MODE_CAPSULE) {
            initVisualizer();
        }
    }

    private void onSongChanged(Intent intent) {
        Song song = Song.fromIntent(intent);
        if (song != null) {
            updateSongUI(song);
            loadLyric(song);
            loadCover(song);
        }
        if (bound && playerBinder != null) {
            updatePlayPauseButton(playerBinder.isPlaying());
            updateCoverRotation(playerBinder.isPlaying());
        }
    }

    private void updateSongUI(Song song) {
        if (tvTitle != null) tvTitle.setText(song.title != null ? song.title : "");
        if (tvArtist != null) tvArtist.setText(song.artist != null ? song.artist : "");
    }

    private void loadLyric(Song song) {
        if (song.title == null) return;
        // 用清洗后的标题做歌词搜索，与 PlayerActivity 一致
        String cleanTitle = Song.cleanSongTitle(song.title, song.artist);
        String cleanArtist = "<unknown>".equals(song.artist) ? "" : (song.artist != null ? song.artist : "");
        // 避免重复加载同一首歌的歌词
        if (cleanTitle.equals(currentLyricTitle) && cleanArtist.equals(currentLyricArtist)) {
            return;
        }
        currentLyricTitle = cleanTitle;
        currentLyricArtist = cleanArtist;
        lyricData = null;
        tvLyric.setText("");

        File lyricsDir = new File(getExternalFilesDir(null), "lyrics");
        LyricFetcher.loadLyric(cleanTitle, cleanArtist, song.filePath, lyricsDir,
                new LyricFetcher.LyricCallback() {
                    @Override
                    public void onLyricFetched(KrcParser.LyricData data) {
                        lyricData = data;
                    }
                    @Override
                    public void onError(String errorMessage) {
                        lyricData = null;
                    }
                }, this, song.title);
    }

    private void loadCover(Song song) {
        if (song.title == null) return;
        // 不重置封面，保留上一首歌的圆形封面直到新封面加载完成，避免切歌时方形闪烁

        com.jingxin.jingxinmusic.util.CoverLoader.load(this, song, 200, 200,
                true, coverExecutor, new com.jingxin.jingxinmusic.util.CoverLoader.CoverCallback() {
            @Override
            public void onCoverLoaded(Bitmap bitmap) {
                setCircularCover(bitmap);
            }

            @Override
            public void onCoverFailed() {
                // 保持默认图标
            }
        });
    }

    private void updatePlayPauseButton(boolean playing) {
        if (btnPlayPause != null) {
            btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    /**
     * 更新频谱播放状态（胶囊模式）
     */
    private void updateSpectrumPlaying(boolean playing) {
        if (capsuleSpectrum != null) {
            capsuleSpectrum.setPlaying(playing);
        }
        if (visualizer != null && visualizerEnabled) {
            try {
                visualizer.setEnabled(playing);
            } catch (Exception ignored) {}
        }
    }

    private void updateCoverRotation(boolean playing) {
        coverRotationHelper.update(playing);
    }

    /**
     * 检测屏幕方向变化，如果变了则重建悬浮窗
     */
    private void checkOrientationChange() {
        boolean isPortrait = isCurrentPortrait();
        if (lastIsPortrait != isPortrait) {
            lastIsPortrait = isPortrait;
            rebuildFloatView();
        }
    }

    /**
     * 重建悬浮窗（屏幕方向变化时调用）
     */
    private void rebuildFloatView() {
        if (floatView != null && floatView.isAttachedToWindow()) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        releaseVisualizer();
        // 重新计算尺寸
        android.util.DisplayMetrics screenMetrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(screenMetrics);
        
        if (floatMode == MODE_CAPSULE) {
            computeCapsuleMetrics();
            floatView = buildCapsuleView();
            floatParams.width = getCapsuleWidth();
            floatParams.height = getCapsuleHeight();
        } else {
            floatView = buildFloatView();
            floatParams.width = floatWidthPx;
            floatParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        }
        
        int[] pos = getSavedFloatPosition();
        floatParams.x = pos[0];
        floatParams.y = pos[1];

        try {
            windowManager.addView(floatView, floatParams);
        } catch (Exception e) {
            Log.e(TAG, "重建悬浮窗失败: " + e.getMessage());
        }

        // 重新加载当前歌曲信息
        loadCurrentSongInfo();
    }

    private void updateProgress() {
        // 检测屏幕方向变化，刷新悬浮窗布局
        checkOrientationChange();

        if (!bound || playerBinder == null) return;
        int pos = playerBinder.getCurrentPosition();
        int dur = playerBinder.getDuration();
        
        // 经典模式更新进度条
        if (progressBar != null && dur > 0) {
            progressBar.setMax(1000);
            progressBar.setProgress((int) ((long) pos * 1000 / dur));
        }

        // 更新当前歌词行（KRC 逐字高亮，LRC 整行高亮）
        if (lyricData != null && lyricData.lines != null && !lyricData.lines.isEmpty()) {
            updateLyricText(pos);
        }
    }

    /**
     * 更新悬浮窗歌词文本（KRC 逐字高亮，LRC 整行高亮）
     */
    private void updateLyricText(long pos) {
        if (tvLyric == null) return;
        KrcParser.LyricLine currentLine = null;
        for (int i = 0; i < lyricData.lines.size(); i++) {
            KrcParser.LyricLine line = lyricData.lines.get(i);
            KrcParser.LyricLine nextLine = (i + 1 < lyricData.lines.size()) ? lyricData.lines.get(i + 1) : null;
            if (pos >= line.startTime && (nextLine == null || pos < nextLine.startTime)) {
                currentLine = line;
                break;
            }
        }
        if (currentLine == null || currentLine.text == null || currentLine.text.isEmpty()) {
            tvLyric.setText("");
            return;
        }

        // KRC 逐字高亮
        if (currentLine.words != null && !currentLine.words.isEmpty()) {
            int playedColor = isNightMode ? ThemeColors.nightLyricCurrent() : ThemeColors.dayTabIndicator();
            int unplayedColor = isNightMode ? ThemeColors.nightLyricNormal() : ThemeColors.FLOAT_LYRIC_DAY_UNPLAYED;

            android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(currentLine.text);
            int start = 0;
            for (KrcParser.LyricWord word : currentLine.words) {
                int end = start + word.text.length();
                if (end > currentLine.text.length()) end = currentLine.text.length();
                if (start >= currentLine.text.length()) break;

                int color;
                boolean wordPlayed = (pos >= word.startTime + word.duration);
                boolean wordPlaying = (pos >= word.startTime && pos < word.startTime + word.duration);
                if (wordPlayed) {
                    color = playedColor;
                } else if (wordPlaying) {
                    float progress = (pos - word.startTime) / (float) word.duration;
                    color = com.jingxin.jingxinmusic.util.ColorUtil.blendColor(playedColor, unplayedColor, progress);
                } else {
                    color = unplayedColor;
                }
                ssb.setSpan(new android.text.style.ForegroundColorSpan(color), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                start = end;
            }
            tvLyric.setText(ssb);
        } else {
            // LRC 整行高亮
            tvLyric.setText(currentLine.text);
        }
    }

    /**
     * 将Bitmap裁剪为圆形并设置到ImageView
     */
    private void setCircularCover(Bitmap bitmap) {
        if (bitmap == null) return;
        int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        Bitmap circular = com.jingxin.jingxinmusic.util.BitmapUtil.createScaledCircularBitmap(bitmap, size);
        if (circular != null) {
            coverImage.setImageBitmap(circular);
        }
    }

    /**
     * 保存悬浮窗位置到 SharedPreferences（分横屏/竖屏）
     */
    private void saveFloatPosition() {
        if (floatParams != null) {
            String key = isCurrentPortrait() ? "portrait" : "landscape";
            getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                    .edit()
                    .putInt("x_" + key, floatParams.x)
                    .putInt("y_" + key, floatParams.y)
                    .apply();
        }
    }

    /**
     * 读取悬浮窗位置（根据当前屏幕方向）
     */
    private int[] getSavedFloatPosition() {
        String key = isCurrentPortrait() ? "portrait" : "landscape";
        SharedPreferences sp = getSharedPreferences("mini_float_pos", MODE_PRIVATE);
        return new int[]{sp.getInt("x_" + key, 16), sp.getInt("y_" + key, 100)};
    }

    // ========== 透明度管理 ==========

    private int getSavedBgAlpha() {
        return getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                .getInt("bg_alpha", DEFAULT_BG_ALPHA);
    }

    private void saveBgAlpha(int alpha) {
        getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                .edit()
                .putInt("bg_alpha", alpha)
                .apply();
    }

    private void applyRootBackground(int bgColor, int bgEndColor, int alpha, View target) {
        int c1 = (alpha << 24) | (bgColor & 0x00FFFFFF);
        int c2 = (alpha << 24) | (bgEndColor & 0x00FFFFFF);
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        d.setCornerRadius((int)(16 * unit));
        target.setBackground(d);
    }

    private void updateFloatBgAlpha(int alpha) {
        currentBgAlpha = alpha;
        if (rootLayout != null) {
            int bgColor = isNightMode ? ThemeColors.nightCardBg() : ThemeColors.dayCardBg();
            int bgEndColor = isNightMode ? ThemeColors.nightCardBgEnd() : ThemeColors.dayCardBgEnd();
            if (floatMode == MODE_CAPSULE) {
                applyCapsuleBackground(bgColor, bgEndColor, alpha, rootLayout, getCapsuleHeight());
            } else {
                applyRootBackground(bgColor, bgEndColor, alpha, rootLayout);
            }
        }
        saveBgAlpha(alpha);
    }

    // ========== 尺寸调节面板 ==========

    /**
     * 构建尺寸调节面板（覆盖在右侧信息区域上方）
     */
    private android.view.View buildSizeAdjustPanel(int textColor) {
        // 半透明背景
        FrameLayout panel = new FrameLayout(this);
        float cornerRadius = 24 * getResources().getDisplayMetrics().density;
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(ThemeColors.FLOAT_PANEL_BG); // 70%不透明黑
        panelBg.setCornerRadius(cornerRadius);
        panel.setBackground(panelBg);
        panel.setClipToOutline(true);
        // 强制按圆角裁剪
        panel.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
        panel.setPadding((int)(10 * unit), (int)(10 * unit), (int)(10 * unit), (int)(10 * unit));

        // 垂直布局：按钮行 + 透明度滑条 + (胶囊模式)宽度滑条 + 样式切换行
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ===== 按钮行 =====
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        int btnSize = (int)(44 * unit); // 大按钮，方便触控
        int btnSpacing = (int)(10 * unit); // 按钮间距

        // + 按钮
        TextView btnPlus = new TextView(this);
        btnPlus.setText("+");
        btnPlus.setTextColor(ThemeColors.sparkColor(isNightMode));
        btnPlus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28 * unit);
        btnPlus.setTypeface(null, android.graphics.Typeface.BOLD);
        btnPlus.setGravity(Gravity.CENTER);
        GradientDrawable plusBg = new GradientDrawable();
        plusBg.setColor(ThemeColors.FLOAT_BUTTON_BG);
        plusBg.setCornerRadius((int)(8 * unit));
        btnPlus.setBackground(plusBg);
        LinearLayout.LayoutParams plusParams = new LinearLayout.LayoutParams(0, btnSize, 1f);
        plusParams.setMarginEnd(btnSpacing / 2);
        btnPlus.setLayoutParams(plusParams);
        btnPlus.setOnClickListener(v -> applyFloatScale(1));

        // - 按钮
        TextView btnMinus = new TextView(this);
        btnMinus.setText("−");
        btnMinus.setTextColor(ThemeColors.sparkColor(isNightMode));
        btnMinus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28 * unit);
        btnMinus.setTypeface(null, android.graphics.Typeface.BOLD);
        btnMinus.setGravity(Gravity.CENTER);
        GradientDrawable minusBg = new GradientDrawable();
        minusBg.setColor(ThemeColors.FLOAT_BUTTON_BG);
        minusBg.setCornerRadius((int)(8 * unit));
        btnMinus.setBackground(minusBg);
        LinearLayout.LayoutParams minusParams = new LinearLayout.LayoutParams(0, btnSize, 1f);
        minusParams.setMarginStart(btnSpacing / 2);
        minusParams.setMarginEnd(btnSpacing / 2);
        btnMinus.setLayoutParams(minusParams);
        btnMinus.setOnClickListener(v -> applyFloatScale(-1));

        // 退出按钮
        TextView btnClose = new TextView(this);
        btnClose.setText("✕");
        btnClose.setTextColor(ThemeColors.sparkColor(isNightMode));
        btnClose.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 22 * unit);
        btnClose.setTypeface(null, android.graphics.Typeface.BOLD);
        btnClose.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(ThemeColors.FLOAT_BUTTON_BG);
        closeBg.setCornerRadius((int)(8 * unit));
        btnClose.setBackground(closeBg);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, btnSize, 1f);
        closeParams.setMarginStart(btnSpacing / 2);
        btnClose.setLayoutParams(closeParams);
        btnClose.setOnClickListener(v -> hideSizeAdjustPanel());

        btnRow.addView(btnPlus);
        btnRow.addView(btnMinus);
        btnRow.addView(btnClose);

        // ===== 透明度滑条行 =====
        LinearLayout alphaRow = new LinearLayout(this);
        alphaRow.setOrientation(LinearLayout.HORIZONTAL);
        alphaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alphaRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alphaRowParams.topMargin = (int)(8 * unit);

        // 透明标签
        TextView tvAlpha = new TextView(this);
        tvAlpha.setText("透");
        tvAlpha.setTextColor(textColor);
        tvAlpha.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 11 * unit);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMarginEnd((int)(6 * unit));

        // SeekBar
        SeekBar alphaSeek = new SeekBar(this);
        alphaSeek.setMax(255);
        alphaSeek.setProgress(currentBgAlpha);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        alphaSeek.setLayoutParams(seekParams);
        // 拖动时实时更新背景透明度
        alphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) updateFloatBgAlpha(progress);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 不透明标签
        TextView tvOpaque = new TextView(this);
        tvOpaque.setText("实");
        tvOpaque.setTextColor(textColor);
        tvOpaque.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 11 * unit);
        LinearLayout.LayoutParams labelParams2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams2.setMarginStart((int)(6 * unit));

        alphaRow.addView(tvAlpha, labelParams);
        alphaRow.addView(alphaSeek);
        alphaRow.addView(tvOpaque, labelParams2);

        column.addView(btnRow);
        column.addView(alphaRow, alphaRowParams);

        // ===== 胶囊模式：宽度滑条行 =====
        if (floatMode == MODE_CAPSULE) {
            LinearLayout widthRow = new LinearLayout(this);
            widthRow.setOrientation(LinearLayout.HORIZONTAL);
            widthRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams widthRowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            widthRowParams.topMargin = (int)(6 * unit);

            TextView tvWidth = new TextView(this);
            tvWidth.setText("宽");
            tvWidth.setTextColor(textColor);
            tvWidth.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 11 * unit);
            widthRow.addView(tvWidth);

            SeekBar widthSeek = new SeekBar(this);
            widthSeek.setMax((int)(CAPSULE_LYRIC_SPAN_MAX - CAPSULE_LYRIC_SPAN_MIN));
            float savedSpan = getSavedCapsuleLyricSpan();
            widthSeek.setProgress((int)(savedSpan - CAPSULE_LYRIC_SPAN_MIN));
            LinearLayout.LayoutParams widthSeekParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            widthSeekParams.setMarginStart((int)(6 * unit));
            widthSeekParams.setMarginEnd((int)(6 * unit));
            widthSeek.setLayoutParams(widthSeekParams);
            widthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        capsuleLyricSpan = (CAPSULE_LYRIC_SPAN_MIN + progress) * unit;
                        saveCapsuleLyricSpan(CAPSULE_LYRIC_SPAN_MIN + progress);
                        applyCapsuleWidth();
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            TextView tvWide = new TextView(this);
            tvWide.setText("宽");
            tvWide.setTextColor(textColor);
            tvWide.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 11 * unit);
            widthRow.addView(tvWide);

            widthRow.addView(widthSeek, 1, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            column.addView(widthRow, widthRowParams);
        }

        panel.addView(column);

        return panel;
    }

    /**
     * 显示尺寸调节面板
     * 面板定位：覆盖封面右侧信息区域，高度为悬浮窗80%，宽度为信息区域80%
     */
    private void showSizeAdjustPanel() {
        if (sizeAdjustPanel == null || floatView == null) return;
        isAdjustingSize = true;

        floatView.post(() -> {
            if (sizeAdjustPanel == null || floatView == null) return;

            // 胶囊模式：临时把窗口高度改为WRAP_CONTENT，让面板不被截断
            if (floatMode == MODE_CAPSULE) {
                floatParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
                try { windowManager.updateViewLayout(floatView, floatParams); } catch (Exception ignored) {}
            }

            int panelWidth = (int) (floatWidthPx * 0.85f);
            // 胶囊模式面板宽度：取胶囊宽度*1.2和200px的较大值，确保按钮不挤
            if (floatMode == MODE_CAPSULE) {
                panelWidth = (int) Math.max(getCapsuleWidth() * 1.2f, 200 * unit);
            }

            // 面板最大不超过屏幕宽度
            android.util.DisplayMetrics screenMetrics = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(screenMetrics);
            panelWidth = Math.min(panelWidth, screenMetrics.widthPixels - (int)(16 * unit));

            // 面板高度：WRAP_CONTENT，让内容自己撑开
            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(panelWidth,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            panelParams.gravity = Gravity.CENTER;

            sizeAdjustPanel.setLayoutParams(panelParams);
            sizeAdjustPanel.setVisibility(android.view.View.VISIBLE);
        });
    }

    /**
     * 隐藏尺寸调节面板（保存尺寸并关闭）
     */
    private void hideSizeAdjustPanel() {
        isAdjustingSize = false;
        if (sizeAdjustPanel != null) {
            sizeAdjustPanel.setVisibility(android.view.View.GONE);
        }
        // 胶囊模式：恢复窗口高度
        if (floatMode == MODE_CAPSULE && floatView != null) {
            floatParams.height = getCapsuleHeight();
            try { windowManager.updateViewLayout(floatView, floatParams); } catch (Exception ignored) {}
        }
    }

    /**
     * 点击缩放悬浮窗（步进5%），立即重建视图
     */
    private void applyFloatScale(float deltaRatio) {
        android.util.DisplayMetrics screenMetrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(screenMetrics);
        int screenWidth = screenMetrics.widthPixels;

        if (floatMode == MODE_CAPSULE) {
            // 胶囊模式：调整 unit（整体等比缩放）
            float currentRatio = getCapsuleUnitRatio();
            float newRatio = currentRatio + (deltaRatio > 0 ? 0.03f : -0.03f);
            newRatio = Math.max(CAPSULE_UNIT_MIN, Math.min(CAPSULE_UNIT_MAX, newRatio));
            if (newRatio == currentRatio) return;
            saveCapsuleUnitRatio(newRatio);
            rebuildFloatViewWithSize();
        } else {
            // 经典模式：原有逻辑
            int minWidth = (int) (screenWidth * FLOAT_SIZE_MIN);
            int maxWidth = (int) (screenWidth * FLOAT_SIZE_MAX);
            int step = (int) (screenWidth * FLOAT_SIZE_STEP);

            int newWidth = floatWidthPx + (deltaRatio > 0 ? step : -step);
            newWidth = Math.max(minWidth, Math.min(maxWidth, newWidth));

            if (newWidth == floatWidthPx) return; // 已到边界，无需重建

            floatWidthPx = newWidth;
            unit = floatWidthPx / 280.0f;

            saveFloatSize();
            rebuildFloatViewWithSize();
        }
    }

    /**
     * 保存悬浮窗尺寸比例（按方向分别存储）
     */
    private void saveFloatSize() {
        android.util.DisplayMetrics screenMetrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(screenMetrics);
        float ratio = (float) floatWidthPx / screenMetrics.widthPixels;
        String key = isCurrentPortrait() ? "portrait" : "landscape";
        getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                .edit()
                .putFloat("size_" + key, ratio)
                .apply();
    }

    /**
     * 读取悬浮窗尺寸比例（按方向），未缩放时返回默认值
     */
    private float getSavedFloatSizeRatio() {
        String key = isCurrentPortrait() ? "portrait" : "landscape";
        SharedPreferences sp = getSharedPreferences("mini_float_pos", MODE_PRIVATE);
        float defaultRatio = isCurrentPortrait() ? 0.40f : 0.30f;
        return sp.getFloat("size_" + key, defaultRatio);
    }

    /**
     * 用当前尺寸重建悬浮窗
     */
    private void rebuildFloatViewWithSize() {
        if (floatView != null && floatView.isAttachedToWindow()) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        releaseVisualizer();
        
        if (floatMode == MODE_CAPSULE) {
            computeCapsuleMetrics();
            floatView = buildCapsuleView();
            floatParams.width = getCapsuleWidth();
            floatParams.height = getCapsuleHeight();
        } else {
            floatView = buildFloatView();
            floatParams.width = floatWidthPx;
            floatParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        }
        
        int[] pos = getSavedFloatPosition();
        floatParams.x = pos[0];
        floatParams.y = pos[1];
        try {
            windowManager.addView(floatView, floatParams);
        } catch (Exception e) {
            Log.e(TAG, "重建悬浮窗失败: " + e.getMessage());
        }
        loadCurrentSongInfo();

        // 调节模式下重建后自动恢复面板
        if (isAdjustingSize) {
            showSizeAdjustPanel();
        }
    }

    /**
     * 判断触摸点是否落在封面区域
     */
    private boolean isTouchOnCover(MotionEvent event) {
        if (coverImage == null) return false;
        int[] location = new int[2];
        coverImage.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x <= location[0] + coverImage.getWidth()
                && y >= location[1] && y <= location[1] + coverImage.getHeight();
    }

    /**
     * 判断当前是否竖屏
     */
    private boolean isCurrentPortrait() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        return metrics.widthPixels < metrics.heightPixels;
    }

    // ========== 胶囊模式 ==========

    /**
     * 计算胶囊模式的尺寸参数（unit 和 capsuleLyricSpan）
     */
    private void computeCapsuleMetrics() {
        android.util.DisplayMetrics screenMetrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(screenMetrics);
        int screenWidth = screenMetrics.widthPixels;
        float ratio = getCapsuleUnitRatio();
        floatWidthPx = (int)(screenWidth * ratio);
        unit = floatWidthPx / 280.0f;
        capsuleLyricSpan = getSavedCapsuleLyricSpan() * unit;
    }

    /**
     * 获取胶囊总宽度（像素）
     * capsuleW = pad*2 + coverSize + gap*2 + lyricSpan + btnSize = 132*unit + lyricSpan
     */
    private int getCapsuleWidth() {
        // capsuleW = pad + coverSize + gap + lyricSpan + gap + btnSize + pad
        //         = 6+48+6+lyricSpan+6+48+6 = 120*unit + lyricSpan
        return (int)(120 * unit + capsuleLyricSpan);
    }

    /**
     * 获取胶囊高度（像素）= 80 * unit
     */
    private int getCapsuleHeight() {
        // 胶囊高度 = 封面直径 + 2倍内边距
        return (int)(48 * unit + 2 * 6 * unit);
    }

    /**
     * 构建胶囊风格悬浮窗视图
     * 布局：[封面] [歌词+频谱] [播放按钮]  横向胶囊，药丸形圆角
     */
    private View buildCapsuleView() {
        computeCapsuleMetrics();

        int coverSize = (int)(48 * unit);
        int btnSize = (int)(48 * unit);
        int pad = (int)(6 * unit);
        int gap = (int)(6 * unit);
        int capsuleH = getCapsuleHeight();
        int capsuleW = getCapsuleWidth();

        // 颜色
        int bgColor = isNightMode ? ThemeColors.nightCardBg() : ThemeColors.dayCardBg();
        int bgEndColor = isNightMode ? ThemeColors.nightCardBgEnd() : ThemeColors.dayCardBgEnd();
        int textPrimary = isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        int iconColor = isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        int sparkColor = ThemeColors.sparkColor(isNightMode);

        // 胶囊根布局
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.setGravity(Gravity.CENTER_VERTICAL);
        rootLayout.setPadding(pad, pad, pad, pad);

        // 药丸形背景
        currentBgAlpha = getSavedBgAlpha();
        applyCapsuleBackground(bgColor, bgEndColor, currentBgAlpha, rootLayout, capsuleH);

        // ===== 左侧：圆形旋转封面 =====
        LinearLayout coverWrap = new LinearLayout(this);
        coverWrap.setOrientation(LinearLayout.VERTICAL);
        coverWrap.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams coverWrapParams = new LinearLayout.LayoutParams(coverSize, coverSize);
        coverWrapParams.setMarginEnd(gap);

        coverImage = new ImageView(this);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(coverSize, coverSize);
        coverImage.setLayoutParams(coverParams);
        coverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        setCircularCover(BitmapFactory.decodeResource(getResources(), R.drawable.ic_music_icon));

        // 圆形裁剪
        coverImage.setClipToOutline(true);
        coverImage.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });

        coverRotationHelper.attach(coverImage);
        coverWrap.addView(coverImage);
        // 点击封面弹出尺寸调节面板，长按封面切换模式
        coverWrap.setOnClickListener(v -> showSizeAdjustPanel());
        coverWrap.setOnLongClickListener(v -> {
            switchFloatMode(MODE_CLASSIC);
            return true;
        });
        rootLayout.addView(coverWrap, coverWrapParams);

        // ===== 中间：歌词 + 频谱（FrameLayout，歌词居中，频谱底部对齐） =====
        capsuleCenterLayout = new FrameLayout(this);
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(
                (int) capsuleLyricSpan, LinearLayout.LayoutParams.MATCH_PARENT);

        // 上层：单行逐字歌词（水平+垂直居中）
        tvLyric = new TextView(this);
        tvLyric.setTextColor(isNightMode ? ThemeColors.nightLyricCurrent() : ThemeColors.FLOAT_LYRIC_DAY_UNPLAYED);
        tvLyric.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16 * unit);
        tvLyric.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLyric.setMaxLines(1);
        tvLyric.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        tvLyric.setMarqueeRepeatLimit(-1);
        tvLyric.setHorizontalFadingEdgeEnabled(true);
        tvLyric.setFadingEdgeLength((int)(10 * unit));
        tvLyric.setSingleLine(true);
        tvLyric.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lyricParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lyricParams.gravity = Gravity.CENTER;

        capsuleCenterLayout.addView(tvLyric, lyricParams);

        // 下层：迷你柱状频谱（底部对齐，与封面底部齐平）
        capsuleSpectrum = new SpectrumView(this);
        capsuleSpectrum.setStyle(SpectrumView.STYLE_COLUMNAR);
        capsuleSpectrum.setNightMode(isNightMode);
        FrameLayout.LayoutParams spectrumParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int)(42 * unit));
        spectrumParams.gravity = Gravity.BOTTOM;
        capsuleCenterLayout.addView(capsuleSpectrum, spectrumParams);

        rootLayout.addView(capsuleCenterLayout, centerParams);

        // ===== 右侧：圆形播放/暂停按钮 =====
        FrameLayout btnContainer = new FrameLayout(this);
        LinearLayout.LayoutParams btnContainerParams = new LinearLayout.LayoutParams(btnSize, btnSize);
        btnContainerParams.setMarginStart(gap);

        // 圆形背景
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.OVAL);
        btnBg.setColor(isNightMode ? 0x33FFFFFF : 0x33000000);
        btnContainer.setBackground(btnBg);

        btnPlayPause = new ImageView(this);
        FrameLayout.LayoutParams btnPpParams = new FrameLayout.LayoutParams(
                (int)(28 * unit), (int)(28 * unit));
        btnPpParams.gravity = Gravity.CENTER;
        btnPlayPause.setLayoutParams(btnPpParams);
        btnPlayPause.setImageResource(R.drawable.ic_pause);
        btnPlayPause.setColorFilter(iconColor);
        btnPlayPause.setOnClickListener(v -> {
            if (bound && playerBinder != null) playerBinder.togglePlayPause();
        });
        btnContainer.addView(btnPlayPause);

        rootLayout.addView(btnContainer, btnContainerParams);

        // ===== 外层 FrameLayout =====
        FrameLayout container = new FrameLayout(this);
        container.addView(rootLayout, new FrameLayout.LayoutParams(capsuleW, capsuleH));

        // 右上角关闭按钮
        ImageView btnClose = new ImageView(this);
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setColorFilter(textPrimary);
        int closeSize = (int)(16 * unit);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(closeSize, closeSize);
        closeParams.gravity = Gravity.END | Gravity.TOP;
        closeParams.setMargins(0, 0, (int)(2 * unit), 0);
        btnClose.setLayoutParams(closeParams);
        btnClose.setOnClickListener(v -> stopSelf());
        container.addView(btnClose);

        // 尺寸调节面板
        // 清空旧引用，重新构建
        tvTitle = null;
        tvArtist = null;
        progressBar = null;
        btnPrev = null;
        btnNext = null;
        sizeAdjustPanel = buildSizeAdjustPanel(textPrimary);
        sizeAdjustPanel.setVisibility(android.view.View.GONE);
        container.addView(sizeAdjustPanel);

        // ===== 拖动 + 点击/双击 =====
        rootLayout.setOnTouchListener((v, event) -> {
            int action = event.getAction() & MotionEvent.ACTION_MASK;

            if (sizeAdjustPanel != null && sizeAdjustPanel.getVisibility() == android.view.View.VISIBLE) {
                return false;
            }

            if (action == MotionEvent.ACTION_DOWN) {
                if (isTouchOnCover(event)) {
                    return false;
                }
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    initialX = floatParams.x;
                    initialY = floatParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    isDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true;
                    floatParams.x = initialX + (int) dx;
                    floatParams.y = initialY + (int) dy;
                    windowManager.updateViewLayout(floatView, floatParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        long now = System.currentTimeMillis();
                        if (now - lastClickTime < DOUBLE_CLICK_INTERVAL) {
                            if (pendingSingleClick != null) {
                                uiHandler.removeCallbacks(pendingSingleClick);
                                pendingSingleClick = null;
                            }
                            lastClickTime = 0;
                            stopSelf();
                        } else {
                            lastClickTime = now;
                            pendingSingleClick = () -> {
                                Intent mainIntent = new Intent(MiniFloatService.this, MainActivity.class);
                                mainIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(mainIntent);
                                pendingSingleClick = null;
                            };
                            uiHandler.postDelayed(pendingSingleClick, DOUBLE_CLICK_INTERVAL);
                        }
                        v.performClick();
                    } else {
                        saveFloatPosition();
                    }
                    return true;
            }
            return false;
        });

        return container;
    }

    /**
     * 胶囊背景（药丸形圆角 = 高度/2）
     */
    private void applyCapsuleBackground(int bgColor, int bgEndColor, int alpha, View target, int capsuleH) {
        int c1 = (alpha << 24) | (bgColor & 0x00FFFFFF);
        int c2 = (alpha << 24) | (bgEndColor & 0x00FFFFFF);
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        d.setCornerRadius(capsuleH / 2f);
        target.setBackground(d);
    }

    /**
     * 胶囊模式独立调整宽度（不重建视图，只更新 LayoutParams）
     */
    private void applyCapsuleWidth() {
        if (capsuleCenterLayout == null || floatParams == null) return;
        int newWidth = getCapsuleWidth();
        int capH = getCapsuleHeight();

        // 更新中间区域宽度
        LinearLayout.LayoutParams centerLp = (LinearLayout.LayoutParams) capsuleCenterLayout.getLayoutParams();
        centerLp.width = (int) capsuleLyricSpan;
        capsuleCenterLayout.setLayoutParams(centerLp);

        // 更新根布局宽度和容器宽度
        FrameLayout.LayoutParams rootLp = (FrameLayout.LayoutParams) rootLayout.getLayoutParams();
        rootLp.width = newWidth;
        rootLp.height = capH;
        rootLayout.setLayoutParams(rootLp);

        // 重新设置药丸形背景，确保圆角 = 高度/2 保持胶囊形状
        int bgColor = isNightMode ? ThemeColors.nightCardBg() : ThemeColors.dayCardBg();
        int bgEndColor = isNightMode ? ThemeColors.nightCardBgEnd() : ThemeColors.dayCardBgEnd();
        applyCapsuleBackground(bgColor, bgEndColor, currentBgAlpha, rootLayout, capH);

        // 更新 WindowManager 宽度（高度不变）
        floatParams.width = newWidth;
        floatParams.height = capH;
        try {
            windowManager.updateViewLayout(floatView, floatParams);
        } catch (Exception ignored) {}
    }

    /**
     * 切换悬浮窗模式（经典 ↔ 胶囊）
     */
    private void switchFloatMode(int newMode) {
        if (newMode == floatMode) {
            hideSizeAdjustPanel();
            return;
        }
        floatMode = newMode;
        getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                .edit().putInt(PREF_STYLE_MODE, newMode).apply();

        // 隐藏面板，重建视图
        hideSizeAdjustPanel();
        rebuildFloatViewWithSize();
    }

    // ========== Visualizer 频谱接入 ==========

    /**
     * 初始化 Visualizer（从 PlayerActivity 移植，精简版）
     */
    private void initVisualizer() {
        if (visualizer != null || !bound || playerBinder == null) return;
        try {
            int sessionId = playerBinder.getAudioSessionId();
            if (sessionId == -1 || sessionId == 0) return;

            visualizer = new Visualizer(sessionId);
            visualizer.setEnabled(false);
            int[] range = visualizer.getCaptureSizeRange();
            visualizer.setCaptureSize(range[1]);
            visualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);

            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {}

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                    if (capsuleSpectrum == null) return;
                    int count = capsuleSpectrum.getBarInputCount();
                    float[] magnitudes = new float[count];
                    float maxMag = 0;
                    for (int i = 0; i < count; i++) {
                        int idx = (i + 1) * 2;
                        if (idx + 1 < fft.length) {
                            byte real = fft[idx];
                            byte imaginary = fft[idx + 1];
                            float mag = (float) Math.sqrt(real * real + imaginary * imaginary);
                            magnitudes[i] = mag;
                            if (mag > maxMag) maxMag = mag;
                        }
                    }
                    float finalMax = maxMag;
                    capsuleSpectrum.post(() -> {
                        if (capsuleSpectrum != null) {
                            capsuleSpectrum.updateDTFMagnitudes(magnitudes, finalMax);
                        }
                    });
                }
            }, Visualizer.getMaxCaptureRate(), false, true);

            boolean playing = (playerBinder != null && playerBinder.isPlaying());
            visualizer.setEnabled(playing);
            visualizerEnabled = true;
            if (capsuleSpectrum != null) {
                capsuleSpectrum.setPlaying(playing);
            }
        } catch (Exception e) {
            Log.w(TAG, "悬浮窗 Visualizer 初始化失败: " + e.getMessage());
            if (visualizer != null) {
                try { visualizer.release(); } catch (Exception ignored) {}
                visualizer = null;
            }
        }
    }

    /**
     * 释放 Visualizer
     */
    private void releaseVisualizer() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.setDataCaptureListener(null, 0, false, false);
                visualizer.release();
            } catch (Exception ignored) {}
            visualizer = null;
        }
        visualizerEnabled = false;
    }

    // ========== 胶囊模式持久化 ==========

    private float getCapsuleUnitRatio() {
        String key = isCurrentPortrait() ? "portrait" : "landscape";
        return getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                .getFloat("capsule_unit_" + key, CAPSULE_UNIT_RATIO);
    }

    private void saveCapsuleUnitRatio(float ratio) {
        String key = isCurrentPortrait() ? "portrait" : "landscape";
        getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                .edit().putFloat("capsule_unit_" + key, ratio).apply();
    }

    private float getSavedCapsuleLyricSpan() {
        String key = isCurrentPortrait() ? "portrait" : "landscape";
        return getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                .getFloat("capsule_span_" + key, CAPSULE_LYRIC_SPAN_DEFAULT);
    }

    private void saveCapsuleLyricSpan(float span) {
        String key = isCurrentPortrait() ? "portrait" : "landscape";
        getSharedPreferences("mini_float_pos", MODE_PRIVATE)
                .edit().putFloat("capsule_span_" + key, span).apply();
    }
}
