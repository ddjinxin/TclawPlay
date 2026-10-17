package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;

/**
 * 播放页底部控制条组件
 *
 * 封装：上一首/播放暂停/下一首/播放模式/历史/收藏/下载 七键的
 * 状态更新（图标、按下可用态）与主题染色；事件经 Listener 回调上抛。
 *
 * 阶段性设计（组件化第1步）：
 * - 布局 XML 由 Scene 层继续做几何手术，故暴露 accessView() 兼容
 * - 状态语义化方法逐步替代 Fragment 直接操控图标
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
