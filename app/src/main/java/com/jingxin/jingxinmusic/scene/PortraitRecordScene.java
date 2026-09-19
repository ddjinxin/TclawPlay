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
        h.recordSetupCoverStyle();
    }

    @Override
    protected void onLayoutTonearm() {
        h.recordLayoutTonearm();
    }

    @Override
    public void exit() {
        h.recordExit();
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        super.onLyricModeChanged(isFullScreen);
        // 全屏歌词时隐藏唱臂，退出全屏恢复（黑胶封面仍在下方，唱臂需要复位）
        if (isFullScreen) {
            h.tonearmView.setVisibility(View.GONE);
        } else {
            h.tonearmView.setVisibility(View.VISIBLE);
            h.tonearmView.setLandscapeMode(h.isLandscapeMode);
            h.tonearmView.refreshAngle();
            h.callback.markTonearmNeedsUpdate();
        }
    }

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        h.recordOnPlayingStateChanged(isPlaying);
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        h.recordOnServiceResumed(isPlaying);
    }

    @Override
    public boolean needsReloadCover() {
        return true;
    }

    @Override
    public void onStyleEnter() {
        h.recordOnStyleEnter();
    }

    @Override
    public void onStyleExit() {
        h.recordOnStyleExit();
    }
}
