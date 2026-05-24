package com.jingxin.jingxinmusic.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * 正方形 ImageView
 * 宽度跟随父布局，高度自动等于宽度，保持 1:1 比例
 */
public class SquareImageView extends ImageView {

    public SquareImageView(Context context) {
        super(context);
    }

    public SquareImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SquareImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 让高度等于宽度，实现正方形
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
