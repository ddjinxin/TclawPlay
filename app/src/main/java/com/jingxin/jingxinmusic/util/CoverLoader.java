package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jingxin.jingxinmusic.model.Song;

import java.io.File;
import java.util.concurrent.ExecutorService;

/**
 * 封面三层加载器：内嵌封面 → 本地缓存 → 在线获取
 * 统一 PlayerActivity 和 MiniFloatService 的封面加载逻辑
 */
public class CoverLoader {

    private static final String TAG = "CoverLoader";

    /**
     * 封面加载回调
     */
    public interface CoverCallback {
        /** 封面加载成功（在主线程回调） */
        void onCoverLoaded(Bitmap bitmap);
        /** 所有来源均失败（在主线程回调） */
        void onCoverFailed();
    }

    /**
     * 异步加载封面：内嵌 → 缓存 → 在线
     *
     * @param context     Context
     * @param song        歌曲
     * @param reqWidth    目标宽度（降采样用，0=不降采样）
     * @param reqHeight   目标高度（降采样用，0=不降采样）
     * @param saveCache   内嵌封面是否保存到缓存+公共目录
     * @param executor    线程池
     * @param callback    回调（主线程）
     */
    public static void load(Context context, Song song, int reqWidth, int reqHeight,
                            boolean saveCache, ExecutorService executor, CoverCallback callback) {
        if (song == null || song.title == null) {
            callback.onCoverFailed();
            return;
        }

        Handler uiHandler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            // 1. 提取音频文件内嵌封面
            Bitmap embedded = CoverFetcher.extractEmbeddedCover(song.filePath);
            if (embedded != null) {
                if (saveCache) {
                    String coverName = Song.toFileName(song.title, song.artist) + ".jpg";
                    File cacheDir = context.getExternalFilesDir("covers");
                    if (cacheDir != null) {
                        File f = new File(cacheDir, coverName);
                        if (!f.exists()) {
                            Song.saveCoverToPublic(context, coverName, embedded);
                        }
                    }
                }
                if (reqWidth > 0 && reqHeight > 0) {
                    Bitmap sampled = BitmapUtil.decodeSampledFromBytes(
                            bitmapToBytes(embedded), reqWidth, reqHeight);
                    if (sampled != null && sampled != embedded) {
                        embedded.recycle();
                        embedded = sampled;
                    }
                }
                Bitmap finalBitmap = embedded;
                uiHandler.post(() -> callback.onCoverLoaded(finalBitmap));
                return;
            }

            // 2. 本地封面缓存
            File coverDir = context.getExternalFilesDir("covers");
            if (coverDir != null) {
                String coverName = Song.toFileName(song.title, song.artist) + ".jpg";
                File coverFile = new File(coverDir, coverName);
                if (coverFile.exists() && coverFile.length() > 0) {
                    Bitmap bmp;
                    if (reqWidth > 0 && reqHeight > 0) {
                        bmp = BitmapUtil.decodeSampledFromFile(coverFile.getAbsolutePath(), reqWidth, reqHeight);
                    } else {
                        bmp = android.graphics.BitmapFactory.decodeFile(coverFile.getAbsolutePath());
                    }
                    if (bmp != null) {
                        uiHandler.post(() -> callback.onCoverLoaded(bmp));
                        return;
                    }
                }
            }

            // 3. 在线获取
            String title = Song.cleanSongTitle(song.title, song.artist);
            String artist = "<unknown>".equals(song.artist) ? "" : song.artist;
            CoverFetcher.fetchCover(title, artist, new CoverFetcher.CoverCallback() {
                @Override
                public void onCoverFetched(Bitmap coverBitmap) {
                    if (saveCache) {
                        String coverName = Song.toFileName(song.title, song.artist) + ".jpg";
                        Song.saveCoverToPublic(context, coverName, coverBitmap);
                    }
                    uiHandler.post(() -> callback.onCoverLoaded(coverBitmap));
                }

                @Override
                public void onError(String errorMessage) {
                    Log.d(TAG, "在线封面获取失败: " + errorMessage);
                    uiHandler.post(() -> callback.onCoverFailed());
                }
            });
        });
    }

    /**
     * Bitmap 转字节数组（用于降采样二次解码）
     */
    private static byte[] bitmapToBytes(Bitmap bmp) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        return baos.toByteArray();
    }
}
