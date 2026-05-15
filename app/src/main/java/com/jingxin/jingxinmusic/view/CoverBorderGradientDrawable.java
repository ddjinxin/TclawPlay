package com.jingxin.jingxinmusic.view;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 封面横向渐变 Drawable
 * 左右两侧为遮罩色实色，向中心渐变到透明
 * 中心区域清晰显示封面，两侧自然融入遮罩色
 */
public class CoverBorderGradientDrawable extends Drawable {

    private final Paint paint;
    private int overlayColor;
    private float borderWidthPx;

    public CoverBorderGradientDrawable(int overlayColor, float borderWidthPx) {
        this.overlayColor = overlayColor;
        this.borderWidthPx = borderWidthPx;
        this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    public void setOverlayColor(int color) {
        this.overlayColor = color;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        int w = bounds.width();
        int h = bounds.height();
        float cx = w / 2f;

        // 透明色（保留RGB，alpha=0）
        int transparentColor = 0x00000000 | (overlayColor & 0x00FFFFFF);

        // 左侧渐变：遮罩色→透明，从左边缘到中心
        paint.setShader(new LinearGradient(0, 0, cx, 0,
                overlayColor, transparentColor, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, cx, h, paint);

        // 右侧渐变：透明→遮罩色，从中心到右边缘
        paint.setShader(new LinearGradient(cx, 0, w, 0,
                transparentColor, overlayColor, Shader.TileMode.CLAMP));
        canvas.drawRect(cx, 0, w, h, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
