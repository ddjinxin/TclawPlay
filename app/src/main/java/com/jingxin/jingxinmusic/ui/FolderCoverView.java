package com.jingxin.jingxinmusic.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
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

/**
 * 文件夹封面View：在文件夹轮廓内裁剪显示封面图
 * 使用 PorterDuff mask 实现，无需硬件加速关闭
 */
public class FolderCoverView extends View {

    private Bitmap coverBitmap;
    private Bitmap defaultCoverBitmap;
    private Bitmap maskBitmap; // 文件夹轮廓遮罩
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path folderPath = new Path();
    private final Matrix shaderMatrix = new Matrix();
    private boolean nightMode = true;
    private int lastWidth = 0;
    private int lastHeight = 0;

    private static final float TAB_HEIGHT = 0.12f;
    private static final float TAB_WIDTH = 0.35f;
    private static final float TAB_NOTCH = 0.05f;
    private static final float CORNER_RADIUS = 0.06f;
    private static final float BORDER_WIDTH = 2f;

    public FolderCoverView(Context context) {
        super(context);
        init();
    }

    public FolderCoverView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FolderCoverView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        maskPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        borderPaint.setStyle(Paint.Style.STROKE);
        float density = getResources().getDisplayMetrics().density;
        borderPaint.setStrokeWidth(BORDER_WIDTH * density);
        borderPaint.setColor(Color.parseColor("#999999"));
        // 必须关闭硬件加速才能使用 PorterDuff Xfermode
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private Bitmap ensureDefaultCover() {
        if (defaultCoverBitmap != null) return defaultCoverBitmap;
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

    /**
     * 生成文件夹轮廓遮罩Bitmap（白色文件夹形状，其余透明）
     */
    private Bitmap ensureMaskBitmap(int w, int h) {
        if (maskBitmap != null && maskBitmap.getWidth() == w && maskBitmap.getHeight() == h) {
            return maskBitmap;
        }
        maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(maskBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        buildFolderPath(w, h);
        canvas.drawPath(folderPath, paint);
        return maskBitmap;
    }

    public void setNightMode(boolean night) {
        this.nightMode = night;
        borderPaint.setColor(night ? Color.parseColor("#555555") : Color.parseColor("#BBBBBB"));
        invalidate();
    }

    public void setCoverBitmap(Bitmap bitmap) {
        this.coverBitmap = bitmap;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // 确定要绘制的封面
        Bitmap bmp = coverBitmap;
        if (bmp == null) {
            bmp = ensureDefaultCover();
        }

        if (bmp != null) {
            // 先用 layer 保存，然后用 PorterDuff mask
            int saveCount = canvas.saveLayer(0, 0, w, h, null, Canvas.ALL_SAVE_FLAG);

            // 1. 绘制封面（缩放填满）
            BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaderMatrix.reset();
            float scaleX = (float) w / bmp.getWidth();
            float scaleY = (float) h / bmp.getHeight();
            float scale = Math.max(scaleX, scaleY);
            shaderMatrix.setScale(scale, scale);
            shaderMatrix.postTranslate(
                    (w - bmp.getWidth() * scale) / 2f,
                    (h - bmp.getHeight() * scale) / 2f);
            shader.setLocalMatrix(shaderMatrix);
            coverPaint.setShader(shader);
            canvas.drawRect(0, 0, w, h, coverPaint);
            coverPaint.setShader(null);

            // 2. 用文件夹轮廓做 mask（DST_IN：只保留封面和mask重叠的部分）
            Bitmap mask = ensureMaskBitmap(w, h);
            canvas.drawBitmap(mask, 0, 0, maskPaint);

            canvas.restoreToCount(saveCount);
        }

        // 画文件夹边框线
        buildFolderPath(w, h);
        canvas.drawPath(folderPath, borderPaint);
    }

    private void buildFolderPath(int w, int h) {
        folderPath.reset();

        float tabH = h * TAB_HEIGHT;
        float tabW = w * TAB_WIDTH;
        float notch = w * TAB_NOTCH;
        float r = Math.min(w, h) * CORNER_RADIUS;

        folderPath.moveTo(r, 0);
        folderPath.lineTo(tabW - notch, 0);
        folderPath.lineTo(tabW, tabH);
        folderPath.lineTo(w - r, tabH);
        folderPath.quadTo(w, tabH, w, tabH + r);
        folderPath.lineTo(w, h - r);
        folderPath.quadTo(w, h, w - r, h);
        folderPath.lineTo(r, h);
        folderPath.quadTo(0, h, 0, h - r);
        folderPath.lineTo(0, r);
        folderPath.quadTo(0, 0, r, 0);

        folderPath.close();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
