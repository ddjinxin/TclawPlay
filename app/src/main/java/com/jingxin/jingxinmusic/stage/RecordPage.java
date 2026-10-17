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
        // 对齐旧 Record 双退出钩子顺序（switchScene：先 onStyleExit 后 exit）：
        // recordOnStyleExit 重置黑胶模式（否则残留在 coverView 上，进沉浸/经典仍显示黑胶）
        h.recordOnStyleExit();
        h.recordExit();
    }

    @Override
    protected void onLayoutTonearm(int width, int height) {
        h.recordLayoutTonearm();
    }

    @Override
    public void onLyricModeChanged(boolean isFullScreen) {
        super.onLyricModeChanged(isFullScreen);
        // 全屏歌词唱臂处理按方向分叉（对齐旧Portrait/LandscapeRecordScene差异）：
        // 竖屏：藏唱臂（黑胶封面在下方，唱臂需复位）；横屏：保留唱臂（LandscapeRecord未覆盖此方法）
        if (!h.isLandscapeMode) {
            if (isFullScreen) {
                h.tonearmView.setVisibility(View.GONE);
            } else {
                h.tonearmView.setVisibility(View.VISIBLE);
                h.tonearmView.setLandscapeMode(h.isLandscapeMode);
                h.tonearmView.refreshAngle();
                h.callback.markTonearmNeedsUpdate();
            }
        } else {
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
