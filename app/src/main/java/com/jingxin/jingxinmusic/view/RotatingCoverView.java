package com.jingxin.jingxinmusic.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.util.Log;
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
    
    private void ensureVinylBitmap() {
        if (vinylDiscBitmap != null) return;
        // 从drawable加载预制黑胶盘位图
            BitmapDrawable drawable = (BitmapDrawable) getResources().getDrawable(R.drawable.bg_playing_disc);
        if (drawable != null) {
            vinylDiscBitmap = drawable.getBitmap();
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
            Log.d(TAG, "用户已手动暂停旋转，跳过 startRotation");
            return;
        }
        if (!isRotating) {
            Log.d(TAG, "开始旋转封面");
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
            Log.d(TAG, "停止旋转封面");
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
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
        }
        // 预制位图由系统管理，不需要手动recycle
    }
}
