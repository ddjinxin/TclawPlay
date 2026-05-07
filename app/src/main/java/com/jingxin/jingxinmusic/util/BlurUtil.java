package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.graphics.Bitmap;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.os.Build;
import android.util.Log;

/**
 * 图片模糊处理工具
 * 使用 RenderScript 实现高性能模糊效果
 */
public class BlurUtil {
    
    private static final String TAG = "BlurUtil";
    private static final float DEFAULT_BLUR_RADIUS = 25f; // 模糊半径（最大 25）
    private static final int BLUR_SCALE_SIZE = 100; // 缩小到 100px 再模糊，大幅提升性能
    
    /**
     * 使用 RenderScript 模糊图片
     * @param context 上下文
     * @param bitmap 原始图片
     * @param radius 模糊半径（0-25）
     * @return 模糊后的图片
     */
    public static Bitmap blur(Context context, Bitmap bitmap, float radius) {
        if (bitmap == null) {
            Log.e(TAG, "原始图片为 null");
            return null;
        }
        
        // 限制模糊半径范围
        radius = Math.max(0, Math.min(25, radius));
        
        try {
            // 先缩小图片到 BLUR_SCALE_SIZE，大幅减少模糊计算量
            float scale = Math.min((float) BLUR_SCALE_SIZE / bitmap.getWidth(),
                                   (float) BLUR_SCALE_SIZE / bitmap.getHeight());
            int scaledWidth = Math.max(1, (int) (bitmap.getWidth() * scale));
            int scaledHeight = Math.max(1, (int) (bitmap.getHeight() * scale));
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
            
            // 对缩小后的图片进行模糊
            Bitmap blurredSmall;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                blurredSmall = renderScriptBlur(context, scaledBitmap, radius);
            } else {
                blurredSmall = fastBlur(scaledBitmap, radius);
            }
            
            if (scaledBitmap != blurredSmall) {
                scaledBitmap.recycle();
            }
            
            if (blurredSmall == null) {
                return bitmap;
            }
            
            // 放大回原始尺寸，放大时自带平滑效果
            Bitmap output = Bitmap.createScaledBitmap(blurredSmall, bitmap.getWidth(), bitmap.getHeight(), true);
            blurredSmall.recycle();
            
            return output;
        } catch (Exception e) {
            Log.e(TAG, "模糊处理失败: " + e.getMessage());
            e.printStackTrace();
            return bitmap;
        }
    }
    
    /**
     * RenderScript 模糊（对已缩小的图片进行）
     */
    private static Bitmap renderScriptBlur(Context context, Bitmap bitmap, float radius) {
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        RenderScript rs = RenderScript.create(context);
        ScriptIntrinsicBlur blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        
        Allocation tmpIn = Allocation.createFromBitmap(rs, bitmap);
        Allocation tmpOut = Allocation.createFromBitmap(rs, output);
        
        blurScript.setRadius(radius);
        blurScript.setInput(tmpIn);
        blurScript.forEach(tmpOut);
        tmpOut.copyTo(output);
        
        rs.destroy();
        return output;
    }
    

    
    /**
     * Java 快速模糊算法（备用方案）
     * @param bitmap 原始图片
     * @param radius 模糊半径
     * @return 模糊后的图片
     */
    private static Bitmap fastBlur(Bitmap bitmap, float radius) {
        if (bitmap == null) return null;
        
        // 缩小图片以提高性能
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int scaleFactor = (int) (radius / 5) + 1;
        int scaledWidth = width / scaleFactor;
        int scaledHeight = height / scaleFactor;
        
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, false);
        
        // Box blur 算法
        int[] pixels = new int[scaledWidth * scaledHeight];
        scaledBitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight);
        
        // 简单的 Box blur（多次应用）
        for (int i = 0; i < 3; i++) {
            boxBlur(pixels, scaledWidth, scaledHeight, (int) radius / scaleFactor);
        }
        
        scaledBitmap.setPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight);
        
        // 放大回原始尺寸
        Bitmap output = Bitmap.createScaledBitmap(scaledBitmap, width, height, true);
        scaledBitmap.recycle();
        
        return output;
    }
    
    /**
     * Box blur 算法
     * @param pixels 像素数组
     * @param width 宽度
     * @param height 高度
     * @param radius 模糊半径
     */
    private static void boxBlur(int[] pixels, int width, int height, int radius) {
        int[] temp = new int[pixels.length];
        
        // 横向模糊
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = 0, g = 0, b = 0, a = 0;
                int count = 0;
                
                for (int i = Math.max(0, x - radius); i <= Math.min(width - 1, x + radius); i++) {
                    int pixel = pixels[y * width + i];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    a += (pixel >> 24) & 0xFF;
                    count++;
                }
                
                temp[y * width + x] = ((a / count) << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }
        
        // 纵向模糊
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = 0, g = 0, b = 0, a = 0;
                int count = 0;
                
                for (int j = Math.max(0, y - radius); j <= Math.min(height - 1, y + radius); j++) {
                    int pixel = temp[j * width + x];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    a += (pixel >> 24) & 0xFF;
                    count++;
                }
                
                pixels[y * width + x] = ((a / count) << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }
    }
}