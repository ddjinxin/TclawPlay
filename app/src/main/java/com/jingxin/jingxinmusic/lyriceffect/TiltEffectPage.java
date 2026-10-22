package com.jingxin.jingxinmusic.lyriceffect;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.view.CoverFlowView;
import com.jingxin.jingxinmusic.view.StarfieldView;
import com.jingxin.jingxinmusic.view.TiltLyricView;

/**
 * 歌词效果⑤：倾诉（移植 folia tilt：斜体独白）
 *
 * 结构：星空底 / 当前行按语义拆 1~4 段竖排居中——随机一段倾斜行
 * （真斜体+波浪错位+强调色），段随演唱逐段揭示，字逐个递进入场、
 * 演唱字脉冲+余晖悬住 / 底部封面流。
 * 单击任意位置退出；无按键无控件。
 */
public class TiltEffectPage extends LyricEffectPage {

    private TiltLyricView lyric;
    private CoverFlowView coverFlow;
    private final CoverFlowView.SongProvider songProvider;
    private final LyricEffectManager.PlayerController controller;

    public TiltEffectPage(Context context, PlayerStateHub hub,
                          CoverFlowView.SongProvider songProvider,
                          LyricEffectManager.PlayerController controller) {
        super(context, hub);
        this.songProvider = songProvider;
        this.controller = controller;
    }

    @Override
    protected void build(LayoutInflater inflater, ViewGroup host) {
        Context ctx = getContext();
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 星空底
        StarfieldView starfield = new StarfieldView(ctx);
        LayoutParams sp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(starfield, sp);

        // 倾诉歌词
        lyric = new TiltLyricView(ctx);
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(lyric, lp);

        // 封面流（底部）
        coverFlow = new CoverFlowView(ctx, songProvider);
        LayoutParams cp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(coverFlow, cp);

        coverFlow.setOnCoverTapListener(offset -> {
            if (controller == null) return;
            if (offset < 0) controller.playPrevious();
            else if (offset > 0) controller.playNext();
            else controller.togglePlayPause();
        });

        // 单击退出
        setClickable(true);
        setOnClickListener(v -> {
            if (exitListener != null) exitListener.onClick(v);
        });
    }

    @Override
    public void onSongChanged(Song song) {
        String text;
        if (song != null) {
            String t = song.title != null ? song.title : "";
            String a = song.artist != null ? song.artist : "";
            text = a.isEmpty() ? t : t + " - " + a;
        } else {
            text = "";
        }
        if (lyric != null) lyric.setTitleText(text);
        if (coverFlow != null) coverFlow.notifySongChanged();
        if (hub != null && lyric != null) {
            lyric.setLyricData(hub.getLyricData());
            lyric.updatePosition(hub.getPositionMs());
        }
    }

    @Override
    public void onLyricLoaded(KrcParser.LyricData data) {
        if (lyric != null) {
            lyric.setLyricData(data);
            lyric.updatePosition(hub != null ? hub.getPositionMs() : 0);
        }
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        if (lyric != null) lyric.updatePosition(positionMs);
    }

    @Override
    public void applyHighlightColor(int color) {
        if (lyric != null) lyric.setColors(color, color);
    }
}
