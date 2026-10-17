package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;

/**
 * 播放页进度组件：SeekBar + 当前时间 + 总时长
 *
 * 封装：进度/时长展示、用户拖动态、播放器同步回调；主题染色接口。
 * 阶段性设计（组件化第1步）：
 * - 时间文本字号/Seekbar thumb 由 Scene 层继续做几何手术，故暴露 seekBar()/currentTimeView()
 * - setProgress/setDuration 语义化方法逐步替代 Fragment 直接操控
 */
public class ProgressPanel extends FrameLayout {

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
                if (listener != null) listener.onSeekStart();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
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
}
