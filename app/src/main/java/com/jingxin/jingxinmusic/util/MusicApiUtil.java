package com.jingxin.jingxinmusic.util;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * 音乐 API 统一搜索工具
 * 提取自 LyricFetcher 和 CoverFetcher 中重复的酷狗/网易云搜索逻辑
 * 只负责搜索，返回原始结果数组，由调用方自行提取所需字段
 */
public class MusicApiUtil {

    private static final String TAG = "MusicApiUtil";

    // 酷狗搜索 API
    public static final String KUGOU_SEARCH_API = "https://mobileservice.kugou.com/api/v3/search/song";

    // 网易云搜索 API
    public static final String NETEASE_SEARCH_API = "https://music.163.com/api/search/get";

    /**
     * 酷狗歌曲搜索
     * @param keyword 搜索关键词（通常为歌名）
     * @param pageSize 返回结果数量
     * @return 搜索结果 info 数组（JSON对象数组），失败返回 null
     */
    public static JSONArray searchKugou(String keyword, int pageSize) {
        try {
            String apiUrl = KUGOU_SEARCH_API + "?format=json&keyword=" +
                    URLEncoder.encode(keyword, "UTF-8") + "&page=1&pagesize=" + pageSize;

            String response = HttpUtil.get(apiUrl);
            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            if (json.optInt("status") != 1) return null;

            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;

            JSONArray info = data.optJSONArray("info");
            if (info == null || info.length() == 0) return null;

            return info;
        } catch (Exception e) {
            Log.e(TAG, "酷狗搜索失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 网易云歌曲搜索
     * @param keyword 搜索关键词（通常为歌名）
     * @return 搜索结果 songs 数组（JSON对象数组），失败返回 null
     */
    public static JSONArray searchNetease(String keyword) {
        try {
            String apiUrl = NETEASE_SEARCH_API + "?s=" +
                    URLEncoder.encode(keyword, "UTF-8") + "&limit=5&type=1&offset=0";

            String response = HttpUtil.get(apiUrl);
            if (response == null) return null;

            JSONObject json = new JSONObject(response);
            JSONObject result = json.optJSONObject("result");
            if (result == null) return null;

            JSONArray songs = result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;

            return songs;
        } catch (Exception e) {
            Log.e(TAG, "网易云搜索失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 在酷狗搜索结果中精确匹配歌曲名
     * @param info 搜索结果数组
     * @param songTitle 目标歌曲名
     * @return 匹配的 JSONObject，无匹配返回第一个结果，数组为空返回 null
     */
    public static JSONObject findKugouMatch(JSONArray info, String songTitle) {
        try {
            for (int i = 0; i < info.length(); i++) {
                JSONObject song = info.getJSONObject(i);
                String name = song.optString("songname", "");
                if (name.equalsIgnoreCase(songTitle) ||
                    name.toLowerCase().contains(songTitle.toLowerCase())) {
                    return song;
                }
            }
            // 未精确匹配，返回第一个
            if (info.length() > 0) return info.getJSONObject(0);
        } catch (Exception e) {
            Log.e(TAG, "酷狗匹配失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 在网易云搜索结果中精确匹配歌曲名
     * @param songs 搜索结果数组
     * @param songTitle 目标歌曲名
     * @return 匹配的 JSONObject，无匹配返回第一个结果，数组为空返回 null
     */
    public static JSONObject findNeteaseMatch(JSONArray songs, String songTitle) {
        try {
            for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.getJSONObject(i);
                String name = song.optString("name", "");
                if (name.equalsIgnoreCase(songTitle) ||
                    name.toLowerCase().contains(songTitle.toLowerCase())) {
                    return song;
                }
            }
            // 未精确匹配，返回第一个
            if (songs.length() > 0) return songs.getJSONObject(0);
        } catch (Exception e) {
            Log.e(TAG, "网易云匹配失败: " + e.getMessage());
        }
        return null;
    }
}
