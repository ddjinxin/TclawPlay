package com.jingxin.jingxinmusic;

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.jingxin.jingxinmusic.floatwindow.BaseFloatActivity;
import com.jingxin.jingxinmusic.floatwindow.LecoFloatManager;
import com.jingxin.jingxinmusic.fragment.MainFragment;
import com.jingxin.jingxinmusic.fragment.SettingsFragment;

import android.content.res.Configuration;
import android.view.KeyEvent;

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
        // 悬浮窗按键转发：悬浮态 Activity 已 onStop，按键实际到达 overlay 窗口
        LecoFloatManager.getInstance().setKeyDelegate(this::handleCarKeyEvent);
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

    // ===== 车机按键映射（录制式） =====

    /**
     * 车机按键统一处理（Activity 窗口与悬浮窗共用入口）：
     * - 录制态：按键交给设置页绑定
     * - 非录制：命中已绑定键码则执行动作
     * 返回 true = 已消费。
     */
    boolean handleCarKeyEvent(KeyEvent event) {
        if (event == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            SettingsFragment settings = findSettingsFragment();
            if (settings != null && settings.isCarKeyRecording()) {
                settings.onCarKeyRecording(event.getKeyCode());
                return true;
            }
            String action = boundCarKeyAction(event.getKeyCode());
            if (action != null) {
                dispatchCarKeyAction(action);
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            SettingsFragment settings = findSettingsFragment();
            if (settings != null && settings.isCarKeyRecording()) return true; // 吞 UP 防触发返回
        }
        return false;
    }

    /**
     * 键码拦截入口：焦点路由把映射键投递到本窗口时（乐酷容器/其他桌面场景），
     * 应用侧自行认领执行——与乐酷自身的转发路径互补不重复。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && handleCarKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 键码→绑定动作（未绑定返回 null） */
    private String boundCarKeyAction(int keyCode) {
        if (keyCode != 0 && keyCode == SettingsFragment.getCarKeyCode(this, "next")) return "next";
        if (keyCode != 0 && keyCode == SettingsFragment.getCarKeyCode(this, "prev")) return "prev";
        if (keyCode != 0 && keyCode == SettingsFragment.getCarKeyCode(this, "play_pause")) return "play_pause";
        if (keyCode != 0 && keyCode == SettingsFragment.getCarKeyCode(this, "home")) return "home";
        return null;
    }

    private void dispatchCarKeyAction(String action) {
        try {
            if ("home".equals(action)) {
                // 返回车机默认桌面：标准 HOME Intent 路由到默认 launcher；
                // 找不到接受者时兑底退后台（音乐不受影响）
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(home);
                } catch (Exception e) {
                    moveTaskToBack(true);
                }
                return;
            }
            Intent intent = new Intent(this, com.jingxin.jingxinmusic.service.MusicPlayerService.class);
            if ("next".equals(action)) {
                intent.setAction("ACTION_NEXT");
            } else if ("prev".equals(action)) {
                intent.setAction("ACTION_PREVIOUS");
            } else {
                intent.setAction("ACTION_PLAY_PAUSE");
            }
            startService(intent);
        } catch (Exception ignored) {
        }
    }

    /** 查找当前栈顶的 SettingsFragment（录制态交互用） */
    private SettingsFragment findSettingsFragment() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        return f instanceof SettingsFragment ? (SettingsFragment) f : null;
    }

    /** LecoFloatManager 悬浮装窗时查询：录制态保持窗口可聚焦（捕获物理键） */
    public boolean isCarKeyRecordingActive() {
        SettingsFragment settings = findSettingsFragment();
        return settings != null && settings.isCarKeyRecording();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
