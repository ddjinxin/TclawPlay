package com.jingxin.jingxinmusic.lyriceffect;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.view.CoverFlowView;

/**
 * 歌词效果管理器（效果注册/进退/Hub 订阅分发）
 *
 * 生命周期：attach = 挂宿主容器淡入 + 订阅 Hub 灌数据；detach = 淡出退订移除。
 * 效果注册：EFFECT 枚举；当前只有星空漫游 STARFIELD。
 * 线程：全部主线程（Hub 回调即主线程）。
 */
public class LyricEffectManager {

    /** 效果枚举（将来胶片/森林/雨滴同法扩） */
    public enum Effect { STARFIELD }

    public interface SongProvider {
        java.util.List<Song> getPlaylist();
        int getCurrentIndex();
    }

    /** 当前高亮歌词色提供者（与播放页 LyricView 真值同步：主题/自定义/光晕动态色） */
    public interface HighlightColorProvider {
        int getHighlightColor();
    }

    /** 播放控制（封面流点击切歌/播放暂停；宿主注入，调用时自行检查绑定态） */
    public interface PlayerController {
        void playPrevious();
        void togglePlayPause();
        void playNext();
    }

    private static final long FADE_MS = 240L;

    private final PlayerStateHub hub;
    private final ViewGroup host;            // 播放页 root_layout（FrameLayout）
    private final SongProvider songProvider;
    private final HighlightColorProvider colorProvider;
    private final PlayerController playerController;
    private StarfieldEffectPage activePage;  // 当前效果页（效果本体）
    private boolean active = false;

    private PlayerStateHub.Observer stateObserver;
    private PlayerStateHub.ProgressObserver progressObserver;
    private PlayerStateHub.LyricObserver lyricObserver;
    private PlayerStateHub.CoverColorObserver coverColorObserver;

    public LyricEffectManager(PlayerStateHub hub, ViewGroup host, SongProvider songProvider,
                              HighlightColorProvider colorProvider, PlayerController playerController) {
        this.hub = hub;
        this.host = host;
        this.songProvider = songProvider;
        this.colorProvider = colorProvider;
        this.playerController = playerController;
    }

    public boolean isActive() { return active; }

    /** 进入效果页（已激活则忽略） */
    public void attach(Effect effect) {
        if (active || host == null) return;
        active = true;

        activePage = new StarfieldEffectPage(host.getContext(), hub,
                new CoverFlowView.SongProvider() {
                    @Override
                    public java.util.List<Song> getPlaylist() {
                        return songProvider != null ? songProvider.getPlaylist() : null;
                    }

                    @Override
                    public int getCurrentIndex() {
                        return songProvider != null ? songProvider.getCurrentIndex() : -1;
                    }
                }, playerController);
        activePage.setExitListener(v -> detach());
        activePage.build(LayoutInflater.from(host.getContext()), host);
        if (colorProvider != null) {
            activePage.applyHighlightColor(colorProvider.getHighlightColor());
        }

        // 淡入（ViewPropertyAnimator：终点即目标 alpha；AlphaAnimation 结束会回弹到 setAlpha 初始值导致隐身）
        activePage.setAlpha(0f);
        host.addView(activePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activePage.animate().alpha(1f).setDuration(FADE_MS).start();

        // 首帧数据灌入
        activePage.onSongChanged(hub != null ? hub.getSong() : null);
        activePage.onLyricLoaded(hub != null ? hub.getLyricData() : null);
        activePage.onProgress(hub != null ? hub.getPositionMs() : 0,
                hub != null ? hub.getDurationMs() : 0);

        subscribe();
    }

    /** 退出效果页（淡出+退订+移除） */
    public void detach() {
        if (!active || activePage == null) return;
        final StarfieldEffectPage page = activePage;
        active = false;
        activePage = null;
        unsubscribe();

        // 淡出后移除（ViewPropertyAnimator 终点 alpha=0，结束回调移除视图）
        page.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            if (page.getParent() == host) host.removeView(page);
        }).start();
        page.setOnClickListener(null);
    }

    private void subscribe() {
        if (hub == null) return;
        stateObserver = state -> {
            StarfieldEffectPage p = activePage;
            if (p == null) return;
            p.onSongChanged(state.song);
            // 歌/日夜/皮肤变化都可能改变播放页高亮色：重拉注入
            applyCurrentHighlight();
        };
        hub.addObserver(stateObserver);
        progressObserver = (pos, dur) -> {
            StarfieldEffectPage p = activePage;
            if (p != null) p.onProgress(pos, dur);
        };
        hub.addProgressObserver(progressObserver);
        lyricObserver = data -> {
            StarfieldEffectPage p = activePage;
            if (p != null) p.onLyricLoaded(data);
        };
        hub.addLyricObserver(lyricObserver);
        // 新封面取色完成（光晕皮肤高亮随封面变）：播放页 LyricView 同帧稍后会更新亮色，
        // 此处 post 到下一帧再拉才能取到更新后的真值（直接拉会拿到旧色）
        coverColorObserver = (top, bottom) -> applyCurrentHighlight();
        hub.addCoverColorObserver(coverColorObserver);
    }

    /** 重拉播放页当前高亮色注入效果页（下一帧执行，等播放页同帧更新完成） */
    private void applyCurrentHighlight() {
        if (colorProvider == null) return;
        StarfieldEffectPage p = activePage;
        if (p == null) return;
        p.post(() -> {
            StarfieldEffectPage page = activePage;
            if (page != null && colorProvider != null) {
                page.applyHighlightColor(colorProvider.getHighlightColor());
            }
        });
    }

    private void unsubscribe() {
        if (hub == null) return;
        if (stateObserver != null) { hub.removeObserver(stateObserver); stateObserver = null; }
        if (progressObserver != null) { hub.removeProgressObserver(progressObserver); progressObserver = null; }
        if (lyricObserver != null) { hub.removeLyricObserver(lyricObserver); lyricObserver = null; }
        if (coverColorObserver != null) { hub.removeCoverColorObserver(coverColorObserver); coverColorObserver = null; }
    }
}
