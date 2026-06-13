package com.jingxin.jingxinmusic.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * HTTP 工具类
 * 统一 User-Agent、超时设置和错误处理
 * 提取自 LyricFetcher.httpGet / CoverFetcher.httpRequest / CoverFetcher.downloadImage
 */
public class HttpUtil {

    private static final String TAG = "HttpUtil";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String BILI_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT = 10000; // 10秒
    private static final int BILI_TIMEOUT = 15000; // B站API 15秒

    /**
     * HTTP GET 请求，返回字符串响应
     * @param apiUrl 请求 URL
     * @return 响应内容，失败返回 null
     */
    public static String get(String apiUrl) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", USER_AGENT);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.e(TAG, "HTTP 失败: " + code + " URL: " + apiUrl);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "HTTP GET 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * HTTP GET 请求，带自定义Header（B站API专用）
     * @param apiUrl 请求 URL
     * @param headers 自定义请求头
     * @return 响应内容，失败返回 null
     */
    public static String getWithHeaders(String apiUrl, Map<String, String> headers) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(BILI_TIMEOUT);
            conn.setReadTimeout(BILI_TIMEOUT);
            conn.setRequestProperty("User-Agent", BILI_USER_AGENT);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.e(TAG, "HTTP 失败: " + code + " URL: " + apiUrl);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "HTTP GET (headers) 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * HTTP POST 请求，带自定义Header和请求体（B站API专用）
     * @param apiUrl 请求 URL
     * @param body 请求体（application/x-www-form-urlencoded 格式）
     * @param headers 自定义请求头
     * @return 响应内容，失败返回 null
     */
    public static String postWithHeaders(String apiUrl, String body, Map<String, String> headers) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(BILI_TIMEOUT);
            conn.setReadTimeout(BILI_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", BILI_USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            // 写入请求体
            if (body != null && !body.isEmpty()) {
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.e(TAG, "HTTP POST 失败: " + code + " URL: " + apiUrl);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "HTTP POST 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * HTTP GET 请求，下载图片并返回 Bitmap
     * @param imageUrl 图片 URL
     * @return Bitmap，失败返回 null
     */
    public static Bitmap getBitmap(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", USER_AGENT);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.e(TAG, "下载图片响应码: " + code);
                return null;
            }

            InputStream is = conn.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            conn.disconnect();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "下载图片失败: " + e.getMessage(), e);
            return null;
        }
    }
}
