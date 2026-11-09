package com.jingxin.jingxinmusic;

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.jingxin.jingxinmusic.floatwindow.BaseFloatActivity;
import com.jingxin.jingxinmusic.floatwindow.LecoFloatManager;
import com.jingxin.jingxinmusic.fragment.MainFragment;

import android.content.res.Configuration;
import android.view.View;
import android.view.Window;

/**
 * 单 Activity 容器
 * 所有页面通过 Fragment 切换，悬浮时只剥离 fragment_container 到覆盖窗口
 *
 * 关键：悬浮态下 Activity 会被系统 onStop，FragmentManager 进入 stopped 状态，
 * 此时 popBackStack / popBackStackImmediate 都会抛 IllegalStateException。
 * 因此悬浮态下不能用 FragmentManager 的 back stack，只能用 replace + commitAllowingStateLoss。
 */
public class HostActivity extends BaseFloatActivity {

    /** 子 Fragment 设置后，MainFragment.onResume 会读取并切换 tab，然后清回 -1 */
    public int pendingTab = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration config = getResources().getConfiguration();
        setContentView(R.layout.activity_host);

        if (savedInstanceState == null) {
            MainFragment mainFragment = new MainFragment();
            int tab = getIntent().getIntExtra("select_tab", -1);
            if (tab >= 0) {
                pendingTab = tab;
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, mainFragment)
                    .commit();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int tab = intent.getIntExtra("select_tab", -1);
        if (tab >= 0) {
            pendingTab = tab;
        }
    }

    /**
     * 导航到指定 Fragment
     * @param fragment 目标 Fragment
     * @param addToBackStack 是否加入返回栈（仅非悬浮态有效）
     */
    public void navigateTo(Fragment fragment, boolean addToBackStack) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment);
        if (addToBackStack) ft.addToBackStack(null);
        // Activity 处于 RESUMED 态才用 commit，否则（乐酷分屏切换 stop 后）
        // commit 会抛 Can not perform this action after onSaveInstanceState
        boolean resumed = getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.RESUMED;
        if (LecoFloatManager.getInstance().isFloating() || !resumed) {
            // 悬浮态或 Activity 已 onStop（乐酷分屏切换时启动后立即退后台）：
            // 必须用 commitAllowingStateLoss（配合崩溃循环防护）
            ft.commitAllowingStateLoss();
        } else {
            ft.commit();
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    /**
     * 状态栏沉浸切换（仅延伸顶部，不影响底部导航区）
     * 沉浸=窗口铺到状态栏下+状态栏透明（背景/封面顶满屏幕，系统图标浮于其上）；
     * 恢复=系统把窗口垫回状态栏下方+主题色状态栏。
     * 按钮避让由 StatusBarAvoid 公式自动接管（沉浸后 rootTop=0 → 避让整条状态栏）。
     * Android 15+ 系统强制边到边，此方法两态等价无副作用。
     */
    public void setStatusBarImmersive(boolean immersive) {
        Window window = getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (immersive) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        } else {
            decor.setSystemUiVisibility(0);
            window.setStatusBarColor(
                    androidx.core.content.ContextCompat.getColor(this, R.color.background));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (LecoFloatManager.getInstance().isFloating()) {
            // 悬浮态：Activity 已被 onStop，FragmentManager 处于 stopped 状态
            // popBackStack / popBackStackImmediate 都会抛 IllegalStateException
            // 直接 replace 回 MainFragment，不用 back stack
            // 但要先把 MainFragment 的 hasAutoResumed 设为 true 防止自动跳回播放页
            MainFragment mainFragment = new MainFragment();
            mainFragment.setAutoResumed(true);
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, mainFragment);
            ft.commitAllowingStateLoss();
        } else {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStack();
            } else {
                super.onBackPressed();
            }
        }
    }
}
