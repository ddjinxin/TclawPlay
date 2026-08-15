package com.jingxin.jingxinmusic.floatwindow;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 悬浮窗兼容基类
 *
 * 重写 findViewById：当本 Activity 正在悬浮（View 被剥离到覆盖窗口）时，
 * findViewById 自动转向覆盖窗口容器查找，避免返回 null 导致崩溃。
 *
 * 这与酷狗 MusicActivity 的做法完全一致：
 *   return FloatWindow.getInstance().getShowingFloatWindow()
 *       ? FloatWindow.getInstance().findViewById(id)
 *       : super.findViewById(id);
 */
public class BaseFloatActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 悬浮模式下，Activity 窗口在 onCreate 阶段就提前设为透明可穿透
        // 否则新 Activity 窗口在 onActivityResumed 之前全屏不透明，挡住乐酷桌面
        LecoFloatManager fm = LecoFloatManager.getInstance();
        if (fm.isFloating()) {
            android.view.Window win = getWindow();
            win.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            // 提前设 alpha=0 让窗口透明，防 onCreate 到 onResume 之间闪现全屏
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.alpha = 0;
            win.setAttributes(lp);
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            win.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            win.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }
    }

    @Override
    public <T extends View> T findViewById(int id) {
        LecoFloatManager fm = LecoFloatManager.getInstance();
        if (fm.isCurrentFloatingActivity(this)) {
            View v = fm.findViewById(id);
            if (v != null) {
                return (T) v;
            }
        }
        return super.findViewById(id);
    }
}
