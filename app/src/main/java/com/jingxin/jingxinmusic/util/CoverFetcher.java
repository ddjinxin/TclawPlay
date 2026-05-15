package com.jingxin.jingxinmusic.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面获取工具类
 * 主来源：酷狗音乐 API
 * 备用来源：网易云音乐 API
 */
public class CoverFetcher {
    private static final String TAG = "CoverFetcher";
    
    // 酷狗搜索 API
    private static final String KUGOU_API_BASE = "https://mobileservice.kugou.com/api/v3/search/song";
    
    // 网易云搜索 API
    private static final String NETEASE_SEARCH_API = "https://music.163.com/api/search/get";
    
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
        try {
            String encodedKeyword = URLEncoder.encode(keyword, "UTF-8");
            String apiUrl = KUGOU_API_BASE + "?format=json&keyword=" + encodedKeyword + "&page=1&pagesize=20";
            
            Log.d(TAG, "搜索 API: " + apiUrl);
            
            String response = HttpUtil.get(apiUrl);
            
            if (response == null) {
                Log.e(TAG, "搜索 API 请求失败");
                return null;
            }
            
            JSONObject json = new JSONObject(response);
            
            // 检查状态
            if (!json.has("status") || json.getInt("status") != 1) {
                Log.e(TAG, "搜索 API 返回错误状态");
                return null;
            }
            
            // 检查是否有结果
            if (!json.has("data")) {
                Log.e(TAG, "搜索结果无 data 字段");
                return null;
            }
            
            JSONObject data = json.getJSONObject("data");
            
            if (!data.has("info")) {
                Log.e(TAG, "搜索结果无 info 字段");
                return null;
            }
            
            JSONArray info = data.getJSONArray("info");
            
            if (info.length() == 0) {
                Log.e(TAG, "搜索结果为空");
                return null;
            }
            
            Log.d(TAG, "搜索结果数量: " + info.length());
            
            // 遍历搜索结果，找到匹配的歌曲（精确匹配歌曲名）
            for (int i = 0; i < info.length(); i++) {
                JSONObject song = info.getJSONObject(i);
                String songName = song.getString("songname");
                String singerName = song.getString("singername");
                
                Log.d(TAG, "检查歌曲: " + songName + " - " + singerName);
                
                // 如果歌曲名包含关键词，返回封面 URL
                if (songName.toLowerCase().contains(songTitle.toLowerCase())) {
                    // 提取封面 URL
                    if (song.has("trans_param")) {
                        JSONObject transParam = song.getJSONObject("trans_param");
                        if (transParam.has("union_cover")) {
                            String coverUrl = transParam.getString("union_cover");
                            // 替换 {size} 为 400（大尺寸）
                            coverUrl = coverUrl.replace("{size}", "400");
                            Log.d(TAG, "找到匹配歌曲封面: " + songName + " → " + coverUrl);
                            return coverUrl;
                        }
                    }
                }
            }
            
            // 如果没有找到匹配的歌曲，返回第一个歌曲的封面
            Log.d(TAG, "未找到精确匹配，使用第一个搜索结果");
            JSONObject firstSong = info.getJSONObject(0);
            if (firstSong.has("trans_param")) {
                JSONObject transParam = firstSong.getJSONObject("trans_param");
                if (transParam.has("union_cover")) {
                    String coverUrl = transParam.getString("union_cover");
                    // 替换 {size} 为 400（大尺寸）
                    coverUrl = coverUrl.replace("{size}", "400");
                    Log.d(TAG, "使用第一个结果封面: " + coverUrl);
                    return coverUrl;
                }
            }
            
            Log.e(TAG, "未找到封面 URL");
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "搜索歌曲失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 网易云搜索：获取封面 URL（HTTPS，无需白名单）
     */
    private static String searchNeteaseAndGetCoverUrl(String songTitle, String artistName) {
        try {
            String keyword = songTitle;
            String apiUrl = NETEASE_SEARCH_API + "?s=" +
                    URLEncoder.encode(keyword, "UTF-8") + "&limit=5&type=1&offset=0";
            
            String response = HttpUtil.get(apiUrl);
            if (response == null) return null;
            
            JSONObject json = new JSONObject(response);
            JSONObject result = json.optJSONObject("result");
            if (result == null) return null;
            
            JSONArray songs = result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;
            
            Log.d(TAG, "网易云搜索结果数量: " + songs.length());
            
            // 精确匹配歌曲名
            for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.getJSONObject(i);
                String name = song.optString("name", "");
                
                if (name.equalsIgnoreCase(songTitle) ||
                    name.toLowerCase().contains(songTitle.toLowerCase())) {
                    JSONObject album = song.optJSONObject("album");
                    if (album != null) {
                        String coverUrl = album.optString("picUrl", "");
                        if (!coverUrl.isEmpty()) {
                            Log.d(TAG, "网易云匹配封面: " + name + " → " + coverUrl);
                            return coverUrl;
                        }
                    }
                }
            }
            
            // 未精确匹配，用第一个结果的封面
            JSONObject firstSong = songs.getJSONObject(0);
            JSONObject album = firstSong.optJSONObject("album");
            if (album != null) {
                String coverUrl = album.optString("picUrl", "");
                if (!coverUrl.isEmpty()) {
                    Log.d(TAG, "网易云使用第一个结果封面: " + coverUrl);
                    return coverUrl;
                }
            }
            
            return null;
        } catch (Exception e) {
            Log.e(TAG, "网易云搜索封面失败: " + e.getMessage());
            return null;
        }
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