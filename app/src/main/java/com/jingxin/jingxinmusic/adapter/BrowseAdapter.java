package com.jingxin.jingxinmusic.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
    private List<Song> allSongs = new ArrayList<>();
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

    public void setAllSongs(List<Song> songs) {
        this.allSongs = songs != null ? songs : new ArrayList<>();
    }

    public List<BrowseItem> getItems() {
        return new ArrayList<>(items);
    }

    public BrowseItem getItem(int position) {
        if (position >= 0 && position < items.size()) {
            return items.get(position);
        }
        return null;
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

        // 图片：根据类型切换显示
        if (item.isDirectory) {
            holder.ivCover.setVisibility(View.GONE);
            holder.ivFolderCover.setVisibility(View.VISIBLE);
            holder.ivFolderCover.setNightMode(isNightMode);
            // 加载目录下第一首歌的封面填充到文件夹轮廓
            loadFolderCover(holder, item);
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

        // 按压反馈：绿色高亮
        holder.itemView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.setBackgroundColor(isNightMode ? ThemeColors.NIGHT_CARD_PRESSED : ThemeColors.DAY_CARD_PRESSED);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setBackgroundColor(0x00000000);
                    break;
            }
            return false; // 不消费事件，让onClick仍能触发
        });
    }

    private void loadCover(ViewHolder holder, BrowseItem item) {
        // 默认封面：应用图标风格
        holder.ivCover.setImageResource(R.drawable.ic_music_icon);
        holder.ivCover.setBackgroundColor(0);
        holder.ivCover.setColorFilter(0);

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
                        coverBitmap = BitmapFactory.decodeFile(coverFile.getAbsolutePath());
                    }
                    // 回退：用歌名前缀匹配
                    if (coverBitmap == null) {
                        File[] coverFiles = coverDir.listFiles((dir, name) ->
                                name.startsWith(songTitle) && name.endsWith(".jpg"));
                        if (coverFiles != null && coverFiles.length > 0) {
                            coverBitmap = BitmapFactory.decodeFile(coverFiles[0].getAbsolutePath());
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
                        holder.ivCover.setBackgroundColor(0);
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
                            holder.ivCover.setBackgroundColor(0);
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
        holder.ivCover.setBackgroundColor(0);
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

    /**
     * 加载文件夹封面：取目录下第一首歌的封面填充到文件夹轮廓
     */
    private void loadFolderCover(ViewHolder holder, BrowseItem folderItem) {
        // 先设置无封面状态（FolderCoverView会自动使用默认封面图标）
        holder.ivFolderCover.setCoverBitmap(null);

        // 从allSongs中找到该目录下所有歌曲
        final String dirPath;
        if (folderItem.path != null) {
            dirPath = folderItem.path.endsWith("/") ? folderItem.path : folderItem.path + "/";
        } else {
            dirPath = null;
        }

        if (dirPath == null || allSongs.isEmpty()) {
            Log.d(TAG, "文件夹封面: dirPath=null或allSongs为空, folder=" + folderItem.name);
            return;
        }

        // 调试：打印路径匹配信息
        int matchCount = 0;
        for (Song s : allSongs) {
            if (s.filePath != null && s.filePath.startsWith(dirPath)) matchCount++;
        }
        Log.d(TAG, "文件夹封面: folder=" + folderItem.name + " dirPath=" + dirPath + " 匹配歌曲数=" + matchCount);
        if (matchCount == 0 && !allSongs.isEmpty()) {
            // 打印前3首歌的路径用于对比
            for (int i = 0; i < Math.min(3, allSongs.size()); i++) {
                Log.d(TAG, "  allSongs[" + i + "].filePath=" + allSongs.get(i).filePath);
            }
            return;
        }

        final int pos = holder.getAdapterPosition();
        final String coverKey = "folder_" + folderItem.name;
        synchronized (loadingCovers) {
            if (loadingCovers.contains(coverKey)) return;
            loadingCovers.add(coverKey);
        }

        coverExecutor.execute(() -> {
            Bitmap coverBitmap = null;
            Song coverSong = null;

            // 遍历目录下所有歌曲，找到第一个有封面的
            int tried = 0;
            for (Song s : allSongs) {
                if (s.filePath == null || !s.filePath.startsWith(dirPath)) continue;
                tried++;

                // 1. 尝试本地封面缓存
                boolean found = false;
                try {
                    File coverDir = context.getExternalFilesDir("covers");
                    if (coverDir != null) {
                        String artist = s.artist != null ? s.artist : "";
                        String coverName = Song.toFileName(s.title, artist) + ".jpg";
                        File coverFile = new File(coverDir, coverName);
                        if (coverFile.exists() && coverFile.length() > 0) {
                            Bitmap bmp = BitmapFactory.decodeFile(coverFile.getAbsolutePath());
                            if (bmp != null) {
                                coverBitmap = bmp;
                                Log.d(TAG, "文件夹封面: 缓存命中 " + s.title);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // 2. 尝试 albumArt URI
                if (s.albumArt != null && !s.albumArt.isEmpty()) {
                    try {
                        Bitmap bmp = BitmapFactory.decodeStream(
                                context.getContentResolver().openInputStream(
                                        android.net.Uri.parse(s.albumArt)));
                        if (bmp != null) {
                            coverBitmap = bmp;
                            Log.d(TAG, "文件夹封面: albumArt命中 " + s.title);
                            break;
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "文件夹封面: albumArt失败 " + s.title + " " + e.getMessage());
                    }
                }

                // 3. 尝试内嵌封面
                if (s.filePath != null) {
                    Bitmap bmp = CoverFetcher.extractEmbeddedCover(s.filePath);
                    if (bmp != null) {
                        coverBitmap = bmp;
                        Log.d(TAG, "文件夹封面: 内嵌封面命中 " + s.title);
                        break;
                    }
                }
            }

            Log.d(TAG, "文件夹封面: folder=" + folderItem.name + " 尝试了" + tried + "首, 结果=" + (coverBitmap != null ? "有封面" : "无封面"));

            synchronized (loadingCovers) { loadingCovers.remove(coverKey); }

            if (coverBitmap != null) {
                final Bitmap bmp = coverBitmap;
                Log.d(TAG, "文件夹封面: 设置封面到View folder=" + folderItem.name);
                uiHandler.post(() -> {
                    holder.ivFolderCover.setCoverBitmap(bmp);
                });
            }
        });
    }
}
