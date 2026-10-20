package com.jingxin.jingxinmusic.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import com.jingxin.jingxinmusic.R;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 旋转封面视图
 * 普通模式：旋转画封面
 * 黑胶模式：仿波尼音乐，用预制位图(vinyl_disc.png)画黑胶盘 + 中心封面
 *   - 封面直径 = 黑胶直径的 2/3
 *   - 黑胶盘纹理和光影预置在PNG里，运行时只做drawBitmap+旋转
 *   - 外侧半透明边框 + 固定环境光高光弧
 */
public class RotatingCoverView extends AppCompatImageView {
    
    private static final String TAG = "RotatingCoverView";
    private static final float ROTATION_SPEED = 15f;
    
    private float currentRotationAngle = 0f;
    private ValueAnimator rotationAnimator;
    private boolean isRotating = false;
    private boolean userPaused = false;
    private boolean vinylMode = false;
    
    // 预制黑胶盘位图
    private Bitmap vinylDiscBitmap = null;
    
    public RotatingCoverView(Context context) { super(context); init(); }
    public RotatingCoverView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public RotatingCoverView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }
    
    private void init() {
        setClipToOutline(true);
        setScaleType(ScaleType.CENTER_CROP);
        setRotation(0f);
        
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
        rotationAnimator.setDuration((long) (360 / ROTATION_SPEED * 1000));
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.addUpdateListener(animation -> {
            currentRotationAngle = (float) animation.getAnimatedValue();
            invalidate();
        });
        
        setOnClickListener(v -> {
            if (isRotating) {
                userPaused = true;
                stopRotation();
            } else {
                userPaused = false;
                startRotation();
            }
        });
    }
    
    private boolean vinylLoadFailed = false;

    private void ensureVinylBitmap() {
        if (vinylDiscBitmap != null || vinylLoadFailed) return;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;  // 禁止密度缩放，防止高密度设备放大解码OOM
            vinylDiscBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bg_playing_disc, opts);
            if (vinylDiscBitmap != null) {
                vinylDiscBitmap = com.jingxin.jingxinmusic.util.BitmapUtil.scaleToMaxSize(vinylDiscBitmap, 600);
            }
            if (vinylDiscBitmap == null) vinylLoadFailed = true;
        } catch (Exception e) {
            vinylLoadFailed = true;
        }
    }
    
    public void setVinylMode(boolean vinyl) {
        this.vinylMode = vinyl;
        setClipToOutline(!vinyl);
        // 黑胶模式去掉background描边（#FF4081粉色环），非黑胶模式恢复
        if (vinyl) {
            setBackground(null);
        } else {
            setBackgroundResource(R.drawable.circle_cover_background);
        }
        invalidate();
    }

    // ===== 页面传参模式（独立页：形态特征收口，页面不再散装手术） =====

    /** 封面形态特征枚举（对应四风格的封面外观） */
    public enum CoverStyle {
        /** 圆形裁剪 + 描边背景（经典） */
        CIRCLE,
        /** 黑胶盘 + 中心圆封（唱片机；黑胶渲染内部自理） */
        VINYL,
        /** 矩形不裁剪 + 无背景（沉浸横屏等矩形用法） */
        RECT,
        /** 矩形 + 前景边缘渐变（沉浸横屏渐变边；渐变 Drawable 由页面经 setBorderGradient 注入） */
        RECT_BORDER_GRADIENT
    }

    /**
     * 应用形态特征（组件内部自理 clipToOutline/background/foreground，幂等）。
     * RECT_BORDER_GRADIENT 的渐变色随后经 setBorderGradient(Drawable) 注入。
     */
    public void setStyle(CoverStyle style) {
        switch (style) {
            case CIRCLE:
                vinylMode = false;
                setClipToOutline(true);
                setBackgroundResource(R.drawable.circle_cover_background);
                com.jingxin.jingxinmusic.util.CompatUtil.setForegroundCompat(this, null);
                break;
            case VINYL:
                vinylMode = true;
                setClipToOutline(false);
                setBackground(null);
                com.jingxin.jingxinmusic.util.CompatUtil.setForegroundCompat(this, null);
                break;
            case RECT:
                vinylMode = false;
                setClipToOutline(false);
                setBackground(null);
                com.jingxin.jingxinmusic.util.CompatUtil.setForegroundCompat(this, null);
                break;
            case RECT_BORDER_GRADIENT:
                vinylMode = false;
                setClipToOutline(false);
                setBackground(null);
                break;
        }
        invalidate();
    }

    /** 注入前景渐变（仅 RECT_BORDER_GRADIENT 风格用，可 null 清除） */
    public void setBorderGradient(android.graphics.drawable.Drawable gradient) {
        com.jingxin.jingxinmusic.util.CompatUtil.setForegroundCompat(this, gradient);
    }

    // ===== 左缘 alpha 淡出（沉浸横屏白天光晕专用） =====

    private float leftFadeRatio = 0f;
    private long fadeSeed = 0L; // 边缘形状种子：每封面随机一次（切歌换形状，播放期稳定不闪烁）

    // 波浪动画态（淡出激活期间常驻；双buffer复用防每帧7MB级分配）
    private ValueAnimator waveAnimator;
    private float wavePhase = 0f;
    private int waveFrame = 0;
    private Bitmap fadeContentCache; // 封面内容离屏缓存（切歌/尺寸变时重建）
    private Bitmap fadeWork;         // 每帧合成工作区（复用，不逐帧分配）
    private int fadeCacheW = -1, fadeCacheH = -1;

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        fadeSeed = System.nanoTime();
        if (fadeContentCache != null) { fadeContentCache.recycle(); fadeContentCache = null; } // 内容变：下帧重建
        if (leftFadeRatio > 0f) invalidate();
    }

    /**
     * 左缘 alpha 淡出比例（0=关闭，≤0.5）。
     * 白线根治：静态渐变带追不上逐帧光晕动画，改为封面左缘淡出——
     * 透出「页面底色+动态光晕」接管过渡，边界两侧是连续流动的同一层动画。
     */
    public void setLeftFade(float ratio) {
        float r = Math.max(0f, Math.min(0.5f, ratio));
        if (this.leftFadeRatio != r) {
            this.leftFadeRatio = r;
            if (r > 0f) startWaveAnim(); else stopWaveAnim();
            invalidate();
        }
    }

    /** 波浪动画启停：淡出激活时相位九秒一圈慢滚；停用即释放双buffer */
    private void startWaveAnim() {
        if (waveAnimator == null) {
            waveAnimator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
            waveAnimator.setDuration(9000L);
            waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
            waveAnimator.setInterpolator(new LinearInterpolator());
            waveAnimator.addUpdateListener(a -> {
                wavePhase = (float) a.getAnimatedValue();
                if ((waveFrame++ & 1) == 0) invalidate(); // 30fps 跳帧（低端机减负，波浪慢动无感知损失）
            });
        }
        if (waveAnimator.isPaused()) waveAnimator.resume();
        else if (!waveAnimator.isRunning()) waveAnimator.start();
    }

    private void stopWaveAnim() {
        if (waveAnimator != null && waveAnimator.isRunning()) waveAnimator.pause();
        releaseFadeBitmaps();
    }

    private void releaseFadeBitmaps() {
        if (fadeContentCache != null) { fadeContentCache.recycle(); fadeContentCache = null; }
        if (fadeWork != null) { fadeWork.recycle(); fadeWork = null; }
        fadeCacheW = -1; fadeCacheH = -1;
    }

    /**
     * 一维 value noise → 改用：双频正弦叠加，参数由 fadeSeed 哈希派生（见 drawLeftFaded）
     */
    /**
     * 左缘淡出绘制（波浪动画版）：内容缓存（切歌/尺寸变才重建）→ 工作区副本上
     * DST_IN 双频正弦波浪（相位随 wavePhase 慢滚：主波同速+细节波1.6倍速逆流）→ 回画。
     * 防 10-19 坑：离屏合成不经 View 前景路径，DST_IN 只作用于本组件内容。
     * 双buffer复用：仅切歌/转屏重建，每帧零分配；参数由 fadeSeed 哈希派生（每封面随机）。
     * 仅非黑胶矩形封面使用；仅淡出激活期间持有位图（停用即释放）。
     */
    private void drawLeftFaded(Canvas canvas, int width, int height, float cx, float cy) {
        try {
            if (fadeContentCache == null || fadeCacheW != width || fadeCacheH != height) {
                releaseFadeBitmaps();
                fadeContentCache = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas cc = new Canvas(fadeContentCache);
                cc.save();
                cc.rotate(currentRotationAngle, cx, cy);
                super.onDraw(cc);
                cc.restore();
                fadeWork = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                fadeCacheW = width; fadeCacheH = height;
            }
            Canvas off = new Canvas(fadeWork);
            off.drawBitmap(fadeContentCache, 0, 0, null); // 副本上做 DST_IN，缓存本体不受伤
            Paint fade = new Paint();
            fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            int fadeW = Math.max(2, (int) (width * leftFadeRatio));
            // 从 fadeSeed 哈希派生随机正弦参数（每封面一组）
            float r1 = (fadeSeed & 0xFFFF) / 65535f;
            float r2 = ((fadeSeed >> 16) & 0xFFFF) / 65535f;
            float r3 = ((fadeSeed >> 32) & 0xFFFF) / 65535f;
            float r4 = ((fadeSeed >> 48) & 0xFFFF) / 65535f;
            float waveLen1 = height * (0.28f + 0.14f * r1);          // 主波：屏高 28%~42%
            float waveLen2 = 90f + 60f * r2;                          // 细节波：90~150px
            float phase1 = (float) (2 * Math.PI * r3) + wavePhase;          // 主波随相位慢滚
            float phase2 = (float) (2 * Math.PI * r4) + wavePhase * 1.6f;   // 细节波1.6倍速逆流（层次感）
            float amp = fadeW * (0.5f + 0.15f * r1);                  // 振幅：带宽 50%~65%
            int band = 4;                                             // 4px/带：曲线平滑无台阶
            for (int y = 0; y < height; y += band) {
                int yEnd = Math.min(height, y + band);
                float yc = y + band / 2f;
                float n = 0.7f * (float) Math.sin(2 * Math.PI * yc / waveLen1 + phase1)
                        + 0.3f * (float) Math.sin(2 * Math.PI * yc / waveLen2 + phase2);
                int edge = Math.round(fadeW + amp * n);
                edge = Math.max(1, Math.min(width, edge));
                fade.setShader(new LinearGradient(0, 0, edge, 0,
                        0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
                off.drawRect(0, y, edge, yEnd, fade);
            }
            canvas.drawBitmap(fadeWork, 0, 0, null);
        } catch (Throwable t) {
            canvas.save();
            canvas.rotate(currentRotationAngle, cx, cy);
            super.onDraw(canvas);
            canvas.restore();
        }
    }
    
    public boolean isVinylMode() {
        return vinylMode;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            super.onDraw(canvas);
            return;
        }
        
        float cx = width / 2f;
        float cy = height / 2f;
        float viewRadius = Math.min(cx, cy);
        
        if (vinylMode) {
            drawVinylCover(canvas, cx, cy, viewRadius);
        } else if (leftFadeRatio > 0f) {
            drawLeftFaded(canvas, width, height, cx, cy);
        } else {
            canvas.save();
            canvas.rotate(currentRotationAngle, cx, cy);
            super.onDraw(canvas);
            canvas.restore();
        }
    }
    
    /**
     * 黑胶唱片模式（仿波尼音乐/网易云）
     * 使用预制PNG位图 + 旋转drawBitmap
     */
    private void drawVinylCover(Canvas canvas, float cx, float cy, float viewRadius) {
        float density = getResources().getDisplayMetrics().density;
        float coverRadius = viewRadius * 2f / 3f;
        
        ensureVinylBitmap();
        
        // 1. 旋转绘制预制黑胶盘位图
        if (vinylDiscBitmap != null && !vinylDiscBitmap.isRecycled()) {
            canvas.save();
            canvas.rotate(currentRotationAngle, cx, cy);
            float bmpSize = viewRadius * 2;
            float left = cx - bmpSize / 2f;
            float top = cy - bmpSize / 2f;
            RectF dst = new RectF(left, top, left + bmpSize, top + bmpSize);
            Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(vinylDiscBitmap, null, dst, bitmapPaint);
            canvas.restore();
        }
        
        // 2. 旋转绘制封面图片（中心圆形区域）
        canvas.save();
        canvas.rotate(currentRotationAngle, cx, cy);
        Path coverPath = new Path();
        coverPath.addCircle(cx, cy, coverRadius - 1f * density, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(coverPath);
        super.onDraw(canvas);
        canvas.restore();
        canvas.restore();
    }
    
    public void startRotation() {
        if (userPaused) {
            return;
        }
        if (!isRotating) {
            isRotating = true;
            if (rotationAnimator.isPaused()) {
                rotationAnimator.resume();
            } else {
                rotationAnimator.start();
            }
        }
    }
    
    public void stopRotation() {
        if (isRotating) {
            isRotating = false;
            rotationAnimator.pause();
        }
    }
    
    public void stopAndResetRotation() {
        isRotating = false;
        userPaused = false;
        currentRotationAngle = 0f;
        rotationAnimator.cancel();
        invalidate();
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 用 pause 而非 cancel，View 被剥离到 overlay 窗口后重新 attach 时可 resume 恢复旋转
        if (rotationAnimator != null && rotationAnimator.isRunning()) {
            rotationAnimator.pause();
        }
        if (waveAnimator != null && waveAnimator.isRunning()) {
            waveAnimator.pause();
        }
        releaseFadeBitmaps(); // detach 期间不驻留双 buffer，attach 后按需重建
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // View 被重新 attach（如剥离到 overlay 窗口后），恢复暂停的旋转动画
        if (rotationAnimator != null && isRotating && rotationAnimator.isPaused()) {
            rotationAnimator.resume();
        }
        if (waveAnimator != null && leftFadeRatio > 0f && waveAnimator.isPaused()) {
            waveAnimator.resume();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        releaseFadeBitmaps(); // 尺寸变（转屏/缩放）：缓存按新尺寸重建
    }
}
