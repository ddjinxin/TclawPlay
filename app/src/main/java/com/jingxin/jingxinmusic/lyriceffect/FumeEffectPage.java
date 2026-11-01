package com.jingxin.jingxinmusic.lyriceffect;

import android.content.Context;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.view.CoverFlowView;
import com.jingxin.jingxinmusic.view.FumeLyricView;
import com.jingxin.jingxinmusic.view.StarfieldView;

/**
 * 歌词效果⑧：浮名（移植 folia fume：整曲排成文章 + 相机运镜）
 *
 * 结构：星空底（同星空：深空径向渐变+星云+星点闪烁，页面级兑底不透原播放页）/
 * 整首歌词排成多列"报纸文章"（hero 副歌大字块跨列、正文块填最矮列），
 * 相机在文章世界坐标里随唱句运镜——smooth 焦点跟打印进度逐字推进、
 * 换块 direct/overview 贝塞尔飞桥 + 弹簧追踪 + 7s 正弦漂浮，
 * 字素 waiting 极淡隐字 → 激活满亮 + 词色混入 + 辉光 → 唱过色尾褪 passed 色，
 * 附印章方块落下脉冲；歌尾起飞整篇俯瞰全景。
 * fume 默认 disableGeometricBackground=true：无几何背景、无频谱依赖。
 * 单击任意位置退出；底部封面流。
 */
public class FumeEffectPage extends LyricEffectPage {

    private StarfieldView starfield;
    private FumeLyricView lyric;
    private CoverFlowView coverFlow;
    private final CoverFlowView.SongProvider songProvider;
    private final LyricEffectManager.PlayerController controller;

    public FumeEffectPage(Context context, PlayerStateHub hub,
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

        // 页面级兑底不透明底色（星空缓存建立前的首帧也不透出播放页）
        setBackgroundColor(android.graphics.Color.rgb(2, 4, 10));

        // 星空底（同星空：星点独立闪烁+深空渐变+星云，零数据依赖）
        starfield = new StarfieldView(ctx);
        LayoutParams sp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(starfield, sp);

        // 文章歌词视图（纯内容层透明星空之上）
        lyric = new FumeLyricView(ctx);
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(lyric, lp);

        // 封面流
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

    /** 壁纸模式：隐藏星空底自绘背景（壁纸层已垫底；GONE 停刷省性能） */
    @Override
    public void onWallpaperModeChanged(boolean wallpaper) {
        if (starfield != null) starfield.setVisibility(wallpaper ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onSongChanged(Song song) {
        if (lyric == null) return;
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

    /** fume 不吃频谱能量（相机/印章均由歌词时间轴驱动），无需注入 */

    @Override
    public void applyHighlightColor(int color) {
        // 高亮色作激活/辉光词色注入（primary 保持 fume 原版浅色基调）
        if (lyric != null && color != 0) lyric.setAccentColor(color);
    }
}
