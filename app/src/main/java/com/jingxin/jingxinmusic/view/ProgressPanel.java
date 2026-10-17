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
