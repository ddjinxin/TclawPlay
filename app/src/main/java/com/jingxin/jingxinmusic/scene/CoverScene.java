package com.jingxin.jingxinmusic.scene;

import android.graphics.Bitmap;

/**
 * 播放页封面/布局策略接口
 * 四种模式：竖屏经典、竖屏沉浸、横屏经典、横屏沉浸
 * 每种模式封装自己的进入/布局/退出/封面处理逻辑
 */
public interface CoverScene {

    /**
     * 进入该模式：设置所有 View 的可见性、层级、属性初始状态
     * 由 toggleImmersiveMode / applyLayoutMode 切换时调用
     */
    void enter();

    /**
     * 重新布局：窗口尺寸变化时（横竖屏切换、画中画等）调用
     * @param width  根 FrameLayout 实际宽度
     * @param height 根 FrameLayout 实际可用高度
     */
    void layout(int width, int height);

    /**
     * 退出该模式：恢复 View 到默认状态（经典竖屏）
     * 由切走该模式时调用
     */
    void exit();

    /**
     * 设置封面图片
     * 每种模式封面展示方式不同（旋转圆形、MATRIX缩放、CENTER_CROP矩形等）
     * @param bitmap 封面图
     */
    void setCover(Bitmap bitmap);

    /**
     * 歌词显示模式变更时更新布局
     * @param isFullScreen 是否全屏歌词
     */
    void onLyricModeChanged(boolean isFullScreen);

    /**
     * info_panel 宽度占根布局比例（0~1）
     * 竖屏=1.0（全宽），横屏=0.65
     */
    float getInfoPanelWidthRatio();

    /**
     * 歌名 topMargin（dp）
     * 竖屏经典=16，横屏=52，竖屏沉浸=屏幕43%
     */
    int getSongNameTopMarginDp();

    /**
     * 频谱高度占可用高度比例
     * 竖屏=0.10，横屏=0.08
     */
    float getSpectrumHeightRatio();
}
