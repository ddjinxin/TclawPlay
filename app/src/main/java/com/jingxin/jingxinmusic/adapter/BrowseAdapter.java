package com.jingxin.jingxinmusic.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.jingxin.jingxinmusic.util.BitmapUtil;
import com.jingxin.jingxinmusic.util.CoverLoader;
import com.jingxin.jingxinmusic.fragment.SettingsFragment;
import android.os.Handler;
import android.os.Looper;
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
    private boolean dynamicBgMode = false;
    // 歌名字号：按屏高比例计算，横竖屏不同比例，下限=12sp等效像素
    private float itemNamePx = 0;
    private boolean itemFontSizeInited = false;

    /** 横竖屏切换时重置，使下次 onBind 重新按当前方向计算字号 */
    public void resetItemFontSize() {
        itemFontSizeInited = false;
    }

    public void setNightMode(boolean night) {
        if (this.isNightMode != night) {
            this.isNightMode = night;
            notifyDataSetChanged();
        }
    }

    public void setDynamicBgMode(boolean dynamic) {
        this.dynamicBgMode = dynamic;
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
        initItemFontSize();
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

        // 名称文字颜色：动态模式白/黑，非动态模式原逻辑
        holder.tvName.setTextColor(dynamicBgMode
                ? (isNightMode ? 0xFFFFFFFF : 0xFF222222)
                : ThemeColors.themedColor(isNightMode, 0xFF333333, 0xFFDDDDDD));
        // 名称字号：按屏高×2.5%，下限=12sp等效像素
        initItemFontSize();
        holder.tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, itemNamePx);

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
            int[] colors = com.jingxin.jingxinmusic.util.ThemeColors.folderGradientColors(folderCount, isNightMode);
            holder.ivFolderCover.setGradientColors(colors[0], colors[1]);
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

    /**
     * 根据屏幕高度计算歌名字号（PX）
     * 纯比例缩放，无下限：竖屏 屏高×2%，横屏 屏高×2.5%
     */
    private void initItemFontSize() {
        if (itemFontSizeInited || context == null) return;
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenHeight = dm.heightPixels;
        boolean isLandscape = context.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        float ratio = isLandscape ? 0.025f : 0.02f;
        itemNamePx = screenHeight * ratio;
        itemFontSizeInited = true;
    }

    private void loadCover(ViewHolder holder, BrowseItem item) {
        // 默认封面：耳机图标，背景渐变跟随主题
        holder.ivCover.setImageResource(R.drawable.ic_music_icon);
        holder.ivCover.setBackground(ThemeColors.cardGradient(isNightMode));

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

            boolean preferLocal = SettingsFragment.isLocalCoverPriority(context);

            // 0. 提取音频内嵌封面（开关打开时）
            if (preferLocal && item.song != null && item.song.filePath != null
                    && new File(item.song.filePath).exists()) {
                coverBitmap = CoverFetcher.extractEmbeddedCover(item.song.filePath, 200, 200);
            }

            // 1. 本地封面缓存
            if (coverBitmap == null) {
                try {
                    File coverDir = CoverLoader.getCoverDir(context);
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
                }
            }

            // 2. 本地歌曲从MediaStore的albumArt URI加载（降采样防OOM）
            if (coverBitmap == null && item.song != null && item.song.albumArt != null
                    && !item.song.albumArt.isEmpty()) {
                try {
                    coverBitmap = BitmapUtil.scaleToMaxSize(
                            BitmapFactory.decodeStream(
                                    context.getContentResolver().openInputStream(
                                            android.net.Uri.parse(item.song.albumArt))), 300);
                } catch (Exception e) {
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

            // 3. 在线获取封面（与播放页统一：清洗歌名+歌手搜索，按"歌名 - 歌手"保存，缓存全应用共享）
            String onlineArtist = item.song != null && !"<unknown>".equals(item.song.artist)
                    ? item.song.artist : "";
            String searchTitle = item.song != null
                    ? Song.cleanSongTitle(songTitle, onlineArtist) : songTitle;
            CoverFetcher.fetchCover(searchTitle, onlineArtist, new CoverFetcher.CoverCallback() {
                @Override
                public void onCoverFetched(Bitmap coverBitmap) {
                    try {
                        File coverDir = CoverLoader.getCoverDir(context);
                        if (coverDir != null) {
                            coverDir.mkdirs();
                            String coverName = Song.toFileName(songTitle, onlineArtist) + ".jpg";
                            File coverFile = new File(coverDir, coverName);
                            if (!coverFile.exists()) {
                                java.io.FileOutputStream fos = new java.io.FileOutputStream(coverFile);
                                coverBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                                fos.close();
                            }
                        }
                    } catch (Exception e) {
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
        Bitmap result;
        if (dirItem.source == Song.SOURCE_WEBDAV) {
            result = findWebDavCover(dirItem);
        } else if (dirItem.source == Song.SOURCE_BILI) {
            result = findBiliCover(dirItem);
        } else {
            // 本地目录：直接扫描文件系统
            String dirPath = dirItem.path != null ? dirItem.path : dirItem.url;
            if (dirPath == null) return null;
            result = scanDirectoryForCover(new File(dirPath));
        }
        return BitmapUtil.scaleToMaxSize(result, 300);
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
                Bitmap cached = CoverLoader.findCachedCoverByName(context, firstTitle);
                if (cached != null) {
                    return cached;
                }
            }

            // 降级：从视频封面URL下载
            String coverUrl = dirItem.biliCover;
            if (coverUrl != null && !coverUrl.isEmpty()) {
                Bitmap cover = com.jingxin.jingxinmusic.util.HttpUtil.getBitmap(coverUrl);
                if (cover != null) {
                    return cover;
                }
            }
        } catch (Exception e) {
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
                Bitmap cover = CoverLoader.findCachedCoverByName(context, songName);
                if (cover != null) {
                    // 保存到WebDAV本地缓存
                    saveCoverCache(cover, new File(webdavCacheDir, webdavUrlToCacheName(dirUrl)));
                    return cover;
                }
            }
        } catch (Exception e) {
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
        return md5Hex(url) + ".jpg";
    }

    private void saveCoverCache(Bitmap bitmap, File outputFile) {
        try {
            Bitmap scaled = bitmap;
            if (bitmap.getWidth() > 200 || bitmap.getHeight() > 200) {
                int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
                float scale = 200f / size;
                scaled = Bitmap.createScaledBitmap(bitmap,
                    (int)(bitmap.getWidth() * scale),
                    (int)(bitmap.getHeight() * scale), true);
            }
            FileOutputStream fos = new FileOutputStream(outputFile);
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
        } catch (Exception e) {
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
        }
        return null;
    }

    /**
     * 递归扫描目录，找第一首音乐文件的封面
     * 第0步：检查目录下是否有缓存封面 .cover_cache.jpg，有则直接返回
     * 找到封面后保存为 .cover_cache.jpg 加速后续访问
     */
    private static final String COVER_CACHE_NAME = ".cover_cache.jpg";
    private static final String COVER_CACHE_META = ".cover_cache_meta.txt";

    private Bitmap scanDirectoryForCover(File dir) {
        if (dir == null || !dir.isDirectory()) return null;

        // 第0步：检查缓存封面文件（校验目录指纹，目录音乐文件变化则缓存失效）
        File cacheFile = new File(dir, COVER_CACHE_NAME);
        if (cacheFile.exists() && !isDirCoverCacheValid(dir)) {
            cacheFile.delete();
            new File(dir, COVER_CACHE_META).delete();
        }
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Bitmap cached = BitmapUtil.decodeSampledFromFile(cacheFile.getAbsolutePath(), 200, 200);
            if (cached != null) {
                return cached;
            }
        }
        
        File[] files = dir.listFiles();
        if (files == null) return null;

        // 第一轮：尝试内嵌封面和MediaStore albumArt
        for (File f : files) {
            if (f.isFile() && WebDavScanner.isMusicFile(f.getName())) {
                // 1. 内嵌封面
                Bitmap cover = CoverFetcher.extractEmbeddedCover(f.getAbsolutePath(), 200, 200);
                if (cover != null) {
                    saveDirCoverCache(dir, cover);
                    return cover;
                }
                // 2. MediaStore albumArt
                cover = getAlbumArtFromMediaStore(f.getAbsolutePath());
                if (cover != null) {
                    saveDirCoverCache(dir, cover);
                    return cover;
                }
            }
        }
        // 第二轮：尝试在线封面缓存
        for (File f : files) {
            if (f.isFile() && WebDavScanner.isMusicFile(f.getName())) {
                Bitmap cover = CoverLoader.findCachedCoverByFilePath(context, f.getAbsolutePath());
                if (cover != null) {
                    saveDirCoverCache(dir, cover);
                    return cover;
                }
            }
        }
        // 第三轮：穿透子文件夹
        for (File f : files) {
            if (f.isDirectory()) {
                Bitmap cover = scanDirectoryForCover(f);
                if (cover != null) {
                    saveDirCoverCache(dir, cover);
                    return cover;
                }
            }
        }
        return null;
    }

    /** 保存目录封面缓存：写jpg + 写目录指纹meta（目录音乐文件变化时缓存失效） */
    private void saveDirCoverCache(File dir, Bitmap bitmap) {
        saveCoverCache(bitmap, new File(dir, COVER_CACHE_NAME));
        writeTextFile(new File(dir, COVER_CACHE_META), computeDirFingerprint(dir));
    }

    /** 目录封面缓存是否有效：meta指纹与当前目录音乐文件列表一致 */
    private static boolean isDirCoverCacheValid(File dir) {
        File metaFile = new File(dir, COVER_CACHE_META);
        if (!metaFile.exists()) return false;
        String meta = readTextFile(metaFile);
        return meta != null && meta.equals(computeDirFingerprint(dir));
    }

    /** 目录第一层音乐文件指纹（名字+大小 MD5，排序后摘要） */
    private static String computeDirFingerprint(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return "";
            List<String> names = new ArrayList<>();
            for (File f : files) {
                if (f.isFile() && WebDavScanner.isMusicFile(f.getName())) {
                    names.add(f.getName() + ":" + f.length());
                }
            }
            java.util.Collections.sort(names);
            StringBuilder sb = new StringBuilder();
            for (String n : names) sb.append(n).append('\n');
            return md5Hex(sb.toString());
        } catch (Exception e) {
            return "";
        }
    }

    private static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static void writeTextFile(File file, String content) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
        }
    }

    private static String readTextFile(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int read = fis.read(buf);
            fis.close();
            return read > 0 ? new String(buf, 0, read, "UTF-8") : "";
        } catch (Exception e) {
            return null;
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
                        return BitmapUtil.scaleToMaxSize(
                                android.provider.MediaStore.Images.Media.getBitmap(
                                        context.getContentResolver(), albumArtUri), 300);
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
