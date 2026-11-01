package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.player.PlayerStateHub;

/**
 * 播放页底部控制条组件
 *
 * 双模式（组件化第2步）：
 * - 订阅模式：attachHub(stateHub)，播放态/播放模式自动刷新
 * - 直调模式：setPlaying/setPlayOrder 保留（悬浮窗/过渡期兼容）
 * 事件经 Listener 上抛；收藏/下载态业务强相关，仍由宿主直调。
 */
public class ControlBar extends LinearLayout {

    /** 控制事件回调（宿主实现，指向播放/收藏等业务） */
    public interface Listener {
        void onPlayPause();
        void onPrevious();
        void onNext();
        void onPlayOrder();
        void onHistory();
        void onFavorite();
        void onDownload();
    }

    private android.widget.FrameLayout btnPlayPause;   // 播放键容器（玻璃子View+图标子View）
    private ImageView btnPlayPauseIcon;                // 播放/暂停图标
    private View btnPlayPauseGlass;                    // 玻璃底（固定尺寸不随容器拉伸）
    private ImageView btnPrevious;
    private ImageView btnNext;
    private ImageView btnHistory;
    private ImageView btnFavorite;
    private ImageView btnDownload;
    private ImageView btnPlayOrder;
    private View buttonsLayout;
    private Listener listener;
    private PlayerStateHub hub;
    private PlayerStateHub.Observer hubObserver;

    /** 绑定数据中枢（订阅模式：播放态/播放模式自动刷新） */
    public void attachHub(PlayerStateHub hub) {
        detachHub();
        this.hub = hub;
        hubObserver = state -> {
            setPlaying(state.playing);
            setPlayOrder(state.playOrder);
        };
        hub.addObserver(hubObserver);
        // 立即应用当前快照
        setPlaying(hub.isPlaying());
        setPlayOrder(hub.getPlayOrder());
    }

    // ===== 页面传参模式（独立页：按钮几何公式收口为实例方法） =====

    /**
     * 底部按钮几何（公式与 CoverSceneHelper.applyButtonMargins 按钮段逐行同源）：
     * 屏高三档基础比例 × 宽高比修正；上一曲/下一曲 ×1.21、播放 ×1.5（先1.25再1.2）；
     * padding 固定基于普通按钮尺寸，保证放大按钮的图标区域真正变大。
     *
     * @param actHeight 可用屏高
     * @param actWidth  可用屏宽
     * @param landscape 是否横屏
     */
    public void applyGeometry(int actHeight, int actWidth, boolean landscape) {
        // 图标放大 30%（仅图标，布局占位/玻璃胶囊不变）：三档系数 ×1.3，
        // 布局高度与玻璃锁定原始值——图标在原尺寸容器内超界居中绘制
        float iconScale = 1.3f;
        float rBack, rPlay;
        float rPrevNext;
        float rPlayBig;
        if (actHeight < 700) {
            rBack = 0.048f; rPlay = 0.058f;
            rPrevNext = 0.048f * 1.21f;
            rPlayBig = 0.058f * 1.5f;
        } else if (actHeight <= 1200) {
            rBack = 0.055f; rPlay = 0.067f;
            rPrevNext = 0.055f * 1.21f;
            rPlayBig = 0.067f * 1.5f;
        } else {
            rBack = 0.060f; rPlay = 0.075f;
            rPrevNext = 0.060f * 1.21f;
            rPlayBig = 0.075f * 1.5f;
        }
        float aspectFactor = 1f + Math.max(0f, (actWidth / (float) actHeight - 1.78f) / 3f);
        // 布局占位：原始尺寸（容器高度不变，底部区域不被撑高）
        int bottomBtnSize = (int) (actHeight * rBack * aspectFactor);
        int prevNextBtnSize = (int) (actHeight * rPrevNext * aspectFactor);
        int playBtnSize = (int) (actHeight * rPlayBig * aspectFactor);
        // 玻璃胶囊尺寸不动：QQ 44x40 比例，原始键高
        int glassW = (int) (playBtnSize * 44f / 40f);
        int glassH = playBtnSize;
        btnPlayPauseGlass.post(() -> {
            android.widget.FrameLayout.LayoutParams g =
                    (android.widget.FrameLayout.LayoutParams) btnPlayPauseGlass.getLayoutParams();
            g.width = glassW;
            g.height = glassH;
            btnPlayPauseGlass.setLayoutParams(g);
        });
        // 图标放大 30%：padding 按原图标内边距等比缩小，图标突破容器超界绘制
        setClipChildren(false);
        setClipToPadding(false);
        if (getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) getParent()).setClipChildren(false);
            ((android.view.ViewGroup) getParent()).setClipToPadding(false);
        }
        // 图标区域尺寸 = 原始尺寸 × 1.3（padding 同步 ×1.3，视觉等比放大）
        int iconPad6 = (int) (bottomBtnSize * 0.15f * iconScale);
        int iconPad8 = (int) (bottomBtnSize * 0.20f * iconScale);
        int iconSizeBack = (int) (bottomBtnSize * iconScale);
        int iconSizePrevNext = (int) (prevNextBtnSize * iconScale);
        // 播放键图标独立尺寸（原match_parent跟随容器改明确值，容器锁原值保玻璃，图标×1.3居中超界）
        int playIconSize = playBtnSize;  // 基准=原始播放键高
        {
            android.widget.FrameLayout.LayoutParams ip =
                    (android.widget.FrameLayout.LayoutParams) btnPlayPauseIcon.getLayoutParams();
            ip.width = (int) (playIconSize * iconScale);
            ip.height = (int) (playIconSize * iconScale);
            btnPlayPauseIcon.setLayoutParams(ip);
            int playPadding = (int) (bottomBtnSize * 0.15f * iconScale);
            btnPlayPauseIcon.setPadding(playPadding, playPadding, playPadding, playPadding);
        }

        float prevNextWeight = landscape ? 1f : 1.5f;
        // 布局占位用原始尺寸 + padding/resizeButton 不再重复设置图标尺寸——
        // 图标放大通过 padding 反推：ImageView 高度锁容器原始值，图标 src 超出 padding 区绘制
        // 简化实现：直接把按钮高度设为图标放大值，但容器（本 LinearLayout）高度保持 wrap 原始，
        // 靠 Gravity.CENTER 的超界绘制达成"占位不变、绘制放大"
        int layoutPlay = playBtnSize;
        resizeButton(R.id.previous_button, iconSizePrevNext, iconPad6, prevNextWeight);
        resizeButton(R.id.play_pause_button, layoutPlay, 0, landscape ? 1f : 2f);
        resizeButton(R.id.next_button, iconSizePrevNext, iconPad6, prevNextWeight);
        resizeButton(R.id.play_order_button, iconSizeBack, iconPad6);
        resizeButton(R.id.history_button, iconSizeBack, iconPad8);
        resizeButton(R.id.mode_button, iconSizeBack, iconPad8);
        resizeButton(R.id.download_button, iconSizeBack, iconPad8);
        // ControlBar 自身高度锁定为原始 wrap 基准（最大占位=播放键原高度），按钮超界不撑高
        post(() -> {
            if (getHeight() > layoutPlay) {
                android.view.ViewGroup.LayoutParams sp = getLayoutParams();
                sp.height = layoutPlay;
                setLayoutParams(sp);
            }
        });
    }

    /** 底边距设置（本组件直接父容器为 FrameLayout 槽或 LinearLayout 流，均 MarginLayoutParams 安全） */
    public void applyBottomMargin(int bottomMarginPx) {
        android.view.ViewGroup.LayoutParams params = getLayoutParams();
        if (params instanceof android.view.ViewGroup.MarginLayoutParams) {
            ((android.view.ViewGroup.MarginLayoutParams) params).bottomMargin = bottomMarginPx;
            setLayoutParams(params);
        }
    }

    /** 按钮高度/padding/weight 复用（内部findViewById，与Helper同源公式） */
    private void resizeButton(int viewId, int size, int padding) {
        resizeButton(viewId, size, padding, 1f);
    }

    private void resizeButton(int viewId, int size, int padding, float weight) {
        View btn = findViewById(viewId);
        if (btn != null) {
            android.widget.LinearLayout.LayoutParams p =
                    (android.widget.LinearLayout.LayoutParams) btn.getLayoutParams();
            p.height = size;
            p.weight = weight;
            btn.setLayoutParams(p);
            if (padding > 0) btn.setPadding(padding, padding, padding, padding);
        }
    }

    /** 解绑数据中枢（onDestroyView 调用，防泄漏） */
    public void detachHub() {
        if (hub != null && hubObserver != null) {
            hub.removeObserver(hubObserver);
        }
        hub = null;
        hubObserver = null;
    }

    public ControlBar(Context context) {
        super(context);
        init(context);
    }

    public ControlBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ControlBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER); // 同旧 control_buttons：子按钮在行高内垂直居中（动态缩放后小键不悬空）
        LayoutInflater.from(context).inflate(R.layout.view_control_bar, this, true);
        btnPlayPause = findViewById(R.id.play_pause_button);
        btnPlayPauseIcon = findViewById(R.id.play_pause_icon);
        btnPlayPauseGlass = findViewById(R.id.play_pause_glass);
        btnPlayPauseIcon = findViewById(R.id.play_pause_icon);
        btnPlayPauseGlass = findViewById(R.id.play_pause_glass);
        btnPrevious = findViewById(R.id.previous_button);
        btnNext = findViewById(R.id.next_button);
        btnHistory = findViewById(R.id.history_button);
        btnFavorite = findViewById(R.id.mode_button);      // 复用 mode_button 位置（收藏）
        btnDownload = findViewById(R.id.download_button);
        btnPlayOrder = findViewById(R.id.play_order_button);
        buttonsLayout = this;

        btnPlayPause.setOnClickListener(v -> { if (listener != null) listener.onPlayPause(); });
        btnPrevious.setOnClickListener(v -> { if (listener != null) listener.onPrevious(); });
        btnNext.setOnClickListener(v -> { if (listener != null) listener.onNext(); });
        btnPlayOrder.setOnClickListener(v -> { if (listener != null) listener.onPlayOrder(); });
        btnHistory.setOnClickListener(v -> { if (listener != null) listener.onHistory(); });
        btnFavorite.setOnClickListener(v -> { if (listener != null) listener.onFavorite(); });
        btnDownload.setOnClickListener(v -> { if (listener != null) listener.onDownload(); });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ===== 状态更新（语义化接口） =====

    /** 播放/暂停图标切换 */
    public void setPlaying(boolean playing) {
        btnPlayPauseIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    /** 播放模式图标：0=顺序 1=随机 2=单曲循环（与 MusicPlayerService 常量一致） */
    public void setPlayOrder(int order) {
        switch (order) {
            case 1: btnPlayOrder.setImageResource(R.drawable.ic_play_order_shuffle); break;
            case 2: btnPlayOrder.setImageResource(R.drawable.ic_play_order_repeat); break;
            default: btnPlayOrder.setImageResource(R.drawable.ic_play_order_sequential); break;
        }
    }

    /** 收藏状态图标 */
    public void setFavorite(boolean favorite) {
        btnFavorite.setImageResource(favorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
    }

    /** 下载按钮可用态（B站离线检测中禁用） */
    public void setDownloadEnabled(boolean enabled) {
        btnDownload.setEnabled(enabled);
        btnDownload.setAlpha(enabled ? 1f : 0.5f);
    }

    /** 下载按钮显隐（非B站歌曲隐藏） */
    public void setDownloadVisible(boolean visible) {
        btnDownload.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ===== 主题染色 =====

    /**
     * 统一按钮染色。已收藏红色优先，
     * day 模式下全部染 buttonColor，夜间清除滤镜恢复白色图标。
     * （图标资源由 setFavorite/setPlayOrder 管理，此处只管滤镜）
     */
    public void applyTheme(boolean isNight, boolean isFavorite, int dayButtonColor, int favoriteRed) {
        // 播放键玻璃底日夜分档（QQ音乐HD双档玻璃）：白天浓玻璃(28%)，夜间淡玻璃(6.7%)。
        // 玻璃在独立子View上（applyGeometry 设固定尺寸不随放大拉伸）；染色只针对图标
        btnPlayPauseGlass.setBackgroundResource(isNight
                ? R.drawable.ic_play_bg_glass_dark
                : R.drawable.ic_play_bg_glass_light);
        ImageView[] buttons = {btnPlayPauseIcon, btnPrevious, btnNext,
                btnHistory, btnPlayOrder, btnDownload};
        if (isNight) {
            for (ImageView btn : buttons) btn.clearColorFilter();
            if (isFavorite) {
                btnFavorite.setColorFilter(favoriteRed, android.graphics.PorterDuff.Mode.SRC_IN);
            } else {
                btnFavorite.clearColorFilter();
            }
        } else {
            for (ImageView btn : buttons) btn.setColorFilter(dayButtonColor, android.graphics.PorterDuff.Mode.SRC_IN);
            if (isFavorite) {
                btnFavorite.setColorFilter(favoriteRed, android.graphics.PorterDuff.Mode.SRC_IN);
            } else {
                btnFavorite.setColorFilter(dayButtonColor, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
    }

    // ===== 兼容访问（Scene 布局手术 / 历史弹窗锚点） =====

    /** 供 Scene 层布局手术与弹窗锚点访问内部按钮（过渡期兼容） */
    /** 供 Scene 层布局手术与弹窗锚点访问内部按钮（播放键已改容器结构，返回外层FrameLayout） */
    public View accessButton(int buttonId) {
        if (buttonId == R.id.play_pause_button) return btnPlayPause;
        if (buttonId == R.id.previous_button) return btnPrevious;
        if (buttonId == R.id.next_button) return btnNext;
        if (buttonId == R.id.history_button) return btnHistory;
        if (buttonId == R.id.mode_button) return btnFavorite;
        if (buttonId == R.id.download_button) return btnDownload;
        if (buttonId == R.id.play_order_button) return btnPlayOrder;
        return null;
    }
}
