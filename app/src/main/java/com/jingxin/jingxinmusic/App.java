package com.jingxin.jingxinmusic;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import android.os.Environment;

import com.jingxin.jingxinmusic.floatwindow.LecoFloatManager;
import com.jingxin.jingxinmusic.service.MiniFloatService;
import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.util.CompatUtil;
import com.jingxin.jingxinmusic.util.CrashHandler;
import com.jingxin.jingxinmusic.util.ThemeHelper;

import java.io.File;

/**
 * 应用类：监听前后台切换，控制悬浮迷你播放窗的显示/隐藏
 */
public class App extends Application {

    private static final String TAG = "App";
    private int activityCount = 0;
    private boolean isForeground = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingStartFloat;

    // 高德导航日夜模式广播（应用级注册，不依赖 MusicPlayerService 生死）
    private static final String ACTION_AUTONAVI = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final int KEY_TYPE_DAY_NIGHT = 10019;
    private static final int EXTRA_STATE_DAY = 37;
    private static final int EXTRA_STATE_NIGHT = 38;
    private long lastAmapThemeTime = 0;

    /** 应用级高德日夜接收器：Service 未运行时也能写 isNight 并转发内部广播 */
    private final BroadcastReceiver amapThemeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_AUTONAVI.equals(intent.getAction())) return;
            if (intent.getIntExtra("KEY_TYPE", -1) != KEY_TYPE_DAY_NIGHT) return;
            int extraState = intent.getIntExtra("EXTRA_STATE", -1);
            if (extraState != EXTRA_STATE_DAY && extraState != EXTRA_STATE_NIGHT) return;

            // 防抖：500ms 内不重复处理
            long now = System.currentTimeMillis();
            if (now - lastAmapThemeTime < 500) return;
            lastAmapThemeTime = now;

            boolean isNight = (extraState == EXTRA_STATE_NIGHT);
            getSharedPreferences("theme", MODE_PRIVATE).edit()
                    .putBoolean("isNight", isNight)
                    .putBoolean("amapTriggered", true)
                    .apply();

            // 发送内部广播通知各页面（首查/播放页/四个设置页均已监听）
            Intent themeIntent = new Intent(MusicPlayerService.ACTION_THEME_CHANGED);
            themeIntent.setPackage(getPackageName());
            themeIntent.putExtra(MusicPlayerService.EXTRA_IS_NIGHT, isNight);
            sendBroadcast(themeIntent);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // 安装崩溃捕获（本地落盘崩溃日志）
        CrashHandler.install(this);

        // 临时调试日志初始化（排查返回键问题，用完删除）

        // 按时间规则自动判断日夜主题（优先级低于高德导航广播）
        ThemeHelper.applyAutoTheme(this);

        // 注册高德日夜广播（应用级：Service 未运行/悬浮态也能跟随高德切换）
        CompatUtil.safeRegisterReceiverExported(this, amapThemeReceiver,
                new IntentFilter(ACTION_AUTONAVI));

        // 初始化乐酷桌面悬浮窗管理器
        LecoFloatManager.getInstance().init(this);

        // 确保公共歌词目录存在（Download/lyrics/）
        ensureLyricsDir();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                activityCount++;
                // 有新Activity启动，取消待执行的悬浮窗启动
                if (pendingStartFloat != null) {
                    handler.removeCallbacks(pendingStartFloat);
                    pendingStartFloat = null;
                }
                if (!isForeground && activityCount > 0) {
                    // 从后台回到前台：隐藏悬浮窗
                    isForeground = true;
                    stopFloatService(activity);
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
                activityCount--;
                if (isForeground && activityCount <= 0) {
                    // 乐酷悬浮模式：应用通过覆盖窗口在悬浮区域内显示，不启动独立悬浮窗
                    if (LecoFloatManager.getInstance().isFloating()) {
                        isForeground = false;
                            return;
                    }
                    // 乐酷悬浮刚退出时 LecoFloatManager 已启动独立悬浮窗，跳过避免重复启动竞态
                    if (LecoFloatManager.getInstance().isLecoFloatExit()) {
                        LecoFloatManager.getInstance().clearLecoFloatExit();
                        isForeground = false;
                        return;
                    }
                    // 延迟2秒确认是否真的进后台。去抖必须长于分屏/窗口重排的stop→start
                    // 间隙（乐酷调分屏实测1~2秒），否则误判进后台触发悬浮窗启停风暴；
                    // 真进后台时仅晚1.7秒出悬浮窗，无感
                    pendingStartFloat = () -> {
                        if (activityCount <= 0 && isForeground) {
                            isForeground = false;
                            startFloatService(activity);
                        }
                        pendingStartFloat = null;
                    };
                    handler.postDelayed(pendingStartFloat, 2000);
                }
            }

            @Override public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    // 悬浮窗启动节流窗口：防止分屏连续调整时启停交错（避免stopSelf销毁延迟窗口内
    // 再次startForegroundService，旧版无onStartCommand兜底时会5秒超时杀进程）
    private static final long FLOAT_START_THROTTLE_MS = 2000;
    private long lastFloatStartAt = 0L;

    private void startFloatService(Context context) {
        // 启动节流：距上次启动不足2秒跳过，与后台判定去抖同窗口
        if (SystemClock.uptimeMillis() - lastFloatStartAt < FLOAT_START_THROTTLE_MS) {
            return;
        }
        lastFloatStartAt = SystemClock.uptimeMillis();
        // 检查悬浮窗开关
        if (!com.jingxin.jingxinmusic.fragment.SettingsFragment.isFloatWindowEnabled(context)) {
            return;
        }
        // 检查悬浮窗权限，无权限时启动 Service 也无法显示窗口，还可能触发车机后台限制
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(context)) {
            return;
        }
        // 只有在有音乐播放服务时才弹悬浮窗
        try {
            Intent intent = new Intent(context, MiniFloatService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // Android 12+ 禁止从后台启动前台服务，忽略即可
            Log.w(TAG, "启动悬浮播放窗失败（可能是后台启动限制）: " + e.getMessage());
        }
    }

    private void stopFloatService(Context context) {
        try {
            context.stopService(new Intent(context, MiniFloatService.class));
        } catch (Exception e) {
            Log.e(TAG, "停止悬浮播放窗失败: " + e.getMessage());
        }
    }

    /**
     * 确保公共歌词目录存在
     * Android 10+ 通过 MediaStore.Downloads 写入，目录由系统自动创建，无需预建
     * Android 9- 用 File API 创建
     */
    private void ensureLyricsDir() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) return;
        try {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File lyricsDir = new File(downloadsDir, "lyrics");
            if (!lyricsDir.exists()) {
                lyricsDir.mkdirs();
            }
        } catch (Exception e) {
            Log.e(TAG, "创建公共歌词目录失败: " + e.getMessage());
        }
    }
}
