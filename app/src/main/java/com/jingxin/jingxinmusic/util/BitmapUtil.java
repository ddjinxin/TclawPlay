package com.jingxin.jingxinmusic.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.io.InputStream;

/**
 * Bitmap 工具类：降采样解码、圆形裁剪等
 */
public class BitmapUtil {

    /**
     * 计算降采样倍数
     */
    public static int calculateInSampleSize(BitmapFactory.Options opts, int reqWidth, int reqHeight) {
        int width = opts.outWidth;
        int height = opts.outHeight;
        int inSampleSize = 1;
        if (width > reqWidth || height > reqHeight) {
            int halfW = width / 2;
            int halfH = height / 2;
            while ((halfW / inSampleSize) >= reqWidth && (halfH / inSampleSize) >= reqHeight) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /**
     * 从文件路径降采样解码 Bitmap
     */
    public static Bitmap decodeSampledFromFile(String path, int reqWidth, int reqHeight) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, opts);
    }

    /**
     * 从字节数组降采样解码 Bitmap
     */
    public static Bitmap decodeSampledFromBytes(byte[] data, int reqWidth, int reqHeight) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    /**
     * 将 Bitmap 裁剪为圆形
     * @param source 原图
     * @param size 输出直径
     * @return 圆形 Bitmap，调用方负责回收 source
     */
    public static Bitmap createCircularBitmap(Bitmap source, int size) {
        if (source == null) return null;
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        // 先将 source 缩放到 size x size
        Bitmap scaled = Bitmap.createScaledBitmap(source, size, size, true);
        canvas.drawBitmap(scaled, 0, 0, paint);
        if (scaled != source) scaled.recycle();
        return output;
    }

    /**
     * 将 Bitmap 缩放并裁剪为圆形
     * @param source 原图
     * @param size 输出直径
     * @return 圆形 Bitmap，调用方负责回收 source
     */
    public static Bitmap createScaledCircularBitmap(Bitmap source, int size) {
        if (source == null) return null;
        // 先裁为正方形（取中心区域）
        int w = source.getWidth();
        int h = source.getHeight();
        int squareSize = Math.min(w, h);
        int x = (w - squareSize) / 2;
        int y = (h - squareSize) / 2;
        Bitmap squared = Bitmap.createBitmap(source, x, y, squareSize, squareSize);
        // 再缩放+圆形裁剪
        Bitmap result = createCircularBitmap(squared, size);
        if (squared != source) squared.recycle();
        return result;
    }
}
