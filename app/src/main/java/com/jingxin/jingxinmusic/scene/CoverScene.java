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

    /**
     * 播放/暂停状态变更时的处理
     * 经典：封面旋转/停止
     * 沉浸：横屏不旋转封面，其他旋转
     * 唱片机：唱臂动画 + 封面旋转
     * @param isPlaying 当前是否正在播放
     */
    void onPlayingStateChanged(boolean isPlaying);

    /**
     * 从 mini 播放条恢复时的特殊处理
     * 唱片机需要同步唱臂状态和位置
     * @param isPlaying 当前是否正在播放
     */
    void onServiceResumed(boolean isPlaying);

    /**
     * 是否显示频谱切换按钮（沉浸模式下圆环类不可用）
     */
    boolean shouldShowSpectrumButton(int spectrumStyle);

    /**
     * 是否旋转封面
     * 沉浸横屏=false，其他=true
     */
    boolean shouldRotateCover();

    /**
     * 切换到该模式时是否需要重新加载封面
     * 沉浸/唱片机=true，经典=false
     */
    boolean needsReloadCover();

    /**
     * 进入该模式时的额外初始化（风格特有逻辑）
     * 在 enter() 之后调用
     */
    void onStyleEnter();

    /**
     * 退出该模式时的额外清理（风格特有逻辑）
     * 在 exit() 之前调用
     */
    void onStyleExit();
}
