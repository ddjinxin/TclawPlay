package com.jingxin.jingxinmusic.lyriceffect;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.view.CoverFlowView;
import com.jingxin.jingxinmusic.view.GlowLyricView;

/**
 * 歌词效果②：流光（移植 folia classic：逐词三态点亮 + 字素双光晕 + 行级散布/呼吸/涟漪）
 *
 * 结构：深空渐变底（垂直 近黑→深蓝黑，不抢词的光） / 顶部歌名 /
 *       中央逐词点亮歌词（词随音弹入发光，唱过保持余韵缓转） / 底部封面流（同星空页交互）
 * 单击任意位置退出；无按键无控件。
 */
public class GlowEffectPage extends LyricEffectPage {

    private GlowLyricView lyric;
    private CoverFlowView coverFlow;
    private final CoverFlowView.SongProvider songProvider;
    private final LyricEffectManager.PlayerController controller;

    /** 深空渐变（顶部近黑 → 中部深蓝 → 底部近黑，中央歌词区最暗，光晕对比最强） */
    private static final int[] BG_COLORS = {
            Color.rgb(0x04, 0x06, 0x0E),
            Color.rgb(0x08, 0x0D, 0x1C),
            Color.rgb(0x04, 0x06, 0x0E),
    };

    public GlowEffectPage(Context context, PlayerStateHub hub,
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

        // 深空渐变底（纯 drawable，零绘制成本）
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, BG_COLORS);
        setBackground(bg);

        // 歌词（含顶部歌名自绘）
        lyric = new GlowLyricView(ctx);
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(lyric, lp);

        // 封面流（底部，同星空页：左二上一曲/中心播放暂停/右二下一曲）
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

        // 单击退出（根消费）
        setClickable(true);
        setOnClickListener(v -> {
            if (exitListener != null) exitListener.onClick(v);
        });
    }

    @Override
    public void onSongChanged(Song song) {
        if (lyric == null) return;
        String text;
        if (song != null) {
            String t = song.title != null ? song.title : "";
            String a = song.artist != null ? song.artist : "";
            text = a.isEmpty() ? t : t + " - " + a;
        } else {
            text = "";
        }
        lyric.setTitleText(text);
        if (coverFlow != null) coverFlow.notifySongChanged();
        if (hub != null) {
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

    /** 应用播放页高亮歌词色（词点亮高亮色，随主题/封面取色变化） */
    public void applyHighlightColor(int color) {
        if (lyric != null) lyric.setHighlightColor(color);
    }
}
