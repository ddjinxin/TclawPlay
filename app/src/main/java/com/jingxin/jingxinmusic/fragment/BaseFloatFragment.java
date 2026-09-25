package com.jingxin.jingxinmusic.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.view.View;
import android.widget.ScrollView;

import androidx.fragment.app.Fragment;

import com.jingxin.jingxinmusic.service.MusicPlayerService;
import com.jingxin.jingxinmusic.util.CompatUtil;

/**
 * 悬浮窗兼容 Fragment 基类
 *
 * 所有 Fragment 的 onCreateView 中使用 view.findViewById 查找视图，
 * 悬浮时通过 LecoFloatManager 管理覆盖窗口容器。
 */
public class BaseFloatFragment extends Fragment {

    /** 主题广播接收器（高德日夜切换），onThemeNightChanged 回调在主线程 */
    private BroadcastReceiver themeReceiver;
    private boolean themeReceiverRegistered = false;

    /**
     * 注册高德日夜主题广播：日夜变化时回调 onThemeNightChanged（新值）
     * 乐酷悬浮态 Activity 已 stopped，onResume 重读不触发，必须广播驱动
     */
    protected void registerThemeReceiver() {
        if (themeReceiverRegistered) return;
        themeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!MusicPlayerService.ACTION_THEME_CHANGED.equals(intent.getAction())) return;
                boolean night = intent.getBooleanExtra(MusicPlayerService.EXTRA_IS_NIGHT, true);
                if (getView() != null) onThemeNightChanged(night);
            }
        };
        IntentFilter filter = new IntentFilter(MusicPlayerService.ACTION_THEME_CHANGED);
        CompatUtil.safeRegisterReceiver(requireContext(), themeReceiver, filter);
        themeReceiverRegistered = true;
    }

    /** 注销主题广播接收器 */
    protected void unregisterThemeReceiver() {
        if (!themeReceiverRegistered) return;
        try {
            if (getContext() != null) getContext().unregisterReceiver(themeReceiver);
        } catch (Exception ignored) {}
        themeReceiver = null;
        themeReceiverRegistered = false;
    }

    /** 日夜变化回调，子类覆写：同步 isNightMode 字段并 applyTheme() */
    protected void onThemeNightChanged(boolean isNight) {}

    /**
     * 统一为 ScrollView 适配顶部 WindowInsets（状态栏高度），
     * 消除各 Fragment 中重复的 OnApplyWindowInsetsListener 样板代码。
     *
     * @param scrollView 需要适配的 ScrollView
     */
    protected void applyTopInset(ScrollView scrollView) {
        if (scrollView == null) return;
        scrollView.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                topInset = insets.getInsets(android.view.WindowInsets.Type.systemBars()).top;
            } else {
                topInset = insets.getSystemWindowInsetTop();
            }
            scrollView.setPadding(
                    scrollView.getPaddingLeft(), topInset,
                    scrollView.getPaddingRight(), scrollView.getPaddingBottom());
            return insets;
        });
    }
}
