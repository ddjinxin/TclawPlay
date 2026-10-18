package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;

/**
 * 播放页进度组件：SeekBar + 当前时间 + 总时长
 *
 * 双模式（组件化第2步）：
 * - 订阅模式：attachHub(stateHub)，进度变化自动刷新（播放页主模式）
 * - 直调模式：setPosition/setDuration 保留（悬浮窗/过渡期兼容）
 * 用户拖动经 Listener 上抛，宿主执行 seek 后经 Hub 回流刷新。
 */
public class ProgressPanel extends LinearLayout {

    /** 拖动/释放回调（宿主实现对播放器 seek，并联动控制栏自动隐藏计时） */
    public interface Listener {
        void onSeekStart();
        void onSeekTo(int positionMs);
        void onSeekStop();
    }

    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private Listener listener;
    private PlayerStateHub hub;
    private PlayerStateHub.ProgressObserver hubObserver;
    private boolean userTouching = false; // 用户拖动中，屏蔽 Hub 回灌避免拉锯

    /** 绑定数据中枢（订阅模式：进度自动刷新） */
    public void attachHub(PlayerStateHub hub) {
        detachHub();
        this.hub = hub;
        hubObserver = (positionMs, durationMs) -> {
            if (userTouching) return; // 拖动中不回灌
            setDuration(durationMs);
            setPosition(positionMs);
        };
        hub.addProgressObserver(hubObserver);
    }

    /** 解绑数据中枢（onDestroyView 调用，防泄漏） */
    public void detachHub() {
        if (hub != null && hubObserver != null) {
            hub.removeProgressObserver(hubObserver);
        }
        hub = null;
        hubObserver = null;
    }
    private boolean bindingSeek = false; // 程序设值时屏蔽 onProgressChanged 的用户分支

    public ProgressPanel(Context context) {
        super(context);
        init(context);
    }

    public ProgressPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ProgressPanel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_progress_panel, this, true);
        seekBar = findViewById(R.id.progress_seek_bar);
        tvCurrentTime = findViewById(R.id.current_time_text);
        tvTotalTime = findViewById(R.id.total_time_text);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrentTime.setText(Song.formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userTouching = true;
                if (listener != null) listener.onSeekStart();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userTouching = false;
                if (listener != null) listener.onSeekTo(sb.getProgress());
                if (listener != null) listener.onSeekStop();
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ===== 状态更新（语义化接口） =====

    /** 播放器当前进度（非用户操作源，不触发拖动回调） */
    public void setPosition(int positionMs) {
        bindingSeek = true;
        seekBar.setProgress(positionMs);
        tvCurrentTime.setText(Song.formatDuration(positionMs));
        bindingSeek = false;
    }

    /** 总时长（含 SeekBar max 同步），duration<=0 时归零 */
    public void setDuration(long durationMs) {
        seekBar.setMax((int) Math.max(0, durationMs));
        tvTotalTime.setText(Song.formatDuration(durationMs));
    }

    /** 换歌重置（00:00 + max 归零） */
    public void resetForNewSong(long durationMs) {
        tvCurrentTime.setText("00:00");
        setDuration(durationMs);
    }

    /** 主题染色：时间文本 */
    public void setTimeTextColor(int color) {
        tvCurrentTime.setTextColor(color);
        tvTotalTime.setTextColor(color);
    }

    /**
     * 进度条视觉（公式与 CoverSceneHelper.applyProgressScaling 同源）：
     * 圆球=屏高2%×aspect 轨道高度=屏高0.5%×aspect；光晕模式球色跟随文字色，默认黄球。
     * 时间文字同步 2% 缩放；已进度段染色：夜间白/白天黑。
     */
    public void applyTrackTheme(int actHeight, int actWidth, boolean night, boolean auroraSkin) {
        float aspectFactor = 1f + Math.max(0f, (actWidth / (float) actHeight - 1.78f) / 3f);
        int thumbSize = Math.max(8, (int) (actHeight * 0.02f * aspectFactor));
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (auroraSkin) {
            thumb.setColor(night
                    ? com.jingxin.jingxinmusic.util.ThemeColors.nightTextPrimary()
                    : com.jingxin.jingxinmusic.util.ThemeColors.dayTextPrimary());
        } else {
            thumb.setColor(android.graphics.Color.parseColor("#FFD54F"));
        }
        thumb.setSize(thumbSize, thumbSize);
        seekBar.setThumb(thumb);
        seekBar.setThumbOffset(thumbSize / 2);
        // 轨道高度（bounds 垂直居中公式同 helper，防轨道贴顶错位）
        int trackHeight = Math.max(2, (int) (actHeight * 0.005f * aspectFactor));
        android.graphics.drawable.Drawable trackDrawable = seekBar.getProgressDrawable();
        if (trackDrawable != null) {
            int trackWidth = seekBar.getWidth() - seekBar.getPaddingLeft() - seekBar.getPaddingRight();
            if (trackWidth <= 0) trackWidth = 1080;
            int paddedHeight = seekBar.getHeight() - seekBar.getPaddingTop() - seekBar.getPaddingBottom();
            int sysTrackHeight = Math.min(trackHeight, Math.max(0, paddedHeight));
            int trackOffset = Math.max(0, (paddedHeight - sysTrackHeight) / 2);
            trackDrawable.setBounds(0, trackOffset, trackWidth, trackOffset + sysTrackHeight);
        }
        // 已进度段染色（同 applySeekBarThemeColor 核心）
        try {
            android.graphics.drawable.Drawable pd = seekBar.getProgressDrawable().mutate();
            if (pd instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.Drawable prog = ((android.graphics.drawable.LayerDrawable) pd)
                        .findDrawableByLayerId(android.R.id.progress);
                if (prog != null) {
                    prog.setColorFilter(night
                            ? com.jingxin.jingxinmusic.util.ThemeColors.nightTextPrimary()
                            : com.jingxin.jingxinmusic.util.ThemeColors.dayTextPrimary(),
                            android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }
        } catch (Exception ignored) {
        }
        float timeTextSize = actHeight * 0.02f * aspectFactor;
        tvCurrentTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, timeTextSize);
        tvTotalTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, timeTextSize);
    }

    // ===== 兼容访问（Scene 布局手术 / 主题滤镜） =====

    public SeekBar seekBar() {
        return seekBar;
    }

    public TextView currentTimeView() {
        return tvCurrentTime;
    }

    public TextView totalTimeView() {
        return tvTotalTime;
    }
}
