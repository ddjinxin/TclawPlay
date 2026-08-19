package com.jingxin.jingxinmusic;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import android.os.Environment;

import com.jingxin.jingxinmusic.floatwindow.LecoFloatManager;
import com.jingxin.jingxinmusic.service.MiniFloatService;
import com.jingxin.jingxinmusic.service.MusicPlayerService;

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

    @Override
    public void onCreate() {
        super.onCreate();

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
                    // 延迟300ms确认是否真的进后台，避免Activity切换时序问题导致误判
                    pendingStartFloat = () -> {
                        if (activityCount <= 0 && isForeground) {
                            isForeground = false;
                            startFloatService(activity);
                        }
                        pendingStartFloat = null;
                    };
                    handler.postDelayed(pendingStartFloat, 300);
                }
            }

            @Override public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private void startFloatService(Context context) {
        // 检查悬浮窗开关
        if (!com.jingxin.jingxinmusic.fragment.SettingsFragment.isFloatWindowEnabled(context)) {
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
