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

    private ImageView btnPlayPause;
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
        int bottomBtnSize = (int) (actHeight * rBack * aspectFactor);
        int prevNextBtnSize = (int) (actHeight * rPrevNext * aspectFactor);
        int playBtnSize = (int) (actHeight * rPlayBig * aspectFactor);

        int bottomPadding6 = (int) (bottomBtnSize * 0.15f);
        int bottomPadding8 = (int) (bottomBtnSize * 0.20f);
        float prevNextWeight = landscape ? 1f : 1.5f;
        resizeButton(R.id.previous_button, prevNextBtnSize, bottomPadding6, prevNextWeight);
        resizeButton(R.id.play_pause_button, playBtnSize, bottomPadding6, landscape ? 1f : 2f);
        resizeButton(R.id.next_button, prevNextBtnSize, bottomPadding6, prevNextWeight);
        resizeButton(R.id.play_order_button, bottomBtnSize, bottomPadding6);
        resizeButton(R.id.history_button, bottomBtnSize, bottomPadding8);
        resizeButton(R.id.mode_button, bottomBtnSize, bottomPadding8);
        resizeButton(R.id.download_button, bottomBtnSize, bottomPadding8);
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
        ImageView btn = findViewById(viewId);
        if (btn != null) {
            android.widget.LinearLayout.LayoutParams p =
                    (android.widget.LinearLayout.LayoutParams) btn.getLayoutParams();
            p.height = size;
            p.weight = weight;
            btn.setLayoutParams(p);
            btn.setPadding(padding, padding, padding, padding);
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
        btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
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
        ImageView[] buttons = {btnPlayPause, btnPrevious, btnNext,
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
    public ImageView accessButton(int buttonId) {
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
