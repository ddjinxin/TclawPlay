package com.jingxin.jingxinmusic.floatwindow;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.CompatUtil;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 乐酷桌面悬浮窗管理器（单 Activity + Fragment 架构简化版）
 *
 * 原理（与酷我 IS_CHILD_VIEW 方案一致）：
 * 1. 收到 showmap 广播后标记 canFloat=true，在 Activity onResume 时
 *    将 fragment_container 从原父容器剥离，放入 WindowManager 覆盖窗口显示到指定区域
 * 2. 只剥离 fragment_container（不剥离整个 ContentView），Activity 窗口仍有外层容器，
 *    不会触发 onSaveInstanceState → Fragment 事务不会崩溃
 * 3. 页面切换通过 Fragment 事务完成，不涉及 Activity 切换
 * 4. closemap 广播时还原 View 并移除覆盖窗口
 */
public class LecoFloatManager {

    private static final String TAG = "LecoFloatManager";

    public static final String ACTION_SHOW_MAP = "com.autonavi.plus.showmap";
    public static final String ACTION_CLOSE_MAP = "com.autonavi.plus.closemap";

    private static volatile LecoFloatManager INSTANCE;

    private Application application;
    private WindowManager windowManager;

    // 覆盖窗口容器
    private FrameLayout windowContainer;
    private WindowManager.LayoutParams windowParams;

    // 当前正在悬浮的 Activity 及其 fragment_container
    private Activity currentFloatingActivity;
    private View currentContentView;
    private ViewGroup originalParent;
    private ViewGroup.LayoutParams originalLayoutParams;

    // 悬浮区域
    private Rect floatRect;
    // 乐酷通知的圆角参数（px），未取到时为 0（直角）
    private float floatCornerRadius = 0f;

    // 状态标志
    private final AtomicBoolean canFloat = new AtomicBoolean(false);
    private final AtomicBoolean isFloating = new AtomicBoolean(false);
    // 乐酷悬浮退出标志：ACTION_CLOSE_MAP 时置 true，floatActivity 时重置
    // 用于通知 App.onActivityStopped 跳过重复启动独立悬浮窗
    private volatile boolean lecoFloatExit = false;
    private FloatReceiver receiver;
    private boolean receiverRegistered = false;

    // 当前 resumed 的 Activity
    private Activity currentResumedActivity;

    private LecoFloatManager() {}

    public static LecoFloatManager getInstance() {
        if (INSTANCE == null) {
            synchronized (LecoFloatManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new LecoFloatManager();
                }
            }
        }
        return INSTANCE;
    }

    public void init(Application app) {
        this.application = app;
        this.windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        registerReceiver();
        app.registerActivityLifecycleCallbacks(new FloatLifecycle());
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        receiver = new FloatReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW_MAP);
        filter.addAction(ACTION_CLOSE_MAP);
        CompatUtil.safeRegisterReceiverExported(application, receiver, filter);
        receiverRegistered = true;
    }

    // ==================== 广播接收 ====================

    private class FloatReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_SHOW_MAP.equals(action)) {
                int x = intent.getIntExtra("x", 0);
                int y = intent.getIntExtra("y", 0);
                int w = intent.getIntExtra("w", 0);
                int h = intent.getIntExtra("h", 0);
                // 读取乐酷新增的圆角参数 r（px），默认 0 即直角
                float r = intent.getFloatExtra("r", 0f);
                floatCornerRadius = r;

                if (w > 0 && h > 0) {
                    floatRect = new Rect(x, y, x + w, y + h);
                    canFloat.set(true);
                    if (isFloating.get()) {
                        // 已在悬浮：只更新窗口尺寸
                        updateFloatWindowSize();
                    } else {
                        // 首次进入悬浮：找当前 resumed 的 Activity
                        Activity target = currentResumedActivity;
                        if (target == null || target.isFinishing() || target.isDestroyed()) {
                            Log.w(TAG, "showmap received but no alive Activity to float");
                            return;
                        }
                        floatActivity(target);
                    }
                }
            } else if (ACTION_CLOSE_MAP.equals(action)) {
                boolean wasFloating = isFloating.get();
                canFloat.set(false);
                lecoFloatExit = true;
                restoreCurrentActivity();
                removeFloatWindow();
                // 只有之前确实处于乐酷悬浮态时才启动独立悬浮窗
                // 否则是乐酷误发/重复广播（如用户已点击悬浮窗回前台），不应启动
                if (wasFloating && com.jingxin.jingxinmusic.fragment.SettingsFragment.isFloatWindowEnabled(application)) {
                    try {
                        Intent floatIntent = new Intent(application, com.jingxin.jingxinmusic.service.MiniFloatService.class);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            application.startForegroundService(floatIntent);
                        } else {
                            application.startService(floatIntent);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "乐酷悬浮退出后启动独立悬浮窗失败（可能是后台启动限制）: " + e.getMessage());
                    }

                    // MiniFloatService 重建 Visualizer 会抢占同一 audioSessionId，
                    // 通知 PlayerFragment 重建频谱（延迟 500ms 确保 Service 启动完）
                    sendSpectrumRestartBroadcast();
                }
            }
        }
    }

    // ==================== Activity 生命周期 ====================

    private class FloatLifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            currentResumedActivity = activity;
            if (canFloat.get() && !isFloating.get()) {
                floatActivity(activity);
            } else if (isFloating.get()) {
                // 已在悬浮态，Activity 被 onResume 说明被拉回前台（如点击乐酷图标）
                // 保持悬浮不变，把 Activity 推回后台，让乐酷继续在前台
                activity.moveTaskToBack(true);
            }
        }

        @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
        @Override
        public void onActivityDestroyed(Activity activity) {
            if (activity == currentResumedActivity) {
                currentResumedActivity = null;
            }
            if (activity == currentFloatingActivity) {
                if (canFloat.get()) {
                    // 乐酷仍处于悬浮模式但 Activity 被销毁（横竖屏切换/内存压力等），
                    // 必须移除覆盖窗口，否则新 Activity 重建后 addView 会因 windowToken 残留而失败，
                    // 导致旧窗口泄漏 + 独立悬浮窗可能被启动 → 双悬浮窗
                    currentContentView = null;
                    currentFloatingActivity = null;
                    isFloating.set(false);
                    removeFloatWindow();
                } else {
                    restoreCurrentActivity();
                    removeFloatWindow();
                }
            }
        }
    }

    // ==================== 圆角裁剪 ====================

    /**
     * 根据乐酷通知的圆角参数 r 设置悬浮窗圆角裁剪。
     * r > 0 时用 ViewOutlineProvider 裁剪圆角，r = 0 时清除裁剪（直角）。
     */
    private void applyFloatCornerRadius() {
        if (windowContainer == null) return;
        if (floatCornerRadius > 0f) {
            windowContainer.setClipToOutline(true);
            windowContainer.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), floatCornerRadius);
                }
            });
            windowContainer.invalidate();
        } else {
            windowContainer.setClipToOutline(false);
            windowContainer.setOutlineProvider(null);
            windowContainer.invalidate();
        }
    }

    // ==================== 核心逻辑 ====================

    private void updateFloatWindowSize() {
        if (windowParams != null && windowContainer != null
                && windowContainer.getWindowToken() != null && floatRect != null) {
            int newW = floatRect.width() - floatRect.left;
            int newH = floatRect.height() - floatRect.top;
            windowParams.x = floatRect.left;
            windowParams.y = floatRect.top;
            windowParams.width = newW;
            windowParams.height = newH;
            windowManager.updateViewLayout(windowContainer, windowParams);
            if (currentContentView != null) {
                forceRelayout(currentContentView, newW, newH);
            }
        }
    }

    private void floatActivity(Activity activity) {
        try {
            // 参照酷我 IS_CHILD_VIEW 方案：只剥离 fragment_container，不剥离整个 ContentView
            // 这样 Activity 窗口仍有外层容器，不会触发 onSaveInstanceState
            View decorView = activity.getWindow().getDecorView();
            ViewGroup fragmentContainer = (ViewGroup) decorView.findViewById(R.id.fragment_container);
            if (fragmentContainer == null) {
                Log.w(TAG, "Cannot find fragment_container for " + activity.getClass().getSimpleName());
                return;
            }
            ViewGroup contentViewParent = (ViewGroup) fragmentContainer.getParent();
            if (contentViewParent == null) {
                Log.w(TAG, "fragment_container has no parent");
                return;
            }
            contentViewParent.removeView(fragmentContainer);

            originalParent = contentViewParent;
            originalLayoutParams = fragmentContainer.getLayoutParams();

            // 创建覆盖窗口容器（用 Application 上下文，不依赖 Activity 生命周期）
            if (windowContainer == null) {
                windowContainer = new FrameLayout(application);
                windowContainer.setOnApplyWindowInsetsListener((v, insets) -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        return android.view.WindowInsets.CONSUMED;
                    }
                    return insets.consumeSystemWindowInsets();
                });
            }

            int floatW = floatRect.width() - floatRect.left;
            int floatH = floatRect.height() - floatRect.top;

            windowContainer.removeAllViews();
            windowContainer.addView(fragmentContainer, new FrameLayout.LayoutParams(floatW, floatH));

            windowContainer.setClipChildren(true);
            windowContainer.setClipToPadding(true);
            applyFloatCornerRadius();

            if (windowParams == null) {
                windowParams = createLayoutParams(floatRect);
            } else {
                windowParams.x = floatRect.left;
                windowParams.y = floatRect.top;
                windowParams.width = floatW;
                windowParams.height = floatH;
            }

            // 确保 windowContainer 不残留旧 windowToken（上一次悬浮未干净移除时）
            if (isFloating.get() && windowContainer != null
                    && windowContainer.getWindowToken() == null) {
                // isFloating 标记为 true 但窗口已脱离 WindowManager，重置状态
                isFloating.set(false);
            }
            if (windowContainer != null && windowContainer.getWindowToken() != null) {
                // 窗口仍在 WindowManager 上（异常残留），先移除再重新添加
                try {
                    windowManager.removeViewImmediate(windowContainer);
                } catch (Exception ignored) {}
                isFloating.set(false);
            }

            if (!isFloating.get()) {
                windowManager.addView(windowContainer, windowParams);
            } else if (windowContainer.getWindowToken() != null) {
            windowManager.updateViewLayout(windowContainer, windowParams);
            applyFloatCornerRadius();
            }

            forceRelayout(fragmentContainer, floatW, floatH);

            currentFloatingActivity = activity;
            currentContentView = fragmentContainer;
            isFloating.set(true);
            lecoFloatExit = false;

            // 进入乐酷悬浮模式：关闭独立桌面悬浮窗
            try {
                application.stopService(new Intent(application,
                        com.jingxin.jingxinmusic.service.MiniFloatService.class));
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop MiniFloatService: " + e.getMessage());
            }

            // MiniFloatService 释放 Visualizer 会连带杀死同一 audioSessionId 上
            // PlayerFragment 的 Visualizer 回调，通知 PlayerFragment 重建
            // 延迟 500ms 确保 MiniFloatService.onDestroy → releaseVisualizer 已执行完
            sendSpectrumRestartBroadcast();
        } catch (Exception e) {
            Log.e(TAG, "floatActivity failed: " + e.getMessage(), e);
        }
    }

    private void restoreCurrentActivity() {
        try {
            if (currentContentView != null && originalParent != null) {
                if (windowContainer != null) {
                    windowContainer.removeView(currentContentView);
                }
                if (originalParent.getWindowToken() != null) {
                    if (currentContentView.getParent() == null) {
                        originalParent.addView(currentContentView, originalLayoutParams != null
                                ? originalLayoutParams
                                : new ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT));
                    }
                    // post 到下一帧强制重新布局为全屏尺寸
                    final View cv = currentContentView;
                    final ViewGroup parent = originalParent;
                    cv.post(() -> {
                        int pw = parent.getWidth();
                        int ph = parent.getHeight();
                        forceRelayout(cv, pw, ph);
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreCurrentActivity failed: " + e.getMessage(), e);
        } finally {
            currentContentView = null;
            currentFloatingActivity = null;
            isFloating.set(false);
        }
    }

    private void removeFloatWindow() {
        try {
            if (windowContainer != null && windowContainer.getWindowToken() != null) {
                windowManager.removeViewImmediate(windowContainer);
            }
        } catch (Exception e) {
            Log.e(TAG, "removeFloatWindow failed: " + e.getMessage(), e);
        }
        if (windowContainer != null) {
            windowContainer.removeAllViews();
        }
        windowParams = null;
        floatCornerRadius = 0f;
        isFloating.set(false);
    }

    // ==================== 窗口参数 ====================

    private WindowManager.LayoutParams createLayoutParams(Rect rect) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = rect.width() - rect.left;
        params.height = rect.height() - rect.top;
        params.x = rect.left;
        params.y = rect.top;
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        params.format = PixelFormat.RGBA_8888;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }

        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        return params;
    }

    // ==================== 工具方法 ====================

    /**
     * 强制对指定 View 执行 measure + layout 到精确尺寸，并请求重排
     */
    private void forceRelayout(View view, int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        view.forceLayout();
        if (windowContainer != null) windowContainer.requestLayout();
    }

    /**
     * 发送频谱重启广播，通知 PlayerFragment 重建 Visualizer
     */
    private void sendSpectrumRestartBroadcast() {
        Intent restartIntent = new Intent("com.jingxin.jingxinmusic.SPECTRUM_RESTART");
        restartIntent.setPackage(application.getPackageName());
        application.sendBroadcast(restartIntent);
    }

    // ==================== 公共 API ====================

    public boolean isFloating() {
        return isFloating.get();
    }

    /** 乐酷悬浮刚退出（由 LecoFloatManager 启动了独立悬浮窗），App 无需重复启动 */
    public boolean isLecoFloatExit() {
        return lecoFloatExit;
    }

    /** App 读取后清除标志，避免后续误判 */
    public void clearLecoFloatExit() {
        lecoFloatExit = false;
    }

    public boolean isCurrentFloatingActivity(Activity activity) {
        return isFloating.get() && activity == currentFloatingActivity;
    }

    /**
     * 获取乐酷悬浮区域宽度（像素），未悬浮时返回 0
     */
    public int getFloatWidth() {
        if (floatRect != null && isFloating.get()) {
            return floatRect.width() - floatRect.left;
        }
        return 0;
    }

    /**
     * 获取乐酷悬浮区域高度（像素），未悬浮时返回 0
     */
    public int getFloatHeight() {
        if (floatRect != null && isFloating.get()) {
            return floatRect.height() - floatRect.top;
        }
        return 0;
    }

    /**
     * 获取乐酷悬浮圆角参数（px），未取到时为 0（直角）
     */
    public float getFloatCornerRadius() {
        return floatCornerRadius;
    }

    public View findViewById(int id) {
        if (windowContainer != null) {
            return windowContainer.findViewById(id);
        }
        return null;
    }
}
