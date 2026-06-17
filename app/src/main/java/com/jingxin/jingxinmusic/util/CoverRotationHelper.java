package com.jingxin.jingxinmusic.util;

import android.animation.ObjectAnimator;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 封面旋转动画工具类
 * 统一 MainActivity 迷你播放条和 MiniFloatService 悬浮窗的封面旋转逻辑
 */
public class CoverRotationHelper {

    /** 旋转周期（毫秒） */
    private static final long ROTATION_DURATION = 12000L;

    private ObjectAnimator animator;

    /**
     * 创建并绑定旋转动画到指定 View
     * @param view 目标 View（通常为封面 ImageView）
     */
    public void attach(View view) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f);
        animator.setDuration(ROTATION_DURATION);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ObjectAnimator.INFINITE);
    }

    /**
     * 启动/恢复旋转（暂停后 resume，未启动则 start）
     */
    public void start() {
        if (animator == null) return;
        if (animator.isPaused()) {
            animator.resume();
        } else if (!animator.isRunning()) {
            animator.start();
        }
    }

    /**
     * 暂停旋转
     */
    public void pause() {
        if (animator != null && animator.isRunning()) {
            animator.pause();
        }
    }

    /**
     * 根据播放状态更新旋转
     */
    public void update(boolean playing) {
        if (playing) {
            start();
        } else {
            pause();
        }
    }

    /**
     * 停止并释放动画
     */
    public void release() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
