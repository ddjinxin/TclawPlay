package com.jingxin.jingxinmusic.lyriceffect;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.view.CoverFlowView;
import com.jingxin.jingxinmusic.view.DioramaLyricView;
import com.jingxin.jingxinmusic.view.StarfieldView;

/**
 * 歌词效果⑨：镜台（移植 folia diorama：三维歌词飞越 + 逐句电影运镜，降级 2D 渲染）
 *
 * 结构：整首歌词沿蜿蜒 3D 走廊排开（每行一个局部坐标系帧，乌龟步进 + seeded 转弯
 * 爬升），文字自由摆位（偏移/缩放/roll/yaw/构图偏移按行 seed 决定）；相机在三维
 * 里 SmoothDamp 追踪"读头"（唱词位置 + tanh 饱和卡车平移），每行按其性格加权
 * 抽一种电影运镜（推近/拉远/环绕/平移/摇臂/定格/涌动/螺旋/摆锤/掠过/大弧/
 * 悬浮/滑升 13 种，前两行去重）；唱读时朝向向文字平面"回正"；词级渐变跟唱
 * 染色（hold 0.35s 后 1.8s 衰减）；切歌 3.2s 电影飞行（弧线/侧倾/瞄准横扫一次
 * 性入镜，新走廊 spawn 在旧段尾部 46 单位远、从雾中浮现）。
 * 降级 2D：世界数学全保真真 3D，渲染时透视投影到 Canvas（文字按投影中心/
 * 缩放/屏幕角/距离生命绘制）；景深雾融背景星空；走廊尘埃粒子。
 * 几何编队与拍点涟漪第一版不做。
 * 单击任意位置退出；底部封面流。
 */
public class DioramaEffectPage extends LyricEffectPage {

    private StarfieldView starfield;
    private DioramaLyricView lyric;
    private CoverFlowView coverFlow;
    private final CoverFlowView.SongProvider songProvider;
    private final LyricEffectManager.PlayerController controller;

    public DioramaEffectPage(Context context, PlayerStateHub hub,
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

        // 页面级兜底不透明底色（星空缓存建立前的首帧也不透出播放页）
        setBackgroundColor(android.graphics.Color.rgb(2, 4, 10));

        // 星空底（同星空漫游：星点独立闪烁+深空渐变+星云，零数据依赖）
        starfield = new StarfieldView(ctx);
        LayoutParams sp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(starfield, sp);

        // 镜台歌词视图（纯内容层）
        lyric = new DioramaLyricView(ctx);
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

    /** 歌曲种子（确定性走廊/摆位/镜头）：title+artist 哈希 */
    private static long songSeedOf(Song song) {
        if (song == null) return 0L;
        String s = (song.title != null ? song.title : "") + "|" + (song.artist != null ? song.artist : "");
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    @Override
    public void onSongChanged(Song song) {
        if (lyric == null) return;
        if (coverFlow != null) coverFlow.notifySongChanged();
        if (hub != null) {
            lyric.setLyricData(hub.getLyricData(), songSeedOf(song));
            lyric.updatePosition(hub.getPositionMs());
        }
    }

    @Override
    public void onLyricLoaded(KrcParser.LyricData data) {
        if (lyric != null) {
            Song cur = songProvider != null && songProvider.getPlaylist() != null
                    && songProvider.getCurrentIndex() >= 0
                    && songProvider.getCurrentIndex() < songProvider.getPlaylist().size()
                    ? songProvider.getPlaylist().get(songProvider.getCurrentIndex()) : null;
            lyric.setLyricData(data, songSeedOf(cur));
            lyric.updatePosition(hub != null ? hub.getPositionMs() : 0);
        }
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
        if (lyric != null) lyric.updatePosition(positionMs);
    }

    /** 镜台不吃频谱（相机音频无关，原版 camera stays audio-free），无需注入 */

    @Override
    public void applyHighlightColor(int color) {
        if (lyric != null && color != 0) lyric.setAccentColor(color);
    }
}
