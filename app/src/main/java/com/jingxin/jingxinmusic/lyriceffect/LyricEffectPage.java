package com.jingxin.jingxinmusic.lyriceffect;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.KrcParser;

/**
 * 歌词效果页基类（纯展示层体系，与风格系统正交）
 *
 * 体系说明：
 * - 入口：播放页任意风格下长按歌词 → LyricEffectManager 淡入全屏效果页
 * - 退出：整页单击即退（无按键无控件无顶栏，"活的海报"）
 * - 数据：全走 PlayerStateHub（歌/词/进度），封面流经 SongProvider 拉列表 + CoverLoader 喂图
 * - 扩展：一个效果一个子类；将来胶片/森林/雨滴同法挂载
 */
public abstract class LyricEffectPage extends FrameLayout {

    protected final PlayerStateHub hub;

    /** 整页单击退出回调（Manager 注入，子类 build 内挂根视图） */
    protected View.OnClickListener exitListener;

    protected LyricEffectPage(Context context, PlayerStateHub hub) {
        super(context);
        this.hub = hub;
    }

    /** 效果视图构建（Manager attach 时调用一次） */
    protected abstract void build(LayoutInflater inflater, ViewGroup host);

    /** 歌曲切换（含首次进入灌入） */
    public abstract void onSongChanged(Song song);

    /** 歌词数据到达/变更 */
    public abstract void onLyricLoaded(KrcParser.LyricData data);

    /** 播放进度 tick（换行判定用） */
    public abstract void onProgress(int positionMs, int durationMs);

    public void setExitListener(View.OnClickListener l) { this.exitListener = l; }
}
