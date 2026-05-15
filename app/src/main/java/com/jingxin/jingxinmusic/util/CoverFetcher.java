package com.jingxin.jingxinmusic.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面获取工具类
 * 主来源：酷狗音乐 API
 * 备用来源：网易云音乐 API
 */
public class CoverFetcher {
    private static final String TAG = "CoverFetcher";
    
    private static ExecutorService executor = Executors.newSingleThreadExecutor();
    
    /**
     * 封面获取回调接口
     */
    public interface CoverCallback {
        void onCoverFetched(Bitmap coverBitmap);
        void onError(String errorMessage);
    }
    
    /**
     * 搜索并获取歌曲封面
     * 主来源：酷狗（重试3次）
     * 备用来源：网易云
     */
    public static void fetchCover(String songTitle, String artistName, CoverCallback callback) {
        executor.execute(() -> {
            // 1. 酷狗搜索（重试3次，只用歌名搜索以扩大匹配范围）
            Bitmap bitmap = fetchFromKugou(songTitle, songTitle, 3);
            if (bitmap != null) {
                callback.onCoverFetched(bitmap);
                return;
            }
            Log.d(TAG, "酷狗封面获取失败，尝试网易云: " + songTitle + " - " + artistName);
            
            // 2. 网易云备用
            bitmap = fetchFromNetease(songTitle, artistName);
            if (bitmap != null) {
                callback.onCoverFetched(bitmap);
                return;
            }
            
            callback.onError("所有封面源均失败");
        });
    }
    
    /**
     * 从酷狗获取封面（支持重试）
     */
    private static Bitmap fetchFromKugou(String searchKeyword, String songTitle, int maxRetries) {
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                String coverUrl = searchKugouAndGetCoverUrl(searchKeyword, songTitle);
                if (coverUrl == null || coverUrl.isEmpty()) {
                    Log.e(TAG, "酷狗未找到封面 URL（第" + (retry + 1) + "次）");
                    if (retry < maxRetries - 1) Thread.sleep(3000);
                    continue;
                }
                
                Log.d(TAG, "酷狗封面 URL: " + coverUrl);
                Bitmap coverBitmap = HttpUtil.getBitmap(coverUrl);
                if (coverBitmap == null) {
                    Log.e(TAG, "酷狗下载封面失败（第" + (retry + 1) + "次）");
                    if (retry < maxRetries - 1) Thread.sleep(3000);
                    continue;
                }
                
                return coverBitmap;
            } catch (Exception e) {
                Log.e(TAG, "酷狗封面获取异常（第" + (retry + 1) + "次）: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }
    
    /**
     * 从网易云获取封面（支持重试）
     */
    private static Bitmap fetchFromNetease(String songTitle, String artistName) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                String coverUrl = searchNeteaseAndGetCoverUrl(songTitle, artistName);
                if (coverUrl == null || coverUrl.isEmpty()) {
                    Log.e(TAG, "网易云未找到封面 URL（第" + (retry + 1) + "次）");
                    if (retry < 2) Thread.sleep(3000);
                    continue;
                }

                Log.d(TAG, "网易云封面 URL: " + coverUrl);
                Bitmap coverBitmap = HttpUtil.getBitmap(coverUrl);
                if (coverBitmap == null) {
                    Log.e(TAG, "网易云下载封面失败（第" + (retry + 1) + "次）");
                    if (retry < 2) Thread.sleep(3000);
                    continue;
                }

                return coverBitmap;
            } catch (Exception e) {
                Log.e(TAG, "网易云封面获取异常（第" + (retry + 1) + "次）: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }
    
    /**
     * 酷狗搜索：获取封面 URL
     */
    private static String searchKugouAndGetCoverUrl(String keyword, String songTitle) {
        JSONArray info = MusicApiUtil.searchKugou(keyword, 20);
        if (info == null) return null;

        JSONObject match = MusicApiUtil.findKugouMatch(info, songTitle);
        if (match != null) {
            String url = extractKugouCoverUrl(match);
            if (url != null) return url;
        }

        // 未精确匹配，尝试遍历所有结果
        for (int i = 0; i < info.length(); i++) {
            try {
                String url = extractKugouCoverUrl(info.getJSONObject(i));
                if (url != null) return url;
            } catch (Exception ignored) {}
        }

        Log.e(TAG, "未找到封面 URL");
        return null;
    }

    /**
     * 从酷狗搜索结果中提取封面 URL
     */
    private static String extractKugouCoverUrl(JSONObject song) {
        try {
            if (song.has("trans_param")) {
                JSONObject transParam = song.getJSONObject("trans_param");
                if (transParam.has("union_cover")) {
                    String coverUrl = transParam.getString("union_cover");
                    coverUrl = coverUrl.replace("{size}", "400");
                    return coverUrl;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "提取酷狗封面URL失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 网易云搜索：获取封面 URL（HTTPS，无需白名单）
     */
    private static String searchNeteaseAndGetCoverUrl(String songTitle, String artistName) {
        JSONArray songs = MusicApiUtil.searchNetease(songTitle);
        if (songs == null) return null;

        JSONObject match = MusicApiUtil.findNeteaseMatch(songs, songTitle);
        if (match != null) {
            String url = extractNeteaseCoverUrl(match);
            if (url != null) return url;
        }

        // 未精确匹配，尝试遍历所有结果
        for (int i = 0; i < songs.length(); i++) {
            try {
                String url = extractNeteaseCoverUrl(songs.getJSONObject(i));
                if (url != null) return url;
            } catch (Exception ignored) {}
        }

        return null;
    }

    /**
     * 从网易云搜索结果中提取封面 URL
     */
    private static String extractNeteaseCoverUrl(JSONObject song) {
        try {
            JSONObject album = song.optJSONObject("album");
            if (album != null) {
                String coverUrl = album.optString("picUrl", "");
                if (!coverUrl.isEmpty()) return coverUrl;
            }
        } catch (Exception e) {
            Log.e(TAG, "提取网易云封面URL失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 从音频文件中提取内嵌封面
     * @param filePath 音频文件路径（绝对路径）
     * @return 封面 Bitmap，无内嵌封面则返回 null
     */
    public static Bitmap extractEmbeddedCover(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filePath);
            byte[] data = retriever.getEmbeddedPicture();
            if (data != null && data.length > 0) {
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp != null) {
                    Log.d(TAG, "提取内嵌封面成功: " + bmp.getWidth() + "x" + bmp.getHeight());
                }
                return bmp;
            }
        } catch (Exception e) {
            Log.d(TAG, "提取内嵌封面失败: " + e.getMessage());
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        return null;
    }

}