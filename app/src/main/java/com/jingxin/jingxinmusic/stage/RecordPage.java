package com.jingxin.jingxinmusic.stage;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.jingxin.jingxinmusic.view.LyricView;

/**
 * 唱片机风格页面（组件化第6步）
 *
 * 继承 ClassicPage，覆盖黑胶/唱臂逻辑（对齐旧 RecordScene 继承 Classic 的结构）：
 * - 封面：黑胶模式（不裁剪），唱臂联动播放态摆角
 * - 全屏歌词：竖屏藏唱臂（黑胶在下方），横屏保留唱臂
 * - 播放/服务恢复：唱臂摆动恢复
 */
public class RecordPage extends ClassicPage {

    public RecordPage(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    @Override
    protected void setupCoverStyle() {
        h.recordSetupCoverStyle();
    }

    @Override
    protected void onStyleEnterView() {
        h.recordOnStyleEnter();
    }

    @Override
    protected void exitView() {
        h.recordExit();
    }

    @Override
    protected void onLayoutTonearm(int width, int height) {
        h.recordLayoutTonearm();
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
        return true; // 黑胶封面需重载
    }
}
