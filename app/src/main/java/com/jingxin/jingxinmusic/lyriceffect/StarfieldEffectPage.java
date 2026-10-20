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
import com.jingxin.jingxinmusic.view.StarLyricView;
import com.jingxin.jingxinmusic.view.StarfieldView;

/**
 * 歌词效果①：星空漫游（对齐 QQ 音乐 Pad 沉浸歌词·星空漫游截图）
 *
 * 结构：全屏星空底（星点独立闪烁+深空渐变+星云） / 顶部歌名 /
 *       中上双行歌词（下一句小字灰蓝 + 当前行大字浅冰蓝柔光，换行星尘浮现） /
 *       底部封面流（列表封面堆叠探出屏幕底）
 * 单击任意位置退出；无按键无控件。
 */
public class StarfieldEffectPage extends LyricEffectPage {

    private StarfieldView starfield;
    private StarLyricView lyric;
    private CoverFlowView coverFlow;
    private final CoverFlowView.SongProvider songProvider;

    public StarfieldEffectPage(Context context, PlayerStateHub hub,
                                CoverFlowView.SongProvider songProvider) {
        super(context, hub);
        this.songProvider = songProvider;
    }

    @Override
    protected void build(LayoutInflater inflater, ViewGroup host) {
        Context ctx = getContext();
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 星空底
        starfield = new StarfieldView(ctx);
        starfield.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(starfield);

        // 歌词（含顶部歌名自绘：歌名/预唱行/当前行同一坐标系，锚点零误差）
        lyric = new StarLyricView(ctx);
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lyric.setLayoutParams(lp);
        addView(lyric, lp);

        // 封面流（底部）
        coverFlow = new CoverFlowView(ctx, songProvider);
        LayoutParams cp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        coverFlow.setLayoutParams(cp);
        addView(coverFlow, cp);

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

    /** 应用播放页高亮歌词色（歌名/当前行统一使用；Manager attach 时注入，并随主题变化重注入） */
    public void applyHighlightColor(int color) {
        if (lyric != null) lyric.setHighlightColor(color);
    }
}
