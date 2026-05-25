package com.jingxin.jingxinmusic.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.ThemeColors;

/**
 * 文件夹封面View（酷狗风格）：
 * - 正方形卡片，6色循环渐变背景
 * - 封面图片居中偏上，约占2/3高度，左右对齐
 * - 封面底部渐隐融入卡片背景
 * - 无封面时用默认耳机图标
 */
public class FolderCoverView extends View {

    private Bitmap coverBitmap;         // 外部设置的封面
    private Bitmap defaultCoverBitmap;  // 默认封面缓存
    private boolean nightMode = true;

    // 渐变背景色
    private int gradientStart = 0;
    private int gradientEnd = 0;

    // 画笔
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final RectF coverRect = new RectF();
    private final RectF cardRect = new RectF();
    private final Path roundRectPath = new Path();

    // 圆角半径
    private final float cornerRadius;

    public FolderCoverView(Context context) {
        this(context, null);
    }

    public FolderCoverView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FolderCoverView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        cornerRadius = 12 * getResources().getDisplayMetrics().density;
    }

    /** 设置日夜模式 */
    public void setNightMode(boolean night) {
        this.nightMode = night;
        invalidate();
    }

    /** 设置外部6色循环渐变色 */
    public void setGradientColors(int startColor, int endColor) {
        this.gradientStart = startColor;
        this.gradientEnd = endColor;
        invalidate();
    }

    /** 构建圆角矩形Path */
    private void buildRoundRectPath(RectF rect, float rx, float ry) {
        roundRectPath.reset();
        roundRectPath.addRoundRect(rect, rx, ry, Path.Direction.CW);
    }

    /** 设置封面Bitmap（可为null，显示默认封面） */
    public void setCoverBitmap(Bitmap bmp) {
        this.coverBitmap = bmp;
        invalidate();
    }

    /** 获取默认封面 */
    private Bitmap ensureDefaultCover() {
        if (defaultCoverBitmap != null && !defaultCoverBitmap.isRecycled()) return defaultCoverBitmap;
        try {
            Drawable drawable = ContextCompat.getDrawable(getContext(), R.drawable.ic_music_icon);
            if (drawable == null) return null;
            int size = 256;
            defaultCoverBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(defaultCoverBitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
        } catch (Exception e) {
            return null;
        }
        return defaultCoverBitmap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float density = getResources().getDisplayMetrics().density;

        // 1. 画圆角渐变背景
        int startColor = gradientStart != 0 ? gradientStart :
                (nightMode ? ThemeColors.nightCardBg() : ThemeColors.dayCardBg());
        int endColor = gradientEnd != 0 ? gradientEnd :
                (nightMode ? ThemeColors.nightCardBgEnd() : ThemeColors.dayCardBgEnd());

        LinearGradient bgGradient = new LinearGradient(0, 0, w, h, startColor, endColor, Shader.TileMode.CLAMP);
        bgPaint.setShader(bgGradient);
        cardRect.set(0, 0, w, h);
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint);
        bgPaint.setShader(null);

        // 2. 计算封面区域：约占卡片65%高度，水平居中，距离顶部8dp
        float coverPadding = 8 * density;
        float coverHeight = (h - coverPadding * 2) * 0.70f;
        float coverWidth = w - coverPadding * 2;
        float coverLeft = coverPadding;
        float coverTop = coverPadding;
        coverRect.set(coverLeft, coverTop, coverLeft + coverWidth, coverTop + coverHeight);

        // 3. 画封面图片
        Bitmap bmp = (coverBitmap != null && !coverBitmap.isRecycled()) ? coverBitmap : ensureDefaultCover();
        if (bmp != null) {
            canvas.save();
            // 裁剪圆角区域
            buildRoundRectPath(coverRect, 8 * density, 8 * density);
            canvas.clipPath(roundRectPath);

            // 计算缩放：coverFit（填满区域），居中裁剪
            float scaleX = coverWidth / bmp.getWidth();
            float scaleY = coverHeight / bmp.getHeight();
            float scale = Math.max(scaleX, scaleY);

            BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaderMatrix.reset();
            shaderMatrix.setScale(scale, scale);
            // 居中偏移
            float dx = coverLeft + (coverWidth - bmp.getWidth() * scale) / 2f;
            float dy = coverTop + (coverHeight - bmp.getHeight() * scale) / 2f;
            shaderMatrix.postTranslate(dx, dy);
            shader.setLocalMatrix(shaderMatrix);
            coverPaint.setShader(shader);
            canvas.drawRect(coverRect, coverPaint);
            coverPaint.setShader(null);

            canvas.restore();

            // 4. 底部渐隐遮罩：封面底部30%区域，从透明渐变到卡片背景色
            float fadeStartY = coverTop + coverHeight * 0.65f;
            float fadeEndY = coverTop + coverHeight;
            LinearGradient fadeGradient = new LinearGradient(
                    0, fadeStartY, 0, fadeEndY,
                    0x00000000,  // 完全透明
                    startColor,  // 融入卡片背景起始色
                    Shader.TileMode.CLAMP);
            fadePaint.setShader(fadeGradient);
            canvas.save();
            buildRoundRectPath(coverRect, 8 * density, 8 * density);
            canvas.clipPath(roundRectPath);
            canvas.drawRect(coverRect.left, fadeStartY, coverRect.right, fadeEndY, fadePaint);
            canvas.restore();
            fadePaint.setShader(null);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
