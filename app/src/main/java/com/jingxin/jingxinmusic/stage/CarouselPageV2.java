package com.jingxin.jingxinmusic.stage;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.view.CoverCarouselAdapter;
import com.jingxin.jingxinmusic.view.CoverCarouselView;
import com.jingxin.jingxinmusic.view.LyricView;
import com.jingxin.jingxinmusic.view.SongTitleBar;

import java.util.List;

/**
 * 轮播风格页面 V2（第8风格，完全自持实例）
 *
 * 继承 ClassicPageV2（染色/背景/订阅/隐藏全复用），但骨架 XML 独立（stage_page_carousel_v2）：
 * - 竖屏：卡片层 cv2_carousel 挂页根顶部（cover_area 收起仅保父类绑定）
 * - 横屏：独立骨架（非经典 65/35 分栏）：卡片置顶全宽 + 信息流满宽 + 底部三件全宽
 *   （旧轮播 setLayoutInfoPanelFullWidth 等效）
 * 卡片几何动态值（尺寸/重叠/贴边距/标题位置）仍代码设（依赖屏高百分比）。
 * - 数据通路：播放列表经 Actions 桥取（playerBinder 引用型数据不走 Hub 值语义广播）
 * - 切歌联动：卡片滑动 → 桥 playSongAt(delta)（Fragment 已含全链路刷新）
 * - 歌词模式锁定：竖屏三行↔五行不进全屏；横屏锁双行（旧轮播行为）
 * - 摇摆动画：随播放态启停
 * - 圆形封面隐藏（父类 onCoverArrived 的 CIRCLE 被覆盖为卡片层接管）；
 *   封面 Bitmap 仅作模糊背景（父类已做）
 */
public class CarouselPageV2 extends ClassicPageV2 {

    // 轮播专属扩展（继承父接口加列表三方法）
    public interface Actions extends ClassicPageV2.Actions {
        List<Song> getPlaylist();
        int getCurrentIndex();
        void playSongAt(int delta); // 相对当前移动（±1 卡片滑动切歌）
    }

    private Actions carouselActions;

    // 卡片层（竖/横两实例，独立自持）
    private CoverCarouselView carousel;
    private CoverCarouselView carouselL;
    private CoverCarouselAdapter carouselAdapter;
    private CoverCarouselAdapter carouselAdapterL;
    private boolean swaying = false;

    public CarouselPageV2(com.jingxin.jingxinmusic.scene.CoverSceneHelper helper) {
        super(helper);
    }

    public void setActions(Actions actions) {
        super.setActions(actions);
        this.carouselActions = actions;
    }

    @Override
    public void install(android.view.LayoutInflater inflater, ViewGroup host) {
        super.install(inflater, host); // inflate 本页 XML + 父类全量绑定
        carousel = portraitRoot.findViewById(R.id.cv2_carousel);
        carouselL = landscapeRoot.findViewById(R.id.cv2_carousel_land);
        carousel.setExecutor(h.executor);
        carouselL.setExecutor(h.executor);
        carouselAdapter = new CoverCarouselAdapter(inflater.getContext(), h.executor);
        carouselAdapterL = new CoverCarouselAdapter(inflater.getContext(), h.executor);
        bindCarousel(carousel, carouselAdapter);
        bindCarousel(carouselL, carouselAdapterL);
        // 卡片两侧溢出可见（镜像旧 rootLayout.setClipChildren(false)，本页私有树一次性设置）
        ((ViewGroup) portraitRoot).setClipChildren(false);
        ((ViewGroup) portraitRoot).setClipToPadding(false);
        ((ViewGroup) landscapeRoot).setClipChildren(false);
        ((ViewGroup) landscapeRoot).setClipToPadding(false);
    }

    /** 轮播独立骨架 XML（结构差异固化进布局；id 与经典版一致复用父类绑定） */
    @Override
    protected int providePortraitLayout() { return R.layout.stage_page_carousel_v2; }

    @Override
    protected int provideLandscapeLayout() { return R.layout.stage_page_carousel_v2_land; }

    private void bindCarousel(CoverCarouselView v, CoverCarouselAdapter adapter) {
        v.setOnSongChangeListener(delta -> {
            if (carouselActions != null) carouselActions.playSongAt(delta);
        });
    }

    @Override
    public void enter() {
        // 圆环频谱降级（镜像旧 CarouselPage.enter：轮播无圆形封面可挂，overlay 样式循环切走）
        if (h.spectrumView != null && h.spectrumView.isCoverOverlayMode()) {
            while (h.spectrumView.isCoverOverlayMode()) {
                h.spectrumView.switchStyle();
            }
        }
        super.enter();
        boolean landscape = h.isLandscapeMode;
        // 圆形封面/封面列退出（XML 默认 GONE，此处防父类路径回置），卡片层接管
        cover.setVisibility(View.GONE);
        coverL.setVisibility(View.GONE);
        coverCol.setVisibility(View.GONE);
        coverArea.setVisibility(View.GONE);
        carousel.setVisibility(landscape ? View.GONE : View.VISIBLE);
        carouselL.setVisibility(landscape ? View.VISIBLE : View.GONE);
        // 歌词模式锁定（旧轮播：竖屏三行↔五行不进全屏；横屏锁双行）
        lockLyricMode(landscape);
        // 列表全量灌入（绑定未完成时为空，服务就绪后的切歌广播会重新拉满自愈）
        syncPlaylistPosition();
        // 摇摆动画随播放态
        if (h.isPlaying) startSwayIfNeeded();
    }

    /** 歌词模式锁定（镜像旧 CarouselPage.enter 歌词分支） */
    private void lockLyricMode(boolean landscape) {
        LyricView ly = landscape ? lyricL : lyric;
        if (ly == null) return;
        if (landscape) {
            if (ly.getDisplayMode() != LyricView.DisplayMode.DOUBLE_LINE) {
                ly.setDisplayMode(LyricView.DisplayMode.DOUBLE_LINE);
            }
            ly.setShowPrevLine(false);
        } else {
            ly.setShowPrevLine(true);
            if (ly.getDisplayMode() == LyricView.DisplayMode.FULL) {
                ly.setDisplayMode(LyricView.DisplayMode.MULTI_LINE);
            }
        }
    }

    /**
     * 列表全量刷新（enter 进入/Fragment 切歌广播/服务就绪后均走此处）：每次重新拉列表。
     * 首次进入时若绑定未完成 getPlaylist 为 null、adapter 列表留空；
     * 服务恢复完成后必发切歌广播、此处重新拉取即可自愈（切横竖屏“封面才出现”的根因）。
     */
    public void syncPlaylistPosition() {
        if (carouselActions == null) return;
        List<Song> playlist = carouselActions.getPlaylist();
        int idx = carouselActions.getCurrentIndex();
        if (playlist == null || playlist.isEmpty()) return;
        carouselAdapter.update(playlist, idx, carousel.getCards());
        carouselAdapterL.update(playlist, idx, carouselL.getCards());
        carousel.requestLayoutCards();
        carouselL.requestLayoutCards();
    }

    private void startSwayIfNeeded() {
        CoverCarouselView active = h.isLandscapeMode ? carouselL : carousel;
        if (active != null && !swaying) {
            active.startSwayAnimation();
            swaying = true;
        }
    }

    private void stopSway() {
        if (carousel != null) carousel.stopSwayAnimation();
        if (carouselL != null) carouselL.stopSwayAnimation();
        swaying = false;
    }

    @Override
    public void layout(int width, int height) {
        super.layout(width, height);
        applyCarouselGeometry(width, height);
    }

    /**
     * 轮播几何覆盖（父类按经典骨架算完后按旧轮播公式重申，逐项镜像旧 CarouselPage.layout）：
     * 竖屏：卡片 25%H+16dp 容器置顶；占位=卡片顶距+容器高+题距 26dp（经典占位公式会让标题压卡片）；
     * 横屏：卡片挂本页根全宽置顶（28%H、顶栏地板线 6%H 含 inset）；标题压卡片下方（字号双限公式）、
     *       艺人距 1%H；歌词锁双行+65%宽居中+场景字号链；频谱 0.08H（父类按经典设了 0.10H）
     */
    private void applyCarouselGeometry(int width, int height) {
        if (h.isLandscapeMode) {
            if (carouselL == null) return;
            int topBarH = Math.max(h.calcTopBarHeight(height, width, true),
                    (int) (height * 0.06f));
            int coverSize = (int) (height * 0.28f);
            carouselL.setCoverSize(coverSize);
            carouselL.setOverlapRatio(0.30f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) carouselL.getLayoutParams();
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = coverSize;
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = topBarH; // 旧横屏 carouselTopMargin（calcTopBarHeight 内含 systemTopInset）
            carouselL.setLayoutParams(lp);
            carouselL.requestLayoutCards();
            // 右列/圆形封面强制收起（父类 layoutLandscapeImpl/applyLyricModeEffect 会回置 VISIBLE）
            coverCol.setVisibility(View.GONE);
            coverL.setVisibility(View.GONE);
            // 标题：压到卡片下方（旧占位公式 topBarH+卡片高，题距 1.5%H；字号双限）
            float nameSize = Math.min(width * 0.65f * 0.048f, height * 0.08f * 0.55f);
            int nameMargin = topBarH + coverSize + Math.max(4, (int) (height * 0.015f));
            titleL.applyScene(new SongTitleBar.TitleScene(nameSize, 0.7f, nameMargin, true));
            LinearLayout.LayoutParams artistLp =
                    (LinearLayout.LayoutParams) titleL.artistView().getLayoutParams();
            artistLp.topMargin = Math.max(2, (int) (height * 0.01f));
            titleL.artistView().setLayoutParams(artistLp);
            // 歌词：锁双行 + 65%宽居中 + 场景字号链（旧公式整段镜像）
            lockLyricMode(true);
            LinearLayout.LayoutParams lyp = (LinearLayout.LayoutParams) lyricL.getLayoutParams();
            lyp.width = (int) (width * 0.65f);
            lyp.gravity = Gravity.CENTER_HORIZONTAL;
            lyp.topMargin = Math.max(4, (int) (height * 0.015f));
            lyricL.setLayoutParams(lyp);
            lyricL.setMaxTextSizeForHeight(Float.MAX_VALUE);
            lyricL.setMaxLyricAreaHeight(Float.MAX_VALUE);
            float lyricTopMargin = Math.max(4, (int) (height * 0.015f));
            float usedHeight = topBarH + height * 0.08f + lyricTopMargin
                    + height * 0.08f + height * 0.01f + height * 0.015f + height * 0.03f;
            float lyricAreaHeight = Math.max(40f, height - usedHeight);
            float cur = nameSize * 1.2f;
            float norm = nameSize * 0.9f;
            float spacing = norm;
            float total = cur + norm + spacing;
            if (total > lyricAreaHeight) {
                spacing = Math.max(norm * 0.3f, lyricAreaHeight - cur - norm);
                total = cur + norm + spacing;
            }
            if (total > lyricAreaHeight) {
                float scale = lyricAreaHeight / total;
                cur *= scale;
                norm *= scale;
                spacing = lyricAreaHeight - cur - norm;
            }
            lyricL.setSceneTextSizes(cur, norm, spacing);
            // 频谱：旧横屏 0.08H
            android.view.ViewGroup.LayoutParams sp = spectrumL.getLayoutParams();
            sp.height = (int) (height * 0.08f);
            spectrumL.setLayoutParams(sp);
        } else {
            if (carousel == null) return;
            int topBarH = h.calcTopBarHeight(height, width, false);
            int coverSize = (int) (height * 0.25f);
            int carouselTopMargin = h.systemTopInset + topBarH + (int) (h.density * 10);
            int carouselHeight = coverSize + (int) (h.density * 16);
            carousel.setCoverSize(coverSize);
            carousel.setOverlapRatio(0.70f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) carousel.getLayoutParams();
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = carouselHeight;
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            lp.topMargin = carouselTopMargin;
            carousel.setLayoutParams(lp);
            carousel.requestLayoutCards();
            // 占位/题距按旧轮播公式（父类占位=经典 5.6%H+25%H+1.6%H，标题会压卡片下缘）
            LinearLayout.LayoutParams ph = (LinearLayout.LayoutParams) coverPlaceholder.getLayoutParams();
            ph.height = carouselTopMargin + carouselHeight;
            coverPlaceholder.setLayoutParams(ph);
            title.applyScene(new SongTitleBar.TitleScene(
                    width * 0.048f, 0.7f, (int) (h.density * 26), true));
        }
    }

    @Override
    protected void onCoverArrived(android.graphics.Bitmap bitmap, boolean landscape) {
        // 轮播：封面不进圆形位（卡片由 adapter 自管图）；模糊背景照常（父类逻辑复用）
        // 触发父类模糊背景链：直接用父类实现的模糊部分，但跳过圆形封面设置
        super.onCoverArrived(bitmap, landscape);
        // 全屏歌词时父类会藏 cover——轮播卡片不受影响（卡片层独立）
        cover.setVisibility(View.GONE);
        coverL.setVisibility(View.GONE);
    }

    @Override
    public void onPlayingStateChanged(boolean isPlaying) {
        super.onPlayingStateChanged(isPlaying);
        if (isPlaying) startSwayIfNeeded();
        else stopSway();
    }

    @Override
    public void onServiceResumed(boolean isPlaying) {
        super.onServiceResumed(isPlaying);
        if (isPlaying) startSwayIfNeeded();
        else stopSway();
    }

    /**
     * 轮播歌词联动（覆写父类：轮播无全屏歌词、标题常显、圆形封面任何分支不回归）。
     * 父类版本在"全屏→多行"回退时会回置旋转封面 VISIBLE+经典占位公式（轮播出现旋转封面、
     * 标题上移贴卡片的根因）；歌词点击在父类页面内直调本方法，必须此处拦截。
     */
    @Override
    protected void applyLyricModeEffect(boolean land) {
        lockLyricMode(land); // 竖屏 FULL→MULTI_LINE 回退；横屏锁双行
        title.setTitleVisible(true);
        titleL.setTitleVisible(true);
        cover.setVisibility(View.GONE);
        coverArea.setVisibility(View.GONE);
        if (lastWidth > 0 && lastHeight > 0) {
            applyCarouselGeometry(lastWidth, lastHeight); // 占位/卡片几何按轮播公式重申
        }
    }

    @Override
    public void setCover(android.graphics.Bitmap bitmap) {
        // 轮播：卡片封面由 adapter 从列表自载；此处沿用父类模糊背景链
        super.setCover(bitmap);
        cover.setVisibility(View.GONE);
        coverL.setVisibility(View.GONE);
    }

    @Override
    public boolean shouldShowSpectrumButton(int spectrumStyle) {
        return !com.jingxin.jingxinmusic.view.SpectrumView.isOverlayStyle(spectrumStyle);
    }

    @Override
    public boolean needsReloadCover() {
        return true;
    }

    @Override
    public float getSpectrumHeightRatio() {
        return h.isLandscapeMode ? 0.08f : 0.10f;
    }

    @Override
    public void exit() {
        super.exit();
        stopSway();
        carousel.setVisibility(View.GONE);
        carouselL.setVisibility(View.GONE);
    }
}
