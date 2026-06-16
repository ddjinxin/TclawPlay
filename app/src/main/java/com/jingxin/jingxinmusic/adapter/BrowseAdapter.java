package com.jingxin.jingxinmusic.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.jingxin.jingxinmusic.util.BitmapUtil;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.BrowseItem;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.ui.FolderCoverView;
import com.jingxin.jingxinmusic.ui.SquareImageView;
import com.jingxin.jingxinmusic.util.CoverFetcher;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.util.WebDavScanner;

import java.io.File;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一浏览卡片适配器
 * 本地目录和WebDAV目录共用
 * 目录：方形文件夹图标卡片
 * 歌曲：方形封面卡片（本地缓存 > 在线获取 > 默认封面）
 */
public class BrowseAdapter extends RecyclerView.Adapter<BrowseAdapter.ViewHolder> {

    private static final String TAG = "BrowseAdapter";

    private List<BrowseItem> items = new ArrayList<>();
    private OnItemClickListener listener;
    private Context context;
    private boolean isNightMode = true;

    public void setNightMode(boolean night) {
        if (this.isNightMode != night) {
            this.isNightMode = night;
            notifyDataSetChanged();
        }
    }

    // 封面加载
    private final ExecutorService coverExecutor = Executors.newFixedThreadPool(2);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Set<String> loadingCovers = new HashSet<>();

    public interface OnItemClickListener {
        void onItemClick(BrowseItem item, int position);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<BrowseItem> items) {
        this.items = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    public List<BrowseItem> getItems() {
        return new ArrayList<>(items);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        context = parent.getContext();
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_webdav, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BrowseItem item = items.get(position);

        // 名称
        if (item.isDirectory) {
            holder.tvName.setText(item.name);
        } else {
            String displayName = item.song != null ? item.song.title :
                    WebDavScanner.nameWithoutExtension(item.name);
            holder.tvName.setText(displayName);
        }

        // 名称文字颜色
        holder.tvName.setTextColor(isNightMode ? 0xFFDDDDDD : 0xFF333333);

        // 图片
        if (item.isDirectory) {
            holder.ivCover.setVisibility(View.GONE);
            holder.ivFolderCover.setVisibility(View.VISIBLE);
            holder.ivFolderCover.setNightMode(isNightMode);
            // 计算当前文件夹在列表中是第几个文件夹，用于6色循环
            int folderCount = 0;
            for (int i = 0; i < position; i++) {
                if (items.get(i).isDirectory) folderCount++;
            }
            android.graphics.drawable.GradientDrawable folderGradient =
                    com.jingxin.jingxinmusic.util.ThemeColors.folderGradient(folderCount, isNightMode);
            holder.ivFolderCover.setGradientColors(
                    folderGradient.getColors()[0], folderGradient.getColors()[1]);
            // 穿透文件夹查找第一首歌的封面
            holder.ivFolderCover.setCoverBitmap(null); // 先清空
            coverExecutor.execute(() -> {
                Bitmap cover = findFirstCoverInDirectory(item);
                if (cover != null) {
                    uiHandler.post(() -> {
                        if (holder.getAdapterPosition() == position) {
                            holder.ivFolderCover.setCoverBitmap(cover);
                        }
                    });
                }
            });
        } else {
            holder.ivCover.setVisibility(View.VISIBLE);
            holder.ivFolderCover.setVisibility(View.GONE);
            loadCover(holder, item);
        }

        // 点击
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item, holder.getAdapterPosition());
            }
        });
    }

    private void loadCover(ViewHolder holder, BrowseItem item) {
        // 默认封面：应用图标风格，背景渐变跟随主题
        holder.ivCover.setImageResource(R.drawable.ic_music_icon);
        holder.ivCover.setBackground(ThemeColors.cardGradient(isNightMode));
        holder.ivCover.setColorFilter(isNightMode ? ThemeColors.nightCoverTint() : ThemeColors.dayCoverTint(), android.graphics.PorterDuff.Mode.SRC_ATOP);

        if (context == null) return;

        String songTitle = item.song != null ? item.song.title :
                WebDavScanner.nameWithoutExtension(item.name);

        String coverKey = songTitle;
        synchronized (loadingCovers) {
            if (loadingCovers.contains(coverKey)) return;
            loadingCovers.add(coverKey);
        }

        int pos = holder.getAdapterPosition();

        coverExecutor.execute(() -> {
            Bitmap coverBitmap = null;

            // 1. 本地封面缓存
            try {
                File coverDir = context.getExternalFilesDir("covers");
                if (coverDir != null) {
                    // 优先用 歌名 - 歌手.jpg 匹配
                    String artist = item.song != null ? item.song.artist : "";
                    String coverName = Song.toFileName(songTitle, artist) + ".jpg";
                    File coverFile = new File(coverDir, coverName);
                    if (coverFile.exists() && coverFile.length() > 0) {
                        coverBitmap = BitmapUtil.decodeSampledFromFile(coverFile.getAbsolutePath(), 200, 200);
                    }
                    // 回退：用歌名前缀匹配
                    if (coverBitmap == null) {
                        File[] coverFiles = coverDir.listFiles((dir, name) ->
                                name.startsWith(songTitle) && name.endsWith(".jpg"));
                        if (coverFiles != null && coverFiles.length > 0) {
                            coverBitmap = BitmapUtil.decodeSampledFromFile(coverFiles[0].getAbsolutePath(), 200, 200);
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "本地封面查找失败: " + e.getMessage());
            }

            // 2. 本地歌曲从MediaStore的albumArt URI加载
            if (coverBitmap == null && item.song != null && item.song.albumArt != null
                    && !item.song.albumArt.isEmpty()) {
                try {
                    coverBitmap = BitmapFactory.decodeStream(
                            context.getContentResolver().openInputStream(
                                    android.net.Uri.parse(item.song.albumArt)));
                } catch (Exception e) {
                    Log.d(TAG, "albumArt加载失败: " + e.getMessage());
                }
            }

            if (coverBitmap != null) {
                Bitmap finalBitmap = coverBitmap;
                synchronized (loadingCovers) { loadingCovers.remove(coverKey); }
                uiHandler.post(() -> {
                    if (holder.getAdapterPosition() == pos) {
                        holder.ivCover.setBackground(null);
                        holder.ivCover.setColorFilter(0);
                        holder.ivCover.setImageBitmap(finalBitmap);
                    }
                });
                return;
            }

            // 3. 在线获取封面
            CoverFetcher.fetchCover(songTitle, "", new CoverFetcher.CoverCallback() {
                @Override
                public void onCoverFetched(Bitmap coverBitmap) {
                    try {
                        File coverDir = context.getExternalFilesDir("covers");
                        if (coverDir != null) {
                            coverDir.mkdirs();
                            String coverName = Song.toFileName(songTitle, "") + ".jpg";
                            File coverFile = new File(coverDir, coverName);
                            if (!coverFile.exists()) {
                                java.io.FileOutputStream fos = new java.io.FileOutputStream(coverFile);
                                coverBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                                fos.close();
                            }
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "保存封面缓存失败: " + e.getMessage());
                    }
                    synchronized (loadingCovers) { loadingCovers.remove(coverKey); }
                    uiHandler.post(() -> {
                        if (holder.getAdapterPosition() == pos) {
                            holder.ivCover.setBackground(null);
                            holder.ivCover.setColorFilter(0);
                            holder.ivCover.setImageBitmap(coverBitmap);
                        }
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    synchronized (loadingCovers) { loadingCovers.remove(coverKey); }
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.ivCover.setImageDrawable(null);
        holder.ivCover.setColorFilter(0);
        holder.ivCover.setBackground(null);
        holder.ivFolderCover.setCoverBitmap(null);
    }

    /**
     * 穿透文件夹查找第一首歌的封面
     * 本地目录：直接扫描文件系统
     * WebDAV目录：远程扫描子目录
     */
    private Bitmap findFirstCoverInDirectory(BrowseItem dirItem) {
        if (dirItem.source == Song.SOURCE_WEBDAV) {
            return findWebDavCover(dirItem);
        }

        if (dirItem.source == Song.SOURCE_BILI) {
            return findBiliCover(dirItem);
        }

        // 本地目录：直接扫描文件系统
        String dirPath = dirItem.path != null ? dirItem.path : dirItem.url;
        if (dirPath == null) return null;
        return scanDirectoryForCover(new File(dirPath));
    }

    /** B站多P视频目录：获取第一首分P标题，查本地封面缓存，降级下载视频封面 */
    private Bitmap findBiliCover(BrowseItem dirItem) {
        try {
            String bvid = dirItem.biliBvid;
            if (bvid == null || bvid.isEmpty()) return null;

            com.jingxin.jingxinmusic.util.BiliConfig config =
                    new com.jingxin.jingxinmusic.util.BiliConfig(context);

            // 获取分P列表，取第一首的标题
            java.util.List<com.jingxin.jingxinmusic.util.BiliApi.VideoPage> pages =
                    com.jingxin.jingxinmusic.util.BiliApi.getVideoPages(bvid, config);
            if (pages == null || pages.isEmpty()) return null;

            // 用第一首分P标题查找本地封面缓存
            String firstTitle = pages.get(0).part;
            if (firstTitle != null && !firstTitle.isEmpty()) {
                // 去除编号前缀（如001.）
                firstTitle = firstTitle.replaceAll("^\\d{1,3}[.\\s\\-]+", "").trim();
                // 去除HTML标签
                firstTitle = firstTitle.replaceAll("<[^>]+>", "").trim();
            }
            if (firstTitle != null && !firstTitle.isEmpty()) {
                Bitmap cached = findCachedCoverByName(firstTitle);
                if (cached != null) {
                    Log.d(TAG, "B站目录命中封面缓存: " + bvid + " title=" + firstTitle);
                    return cached;
                }
            }

            // 降级：从视频封面URL下载
            String coverUrl = dirItem.biliCover;
            if (coverUrl != null && !coverUrl.isEmpty()) {
                Bitmap cover = com.jingxin.jingxinmusic.util.HttpUtil.getBitmap(coverUrl);
                if (cover != null) {
                    Log.d(TAG, "B站目录用视频封面: " + bvid);
                    return cover;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "B站目录封面查找失败: " + e.getMessage());
        }
        return null;
    }

    /** WebDAV目录：先查本地缓存，未命中则远程扫描 */
    private Bitmap findWebDavCover(BrowseItem dirItem) {

        try {
            String dirUrl = dirItem.url;
            if (dirUrl == null) return null;
            // 确保以/结尾
            if (!dirUrl.endsWith("/")) dirUrl = dirUrl + "/";

            // 第0步：检查本地WebDAV封面缓存
            File webdavCacheDir = getWebDavCacheDir();
            if (webdavCacheDir != null) {
                String cacheFileName = webdavUrlToCacheName(dirUrl);
                File cacheFile = new File(webdavCacheDir, cacheFileName);
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    Bitmap cached = BitmapUtil.decodeSampledFromFile(cacheFile.getAbsolutePath(), 200, 200);
                    if (cached != null) {
                        Log.d(TAG, "命中WebDAV封面缓存: " + dirUrl);
                        return cached;
                    }
                }
            }

            com.jingxin.jingxinmusic.util.WebDavConfig config =
                    new com.jingxin.jingxinmusic.util.WebDavConfig(context);
            if (config == null || !config.isConfigured()) return null;

            com.jingxin.jingxinmusic.util.WebDavScanner scanner =
                    new com.jingxin.jingxinmusic.util.WebDavScanner(config);

            // 递归查找第一首音乐文件
            String songName = findFirstSongName(scanner, dirUrl, 3);
            if (songName != null) {
                // 从在线缓存中查找封面
                Bitmap cover = findCachedCoverByName(songName);
                if (cover != null) {
                    // 保存到WebDAV本地缓存
                    saveWebDavCoverCache(dirUrl, cover);
                    return cover;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "WebDAV封面查找失败: " + e.getMessage());
        }
        return null;
    }

    /** 获取WebDAV封面本地缓存目录 */
    private File getWebDavCacheDir() {
        try {
            File dir = new File(context.getExternalFilesDir(null), "webdav_covers");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    /** 将WebDAV URL转换为缓存文件名（MD5摘要避免特殊字符） */
    private static String webdavUrlToCacheName(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString() + ".jpg";
        } catch (Exception e) {
            // 回退：用URL哈希值
            return Integer.toHexString(url.hashCode()) + ".jpg";
        }
    }

    /** 保存WebDAV封面到本地缓存 */
    private void saveWebDavCoverCache(String dirUrl, Bitmap cover) {
        try {
            File webdavCacheDir = getWebDavCacheDir();
            if (webdavCacheDir == null) return;
            Bitmap scaled = cover;
            if (cover.getWidth() > 200 || cover.getHeight() > 200) {
                int size = Math.min(cover.getWidth(), cover.getHeight());
                float scale = 200f / size;
                scaled = Bitmap.createScaledBitmap(cover,
                    (int)(cover.getWidth() * scale),
                    (int)(cover.getHeight() * scale), true);
            }
            String cacheFileName = webdavUrlToCacheName(dirUrl);
            File cacheFile = new File(webdavCacheDir, cacheFileName);
            FileOutputStream fos = new FileOutputStream(cacheFile);
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            Log.d(TAG, "保存WebDAV封面缓存: " + dirUrl);
        } catch (Exception e) {
            Log.d(TAG, "保存WebDAV封面缓存失败: " + e.getMessage());
        }
    }

    /** 递归扫描WebDAV目录找第一首音乐文件名 */
    private String findFirstSongName(com.jingxin.jingxinmusic.util.WebDavScanner scanner,
                                      String dirUrl, int maxDepth) {
        if (maxDepth <= 0) return null;
        try {
            java.util.List<com.jingxin.jingxinmusic.util.WebDavScanner.DavItem> items =
                    scanner.listDirectory(dirUrl);
            if (items == null) return null;

            // 先找当前目录的音乐文件
            for (com.jingxin.jingxinmusic.util.WebDavScanner.DavItem item : items) {
                if (!item.isDirectory && WebDavScanner.isMusicFile(item.name)) {
                    return com.jingxin.jingxinmusic.util.WebDavScanner.nameWithoutExtension(item.name);
                }
            }
            // 穿透子文件夹
            for (com.jingxin.jingxinmusic.util.WebDavScanner.DavItem item : items) {
                if (item.isDirectory) {
                    String result = findFirstSongName(scanner, item.url, maxDepth - 1);
                    if (result != null) return result;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "WebDAV扫描失败: " + e.getMessage());
        }
        return null;
    }

    /** 根据歌曲名从在线封面缓存查找 */
    private Bitmap findCachedCoverByName(String songName) {
        if (songName == null) return null;
        try {
            File coversDir = context.getExternalFilesDir("covers");
            if (coversDir != null && coversDir.isDirectory()) {
                File[] coverFiles = coversDir.listFiles();
                if (coverFiles != null) {
                    // 封面缓存文件名格式：歌曲名-歌手名.jpg
                    String lowerName = songName.toLowerCase();
                    for (File cf : coverFiles) {
                        String cfName = cf.getName().replace(".jpg", "").toLowerCase();
                        if (cfName.startsWith(lowerName) || lowerName.startsWith(cfName)) {
                            Bitmap bmp = BitmapUtil.decodeSampledFromFile(cf.getAbsolutePath(), 200, 200);
                            if (bmp != null) return bmp;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    /**
     * 递归扫描目录，找第一首音乐文件的封面
     * 第0步：检查目录下是否有缓存封面 .cover_cache.jpg，有则直接返回
     * 找到封面后保存为 .cover_cache.jpg 加速后续访问
     */
    private static final String COVER_CACHE_NAME = ".cover_cache.jpg";

    private Bitmap scanDirectoryForCover(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        
        // 第0步：检查缓存封面文件
        File cacheFile = new File(dir, COVER_CACHE_NAME);
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Bitmap cached = BitmapUtil.decodeSampledFromFile(cacheFile.getAbsolutePath(), 200, 200);
            if (cached != null) {
                Log.d(TAG, "命中封面缓存: " + dir.getName());
                return cached;
            }
        }
        
        File[] files = dir.listFiles();
        if (files == null) return null;

        // 第一轮：尝试内嵌封面和MediaStore albumArt
        for (File f : files) {
            if (f.isFile() && WebDavScanner.isMusicFile(f.getName())) {
                // 1. 内嵌封面
                Bitmap cover = CoverFetcher.extractEmbeddedCover(f.getAbsolutePath());
                if (cover != null) {
                    saveCoverCache(dir, cover);
                    return cover;
                }
                // 2. MediaStore albumArt
                cover = getAlbumArtFromMediaStore(f.getAbsolutePath());
                if (cover != null) {
                    saveCoverCache(dir, cover);
                    return cover;
                }
            }
        }
        // 第二轮：尝试在线封面缓存
        for (File f : files) {
            if (f.isFile() && WebDavScanner.isMusicFile(f.getName())) {
                Bitmap cover = getCachedCover(f.getAbsolutePath());
                if (cover != null) {
                    saveCoverCache(dir, cover);
                    return cover;
                }
            }
        }
        // 第三轮：穿透子文件夹
        for (File f : files) {
            if (f.isDirectory()) {
                Bitmap cover = scanDirectoryForCover(f);
                if (cover != null) {
                    saveCoverCache(dir, cover);
                    return cover;
                }
            }
        }
        return null;
    }
    
    /**
     * 保存封面缓存到目录下
     * 保存为一个较小的图片（200x200），避免占用过多存储
     */
    private void saveCoverCache(File dir, Bitmap cover) {
        try {
            // 缩放至合理大小，节省存储空间
            Bitmap scaled = cover;
            if (cover.getWidth() > 200 || cover.getHeight() > 200) {
                int size = Math.min(cover.getWidth(), cover.getHeight());
                float scale = 200f / size;
                scaled = Bitmap.createScaledBitmap(cover, 
                    (int)(cover.getWidth() * scale), 
                    (int)(cover.getHeight() * scale), true);
            }
            File cacheFile = new File(dir, COVER_CACHE_NAME);
            FileOutputStream fos = new FileOutputStream(cacheFile);
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            Log.d(TAG, "保存封面缓存: " + dir.getName());
        } catch (Exception e) {
            Log.d(TAG, "保存封面缓存失败: " + e.getMessage());
        }
    }

    /** 从MediaStore获取albumArt */
    private Bitmap getAlbumArtFromMediaStore(String filePath) {
        try {
            android.database.Cursor cursor = context.getContentResolver().query(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Audio.Media.ALBUM_ID},
                    android.provider.MediaStore.Audio.Media.DATA + "=?",
                    new String[]{filePath},
                    null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        long albumId = cursor.getLong(0);
                        android.net.Uri albumArtUri = android.content.ContentUris.withAppendedId(
                                android.net.Uri.parse("content://media/external/audio/albumart"),
                                albumId);
                        return android.provider.MediaStore.Images.Media.getBitmap(
                                context.getContentResolver(), albumArtUri);
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            // albumArt不可用，静默处理
        }
        return null;
    }

    /** 尝试从在线封面缓存中查找 */
    private Bitmap getCachedCover(String filePath) {
        try {
            // 从文件路径推导歌曲名
            String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
            String songName = fileName;
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx > 0) songName = fileName.substring(0, dotIdx);
            // 查找covers目录中以歌曲名开头的缓存文件
            File coversDir = context.getExternalFilesDir("covers");
            if (coversDir != null && coversDir.isDirectory()) {
                File[] coverFiles = coversDir.listFiles();
                if (coverFiles != null) {
                    String lowerSongName = songName.toLowerCase();
                    for (File cf : coverFiles) {
                        String cfName = cf.getName().toLowerCase();
                        if (cfName.startsWith(lowerSongName) || lowerSongName.startsWith(cfName.replace(".jpg", ""))) {
                            Bitmap bmp = BitmapUtil.decodeSampledFromFile(cf.getAbsolutePath(), 200, 200);
                            if (bmp != null) return bmp;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 缓存查找失败，静默
        }
        return null;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        SquareImageView ivCover;
        FolderCoverView ivFolderCover;
        TextView tvName;

        ViewHolder(View view) {
            super(view);
            ivCover = view.findViewById(R.id.iv_cover);
            ivFolderCover = view.findViewById(R.id.iv_folder_cover);
            tvName = view.findViewById(R.id.tv_name);
        }
    }
}
