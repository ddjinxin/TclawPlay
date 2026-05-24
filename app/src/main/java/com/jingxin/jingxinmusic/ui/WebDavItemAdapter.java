package com.jingxin.jingxinmusic.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.model.Song;
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
 * WebDAV 目录项适配器
 * 网格布局显示目录和音乐文件
 * 目录：方形文件夹图标卡片
 * 歌曲文件：方形封面卡片（本地缓存 > 在线获取 > 默认封面）
 */
public class WebDavItemAdapter extends RecyclerView.Adapter<WebDavItemAdapter.ViewHolder> {

    private static final String TAG = "WebDavItemAdapter";

    private List<WebDavScanner.DavItem> items = new ArrayList<>();
    private OnItemClickListener listener;
    private Context context;

    // 封面加载
    private final ExecutorService coverExecutor = Executors.newFixedThreadPool(2);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Set<String> loadingCovers = new HashSet<>(); // 正在加载封面的key，防重复

    public interface OnItemClickListener {
        void onItemClick(WebDavScanner.DavItem item, int position);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<WebDavScanner.DavItem> items) {
        this.items = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    /**
     * 获取指定位置的DavItem
     */
    public WebDavScanner.DavItem getItem(int position) {
        if (position >= 0 && position < items.size()) {
            return items.get(position);
        }
        return null;
    }

    /**
     * 获取所有项目（用于构建播放列表）
     */
    public List<WebDavScanner.DavItem> getItems() {
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
        WebDavScanner.DavItem item = items.get(position);

        // 名称
        if (item.isDirectory) {
            holder.tvName.setText(item.name);
        } else {
            holder.tvName.setText(WebDavScanner.nameWithoutExtension(item.name));
        }

        // 图片
        if (item.isDirectory) {
            // 目录：方形文件夹图标
            holder.ivCover.setImageResource(R.drawable.ic_folder_card);
            holder.ivCover.setBackgroundColor(0); // 清除背景色，图标自带背景
        } else {
            // 歌曲文件：加载封面
            String songTitle = WebDavScanner.nameWithoutExtension(item.name);
            loadCover(holder, songTitle, item);
        }

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item, holder.getAdapterPosition());
            }
        });
    }

    /**
     * 加载歌曲封面
     * 1. 本地封面缓存（covers目录下 歌名.jpg）
     * 2. 在线获取（酷狗/网易云）
     * 3. 默认封面
     */
    private void loadCover(ViewHolder holder, String songTitle, WebDavScanner.DavItem item) {
        // 先设默认封面
        holder.ivCover.setImageResource(R.drawable.ic_default_cover_card);
        holder.ivCover.setBackgroundColor(0);

        if (context == null) return;

        String coverKey = songTitle;
        // 防止重复加载
        synchronized (loadingCovers) {
            if (loadingCovers.contains(coverKey)) return;
            loadingCovers.add(coverKey);
        }

        int adapterPosition = holder.getAdapterPosition();

        coverExecutor.execute(() -> {
            Bitmap coverBitmap = null;

            // 1. 本地封面缓存
            try {
                File coverDir = context.getExternalFilesDir("covers");
                if (coverDir != null) {
                    String coverName = Song.toFileName(songTitle, "") + ".jpg";
                    File coverFile = new File(coverDir, coverName);
                    if (coverFile.exists() && coverFile.length() > 0) {
                        coverBitmap = BitmapFactory.decodeFile(coverFile.getAbsolutePath());
                    }
                    // 如果没找到，也尝试用原始歌名直接匹配
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

            if (coverBitmap != null) {
                Bitmap finalBitmap = coverBitmap;
                synchronized (loadingCovers) { loadingCovers.remove(coverKey); }
                uiHandler.post(() -> {
                    if (holder.getAdapterPosition() == adapterPosition) {
                        holder.ivCover.setImageBitmap(finalBitmap);
                    }
                });
                return;
            }

            // 2. 在线获取封面
            CoverFetcher.fetchCover(songTitle, "", new CoverFetcher.CoverCallback() {
                @Override
                public void onCoverFetched(Bitmap coverBitmap) {
                    // 保存到本地缓存
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
                        if (holder.getAdapterPosition() == adapterPosition) {
                            holder.ivCover.setImageBitmap(coverBitmap);
                        }
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    synchronized (loadingCovers) { loadingCovers.remove(coverKey); }
                    Log.d(TAG, "封面获取失败: " + songTitle + " - " + errorMessage);
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
        // 回收时清理图片引用，避免错位
        holder.ivCover.setImageDrawable(null);
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
