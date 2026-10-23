package com.jingxin.jingxinmusic.lyriceffect;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.view.CladdaghLyricView;
import com.jingxin.jingxinmusic.view.CoverFlowView;
import com.jingxin.jingxinmusic.view.GlowAmbientView;

/**
 * 歌词效果⑦：回环（移植 folia claddagh：倾斜椭圆环歌词轨）
 *
 * 结构：深空渐变底 + 封面主色氛围微尘 / 细长椭圆环逆时针倾斜 45° 横跨屏幕
 * （字符沿环分布，当前唱到的字在环正前方最大最亮，环随歌声连续缓转，
 * 换行整环弹簧转向 180° 前后互换，背景环字符模糊缩小）+ 中心垂线随低频呼吸 /
 * 底部封面流。
 * 副歌行字符奇偶交错+三重辉光。单击任意位置退出；无按键无控件。
 */
public class CladdaghEffectPage extends LyricEffectPage {

    private GlowAmbientView ambient;
    private CladdaghLyricView lyric;
    private CoverFlowView coverFlow;
    private final CoverFlowView.SongProvider songProvider;
    private final LyricEffectManager.PlayerController controller;

    /** 深空渐变（同流光页） */
    private static final int[] BG_COLORS = {
            android.graphics.Color.rgb(0x04, 0x06, 0x0E),
            android.graphics.Color.rgb(0x08, 0x0D, 0x1C),
            android.graphics.Color.rgb(0x04, 0x06, 0x0E),
    };

    public CladdaghEffectPage(Context context, PlayerStateHub hub,
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

        // 深空渐变底
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, BG_COLORS);
        setBackground(bg);

        // 封面主色氛围微尘（同流光页）
        ambient = new GlowAmbientView(ctx);
        LayoutParams ap = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(ambient, ap);

        // 回环歌词环
        lyric = new CladdaghLyricView(ctx);
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

    @Override
    public void onSongChanged(Song song) {
        if (lyric == null) return;
        if (coverFlow != null) coverFlow.notifySongChanged();
        if (hub != null) {
            lyric.setLyricData(hub.getLyricData());
            lyric.updatePosition(hub.getPositionMs());
            if (ambient != null) ambient.setTintColors(hub.getCoverColorTop(), hub.getCoverColorBottom());
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

    /** 低频能量注入（环半径呼吸/中心线/副歌交错/辉光增强） */
    @Override
    public void onEnergy(float energy) {
        if (lyric != null) lyric.setEnergy(energy);
    }

    @Override
    public void applyHighlightColor(int color) {
        // 高亮色作扫字点亮色；基色走主色同源
        if (lyric != null && color != 0) lyric.setColors(color, color);
    }

    /** 封面取色广播（Manager 转发）：氛围微尘同步换色 */
    public void onCoverColorsChanged(int top, int bottom) {
        if (ambient != null) ambient.setTintColors(top, bottom);
    }
}
