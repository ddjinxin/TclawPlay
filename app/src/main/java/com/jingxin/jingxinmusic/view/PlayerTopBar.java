package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.jingxin.jingxinmusic.R;

/**
 * 播放页顶栏（全风格共用：经典/唱机/轮播/沉浸 × 横竖屏）
 *
 * 结构（单一定义，替代此前 8 份 XML 按钮块 + 2 份复制的 layoutTopBar 公式）：
 * [返回][音效][歌词动效] ←弹性空位→ [歌词搜索][频谱][装扮]
 * - 返回键原规格公式（三档比例×aspectFactor×1.3，含自身 padding）
 * - 右组三条均分 35% 屏宽（weight）
 * - 左列三键相邻图标间隙 = 右组相邻键间隙（margin 补齐）
 * - 日夜染色：夜间原图标（白色），白天染主题深色
 */
public class PlayerTopBar extends LinearLayout {

    /** 按钮事件（页面只管视图，动作全部回调宿主） */
    public interface Listener {
        default void onBack() {}
        default void onEffect(View anchor) {}
        default void onLyricEffect() {}
        default void onLyricSearch(View anchor) {}
        default void onSpectrum(View anchor, boolean longPress) {}
        default void onOutfit() {}
    }

    private final ImageView btnBack = new ImageView(getContext(), null, 0);
    private final ImageView btnEffect = new ImageView(getContext(), null, 0);
    private final ImageView btnLyricFx = new ImageView(getContext(), null, 0);
    private final LinearLayout rightGroup = new LinearLayout(getContext());
    private final ImageView btnSearch = new ImageView(getContext(), null, 0);
    private final ImageView btnSpectrum = new ImageView(getContext(), null, 0);
    private final ImageView btnOutfit = new ImageView(getContext(), null, 0);
    private final View spacer = new View(getContext());

    private Listener listener;

    public PlayerTopBar(Context context) { this(context, null); }

    public PlayerTopBar(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        TypedArray ta = getContext().obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackgroundBorderless});
        int rippleBg = ta.getResourceId(0, 0);
        ta.recycle();

        addView(btnBack, new LayoutParams(0, 0));
        addView(btnEffect, new LayoutParams(0, 0));
        addView(btnLyricFx, new LayoutParams(0, 0));
        addView(spacer, new LayoutParams(0, 0, 1f));

        rightGroup.setOrientation(HORIZONTAL);
        rightGroup.setGravity(Gravity.CENTER_VERTICAL);
        rightGroup.addView(btnSearch, new LayoutParams(0, 0, 1f));
        rightGroup.addView(btnSpectrum, new LayoutParams(0, 0, 1f));
        rightGroup.addView(btnOutfit, new LayoutParams(0, 0, 1f));
        addView(rightGroup, new LayoutParams(0, 0));

        // 图标与交互规格（同旧 XML：白色矢量 + 涟漪 + TV 焦点 + 可点）
        for (ImageView b : buttons()) {
            b.setClickable(true);
            b.setFocusable(true);
            // setForeground 是 API 23+ 方法，Android 5.1（API 22）不存在会 NoSuchMethodError；
            // TV 焦点高亮仅大屏 TV 场景需要，低版本跳过
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                b.setForeground(getContext().getDrawable(R.drawable.tv_focus_round));
            }
            if (rippleBg != 0) b.setBackgroundResource(rippleBg);
            b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        btnBack.setImageResource(R.drawable.ic_back);
        btnEffect.setImageResource(R.drawable.ic_effect);
        btnLyricFx.setImageResource(R.drawable.ic_lyric_fx);
        btnSearch.setImageResource(R.drawable.ic_lyric_search);
        btnSpectrum.setImageResource(R.drawable.ic_spectrum);
        btnOutfit.setImageResource(R.drawable.ic_outfit);

        btnBack.setOnClickListener(v -> { if (listener != null) listener.onBack(); });
        btnEffect.setOnClickListener(v -> { if (listener != null) listener.onEffect(btnEffect); });
        btnLyricFx.setOnClickListener(v -> { if (listener != null) listener.onLyricEffect(); });
        btnSearch.setOnClickListener(v -> { if (listener != null) listener.onLyricSearch(btnSearch); });
        btnSpectrum.setOnClickListener(v -> {
            if (listener != null) listener.onSpectrum(btnSpectrum, false);
        });
        btnSpectrum.setOnLongClickListener(v -> {
            if (listener != null) listener.onSpectrum(btnSpectrum, true);
            return true;
        });
        btnOutfit.setOnClickListener(v -> { if (listener != null) listener.onOutfit(); });
    }

    private ImageView[] buttons() {
        return new ImageView[]{btnBack, btnEffect, btnLyricFx, btnSearch, btnSpectrum, btnOutfit};
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 日夜染色：夜间清滤镜（白色原图），白天染主题深色 */
    public void applyTheme(boolean night, int dayColor) {
        for (ImageView b : buttons()) {
            if (night) b.clearColorFilter();
            else b.setColorFilter(dayColor, PorterDuff.Mode.SRC_IN);
        }
    }

    /**
     * 顶栏几何（公式原样沉自 StandardPageBase.layoutTopBar / ImmersivePageV2.layoutTopBarImpl）
     * @param systemTopInset 系统状态栏高度（车机 0）
     */
    public void applyGeometry(int width, int height, boolean landscape,
                              float density, int systemTopInset) {
        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) getLayoutParams();
        if (bp != null) {
            bp.topMargin = systemTopInset + Math.max(4, (int) (height * 0.01f));
            setLayoutParams(bp);
        }
        int sidePadding = (int) (density * 8);
        setPadding(sidePadding, 0, sidePadding, 0);

        float rBack = (height < 700) ? 0.048f : (height <= 1200) ? 0.055f : 0.060f;
        float rRight = (height < 700) ? 0.043f : (height <= 1200) ? 0.050f : 0.055f;
        float rBackTop = landscape ? rBack + 0.005f : rBack;
        float rRightTop = landscape ? rRight + 0.005f : rRight;
        float aspectFactor = 1f + Math.max(0f, (width / (float) height - 1.78f) / 3f);
        int topBtnSize = (int) (height * rBackTop * aspectFactor * 1.3f);
        int topRightBtnSize = (int) (height * rRightTop * aspectFactor);
        int backPadding = (int) (topBtnSize / 1.3f * 0.15f);

        // 右组：宽 35% 屏宽，高 topRightBtnSize
        int rightGroupW = (int) (width * 0.35f);
        LayoutParams rp = (LayoutParams) rightGroup.getLayoutParams();
        rp.width = rightGroupW;
        rp.height = topRightBtnSize;
        rp.weight = 0;
        rightGroup.setLayoutParams(rp);

        // 键内边距（横竖统一 15%）：此前横屏 5% 把右五键图标撑满按钮，返回键 1.3 倍
        // 放大在横屏被抹平；统一 15% 后两方向层级一致（差约 55%）且图标不致过小
        int topRightPadding = (int) (topRightBtnSize * 0.15f);

        // 间距基准：右组相邻键图标间隙（每键均分 rightGroupW/3，图标居中）
        int gapRight = Math.max(0, rightGroupW / 3 - (topRightBtnSize - 2 * topRightPadding));
        // 返回→音效：紧挨时自带间隙 backPadding+topRightPadding，margin 补齐到基准
        int gapBackEff = Math.max(0, gapRight - backPadding - topRightPadding);
        // 音效→动效：紧挨时自带间隙 2×topRightPadding
        int gapEffFx = Math.max(0, gapRight - 2 * topRightPadding);

        LayoutParams backP = new LayoutParams(topBtnSize, topBtnSize);
        backP.rightMargin = gapBackEff;
        btnBack.setLayoutParams(backP);
        btnBack.setPadding(backPadding, backPadding, backPadding, backPadding);

        LayoutParams effP = new LayoutParams(topRightBtnSize, topRightBtnSize);
        effP.rightMargin = gapEffFx;
        btnEffect.setLayoutParams(effP);
        btnEffect.setPadding(topRightPadding, topRightPadding, topRightPadding, topRightPadding);

        LayoutParams fxP = new LayoutParams(topRightBtnSize, topRightBtnSize);
        btnLyricFx.setLayoutParams(fxP);
        btnLyricFx.setPadding(topRightPadding, topRightPadding, topRightPadding, topRightPadding);

        // 右组三键：均分（weight）
        for (ImageView b : new ImageView[]{btnOutfit, btnSpectrum, btnSearch}) {
            LayoutParams p = (LayoutParams) b.getLayoutParams();
            p.height = topRightBtnSize;
            p.weight = 1;
            b.setLayoutParams(p);
            b.setPadding(topRightPadding, topRightPadding, topRightPadding, topRightPadding);
        }
    }
}
