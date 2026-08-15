package com.jingxin.jingxinmusic.floatwindow;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.MainActivity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 乐酷桌面悬浮窗管理器
 *
 * 原理（与酷我/酷狗 Touka FWSK SDK 相同的 View 剥离方案）：
 * 1. 收到 showmap 广播后标记 canFloat=true，在当前 Activity onResume 时
 *    将其内容 View 从原父容器剥离，放入 WindowManager 覆盖窗口显示到指定区域
 * 2. Activity 切换时先还原旧 Activity 的 View，再剥离新 Activity 的 View（不摘窗口）
 * 3. closemap 广播时还原 View 并移除覆盖窗口
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

    // 当前正在悬浮的 Activity 及其内容 View
    private Activity currentFloatingActivity;
    private View currentContentView;
    private ViewGroup originalParent;
    private int originalIndex;

    // 所有被剥离过 View 的 Activity（Activity 切换时不还原旧 View，保持窗口透明）
    private final java.util.Map<Activity, View[]> detachedActivities = new java.util.concurrent.ConcurrentHashMap<>();
    // detachedActivities value: [0]=contentView, [1]=originalParent (ViewGroup)

    // 悬浮区域
    private Rect floatRect;

    // 状态标志
    private final AtomicBoolean canFloat = new AtomicBoolean(false);
    private final AtomicBoolean isFloating = new AtomicBoolean(false);
    private FloatReceiver receiver;
    private boolean receiverRegistered = false;

    // Activity 切换宽限期：切换后短时间内忽略 closemap
    // 因为 startActivity 创建新窗口会触发乐酷发 closemap
    private long floatGraceUntil = 0;
    private static final long GRACE_DURATION_MS = 2000;

    // 当前 resumed 的 Activity（在 onActivityResumed 中更新）
    private Activity currentResumedActivity;

    // 所有存活的 Activity（按创建顺序）
    private final java.util.List<Activity> liveActivities = new java.util.concurrent.CopyOnWriteArrayList<>();

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

    /**
     * 在 Application.onCreate 中调用，注册广播和生命周期回调
     */
    public void init(Application app) {
        this.application = app;
        this.windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        registerReceiver();
        app.registerActivityLifecycleCallbacks(new FloatLifecycle());
        Log.d(TAG, "LecoFloatManager initialized");
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        receiver = new FloatReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW_MAP);
        filter.addAction(ACTION_CLOSE_MAP);
        application.registerReceiver(receiver, filter);
        receiverRegistered = true;
        Log.d(TAG, "Broadcast receiver registered");
    }

    // ==================== 广播接收 ====================

    private class FloatReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "onReceive: " + action);

            if (ACTION_SHOW_MAP.equals(action)) {
                int x = intent.getIntExtra("x", 0);
                int y = intent.getIntExtra("y", 0);
                int w = intent.getIntExtra("w", 0);
                int h = intent.getIntExtra("h", 0);
                Log.d(TAG, "showmap: x=" + x + " y=" + y + " w=" + w + " h=" + h);

                if (w > 0 && h > 0) {
                    floatRect = new Rect(x, y, x + w, y + h);
                    canFloat.set(true);
                    // 收到 showmap 后主动触发当前 Activity 悬浮
                    if (currentResumedActivity != null) {
                        tryFloatActivity(currentResumedActivity);
                    }
                }
            } else if (ACTION_CLOSE_MAP.equals(action)) {
                Log.d(TAG, "closemap received");
                // 宽限期内忽略 closemap（Activity 切换时乐酷会误发）
                if (isFloating.get() && System.currentTimeMillis() < floatGraceUntil) {
                    Log.d(TAG, "closemap ignored (within grace period)");
                    return;
                }
                canFloat.set(false);
                restoreCurrentActivity();
                removeFloatWindow();
                // 乐酷悬浮退出后，如果 app 在后台，恢复独立桌面悬浮窗
                Intent floatIntent = new Intent(application, com.jingxin.jingxinmusic.service.MiniFloatService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    application.startForegroundService(floatIntent);
                } else {
                    application.startService(floatIntent);
                }
                Log.d(TAG, "Leco float closed, MiniFloatService started");
            }
        }
    }

    // ==================== Activity 生命周期 ====================

    private class FloatLifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            currentResumedActivity = activity;
            Log.d(TAG, "onActivityResumed: " + activity.getClass().getSimpleName()
                    + " canFloat=" + canFloat.get() + " isFloating=" + isFloating.get());
            if (canFloat.get()) {
                // Activity 切换时设置宽限期，防止乐酷误发 closemap
                if (isFloating.get() && currentFloatingActivity != activity) {
                    floatGraceUntil = System.currentTimeMillis() + GRACE_DURATION_MS;
                    Log.d(TAG, "Grace period set until " + floatGraceUntil);
                }
                tryFloatActivity(activity);
            }
        }

        @Override
        public void onActivityCreated(Activity a, android.os.Bundle b) {
            liveActivities.add(a);
        }
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
        @Override
        public void onActivityDestroyed(Activity activity) {
            liveActivities.remove(activity);
            // 从已剥离列表中移除（Activity 已销毁，不需还原）
            detachedActivities.remove(activity);
            if (activity == currentFloatingActivity) {
                Log.d(TAG, "onActivityDestroyed: " + activity.getClass().getSimpleName()
                        + " was floating, canFloat=" + canFloat.get());
                if (canFloat.get()) {
                    // 乐酷仍在要求悬浮：不摘窗口，找到 MainActivity 接管
                    if (windowContainer != null && currentContentView != null) {
                        windowContainer.removeView(currentContentView);
                    }
                    currentContentView = null;
                    currentFloatingActivity = null;
                    Activity main = findLiveActivity(MainActivity.class);
                    if (main != null && !main.isFinishing() && !main.isDestroyed()) {
                        Log.d(TAG, "Found alive MainActivity, peeling its view to float window");
                        floatActivity(main);
                    } else {
                        Log.d(TAG, "No alive MainActivity, removing float window");
                        removeFloatWindow();
                    }
                } else {
                    restoreCurrentActivity();
                    removeFloatWindow();
                }
            }
        }
    }

    // ==================== 核心逻辑 ====================

    /**
     * 尝试将指定 Activity 的内容 View 剥离到覆盖窗口
     * 处理 Activity 切换时的无缝转移
     */
    private void tryFloatActivity(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        // 如果就是这个 Activity 正在悬浮，检查 showmap 参数是否变化
        if (activity == currentFloatingActivity && isFloating.get()) {
            // 悬浮区域变化时（如旋转屏幕后乐酷重发 showmap），更新窗口位置和尺寸
            if (windowParams != null && floatRect != null) {
                // 乐酷传的 w/h 包含了从屏幕原点的偏移，实际宽高需减去 x/y
                int newW = floatRect.width() - floatRect.left;
                int newH = floatRect.height() - floatRect.top;
                if (windowParams.x != floatRect.left || windowParams.y != floatRect.top
                        || windowParams.width != newW || windowParams.height != newH) {
                    Log.d(TAG, "Float rect changed, updating: " + floatRect
                            + " => W=" + newW + " H=" + newH);
                    windowParams.x = floatRect.left;
                    windowParams.y = floatRect.top;
                    windowParams.width = newW;
                    windowParams.height = newH;
                    if (windowContainer != null && windowContainer.getWindowToken() != null) {
                        windowManager.updateViewLayout(windowContainer, windowParams);
                    }
                    // 重新布局 contentView
                    if (currentContentView != null && windowContainer != null) {
                        final int fw = newW;
                        final int fh = newH;
                        final View cv = currentContentView;
                        windowContainer.post(() -> {
                            cv.measure(
                                    View.MeasureSpec.makeMeasureSpec(fw, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(fh, View.MeasureSpec.EXACTLY));
                            cv.layout(0, 0, fw, fh);
                            cv.forceLayout();
                            windowContainer.requestLayout();
                        });
                    }
                }
            }
            Log.d(TAG, "Already floating this activity: " + activity.getClass().getSimpleName());

            // 即便是同一个 Activity 已在悬浮，am start 仍会把静心音乐 Task 提到前台，
            // 全屏矩形窗口（即使 alpha=0）会盖住乐酷的圆角。
            // 需要再次 moveTaskToBack 让乐酷回到前台。
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    activity.moveTaskToBack(true);
                    Log.d(TAG, "moveTaskToBack done (already floating)");
                } catch (Exception e) {
                    Log.w(TAG, "moveTaskToBack failed (already floating): " + e.getMessage());
                }
            }, 300);
            return;
        }

        // 如果有其他 Activity 正在悬浮：不还原旧 View，直接剥离新 Activity 的 View
        // 旧 Activity 窗口保持空壳透明，乐酷桌面透过来
        if (isFloating.get() && currentFloatingActivity != null && currentFloatingActivity != activity) {
            Log.d(TAG, "Switching float from " + currentFloatingActivity.getClass().getSimpleName()
                    + " to " + activity.getClass().getSimpleName()
                    + " (keeping old view detached)");
            // 隐藏旧 Activity 窗口：只设 NOT_TOUCHABLE 不够，需完全隐藏 Surface 才能让乐酷接收触摸
            hideActivityWindow(currentFloatingActivity);
            // 记录旧 Activity 的 View 信息（closemap 时统一还原）
            saveDetachedActivity(currentFloatingActivity, currentContentView, originalParent);
            // 从覆盖窗口容器移除旧 View（不还回原父容器）
            if (windowContainer != null && currentContentView != null) {
                windowContainer.removeView(currentContentView);
            }
            currentContentView = null;
            currentFloatingActivity = null;
            // 注意：isFloating 保持 true，窗口还在
        }

        floatActivity(activity);
    }

    /**
     * 将 Activity 的内容 View 剥离到覆盖窗口
     * 如果该 Activity 之前已被剥离过（在 detachedActivities 中），直接复用其 View
     */
    private void floatActivity(Activity activity) {
        try {
            View contentView;
            ViewGroup contentViewParent;

            // 检查是否已被剥离过（Activity 切换回来的情况）
            View[] saved = detachedActivities.remove(activity);
            if (saved != null) {
                contentView = saved[0];
                contentViewParent = (ViewGroup) saved[1];
                Log.d(TAG, "Reusing detached view for " + activity.getClass().getSimpleName());
            } else {
                // 首次剥离
                View decorView = activity.getWindow().getDecorView();
                contentViewParent = (ViewGroup) decorView.findViewById(android.R.id.content);
                if (contentViewParent == null || contentViewParent.getChildCount() == 0) {
                    Log.w(TAG, "Cannot find content view for " + activity.getClass().getSimpleName());
                    return;
                }
                contentView = contentViewParent.getChildAt(0);
                if (contentView == null) {
                    Log.w(TAG, "Content view child is null");
                    return;
                }
                contentViewParent.removeView(contentView);
            }

            originalParent = contentViewParent;
            originalIndex = 0;

            // 将 Activity 窗口设为透明，让乐酷桌面透过来
            showActivityWindow(activity);
            makeActivityWindowTransparent(activity);

            // 创建或复用覆盖窗口容器
            if (windowContainer == null) {
                windowContainer = new FrameLayout(activity);
                // 覆盖窗口不在 Activity 窗口层级中，系统会注入状态栏 inset 导致空白
                // 消费所有 inset，返回全 0，让内容填满悬浮区域
                windowContainer.setOnApplyWindowInsetsListener((v, insets) -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        return android.view.WindowInsets.CONSUMED;
                    }
                    return insets.consumeSystemWindowInsets();
                });
            }
            // 乐酷传的 w/h 包含了从屏幕原点的偏移，实际宽高需减去 x/y
            int floatW = floatRect.width() - floatRect.left;
            int floatH = floatRect.height() - floatRect.top;

            windowContainer.removeAllViews();
            windowContainer.addView(contentView, new FrameLayout.LayoutParams(
                    floatW, floatH));

            // 裁剪到悬浮区域大小，防止子 View 溢出
            windowContainer.setClipChildren(true);
            windowContainer.setClipToPadding(true);
            windowContainer.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, floatW, floatH, 0);
                }
            });
            windowContainer.setClipToOutline(true);

            if (windowParams == null) {
                windowParams = createLayoutParams(floatRect);
            } else {
                updateWindowPosition(floatRect);
            }

            // 窗口本身也要设为减去偏移后的尺寸
            if (windowParams != null) {
                windowParams.width = floatW;
                windowParams.height = floatH;
                if (windowContainer.getWindowToken() != null) {
                    windowManager.updateViewLayout(windowContainer, windowParams);
                }
            }

            if (!isFloating.get()) {
                windowManager.addView(windowContainer, windowParams);
                Log.d(TAG, "WindowManager.addView done");
            }

            // 窗口添加后强制按悬浮尺寸重新布局
            final int fw = floatW;
            final int fh = floatH;
            final View cv = contentView;
            windowContainer.post(() -> {
                cv.measure(
                        View.MeasureSpec.makeMeasureSpec(fw, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(fh, View.MeasureSpec.EXACTLY));
                cv.layout(0, 0, fw, fh);
                cv.forceLayout();
                windowContainer.requestLayout();
            });

            currentFloatingActivity = activity;
            currentContentView = contentView;
            isFloating.set(true);

            // 进入乐酷悬浮模式：关闭独立桌面悬浮窗（MiniFloatService）
            // 防止两者同时显示
            try {
                application.stopService(new Intent(application,
                        com.jingxin.jingxinmusic.service.MiniFloatService.class));
                Log.d(TAG, "MiniFloatService stopped (entering Leco float mode)");
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop MiniFloatService: " + e.getMessage());
            }

            // 把静心音乐 Task 移到后台，让乐酷 Task 回到前面
            // 这样乐酷能正常接收触摸（Android 12+ 的 BLOCK_UNTRUSTED 会遮挡不同签名的底层 Task）
            // 覆盖窗口（APPLICATION_OVERLAY）z-order 更高，不受影响
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    activity.moveTaskToBack(true);
                    Log.d(TAG, "moveTaskToBack done");
                } catch (Exception e) {
                    Log.w(TAG, "moveTaskToBack failed: " + e.getMessage());
                }
            }, 300);

            Log.d(TAG, "Floating activity: " + activity.getClass().getSimpleName()
                    + " rect=" + floatRect);

        } catch (Exception e) {
            Log.e(TAG, "floatActivity failed: " + e.getMessage(), e);
        }
    }

    /**
     * 悬浮时把 Activity 窗口设为透明可穿透
     * 通过 windowAlpha=0 让 WMS 将窗口标记为可透明合成，效果等同 windowIsTranslucent
     * 但完全运行时控制，不影响正常全屏模式
     */
    private void makeActivityWindowTransparent(Activity activity) {
        try {
            android.view.Window win = activity.getWindow();
            if (win != null) {
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            // alpha=0 让 WMS 窗口可透明合成，相当于 windowIsTranslucent 的运行时等效
            win.setDimAmount(0);
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.alpha = 0;
            win.setAttributes(lp);
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            // 清除状态栏和导航栏背景色，否则空壳窗口的系统栏区域会显示背景色挡住乐酷桌面
            win.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            win.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            View decorView = win.getDecorView();
            if (decorView != null) {
                decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                View content = decorView.findViewById(android.R.id.content);
                if (content != null) {
                    content.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                }
            }
            win.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
        } catch (Exception e) {
            Log.e(TAG, "makeActivityWindowTransparent failed: " + e.getMessage());
        }
    }

    /**
     * 完全隐藏 Activity 窗口（切换页面时对旧 Activity 调用）
     * NOT_TOUCHABLE 不足以让触摸穿透，需要把 DecorView 隐藏才能让乐酷接收事件
     */
    private void hideActivityWindow(Activity activity) {
        try {
            android.view.Window win = activity.getWindow();
            if (win != null) {
                // 透明 + 不可触摸 + 不可聚焦
                makeActivityWindowTransparent(activity);
                // 隐藏 DecorView，让窗口 Surface 不再渲染
                View decorView = win.getDecorView();
                if (decorView != null) {
                    decorView.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "hideActivityWindow failed: " + e.getMessage());
        }
    }

    /**
     * 恢复被隐藏的 Activity 窗口
     */
    private void showActivityWindow(Activity activity) {
        try {
            android.view.Window win = activity.getWindow();
            if (win != null) {
                View decorView = win.getDecorView();
                if (decorView != null && decorView.getVisibility() != View.VISIBLE) {
                    decorView.setVisibility(View.VISIBLE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "showActivityWindow failed: " + e.getMessage());
        }
    }

    /**
     * 还原 Activity 窗口
     */
    private void restoreActivityWindow(Activity activity) {
        try {
            showActivityWindow(activity);
            android.view.Window win = activity.getWindow();
            if (win != null) {
                win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                // 恢复窗口不透明
                WindowManager.LayoutParams lp = win.getAttributes();
                lp.alpha = 1;
                win.setAttributes(lp);
                // 恢复状态栏和导航栏背景色
                win.setStatusBarColor(activity.getColor(com.jingxin.jingxinmusic.R.color.background));
                win.setNavigationBarColor(activity.getColor(com.jingxin.jingxinmusic.R.color.background));
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreActivityWindow failed: " + e.getMessage());
        }
    }

    /**
     * 记录被剥离过 View 的 Activity，closemap 时统一还原
     */
    private void saveDetachedActivity(Activity activity, View contentView, ViewGroup parent) {
        if (activity != null && contentView != null && parent != null) {
            detachedActivities.put(activity, new View[]{contentView, parent});
        }
    }

    /**
     * 还原所有被剥离的 Activity View（closemap 时调用）
     */
    private void restoreAllDetachedViews() {
        for (java.util.Map.Entry<Activity, View[]> entry : detachedActivities.entrySet()) {
            Activity activity = entry.getKey();
            View[] data = entry.getValue();
            try {
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed()
                        && data[1] != null && data[1].getWindowToken() != null) {
                    ViewGroup parent = (ViewGroup) data[1];
                    // 如果 View 已在 parent 中则跳过
                    if (data[0].getParent() == null) {
                        parent.addView(data[0]);
                    }
                    restoreActivityWindow(activity);
                }
            } catch (Exception e) {
                Log.e(TAG, "restoreAllDetachedViews failed for " + activity.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        detachedActivities.clear();
    }

    /**
     * 还原当前 Activity 的 View 并完全移除覆盖窗口
     */
    private void restoreCurrentActivity() {
        try {
            // 还原所有被剥离的 View
            restoreAllDetachedViews();
            // 还原当前悬浮的 View
            if (currentContentView != null && originalParent != null) {
                windowContainer.removeView(currentContentView);
                if (originalParent.getWindowToken() != null) {
                    if (currentContentView.getParent() == null) {
                        originalParent.addView(currentContentView);
                    }
                    Log.d(TAG, "View fully restored");
                }
                if (currentFloatingActivity != null) {
                    restoreActivityWindow(currentFloatingActivity);
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
                Log.d(TAG, "Float window removed");
            }
        } catch (Exception e) {
            Log.e(TAG, "removeFloatWindow failed: " + e.getMessage(), e);
        }
        if (windowContainer != null) {
            windowContainer.removeAllViews();
        }
        windowParams = null;
        isFloating.set(false);
    }

    // ==================== 窗口参数 ====================

    private WindowManager.LayoutParams createLayoutParams(Rect rect) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        // 乐酷传的 w/h 包含了从屏幕原点的偏移，实际宽高需减去 x/y
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

    private void updateWindowPosition(Rect rect) {
        if (windowParams != null && rect != null) {
            // 乐酷传的 w/h 包含了从屏幕原点的偏移，实际宽高需减去 x/y
            windowParams.width = rect.width() - rect.left;
            windowParams.height = rect.height() - rect.top;
            windowParams.x = rect.left;
            windowParams.y = rect.top;
            if (windowContainer != null && windowContainer.getWindowToken() != null) {
                windowManager.updateViewLayout(windowContainer, windowParams);
            }
        }
    }

    // ==================== 公共 API ====================

    /**
     * 从存活 Activity 列表中找到指定类的实例
     */
    private Activity findLiveActivity(Class<?> clazz) {
        for (int i = liveActivities.size() - 1; i >= 0; i--) {
            Activity a = liveActivities.get(i);
            if (clazz.isInstance(a) && !a.isFinishing() && !a.isDestroyed()) {
                return a;
            }
        }
        return null;
    }

    public boolean isFloating() {
        return isFloating.get();
    }

    public boolean isCurrentFloatingActivity(Activity activity) {
        return isFloating.get() && activity == currentFloatingActivity;
    }

    /**
     * 从覆盖窗口容器中查找 View
     * BaseFloatActivity.findViewById 会调用此方法
     */
    public View findViewById(int id) {
        if (windowContainer != null) {
            return windowContainer.findViewById(id);
        }
        return null;
    }
}
