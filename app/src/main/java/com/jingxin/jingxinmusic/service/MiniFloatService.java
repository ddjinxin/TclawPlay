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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

    private WindowManager windowManager;
    private View floatView;
    private WindowManager.LayoutParams floatParams;

    // 视图引用
    private ImageView coverImage;
    private TextView tvTitle;
    private TextView tvArtist;
    private TextView tvLyric;
    private ProgressBar progressBar;
    private ImageView btnPrev;
    private ImageView btnPlayPause;
    private ImageView btnNext;

    // 播放服务
    private MusicPlayerService.MusicPlayerBinder playerBinder;
    private boolean bound = false;

    // 歌词
    private KrcParser.LyricData lyricData;
    private String currentLyricTitle = "";
    private String currentLyricArtist = "";

    // 封面旋转动画
    private android.animation.ObjectAnimator coverRotateAnimator;

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

    @Override
    public void onCreate() {
        super.onCreate();

        ThemeColors.init(this);
        isNightMode = getSharedPreferences("theme", MODE_PRIVATE).getBoolean("isNight", true);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        lastIsPortrait = isCurrentPortrait();
        floatView = buildFloatView();
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
        if (coverRotateAnimator != null) coverRotateAnimator.cancel();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "悬浮播放窗", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("悬浮迷你播放窗服务");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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
        // 竖屏：屏幕宽度的1/3，横屏：屏幕宽度的25%
        floatWidthPx = (int) (screenMetrics.widthPixels * (isPortrait ? 1.0f / 3 : 0.25));
        unit = floatWidthPx / 280.0f; // 以280dp为基准的比例因子

        // 颜色
        int bgColor = isNightMode ? ThemeColors.nightCardBg() : ThemeColors.dayCardBg();
        int bgEndColor = isNightMode ? ThemeColors.nightCardBgEnd() : ThemeColors.dayCardBgEnd();
        int textPrimary = isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();
        int textSecondary = isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary();
        int lyricColor = isNightMode ? ThemeColors.nightLyricCurrent() : ThemeColors.dayLyricCurrent();
        int iconColor = isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary();

        // 外层圆角卡片（水平布局：左封面 + 右信息）
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.setPadding((int)(12 * unit), (int)(10 * unit), (int)(12 * unit), (int)(10 * unit));
        rootLayout.setGravity(Gravity.CENTER_VERTICAL);

        // 圆角半透明渐变背景
        int bgAlpha = 0xCC; // 80%不透明度（半透明）
        int bgColorAlpha = (bgAlpha << 24) | (bgColor & 0x00FFFFFF);
        int bgEndColorAlpha = (bgAlpha << 24) | (bgEndColor & 0x00FFFFFF);
        GradientDrawable bgDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{bgColorAlpha, bgEndColorAlpha});
        bgDrawable.setCornerRadius((int)(16 * unit));
        rootLayout.setBackground(bgDrawable);

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
        coverRotateAnimator = android.animation.ObjectAnimator.ofFloat(coverImage, View.ROTATION, 0f, 360f);
        coverRotateAnimator.setDuration(12000);
        coverRotateAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        coverRotateAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        // 先不启动，等数据加载后再判断

        coverWrap.addView(coverImage);
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
        tvLyric.setTextColor(isNightMode ? ThemeColors.nightLyricCurrent() : 0xFF555555);
        tvLyric.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13 * unit);
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

        // ===== 单击回app / 双击关闭悬浮窗 =====
        rootLayout.setOnClickListener(v -> {
            // 点击逻辑移到 onTouch 的 ACTION_UP 中处理
        });

        // ===== 拖动 + 点击/双击 =====
        rootLayout.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
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

        return rootLayout;
    }

    private android.graphics.drawable.Drawable buildProgressDrawable(boolean night) {
        int progressColor = night ? ThemeColors.nightTabIndicator() : ThemeColors.dayTabIndicator();
        int bgColor = night ? 0x33FFFFFF : 0x33000000;

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
                floatWidthPx,  // 屏幕宽度20%
                WindowManager.LayoutParams.WRAP_CONTENT,
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
        tvTitle.setText(song.title != null ? song.title : "");
        tvArtist.setText(song.artist != null ? song.artist : "");
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
                }, this);
    }

    private void loadCover(Song song) {
        if (song.title == null) return;
        // 不重置封面，保留上一首歌的圆形封面直到新封面加载完成，避免切歌时方形闪烁

        // 异步加载封面
        new Thread(() -> {
            // 1. 提取音频文件内嵌封面
            Bitmap embedded = CoverFetcher.extractEmbeddedCover(song.filePath);
            if (embedded != null) {
                uiHandler.post(() -> setCircularCover(embedded));
                return;
            }

            // 2. 本地封面缓存
            File coverDir = getExternalFilesDir("covers");
            if (coverDir != null) {
                String coverName = Song.toFileName(song.title, song.artist) + ".jpg";
                File coverFile = new File(coverDir, coverName);
                if (coverFile.exists() && coverFile.length() > 0) {
                    Bitmap bmp = BitmapFactory.decodeFile(coverFile.getAbsolutePath());
                    if (bmp != null) {
                        uiHandler.post(() -> setCircularCover(bmp));
                        return;
                    }
                }
            }

            // 3. 在线获取
            String title = Song.cleanSongTitle(song.title, song.artist);
            String artist = "<unknown>".equals(song.artist) ? "" : song.artist;
            CoverFetcher.fetchCover(title, artist, new CoverFetcher.CoverCallback() {
                @Override
                public void onCoverFetched(Bitmap coverBitmap) {
                    String coverName = Song.toFileName(song.title, song.artist) + ".jpg";
                    Song.saveCoverToPublic(MiniFloatService.this, coverName, coverBitmap);
                    uiHandler.post(() -> {
                        if (coverBitmap != null) setCircularCover(coverBitmap);
                    });
                }
                @Override
                public void onError(String errorMessage) {
                    // 保持默认图标
                }
            });
        }, "FloatCoverLoader").start();
    }

    private void updatePlayPauseButton(boolean playing) {
        if (btnPlayPause != null) {
            btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    private void updateCoverRotation(boolean playing) {
        if (coverRotateAnimator == null) return;
        if (playing) {
            // 使用resume避免重新start导致角度重置为0度
            if (coverRotateAnimator.isPaused()) {
                coverRotateAnimator.resume();
            } else if (!coverRotateAnimator.isRunning()) {
                coverRotateAnimator.start();
            }
        } else {
            if (coverRotateAnimator.isRunning()) {
                coverRotateAnimator.pause();
            }
        }
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
        // 重新计算尺寸
        android.util.DisplayMetrics screenMetrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(screenMetrics);
        boolean isPortrait = screenMetrics.widthPixels < screenMetrics.heightPixels;
        floatWidthPx = (int) (screenMetrics.widthPixels * (isPortrait ? 1.0f / 3 : 0.25));
        unit = floatWidthPx / 280.0f;

        floatView = buildFloatView();
        floatParams.width = floatWidthPx;
        // 恢复当前方向对应的位置
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

        if (!bound || playerBinder == null || progressBar == null) return;
        int pos = playerBinder.getCurrentPosition();
        int dur = playerBinder.getDuration();
        if (dur > 0) {
            // 使用0-1000范围，避免大数值dur和ClipDrawable的level计算精度问题
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
            int unplayedColor = isNightMode ? ThemeColors.nightLyricNormal() : 0xFF555555;

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
                    color = blendColor(playedColor, unplayedColor, progress);
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

    private int blendColor(int c1, int c2, float progress) {
        int r = (int) (android.graphics.Color.red(c1) * progress + android.graphics.Color.red(c2) * (1 - progress));
        int g = (int) (android.graphics.Color.green(c1) * progress + android.graphics.Color.green(c2) * (1 - progress));
        int b = (int) (android.graphics.Color.blue(c1) * progress + android.graphics.Color.blue(c2) * (1 - progress));
        return android.graphics.Color.rgb(r, g, b);
    }

    /**
     * 将Bitmap裁剪为圆形并设置到ImageView
     */
    private void setCircularCover(Bitmap bitmap) {
        if (bitmap == null) return;
        int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        Bitmap squared = Bitmap.createBitmap(bitmap,
                (bitmap.getWidth() - size) / 2, (bitmap.getHeight() - size) / 2, size, size);
        Bitmap circular = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(circular);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setAntiAlias(true);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(squared, 0, 0, paint);
        coverImage.setImageBitmap(circular);
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

    /**
     * 判断当前是否竖屏
     */
    private boolean isCurrentPortrait() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        return metrics.widthPixels < metrics.heightPixels;
    }
}
