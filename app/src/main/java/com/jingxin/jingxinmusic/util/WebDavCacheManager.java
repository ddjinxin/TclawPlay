package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.util.Log;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * WebDAV 音乐缓存管理器
 * 使用 Media3 SimpleCache 实现边播放边缓存
 * - 播放时自动缓存到本地
 * - 重复播放优先从缓存读取
 * - LRU淘汰：超出限制时自动删除最久未访问的
 */
public class WebDavCacheManager {

    private static final String TAG = "WebDavCache";
    private static final String CACHE_DIR_NAME = "webdav_music_cache";

    private static WebDavCacheManager instance;

    private SimpleCache cache;
    private File cacheDir;

    private WebDavCacheManager(Context context) {
        cacheDir = new File(context.getCacheDir(), CACHE_DIR_NAME);
        if (!cacheDir.exists()) cacheDir.mkdirs();

        WebDavConfig config = new WebDavConfig(context);
        long maxBytes = getCacheMaxBytes(config);
        LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(maxBytes);

        try {
            cache = new SimpleCache(cacheDir, evictor);
            Log.d(TAG, "缓存初始化成功: max=" + formatSize(maxBytes)
                    + ", 当前占用=" + formatSize(cache.getCacheSpace()));
        } catch (Exception e) {
            Log.e(TAG, "缓存初始化失败，清空重试: " + e.getMessage());
            deleteRecursive(cacheDir);
            cacheDir.mkdirs();
            try {
                cache = new SimpleCache(cacheDir, evictor);
                Log.d(TAG, "缓存重试成功");
            } catch (Exception e2) {
                Log.e(TAG, "缓存重试仍失败: " + e2.getMessage());
                cache = null;
            }
        }
    }

    public static synchronized WebDavCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new WebDavCacheManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * 创建带缓存的HTTP DataSource.Factory
     * CacheDataSource包装OkHttpDataSource，实现：缓存命中→本地读取，缓存未命中→网络拉取+写入缓存
     */
    public DataSource.Factory createCachedHttpDataSourceFactory(
            OkHttpClient okHttpClient, WebDavConfig config) {
        if (cache == null) {
            // 缓存不可用，降级为普通HTTP请求
            Log.w(TAG, "缓存不可用，降级为无缓存模式");
            return new OkHttpDataSource.Factory(okHttpClient)
                    .setUserAgent("JingXinMusic")
                    .setDefaultRequestProperties(createAuthHeaders(config));
        }

        // 上游：OkHttp + WebDAV认证
        DataSource.Factory upstreamFactory = new OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("JingXinMusic")
                .setDefaultRequestProperties(createAuthHeaders(config));

        // 缓存层：CacheDataSource包装上游
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE);
    }

    /**
     * 获取缓存已用空间
     */
    public long getCacheSize() {
        if (cache != null) return cache.getCacheSpace();
        return 0;
    }

    /**
     * 检查指定URL是否已完整缓存
     */
    public boolean isFullyCached(String url) {
        if (cache == null || url == null) return false;
        return cache.isCached(url, 0, -1);
    }

    /**
     * 清除所有缓存
     */
    public void clearCache() {
        if (cache != null) {
            try {
                // 逐个移除缓存条目（SimpleCache没有removeAll方法）
                for (String key : new ArrayList<>(cache.getKeys())) {
                    for (CacheSpan span : new ArrayList<>(cache.getCachedSpans(key))) {
                        cache.removeSpan(span);
                    }
                }
                Log.d(TAG, "缓存已清除");
            } catch (Exception e) {
                Log.e(TAG, "清除缓存失败: " + e.getMessage());
            }
        }
    }

    /**
     * 获取缓存最大容量（字节）
     */
    private long getCacheMaxBytes(WebDavConfig config) {
        int mb = config.getCacheSizeMb();
        if (mb <= 0) return Long.MAX_VALUE; // 无限制
        return (long) mb * 1024L * 1024L;
    }

    /**
     * 创建WebDAV认证请求头
     */
    private Map<String, String> createAuthHeaders(WebDavConfig config) {
        Map<String, String> headers = new HashMap<>();
        String auth = config.getAuthHeader();
        if (auth != null && !auth.isEmpty()) {
            headers.put("Authorization", auth);
        }
        return headers;
    }

    /**
     * 格式化文件大小
     */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0B";
        if (bytes == Long.MAX_VALUE) return "无限制";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 递归删除文件/目录
     */
    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}
