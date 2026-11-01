package com.jingxin.jingxinmusic.lyriceffect;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.player.PlayerStateHub;
import com.jingxin.jingxinmusic.util.EffectWallpaperStore;
import com.jingxin.jingxinmusic.util.KrcParser;
import com.jingxin.jingxinmusic.view.CoverFlowView;
import com.jingxin.jingxinmusic.view.EffectWallpaperView;
import com.jingxin.jingxinmusic.view.PendoloClockworkView;

/**
 * 歌词效果管理器（效果注册/进退/Hub 订阅分发）
 *
 * 生命周期：attach = 挂宿主容器淡入 + 订阅 Hub 灌数据；detach = 淡出退订移除。
 * 效果注册：EFFECT 枚举 + attach switch 分发；现有星空 STARFIELD / 流光 GLOW /
 * 时计 PENDELO / 心象 CADENZA / 倾诉 TILT / 云阶 PARTITA / 浮名 FUME / 镜台 DIORAMA。
 * 线程：全部主线程（Hub 回调即主线程）。
 */
public class LyricEffectManager {

    /** 效果枚举（将来胶片/森林/雨滴同法扩） */
    public enum Effect { STARFIELD, GLOW, PENDELO, CADENZA, TILT, PARTITA, FUME, DIORAMA }

    /** 低频能量提供者（时计摆轮驱动；无频谱环境返回 0，机芯恒速） */
    public interface BassEnergyProvider { float getBassEnergy(); }

    private BassEnergyProvider bassProvider;

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
    private static final String TAG_PAGE = "lyric_effect_page"; // 效果页标识（残留清扫用）

    private final PlayerStateHub hub;
    private final ViewGroup host;            // 播放页 root_layout（FrameLayout）
    private final SongProvider songProvider;
    private final HighlightColorProvider colorProvider;
    private final PlayerController playerController;
    private LyricEffectPage activePage;      // 当前效果页（效果本体，STARFIELD/GLOW 多态）
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

    /** 注入低频能量源（时计用；可随时换置/null） */
    public void setBassEnergyProvider(BassEnergyProvider p) { this.bassProvider = p; }

    /** 进入效果页（已激活则忽略） */
    public void attach(Effect effect) {
        if (active || host == null) return;
        // 残留旧页清理：上一次淡出的 withEndAction 可能在 reparent 时被取消（View detach
        // 中断 ViewPropertyAnimator），旧页/其壁纸层滞留树上——按 tag 清扫，防叠层与泄漏
        cleanOrphanPages();
        active = true;

        // 封面流数据源适配（两页共用）
        CoverFlowView.SongProvider coverProvider = new CoverFlowView.SongProvider() {
            @Override
            public java.util.List<Song> getPlaylist() {
                return songProvider != null ? songProvider.getPlaylist() : null;
            }

            @Override
            public int getCurrentIndex() {
                return songProvider != null ? songProvider.getCurrentIndex() : -1;
            }
        };

        switch (effect) {
            case GLOW:
                activePage = new GlowEffectPage(host.getContext(), hub, coverProvider, playerController);
                break;
            case PENDELO: {
                PendoloClockworkView.BassProvider bass = (bassProvider != null)
                        ? bassProvider::getBassEnergy : null;
                activePage = new PendoloEffectPage(host.getContext(), hub, coverProvider,
                        playerController, bass);
                break;
            }
            case CADENZA:
                activePage = new CadenzaEffectPage(host.getContext(), hub, coverProvider, playerController);
                break;
            case TILT:
                activePage = new TiltEffectPage(host.getContext(), hub, coverProvider, playerController);
                break;
            case PARTITA:
                activePage = new PartitaEffectPage(host.getContext(), hub, coverProvider, playerController);
                break;
            case FUME:
                activePage = new FumeEffectPage(host.getContext(), hub, coverProvider, playerController);
                break;
            case DIORAMA:
                activePage = new DioramaEffectPage(host.getContext(), hub, coverProvider, playerController);
                break;
            case STARFIELD:
            default:
                activePage = new StarfieldEffectPage(host.getContext(), hub, coverProvider, playerController);
                break;
        }
        activePage.setExitListener(v -> detach());
        activePage.build(LayoutInflater.from(host.getContext()), host);
        activePage.setTag(TAG_PAGE);
        // 防透底：效果页自身无背景（星空/壁纸负责铺底），页面渲染层异常时
        //（壁纸死链/未加载完成）会透出底下播放页——垫层纯黑底色，保证效果页永远不透明
        activePage.setBackgroundColor(android.graphics.Color.BLACK);

        // 壁纸模式：壁纸层垫底插入（盖住页面背景色/渐变/星空），失败回退默认背景
        if (injectWallpaper()) {
            activePage.onWallpaperModeChanged(true);
        }

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

        // 数据就绪重试（最多4次×200ms）：乐酷分屏切换时 Fragment 重建后 bindService
        // 尚未回调（bound=false），歌曲列表返回 null → 封面流永不加载（Hub 仅值变化才推）。
        // 此处轮询补灌，兜掉竞态窗口；列表就绪即止
        retryInitialData(0);

        subscribe();
    }

    /** 清扫树上残留的旧效果页（tag 识别，与当前页不同才移除） */
    private void cleanOrphanPages() {
        if (host == null) return;
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            android.view.View child = host.getChildAt(i);
            if (child instanceof LyricEffectPage && child != activePage
                    && TAG_PAGE.equals(child.getTag())) {
                child.animate().cancel();
                child.setAlpha(1f);
                if (((LyricEffectPage) child).getWallpaperView() != null) {
                    ((LyricEffectPage) child).getWallpaperView().release();
                }
                host.removeView(child);
            }
        }
    }

    /** 分屏重建/bindService 竞态兜底：歌曲未就绪时延迟重试补灌首帧数据 */
    private void retryInitialData(int attempt) {
        if (!active || activePage == null) return; // 效果页已退出
        if (hub == null || hub.getSong() != null) return; // 数据已就绪
        if (attempt >= 4) return;
        final LyricEffectPage page = activePage;
        host.postDelayed(() -> {
            if (!active || page != activePage) return; // 已换页/退出
            if (hub.getSong() == null) {
                page.onSongChanged(hub.getSong());
                retryInitialData(attempt + 1);
            }
        }, 200L);
    }

    /** 退出效果页（淡出+退订+移除） */
    public void detach() {
        if (!active || activePage == null) return;
        final LyricEffectPage page = activePage;
        active = false;
        activePage = null;
        unsubscribe();
        if (onDetachListener != null) onDetachListener.run(); // 效果页释放回调（拼贴页恢复卡内频谱）

        // 淡出后移除（ViewPropertyAnimator 终点 alpha=0，结束回调移除视图）
        page.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            // 壁纸层随页释放（图片 recycle / 视频 stop+release）
            if (page.getWallpaperView() != null) page.getWallpaperView().release();
            if (page.getParent() == host) host.removeView(page);
        }).start();
        page.setOnClickListener(null);
    }

    /** 效果页退出回调（detach 时刻；拼贴页用它恢复卡内频谱） */
    private Runnable onDetachListener;

    public void setOnDetachListener(Runnable listener) {
        this.onDetachListener = listener;
    }

    /** 壁纸模式注入：设置开启且壁纸文件存在 → 垫底插入渲染层，成功与否均返回是否处于壁纸模式 */
    private boolean injectWallpaper() {
        if (host == null || activePage == null) return false;
        if (!EffectWallpaperStore.isWallpaperMode(host.getContext())) return false;
        java.io.File file = EffectWallpaperStore.findWallpaperFile(host.getContext());
        if (file == null) return false;   // 未设壁纸：回退默认背景（不黑屏）
        EffectWallpaperView wallpaper = new EffectWallpaperView(host.getContext());
        // 异步失败兜底（视频 error 等）：恢复默认背景并移除壁纸层，绝不透出播放页
        wallpaper.setOnWallpaperErrorListener(() -> {
            LyricEffectPage page = activePage;
            if (page == null || wallpaper.getParent() != page) return; // 页已退出/已换
            page.removeView(wallpaper);
            page.onWallpaperModeChanged(false);
        });
        if (!wallpaper.load(file)) {
            wallpaper.release();         // 解码/打开失败：回退默认背景
            return false;
        }
        activePage.addView(wallpaper, 0);
        activePage.setWallpaperView(wallpaper);
        return true;
    }

    private void subscribe() {
        if (hub == null) return;
        stateObserver = state -> {
            LyricEffectPage p = activePage;
            if (p == null) return;
            p.onSongChanged(state.song);
            // 歌/日夜/皮肤变化都可能改变播放页高亮色：重拉注入
            applyCurrentHighlight();
        };
        hub.addObserver(stateObserver);
        progressObserver = (pos, dur) -> {
            LyricEffectPage p = activePage;
            if (p != null) {
                p.onProgress(pos, dur);
                // 能量注入（心象等页用；无提供者时 0——效果页保底静态呼吸）
                p.onEnergy(bassProvider != null ? bassProvider.getBassEnergy() : 0f);
            }
        };
        hub.addProgressObserver(progressObserver);
        lyricObserver = data -> {
            LyricEffectPage p = activePage;
            if (p != null) p.onLyricLoaded(data);
        };
        hub.addLyricObserver(lyricObserver);
        // 新封面取色完成（光晕皮肤高亮随封面变）：播放页 LyricView 同帧稍后会更新亮色，
        // 此处 post 到下一帧再拉才能取到更新后的真值（直接拉会拿到旧色）
        coverColorObserver = (top, bottom) -> {
            // 高亮色走播放页真值通道（下一帧拉取）
            applyCurrentHighlight();
            // 氛围微尘直接吃封面双色（星空页无此需求则忽略）
            if (activePage instanceof GlowEffectPage) ((GlowEffectPage) activePage).onCoverColorsChanged(top, bottom);
            if (activePage instanceof PendoloEffectPage) ((PendoloEffectPage) activePage).onCoverColorsChanged(top, bottom);
        };
        hub.addCoverColorObserver(coverColorObserver);
    }

    /** 重拉播放页当前高亮色注入效果页（下一帧执行，等播放页同帧更新完成） */
    private void applyCurrentHighlight() {
        if (colorProvider == null) return;
        LyricEffectPage p = activePage;
        if (p == null) return;
        p.post(() -> {
            LyricEffectPage page = activePage;
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
