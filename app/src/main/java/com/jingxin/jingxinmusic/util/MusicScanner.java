package com.jingxin.jingxinmusic.util;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;

import com.jingxin.jingxinmusic.model.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地音乐扫描器
 * 通过 MediaStore 查询 + 文件系统遍历扫描手机上的所有音乐文件
 * MediaStore 覆盖内置存储，文件遍历补充 U 盘/SD 卡等可移动存储
 *
 * 缓存机制：扫描结果持久化为 JSON 文件，启动时先读缓存秒开，后台异步刷新
 *
 * 手动扫描：强制清缓存 + 强制 triggerMediaScan + 三路扫描（MediaStore + U盘遍历 + 内置存储遍历）
 */
public class MusicScanner {

    private static final String TAG = "MusicScanner";

    // 记录最近一次 triggerMediaScan 的时间，供 ContentObserver 判断是否忽略回环
    public static volatile long lastMediaScanTime = 0;

    // 缓存文件名
    private static final String CACHE_FILE = "music_cache.json";
    // 缓存有效期（10分钟），超时后才真正扫描
    private static final long CACHE_VALID_MS = 10 * 60 * 1000;

    // 手动扫描广播
    public static final String ACTION_SCAN_COMPLETE = "com.jingxin.jingxinmusic.SCAN_COMPLETE";

    // traverseDirectory 安全限制
    private static final int MAX_TRAVERSE_DEPTH = 8;
    private static final int MAX_TRAVERSE_FILES = 5000;
    private static final int MAX_INTERNAL_TRAVERSE_DEPTH = 5;
    private static final int MAX_INTERNAL_TRAVERSE_FILES = 5000;

    // 手动扫描元数据补充上限
    private static final int MAX_METADATA_EXTRACT = 200;

    // 递归遍历用 .nomedia 标记
    private static final Set<String> SKIP_DIR_NAMES = new HashSet<>();
    static {
        SKIP_DIR_NAMES.add("Android");
        SKIP_DIR_NAMES.add(".thumbnails");
    }

    /**
     * 扫描手机上的所有音乐文件（带缓存）
     * 1. MediaStore 查询（覆盖内置存储已索引的歌曲）
     * 2. 文件遍历扫描（补充 U 盘/SD 卡等 MediaStore 未索引的歌曲）
     * 3. 合并去重
     * 4. 保存缓存
     *
     * 注意：triggerMediaScan 不在此方法中调用，由调用方按需触发
     */
    public static List<Song> scanMusic(Context context) {
        // 第一步：MediaStore 查询
        List<Song> mediaStoreSongs = scanByMediaStore(context);
        Log.d(TAG, "MediaStore 扫描: " + mediaStoreSongs.size() + " 首");

        // 第二步：文件遍历扫描（探测 U 盘/SD 卡）
        List<Song> fileTraversalSongs = scanByFileTraversal(context);
        Log.d(TAG, "文件遍历扫描: " + fileTraversalSongs.size() + " 首");

        // 第三步：合并去重（以 filePath 为键，MediaStore 结果优先）
        Set<String> existingPaths = new HashSet<>();
        for (Song song : mediaStoreSongs) {
            if (song.filePath != null) {
                existingPaths.add(song.filePath);
            }
        }

        List<Song> merged = new ArrayList<>(mediaStoreSongs);
        for (Song song : fileTraversalSongs) {
            if (song.filePath != null && !existingPaths.contains(song.filePath)) {
                merged.add(song);
                existingPaths.add(song.filePath);
            }
        }

        Log.d(TAG, "合并去重后: " + merged.size() + " 首");

        // 保存缓存
        saveCache(context, merged);
        return merged;
    }

    // ========== 手动扫描 ==========

    /**
     * 手动扫描（强制全量三路扫描）
     *
     * 流程：
     * 1. 清除缓存
     * 2. 强制 triggerMediaScan
     * 3. 轮询等待 MediaStore 索引稳定（最多5次×2秒=10秒）
     * 4. 三路扫描：MediaStore + U盘文件遍历 + 内置存储遍历
     * 5. 二次检查结果稳定性
     * 6. 发送扫描完成广播
     *
     * @param context 上下文
     * @param callback 回调（在调用线程执行，null 也可）
     */
    public static void manualScan(final Context context, final ManualScanCallback callback) {
        new Thread(() -> {
            try {
                Log.d(TAG, "===== 手动扫描开始 =====");

                // Step 1: 清除缓存
                clearCache(context);

                // Step 2: 强制 triggerMediaScan
                triggerMediaScan(context);

                // Step 3: 轮询等待 MediaStore 索引稳定
                int prevCount = getMediaStoreCount(context);
                boolean stable = false;
                for (int i = 0; i < 5; i++) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    int currCount = getMediaStoreCount(context);
                    Log.d(TAG, "轮询[" + (i + 1) + "]: prev=" + prevCount + " curr=" + currCount);
                    if (currCount == prevCount) {
                        stable = true;
                        break;
                    }
                    prevCount = currCount;
                }
                Log.d(TAG, "索引稳定: " + stable);

                // Step 4: 三路全量扫描
                List<Song> result = manualScanInternal(context);

                // Step 5: 二次检查结果稳定性
                int firstCount = result.size();
                if (!stable || firstCount != prevCount) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    List<Song> result2 = manualScanInternal(context);
                    if (result2.size() != firstCount) {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        result = manualScanInternal(context);
                    } else {
                        result = result2;
                    }
                }

                Log.d(TAG, "===== 手动扫描完成，共 " + result.size() + " 首 =====");

                // 发送广播
                Intent scanIntent = new Intent(ACTION_SCAN_COMPLETE);
                context.sendBroadcast(scanIntent);

                // 回调
                if (callback != null) {
                    callback.onScanComplete(result);
                }
            } catch (Exception e) {
                Log.e(TAG, "手动扫描异常: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onScanComplete(new ArrayList<>());
                }
            }
        }, "ManualScanThread").start();
    }

    /**
     * 手动扫描内部三路扫描逻辑
     */
    private static List<Song> manualScanInternal(Context context) {
        // 路1: MediaStore 多卷查询
        List<Song> mediaStoreSongs = scanByMediaStore(context);
        Log.d(TAG, "手动扫描 路1(MediaStore): " + mediaStoreSongs.size() + " 首");

        // 路2: U盘/SD卡文件遍历
        List<Song> fileTraversalSongs = scanByFileTraversal(context);
        Log.d(TAG, "手动扫描 路2(U盘遍历): " + fileTraversalSongs.size() + " 首");

        // 路3: 内置存储文件遍历
        List<Song> internalSongs = scanInternalByFileTraversal(context);
        Log.d(TAG, "手动扫描 路3(内置存储遍历): " + internalSongs.size() + " 首");

        // 合并去重（以 filePath 为键，MediaStore 优先，路2次之，路3最后）
        Set<String> existingPaths = new HashSet<>();
        List<Song> merged = new ArrayList<>();

        for (Song song : mediaStoreSongs) {
            if (song.filePath != null && !existingPaths.contains(song.filePath)) {
                merged.add(song);
                existingPaths.add(song.filePath);
            } else if (song.filePath == null) {
                merged.add(song);
            }
        }
        for (Song song : fileTraversalSongs) {
            if (song.filePath != null && !existingPaths.contains(song.filePath)) {
                merged.add(song);
                existingPaths.add(song.filePath);
            }
        }
        for (Song song : internalSongs) {
            if (song.filePath != null && !existingPaths.contains(song.filePath)) {
                merged.add(song);
                existingPaths.add(song.filePath);
            }
        }

        // 补充U盘歌曲元数据
        enrichMetadata(merged);

        // 保存缓存
        saveCache(context, merged);
        return merged;
    }

    /**
     * 手动扫描回调接口
     */
    public interface ManualScanCallback {
        void onScanComplete(List<Song> songs);
    }

    // ========== 缓存机制 ==========

    /**
     * 加载缓存的歌曲列表
     * @return 缓存列表，无缓存或已过期返回 null
     */
    public static List<Song> loadCache(Context context) {
        File cacheFile = new File(context.getCacheDir(), CACHE_FILE);
        if (!cacheFile.exists()) {
            Log.d(TAG, "无缓存文件");
            return null;
        }
        // 检查缓存是否过期
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > CACHE_VALID_MS) {
            Log.d(TAG, "缓存已过期（" + (age / 1000) + "秒）");
            return null;
        }
        try {
            FileInputStream fis = new FileInputStream(cacheFile);
            byte[] data = new byte[(int) cacheFile.length()];
            fis.read(data);
            fis.close();
            String json = new String(data, "UTF-8");
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.getJSONArray("songs");
            List<Song> songs = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                songs.add(Song.fromJson(arr.getJSONObject(i)));
            }

            // 校验 storageVolumesHash（可移动存储变化则缓存失效）
            int cachedHash = root.optInt("storageVolumesHash", 0);
            int currentHash = computeStorageVolumesHash(context);
            if (cachedHash != currentHash) {
                Log.d(TAG, "存储卷变化，缓存失效 (cached=" + cachedHash + " current=" + currentHash + ")");
                return null;
            }

            Log.d(TAG, "从缓存加载 " + songs.size() + " 首歌曲");
            return songs;
        } catch (Exception e) {
            Log.e(TAG, "读取缓存失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 保存歌曲列表到缓存文件
     */
    private static void saveCache(Context context, List<Song> songs) {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Song song : songs) {
                arr.put(song.toJson());
            }
            root.put("songs", arr);
            root.put("timestamp", System.currentTimeMillis());
            root.put("storageVolumesHash", computeStorageVolumesHash(context));

            File cacheFile = new File(context.getCacheDir(), CACHE_FILE);
            FileOutputStream fos = new FileOutputStream(cacheFile);
            fos.write(root.toString().getBytes("UTF-8"));
            fos.close();
            Log.d(TAG, "缓存已保存: " + songs.size() + " 首歌曲");
        } catch (Exception e) {
            Log.e(TAG, "保存缓存失败: " + e.getMessage());
        }
    }

    /**
     * 判断是否有有效缓存（用于决定是否跳过 triggerMediaScan）
     */
    public static boolean hasValidCache(Context context) {
        File cacheFile = new File(context.getCacheDir(), CACHE_FILE);
        if (!cacheFile.exists()) return false;
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > CACHE_VALID_MS) return false;
        // 校验 storageVolumesHash
        try {
            FileInputStream fis = new FileInputStream(cacheFile);
            byte[] data = new byte[(int) cacheFile.length()];
            fis.read(data);
            fis.close();
            JSONObject root = new JSONObject(new String(data, "UTF-8"));
            int cachedHash = root.optInt("storageVolumesHash", 0);
            int currentHash = computeStorageVolumesHash(context);
            return cachedHash == currentHash;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清除缓存
     */
    public static void clearCache(Context context) {
        File cacheFile = new File(context.getCacheDir(), CACHE_FILE);
        if (cacheFile.exists()) {
            cacheFile.delete();
            Log.d(TAG, "缓存已清除");
        }
    }

    /**
     * 计算可移动存储路径的 hash（用于检测U盘变化）
     */
    private static int computeStorageVolumesHash(Context context) {
        List<File> storages = detectRemovableStorage(context);
        StringBuilder sb = new StringBuilder();
        for (File f : storages) {
            sb.append(f.getAbsolutePath());
        }
        return sb.toString().hashCode();
    }

    // ========== MediaStore 查询 ==========

    /**
     * MediaStore 查询扫描
     * API 29+: 多卷查询（含U盘卷）
     * API 21-28: 单卷查询（系统自动聚合）
     */
    private static List<Song> scanByMediaStore(Context context) {
        List<Song> songs = new ArrayList<>();

        // 确定要查询的卷名列表
        List<String> volumeNames = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                Set<String> volSet = MediaStore.getExternalVolumeNames(context);
                if (volSet != null && !volSet.isEmpty()) {
                    volumeNames.addAll(volSet);
                } else {
                    volumeNames.add(MediaStore.VOLUME_EXTERNAL);
                }
            } catch (Exception e) {
                Log.w(TAG, "获取外部卷名失败，使用默认: " + e.getMessage());
                volumeNames.add(MediaStore.VOLUME_EXTERNAL);
            }
        } else {
            // API 21-28: 用 EXTERNAL_CONTENT_URI，系统自动聚合所有卷
            volumeNames.add(null); // 标记用 EXTERNAL_CONTENT_URI
        }

        for (String volumeName : volumeNames) {
            Uri collection;
            if (volumeName == null) {
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            } else {
                collection = MediaStore.Audio.Media.getContentUri(volumeName);
            }
            songs.addAll(queryMediaStore(context, collection));
        }

        Log.d(TAG, "MediaStore 扫描完成，共 " + songs.size() + " 首歌曲（" + volumeNames.size() + " 个卷）");
        return songs;
    }

    /**
     * 查询单个 MediaStore 卷
     */
    private static List<Song> queryMediaStore(Context context, Uri collection) {
        List<Song> songs = new ArrayList<>();

        String[] projection;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.DISPLAY_NAME
            };
        } else {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
            };
        }

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media.DURATION + " > 30000";

        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, selection, null, sortOrder)) {

            if (cursor == null) {
                Log.e(TAG, "查询 MediaStore 返回 null，可能缺少存储权限");
                return songs;
            }

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int relPathCol = -1;
            int displayNameCol = -1;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH);
                displayNameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
            }

            while (cursor.moveToNext()) {
                Song song = new Song();
                song.id = cursor.getLong(idCol);
                song.title = cursor.getString(titleCol);
                song.artist = cursor.getString(artistCol);
                song.album = cursor.getString(albumCol);
                song.duration = cursor.getLong(durationCol);
                song.filePath = cursor.getString(dataCol);
                song.albumArt = getAlbumArtUri(cursor.getLong(albumIdCol));
                song.contentUri = "content://media/external/audio/media/" + song.id;

                if (song.filePath == null && relPathCol >= 0 && displayNameCol >= 0) {
                    String relPath = cursor.getString(relPathCol);
                    String displayName = cursor.getString(displayNameCol);
                    if (relPath != null && displayName != null) {
                        song.filePath = "/storage/emulated/0/" + relPath + displayName;
                    }
                }

                // 扩展名过滤：排除不在支持列表内的文件（如 .amr 录音）
                if (song.filePath != null && !WebDavScanner.isMusicFile(song.filePath)) {
                    continue;
                }

                if (song.artist != null && !song.artist.equals("<unknown>")) {
                    song.displayName = song.artist + " - " + song.title;
                } else {
                    song.displayName = song.title;
                }

                song.sourceType = Song.SOURCE_LOCAL;
                songs.add(song);
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore 扫描失败: " + e.getMessage(), e);
        }

        return songs;
    }

    /**
     * 获取 MediaStore 中音乐数量（用于手动扫描轮询检测索引稳定）
     */
    private static int getMediaStoreCount(Context context) {
        int count = 0;
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media.DURATION + " > 30000";

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // 多卷计数：覆盖内置存储 + U盘/SD卡独立卷
            Set<String> volSet;
            try {
                volSet = MediaStore.getExternalVolumeNames(context);
            } catch (Exception e) {
                Log.w(TAG, "getMediaStoreCount 获取卷名失败: " + e.getMessage());
                volSet = null;
            }
            if (volSet == null || volSet.isEmpty()) {
                count += queryMediaStoreCount(context, MediaStore.VOLUME_EXTERNAL, selection);
            } else {
                for (String volumeName : volSet) {
                    count += queryMediaStoreCount(context, volumeName, selection);
                }
            }
        } else {
            count += queryMediaStoreCount(context, null, selection);
        }
        return count;
    }

    /**
     * 查询单个卷的 MediaStore 音频数量
     */
    private static int queryMediaStoreCount(Context context, String volumeName, String selection) {
        Uri collection;
        if (volumeName == null) {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        } else {
            collection = MediaStore.Audio.Media.getContentUri(volumeName);
        }
        try (Cursor cursor = context.getContentResolver().query(
                collection, new String[]{"count(*)"}, selection, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "queryMediaStoreCount[" + volumeName + "] 失败: " + e.getMessage());
        }
        return 0;
    }

    // ========== 文件系统遍历扫描（补充 U 盘/SD 卡） ==========

    /**
     * 探测可移动存储挂载点（U 盘、SD 卡）
     * 优先使用 StorageManager 获取真实挂载路径，兜底硬编码路径
     */
    private static List<File> detectRemovableStorage(Context context) {
        List<File> storages = new ArrayList<>();
        Set<String> foundPaths = new HashSet<>();

        // 优先通道：StorageManager
        try {
            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                List<StorageVolume> volumes = sm.getStorageVolumes();
                for (StorageVolume volume : volumes) {
                    try {
                        // 排除内置模拟存储
                        if (volume.isEmulated()) continue;

                        // 只取已挂载的（排除 checking/unmounting 等过渡态）
                        String state = volume.getState();
                        if (!"mounted".equals(state)) continue;

                        File dir = null;
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            // API 30+：公开 getDirectory()
                            dir = volume.getDirectory();
                        } else {
                            // API 21-29：反射 getPath()
                            try {
                                Method getPath = StorageVolume.class.getMethod("getPath");
                                String path = (String) getPath.invoke(volume);
                                if (path != null) {
                                    dir = new File(path);
                                }
                            } catch (Exception reflectEx) {
                                Log.w(TAG, "反射 getPath 失败: " + reflectEx.getMessage());
                            }
                        }

                        if (dir != null && dir.isDirectory() && dir.canRead()) {
                            String absPath = dir.getAbsolutePath();
                            if (!foundPaths.contains(absPath)) {
                                foundPaths.add(absPath);
                                storages.add(dir);
                                Log.d(TAG, "[StorageManager] 发现可移动存储: " + absPath + " state=" + state);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "处理 StorageVolume 异常: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "StorageManager 获取存储卷失败: " + e.getMessage());
        }

        // 兜底通道：硬编码路径探测
        Set<String> internalPaths = new HashSet<>();
        internalPaths.add("/storage/emulated");
        internalPaths.add("/storage/emulated/0");
        internalPaths.add("/storage/self");
        internalPaths.add(Environment.getExternalStorageDirectory().getAbsolutePath());

        String[] probePaths = {
                "/mnt/usb_storage", "/mnt/media_rw", "/mnt/usb",
                "/mnt/sdcard2", "/mnt/ext_sdcard", "/mnt/external_sd",
                "/storage", "/mnt/usbhost", "/mnt/udisk", "/mnt/expand"
        };

        for (String probe : probePaths) {
            File dir = new File(probe);
            if (!dir.isDirectory() || !dir.canRead()) continue;

            File[] children = dir.listFiles();
            if (children == null) continue;

            for (File child : children) {
                if (!child.isDirectory() || !child.canRead()) continue;
                String childPath = child.getAbsolutePath();
                if (internalPaths.contains(childPath)) continue;
                if (child.getName().equals("emulated") || child.getName().equals("self")) continue;
                if (child.getName().equals("sdcard0")) continue;
                // 去重
                if (foundPaths.contains(childPath)) continue;

                foundPaths.add(childPath);
                storages.add(child);
                Log.d(TAG, "[硬编码] 发现可移动存储: " + childPath);
            }
        }

        if (storages.isEmpty()) {
            Log.d(TAG, "未发现可移动存储");
        }
        return storages;
    }

    /**
     * 文件系统遍历扫描
     * 探测 U 盘/SD 卡挂载点，递归扫描音乐文件
     */
    private static List<Song> scanByFileTraversal(Context context) {
        List<Song> songs = new ArrayList<>();
        List<File> storages = detectRemovableStorage(context);

        for (File storage : storages) {
            int[] counter = {0};
            traverseDirectory(storage, songs, 0, counter);
        }

        return songs;
    }

    /**
     * 递归遍历目录，收集音乐文件（带安全保护）
     *
     * @param dir 当前目录
     * @param songs 收集列表
     * @param depth 当前递归深度
     * @param counter 文件计数器（int[1]）
     */
    private static void traverseDirectory(File dir, List<Song> songs, int depth, int[] counter) {
        if (depth > MAX_TRAVERSE_DEPTH) return;
        if (counter[0] > MAX_TRAVERSE_FILES) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        // 检查 .nomedia
        for (File f : files) {
            if (f.isFile() && f.getName().equals(".nomedia")) return;
        }

        for (File file : files) {
            if (counter[0] > MAX_TRAVERSE_FILES) return;

            if (file.isDirectory()) {
                // 跳过特殊目录
                if (SKIP_DIR_NAMES.contains(file.getName())) continue;
                // 跳过符号链接
                try {
                    if (!file.getCanonicalPath().equals(file.getAbsolutePath())) continue;
                } catch (Exception e) {
                    continue;
                }
                traverseDirectory(file, songs, depth + 1, counter);
            } else if (file.isFile() && WebDavScanner.isMusicFile(file.getName())) {
                counter[0]++;
                Song song = buildSongFromFile(file);
                if (song != null) {
                    songs.add(song);
                }
            }
        }
    }

    /**
     * 内置存储文件遍历（仅手动扫描使用）
     * API 30+: 用 MediaStore.Files 查询（绕过 Scoped Storage 限制）
     * API 29-: 用 File.listFiles() 遍历
     */
    private static List<Song> scanInternalByFileTraversal(Context context) {
        List<Song> songs = new ArrayList<>();

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Android 11+: 用 MediaStore.Files 查询，不加 IS_MUSIC 过滤
            // 让 DSD/DTS 等被系统标记为非音乐的格式也能被查到
            // cursor 遍历时用扩展名过滤
            songs.addAll(scanByMediaStoreFiles(context));
        } else {
            // Android 9-10: File 遍历
            File storageRoot = Environment.getExternalStorageDirectory();
            File[] topDirs = storageRoot.listFiles();
            if (topDirs != null) {
                int[] counter = {0};
                for (File dir : topDirs) {
                    if (!dir.isDirectory()) continue;
                    if (SKIP_DIR_NAMES.contains(dir.getName())) continue;
                    if (dir.getName().startsWith(".")) continue;
                    traverseDirectoryInternal(dir, songs, 0, counter);
                }
            }
        }

        return songs;
    }

    /**
     * 通过 MediaStore.Files 查询内置存储中所有支持格式的音频文件（API 30+）
     * 不加 IS_MUSIC 过滤，用扩展名过滤
     */
    private static List<Song> scanByMediaStoreFiles(Context context) {
        List<Song> songs = new ArrayList<>();

        // 多卷查询：覆盖内置存储 + U盘/SD卡独立卷
        List<String> volumeNames = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                Set<String> volSet = MediaStore.getExternalVolumeNames(context);
                if (volSet != null && !volSet.isEmpty()) {
                    volumeNames.addAll(volSet);
                } else {
                    volumeNames.add(MediaStore.VOLUME_EXTERNAL);
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaStore.Files 获取外部卷名失败: " + e.getMessage());
                volumeNames.add(MediaStore.VOLUME_EXTERNAL);
            }
        } else {
            volumeNames.add(MediaStore.VOLUME_EXTERNAL);
        }

        for (String volumeName : volumeNames) {
            songs.addAll(queryMediaStoreFiles(context, volumeName));
        }

        Log.d(TAG, "MediaStore.Files 多卷扫描完成，共 " + songs.size() + " 首歌曲（" + volumeNames.size() + " 个卷）");
        return songs;
    }

    /**
     * 查询单个 MediaStore.Files 卷
     */
    private static List<Song> queryMediaStoreFiles(Context context, String volumeName) {
        List<Song> songs = new ArrayList<>();

        Uri collection = MediaStore.Files.getContentUri(volumeName);

        String[] projection;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.RELATIVE_PATH,
                    MediaStore.Files.FileColumns.DISPLAY_NAME
            };
        } else {
            projection = new String[]{
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Files.FileColumns.MEDIA_TYPE
            };
        }

        // 查询所有音频/媒体类型文件
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + " = " +
                MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO +
                " AND " + MediaStore.Audio.Media.DURATION + " > 30000";

        String sortOrder = MediaStore.Files.FileColumns.TITLE + " ASC";

        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, selection, null, sortOrder)) {

            if (cursor == null) return songs;

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);
            int albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int relPathCol = -1;
            int displayNameCol = -1;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                relPathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH);
                displayNameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
            }

            while (cursor.moveToNext()) {
                String filePath = cursor.getString(dataCol);

                // 扩展名过滤（核心：只保留支持列表内的格式）
                if (filePath == null || !WebDavScanner.isMusicFile(filePath)) continue;

                Song song = new Song();
                song.id = cursor.getLong(idCol);
                song.title = cursor.getString(titleCol);
                song.artist = cursor.getString(artistCol);
                song.album = cursor.getString(albumCol);
                song.duration = cursor.getLong(durationCol);
                song.filePath = filePath;
                song.albumArt = getAlbumArtUri(cursor.getLong(albumIdCol));
                song.contentUri = "content://media/external/audio/media/" + song.id;
                song.sourceType = Song.SOURCE_LOCAL;

                if (song.filePath == null && relPathCol >= 0 && displayNameCol >= 0) {
                    String relPath = cursor.getString(relPathCol);
                    String displayName = cursor.getString(displayNameCol);
                    if (relPath != null && displayName != null) {
                        song.filePath = "/storage/emulated/0/" + relPath + displayName;
                    }
                }

                if (song.artist != null && !song.artist.equals("<unknown>")) {
                    song.displayName = song.artist + " - " + song.title;
                } else {
                    song.displayName = song.title;
                }

                songs.add(song);
            }

            Log.d(TAG, "MediaStore.Files[" + volumeName + "] 扫描完成，共 " + songs.size() + " 首歌曲");
        } catch (Exception e) {
            Log.e(TAG, "MediaStore.Files 扫描失败: " + e.getMessage(), e);
        }

        return songs;
    }

    /**
     * 内置存储递归遍历（API 29-，带安全保护）
     */
    private static void traverseDirectoryInternal(File dir, List<Song> songs, int depth, int[] counter) {
        if (depth > MAX_INTERNAL_TRAVERSE_DEPTH) return;
        if (counter[0] > MAX_INTERNAL_TRAVERSE_FILES) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        // 检查 .nomedia
        for (File f : files) {
            if (f.isFile() && f.getName().equals(".nomedia")) return;
        }

        for (File file : files) {
            if (counter[0] > MAX_INTERNAL_TRAVERSE_FILES) return;

            if (file.isDirectory()) {
                if (SKIP_DIR_NAMES.contains(file.getName())) continue;
                if (dir.getName().startsWith(".")) continue;
                traverseDirectoryInternal(file, songs, depth + 1, counter);
            } else if (file.isFile() && WebDavScanner.isMusicFile(file.getName())) {
                counter[0]++;
                Song song = buildSongFromFile(file);
                if (song != null) {
                    songs.add(song);
                }
            }
        }
    }

    /**
     * 从文件构建 Song 对象
     * 不依赖 MediaStore，仅用文件信息构造
     */
    private static Song buildSongFromFile(File file) {
        String fileName = file.getName();
        String nameWithoutExt = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf("."))
                : fileName;

        Song song = new Song();
        song.sourceType = Song.SOURCE_LOCAL;
        song.title = nameWithoutExt;
        song.artist = "<unknown>";
        song.album = file.getParentFile() != null ? file.getParentFile().getName() : "";
        song.duration = 0; // 自动扫描时为0，手动扫描时由 enrichMetadata 补充
        song.filePath = file.getAbsolutePath();
        song.contentUri = null;
        song.displayName = nameWithoutExt;
        song.id = file.hashCode();

        return song;
    }

    /**
     * 补充文件遍历歌曲的元数据（仅手动扫描调用）
     * 用 MediaMetadataRetriever 从文件内嵌 tag 提取 duration/artist/album/title
     * 上限 MAX_METADATA_EXTRACT 首，超过的保持默认值
     */
    private static void enrichMetadata(List<Song> songs) {
        int processed = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            for (Song song : songs) {
                if (processed >= MAX_METADATA_EXTRACT) break;
                // 只补充非 MediaStore 来源的歌曲（duration=0 且 contentUri=null）
                if (song.duration > 0 || song.contentUri != null) continue;
                if (song.filePath == null || song.filePath.isEmpty()) continue;

                try {
                    retriever.setDataSource(song.filePath);
                    String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (duration != null) {
                        song.duration = Long.parseLong(duration);
                    }
                    String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    if (artist != null && !artist.isEmpty()) {
                        song.artist = artist;
                        song.displayName = artist + " - " + song.title;
                    }
                    String album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    if (album != null && !album.isEmpty()) {
                        song.album = album;
                    }
                    String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    if (title != null && !title.isEmpty()) {
                        song.title = title;
                        if (!"<unknown>".equals(song.artist)) {
                            song.displayName = song.artist + " - " + title;
                        } else {
                            song.displayName = title;
                        }
                    }
                    processed++;
                } catch (Exception e) {
                    Log.w(TAG, "提取元数据失败: " + song.filePath + " - " + e.getMessage());
                }
            }
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                // ignore
            }
        }
        Log.d(TAG, "元数据补充完成: 处理 " + processed + " 首");
    }

    /**
     * 获取专辑封面 URI
     */
    private static String getAlbumArtUri(long albumId) {
        if (albumId <= 0) return null;
        return "content://media/external/audio/albumart/" + albumId;
    }

    /**
     * 触发媒体扫描，让 MediaStore 索引新增的音乐文件
     * 解决 adb push 或第三方下载的文件不会自动被 MediaStore 索引的问题
     * Android 10+：通过 MediaScannerConnection 扫描公共音频目录 + 常见音乐App下载目录
     * Android 9-：用 File API 遍历收集音频文件路径
     * @return 是否触发了扫描
     */
    public static boolean triggerMediaScan(Context context) {
        boolean scanned;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+：MediaScannerConnection 扫描扩展后的目录列表
            String storage = Environment.getExternalStorageDirectory().getAbsolutePath();
            List<String> pathList = new ArrayList<>(Arrays.asList(
                    storage + "/Music",
                    storage + "/Download",
                    storage + "/Records",
                    storage + "/Recordings",
                    storage + "/Audio",
                    storage + "/Podcasts",
                    storage + "/netease/cloudmusic/Music",
                    storage + "/kugou/down_c",
                    storage + "/qqmusic/song",
                    storage + "/kwdownload/song"
            ));

            // 探测U盘/SD卡挂载点，加入扫描路径
            List<File> removableStorages = detectRemovableStorage(context);
            for (File usb : removableStorages) {
                collectAudioDirs(usb, pathList, 0);
            }

            String[] scanPaths = pathList.toArray(new String[0]);
            MediaScannerConnection.scanFile(context, scanPaths, null, null);
            Log.d(TAG, "触发媒体扫描: " + scanPaths.length + " 个路径（含U盘）");
            scanned = true;
        } else {
            // Android 9-：用 File API 遍历收集音频文件路径
            File storageRoot = Environment.getExternalStorageDirectory();
            String[] musicDirs = {"Music", "Download", "Records", "Recordings", "Audio", "Podcasts",
                    "netease/cloudmusic/Music", "kugou/down_c", "qqmusic/song", "kwdownload/song"};
            scanned = false;
            for (String dirName : musicDirs) {
                File dir = new File(storageRoot, dirName);
                if (!dir.isDirectory()) continue;
                File[] files = dir.listFiles();
                if (files == null) continue;
                List<String> paths = new ArrayList<>();
                collectAudioFiles(dir, paths);
                if (!paths.isEmpty()) {
                    String[] pathsArray = paths.toArray(new String[0]);
                    MediaScannerConnection.scanFile(context, pathsArray, null, null);
                    Log.d(TAG, "触发媒体扫描: " + dirName + "/ 下 " + pathsArray.length + " 个音频文件");
                    scanned = true;
                }
            }
        }
        if (scanned) {
            lastMediaScanTime = System.currentTimeMillis();
        }
        return scanned;
    }

    /**
     * 递归收集目录下的音频文件路径（带安全保护）
     */
    private static void collectAudioFiles(File dir, List<String> paths) {
        File[] files = dir.listFiles();
        if (files == null) return;

        // 检查 .nomedia
        for (File f : files) {
            if (f.isFile() && f.getName().equals(".nomedia")) return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                if (SKIP_DIR_NAMES.contains(file.getName())) continue;
                collectAudioFiles(file, paths);
            } else if (file.isFile() && WebDavScanner.isMusicFile(file.getName())) {
                paths.add(file.getAbsolutePath());
            }
        }
    }

    /**
     * 收集U盘/SD卡下的音频目录路径（用于 triggerMediaScan）
     * 递归收集含音频文件的目录，深度≤3，避免全盘扫描
     */
    private static void collectAudioDirs(File dir, List<String> paths, int depth) {
        if (depth > 3) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isFile() && f.getName().equals(".nomedia")) return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                if (SKIP_DIR_NAMES.contains(file.getName())) continue;
                collectAudioDirs(file, paths, depth + 1);
            } else if (file.isFile() && WebDavScanner.isMusicFile(file.getName())) {
                // 当前目录含音频文件，加入扫描路径
                String dirPath = dir.getAbsolutePath();
                if (!paths.contains(dirPath)) {
                    paths.add(dirPath);
                }
            }
        }
    }
}
