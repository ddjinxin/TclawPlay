package com.jingxin.jingxinmusic;

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.jingxin.jingxinmusic.floatwindow.BaseFloatActivity;
import com.jingxin.jingxinmusic.floatwindow.LecoFloatManager;
import com.jingxin.jingxinmusic.fragment.MainFragment;

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
        if (LecoFloatManager.getInstance().isFloating()) {
            ft.commitAllowingStateLoss();
        } else {
            ft.commit();
        }
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
