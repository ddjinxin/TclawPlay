package com.jingxin.jingxinmusic.scene;

import android.view.View;

/**
 * 竖屏唱片机模式——继承竖屏经典，仅覆盖黑胶/唱臂相关逻辑
 */
public class PortraitRecordScene extends PortraitClassicScene {

    public PortraitRecordScene(CoverSceneHelper helper) {
        super(helper);
    }

    @Override
    protected void setupCoverStyle() {
        // 黑胶模式：不裁剪，无圆形边框
        h.coverView.setClipToOutline(false);
        h.coverView.setBackground(null);
        h.coverView.setForeground(null);
    }

    @Override
    protected void onLayoutTonearm() {
        if (h.tonearmView != null) {
            h.tonearmView.setLandscapeMode(false);
            h.callback.updateTonearmPosition();
            h.tonearmView.refreshAngle();
        }
    }

    @Override
    public void exit() {
        // 隐藏唱臂
        if (h.tonearmView != null) {
            h.tonearmView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        // 唱片机：唱臂动画 + 封面旋转
        if (h.tonearmView != null) {
            h.tonearmView.setPlaying(isPlaying);
        }
        if (isPlaying) {
            h.coverView.startRotation();
        } else {
            h.coverView.stopRotation();
        }
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        // 从 mini 播放条恢复：同步唱臂状态和位置
        if (h.tonearmView != null) {
            h.tonearmView.setLandscapeMode(h.isLandscapeMode);
            h.tonearmView.setPlaying(isPlaying);
            h.tonearmView.refreshAngle();
            h.callback.updateTonearmPosition();
        }
    }

    @Override
    public boolean needsReloadCover() {
        return true;
    }

    @Override
    public void onStyleEnter() {
        // 进入唱片机模式：启用黑胶 + 显示唱臂
        h.coverView.setVinylMode(true);
        if (h.tonearmView != null) {
            h.tonearmView.setVisibility(View.VISIBLE);
            h.tonearmView.setLandscapeMode(h.isLandscapeMode);
            h.tonearmView.setNightMode(h.isNightMode);
            boolean isCurrentlyPlaying = h.isPlaying;
            h.tonearmView.setPlaying(isCurrentlyPlaying);
            h.tonearmView.refreshAngle();
            h.callback.updateTonearmPosition();
        }
    }

    @Override
    public void onStyleExit() {
        // 退出唱片机模式：关闭黑胶 + 隐藏唱臂
        h.coverView.setVinylMode(false);
        if (h.tonearmView != null) {
            h.tonearmView.setVisibility(View.GONE);
        }
    }
}
