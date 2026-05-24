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
import com.jingxin.jingxinmusic.ui.SquareImageView;
import com.jingxin.jingxinmusic.util.CoverFetcher;
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

        // 图片
        if (item.isDirectory) {
            // 文件夹卡片：代码控制背景色 + 纯图标
            holder.ivCover.setImageResource(R.drawable.ic_folder_icon);
            holder.ivCover.setBackgroundColor(isNightMode ? 0xFF333333 : 0xFFD0D0D0);
            holder.ivCover.setColorFilter(isNightMode ? 0xFFAAAAAA : 0xFF555555);
        } else {
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
        TextView tvName;

        ViewHolder(View view) {
            super(view);
            ivCover = view.findViewById(R.id.iv_cover);
            tvName = view.findViewById(R.id.tv_name);
        }
    }
}
