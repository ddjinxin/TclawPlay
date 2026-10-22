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
import com.jingxin.jingxinmusic.view.PendoloClockworkView;
import com.jingxin.jingxinmusic.view.PendoloLyricView;
import com.jingxin.jingxinmusic.view.StarfieldView;

/**
 * 歌词效果③：时计（移植 folia pendolo：左机械机芯 + 右弧形歌词轨道）
 *
 * 结构：深空渐变底 + 氛围微尘（同流光页） / 左侧机芯（齿轮/游丝/秒轮随歌走，
 * 低频踩点时游丝摆动） / 右半圆弧形歌词轨（当前行 0° 扫字，唱过向上卷、
 * 未唱向下卷，换行棘轮步进+回摆） / 底部封面流。
 * 副歌行（重复行≥2次）扫字染深+双层辉光+行背光（chorus presentation）。
 * 单击任意位置退出；无按键无控件。
 */
public class PendoloEffectPage extends LyricEffectPage {

    private StarfieldView starfield;
    private PendoloClockworkView clockwork;
    private PendoloLyricView lyric;
    private CoverFlowView coverFlow;
    private final CoverFlowView.SongProvider songProvider;
    private final LyricEffectManager.PlayerController controller;
    private final PendoloClockworkView.BassProvider bassProvider;

    public PendoloEffectPage(Context context, PlayerStateHub hub,
                             CoverFlowView.SongProvider songProvider,
                             LyricEffectManager.PlayerController controller,
                             PendoloClockworkView.BassProvider bassProvider) {
        super(context, hub);
        this.songProvider = songProvider;
        this.controller = controller;
        this.bassProvider = bassProvider;
    }

    @Override
    protected void build(LayoutInflater inflater, ViewGroup host) {
        Context ctx = getContext();
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 星空底（复用 StarfieldEffectPage 同款：星点独立闪烁+深空渐变+星云，自带底色）
        StarfieldView starfield = new StarfieldView(ctx);
        LayoutParams sp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(starfield, sp);

        // 歌词轨道（持有棘轮弹簧，咬合角推给机芯）
        lyric = new PendoloLyricView(ctx);
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(lyric, lp);

        // 机械机芯
        clockwork = new PendoloClockworkView(ctx);
        LayoutParams cp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(clockwork, cp);
        if (bassProvider != null) clockwork.setBassProvider(bassProvider);

        // 轨道弹簧 → 机芯啮合（同一弹簧值驱动两边，齿轮与歌词咬合同步）
        lyric.setTickListener((springValue, targetValue) -> {
            if (clockwork != null) clockwork.setTickTarget(targetValue);
        });

        // 封面流（横屏=右缘竖排链·逆时针90°·占70%屏高；竖屏=原底部横排）
        coverFlow = new CoverFlowView(ctx, songProvider);
        coverFlow.setVerticalChainMode(true);
        LayoutParams cfp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(coverFlow, cfp);
        coverFlow.setOnCoverTapListener(offset -> {
            if (controller == null) return;
            if (offset < 0) controller.playPrevious();
            else if (offset > 0) controller.playNext();
            else controller.togglePlayPause();
        });

        setClickable(true);
        setOnClickListener(v -> {
            if (exitListener != null) exitListener.onClick(v);
        });
    }

    @Override
    public void onSongChanged(Song song) {
        if (coverFlow != null) coverFlow.notifySongChanged();
        if (hub != null) {
            if (lyric != null) {
                lyric.setLyricData(hub.getLyricData());
                lyric.updatePosition(hub.getPositionMs());
            }
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
        if (clockwork != null) clockwork.setPositionMs(positionMs);
    }

    @Override
    public void applyHighlightColor(int color) {
        // 词主色走高亮色；机芯强调色同源（齿轮焦轴/宝石/摆轮点缀）
        if (lyric != null && color != 0) lyric.setColors(color, color);
        if (clockwork != null && color != 0) clockwork.setColors(color, color);
    }

    /** 封面取色广播（Manager 转发）：星空底自带深空色板，无需换色（空实现保持接口） */
    public void onCoverColorsChanged(int top, int bottom) {
    }
}
