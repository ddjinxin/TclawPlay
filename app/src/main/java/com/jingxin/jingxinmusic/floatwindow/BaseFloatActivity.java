package com.jingxin.jingxinmusic.floatwindow;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 悬浮窗兼容基类（单 Activity 架构简化版）
 *
 * 重写 findViewById：当本 Activity 正在悬浮（View 被剥离到覆盖窗口）时，
 * findViewById 自动转向覆盖窗口容器查找，避免返回 null 导致崩溃。
 */
public class BaseFloatActivity extends AppCompatActivity {

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
