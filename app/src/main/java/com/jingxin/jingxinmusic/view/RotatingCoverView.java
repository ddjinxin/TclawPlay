package com.jingxin.jingxinmusic.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;

import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 旋转封面视图
 * 模拟唱片旋转效果，播放时自动旋转，暂停时停止
 * 
 * 注意：使用Canvas.rotate()在onDraw中旋转画布内容，而不是旋转整个View
 * 这样View的位置固定不变，只有内部图片在旋转，ConstraintLayout可以正确计算约束
 */
public class RotatingCoverView extends AppCompatImageView {
    
    private static final String TAG = "RotatingCoverView";
    
    // 旋转速度（每秒旋转的角度）
    private static final float ROTATION_SPEED = 15f;  // 每秒旋转15度（模拟唱片效果）
    
    // 当前旋转角度（用于在onDraw中旋转画布）
    private float currentRotationAngle = 0f;
    
    // 旋转动画（使用ValueAnimator更新角度，而不是修改View的rotation属性）
    private ValueAnimator rotationAnimator;
    
    // 是否正在旋转
    private boolean isRotating = false;
    
    // 用户是否手动暂停了旋转（点击控制）
    private boolean userPaused = false;
    
    public RotatingCoverView(Context context) {
        super(context);
        init();
    }
    
    public RotatingCoverView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public RotatingCoverView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    /**
     * 初始化
     */
    private void init() {
        // 设置圆形裁剪（根据背景的oval shape裁剪图片）
        setClipToOutline(true);
        setScaleType(ScaleType.CENTER_CROP);
        
        // 确保View本身的rotation属性为0（不旋转View本身）
        setRotation(0f);
        
        // 初始化旋转动画（使用ValueAnimator更新角度）
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
        rotationAnimator.setDuration((long) (360 / ROTATION_SPEED * 1000));  // 完整一圈的时间
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.setInterpolator(new LinearInterpolator());  // 匀速旋转
        
        // 监听动画更新，更新当前旋转角度并重绘
        rotationAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                currentRotationAngle = (float) animation.getAnimatedValue();
                invalidate();  // 触发重绘，在onDraw中旋转画布
            }
        });
        
        // 点击封面停止/恢复旋转
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isRotating) {
                    // 用户点击停止旋转
                    userPaused = true;
                    stopRotation();
                } else {
                    // 用户点击恢复旋转
                    userPaused = false;
                    startRotation();
                }
            }
        });
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        // 在绘制前，先旋转画布（围绕View的中心点）
        // 这样绘制的内容（图片）会旋转，但View本身的位置不变
        int width = getWidth();
        int height = getHeight();
        
        if (width > 0 && height > 0) {
            // 保存画布状态
            canvas.save();
            
            // 旋转画布（围绕中心点）
            canvas.rotate(currentRotationAngle, width / 2f, height / 2f);
            
            // 绘制图片（在旋转后的画布上绘制）
            super.onDraw(canvas);
            
            // 恢复画布状态
            canvas.restore();
        } else {
            super.onDraw(canvas);
        }
    }
    
    /**
     * 开始旋转
     */
    public void startRotation() {
        // 如果用户手动暂停了旋转，则不旋转
        if (userPaused) {
            Log.d(TAG, "用户已手动暂停旋转，跳过 startRotation");
            return;
        }
        
        if (!isRotating) {
            Log.d(TAG, "开始旋转封面");
            isRotating = true;
            
            // 从当前角度开始旋转
            if (rotationAnimator.isPaused()) {
                rotationAnimator.resume();
            } else {
                rotationAnimator.start();
            }
        }
    }
    
    /**
     * 停止旋转
     */
    public void stopRotation() {
        if (isRotating) {
            Log.d(TAG, "停止旋转封面");
            isRotating = false;
            
            // 停止动画，保留当前角度
            rotationAnimator.pause();
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        
        // 停止动画
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
        }
    }
}