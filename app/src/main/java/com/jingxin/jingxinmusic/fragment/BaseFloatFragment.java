package com.jingxin.jingxinmusic.fragment;

import android.os.Build;
import android.view.View;
import android.widget.ScrollView;

import androidx.fragment.app.Fragment;

/**
 * 悬浮窗兼容 Fragment 基类
 *
 * 所有 Fragment 的 onCreateView 中使用 view.findViewById 查找视图，
 * 悬浮时通过 LecoFloatManager 管理覆盖窗口容器。
 */
public class BaseFloatFragment extends Fragment {

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
