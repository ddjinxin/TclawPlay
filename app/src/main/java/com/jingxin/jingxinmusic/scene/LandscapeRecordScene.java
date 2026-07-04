package com.jingxin.jingxinmusic.scene;

/**
 * 横屏唱片机模式——继承横屏经典，仅覆盖黑胶/唱臂相关逻辑
 */
public class LandscapeRecordScene extends LandscapeClassicScene {

    public LandscapeRecordScene(CoverSceneHelper helper) {
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
