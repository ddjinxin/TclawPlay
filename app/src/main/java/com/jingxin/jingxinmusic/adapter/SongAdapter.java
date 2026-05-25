package com.jingxin.jingxinmusic.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import com.jingxin.jingxinmusic.model.FolderInfo;
import com.jingxin.jingxinmusic.model.Song;
import com.jingxin.jingxinmusic.util.CoverFetcher;
import com.jingxin.jingxinmusic.util.ThemeColors;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 多类型适配器：支持目录模式、全部模式、收藏模式
 */
public class SongAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnSongClickListener {
        void onSongClick(Song song);
    }

    public interface OnFolderClickListener {
        void onFolderClick(FolderInfo folder, boolean expanded);
    }

    private static final int TYPE_FOLDER = 0;
    private static final int TYPE_SONG = 1;
    private static final int TYPE_EMPTY = 2;

    private final Context context;
    private List<Song> allSongs = new ArrayList<>();
    private List<FolderInfo> folders = new ArrayList<>();
    private List<Song> favoriteSongs = new ArrayList<>();

    // 当前显示的扁平化列表
    private List<Object> displayItems = new ArrayList<>();

    private OnSongClickListener songListener;
    private OnFolderClickListener folderListener;

    // 当前模式：0=目录, 1=全部, 2=收藏
    private int currentMode = 0;

    // 主题（只设字段，不触发 notifyDataSetChanged）
    private boolean isNightMode = true;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void setNightMode(boolean night) {
        isNightMode = night;
    }

    // ========== 封面文件工具 ==========

    private static File getCoverFile(Context ctx, Song song) {
        File dir = ctx.getExternalFilesDir("covers");
        if (dir != null) dir.mkdirs();
        String coverName = com.jingxin.jingxinmusic.model.Song.toFileName(song.title, song.artist) + ".jpg";
        return new File(dir, coverName);
    }

    private static void saveCoverFile(Context ctx, Song song, Bitmap bmp) {
        if (ctx == null || bmp == null) return;
        try {
            File f = getCoverFile(ctx, song);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();
        } catch (Exception e) {
            Log.e("SongAdapter", "保存封面失败: " + e.getMessage());
        }
    }

    public SongAdapter(Context context) {
        this.context = context;
    }

    // ========== 数据设置 ==========

    public void setAllSongs(List<Song> songs) {
        this.allSongs = songs != null ? songs : new ArrayList<>();
        buildFolders();
        refreshDisplay();
    }

    public void setFavoriteSongs(List<Song> songs) {
        this.favoriteSongs = songs != null ? songs : new ArrayList<>();
        if (currentMode == 2) refreshDisplay();
    }

    public List<Song> getAllSongs() {
        return allSongs;
    }

    public List<Song> getFavoriteSongs() {
        return favoriteSongs;
    }

    // ========== 模式切换 ==========

    public void switchMode(int mode) {
        this.currentMode = mode;
        refreshDisplay();
    }

    public int getCurrentMode() {
        return currentMode;
    }

    // ========== 目录构建 ==========

    private void buildFolders() {
        folders.clear();
        // 用 LinkedHashMap 保持插入顺序
        Map<String, FolderInfo> folderMap = new LinkedHashMap<>();
        for (Song song : allSongs) {
            if (song.filePath == null) continue;
            String parentPath = song.filePath.substring(0, song.filePath.lastIndexOf('/'));
            if (!folderMap.containsKey(parentPath)) {
                String folderName = parentPath.substring(parentPath.lastIndexOf('/') + 1);
                folderMap.put(parentPath, new FolderInfo(parentPath, folderName, song.albumArt, new ArrayList<>()));
            }
            FolderInfo fi = folderMap.get(parentPath);
            fi.songs.add(song);
            // 封面取第一首有封面的
            if (fi.coverArt == null && song.albumArt != null && !song.albumArt.isEmpty()) {
                fi.coverArt = song.albumArt;
            }
        }
        folders.addAll(folderMap.values());
    }

    // ========== 刷新显示列表 ==========

    private void refreshDisplay() {
        displayItems.clear();
        switch (currentMode) {
            case 0: // 目录模式
                for (FolderInfo fi : folders) {
                    displayItems.add(fi);
                    if (fi.expanded) {
                        displayItems.addAll(fi.songs);
                    }
                }
                break;
            case 1: // 全部模式
                displayItems.addAll(allSongs);
                break;
            case 2: // 收藏模式
                displayItems.addAll(favoriteSongs);
                break;
        }
        notifyDataSetChanged();
    }

    // ========== 搜索过滤 ==========

    public void filter(String query) {
        if (currentMode == 0) {
            // 目录模式：搜索目录名，匹配则展开该目录
            filterFolders(query);
        } else {
            // 全部/收藏模式：搜索歌曲
            filterSongs(query);
        }
    }

    private void filterFolders(String query) {
        displayItems.clear();
        boolean hasQuery = query != null && !query.trim().isEmpty();
        String lowerQuery = hasQuery ? query.toLowerCase(Locale.getDefault()) : null;

        for (FolderInfo fi : folders) {
            if (!hasQuery || fi.folderName.toLowerCase(Locale.getDefault()).contains(lowerQuery)) {
                displayItems.add(fi);
                if (fi.expanded) {
                    if (hasQuery) {
                        for (Song s : fi.songs) {
                            if (s.title.toLowerCase(Locale.getDefault()).contains(lowerQuery) ||
                                s.artist.toLowerCase(Locale.getDefault()).contains(lowerQuery)) {
                                displayItems.add(s);
                            }
                        }
                    } else {
                        displayItems.addAll(fi.songs);
                    }
                }
            }
        }
        notifyDataSetChanged();
    }

    private void filterSongs(String query) {
        displayItems.clear();
        List<Song> sourceList = (currentMode == 2) ? favoriteSongs : allSongs;
        boolean hasQuery = query != null && !query.trim().isEmpty();

        if (!hasQuery) {
            displayItems.addAll(sourceList);
        } else {
            String lowerQuery = query.toLowerCase(Locale.getDefault());
            for (Song song : sourceList) {
                if (song.title.toLowerCase(Locale.getDefault()).contains(lowerQuery) ||
                    song.artist.toLowerCase(Locale.getDefault()).contains(lowerQuery) ||
                    song.album.toLowerCase(Locale.getDefault()).contains(lowerQuery)) {
                    displayItems.add(song);
                }
            }
        }
        notifyDataSetChanged();
    }

    // ========== 目录展开/收起 ==========

    public void toggleFolder(int adapterPosition) {
        Object item = (adapterPosition >= 0 && adapterPosition < displayItems.size())
                ? displayItems.get(adapterPosition) : null;
        if (item instanceof FolderInfo) {
            FolderInfo fi = (FolderInfo) item;
            fi.expanded = !fi.expanded;
            refreshDisplay();
        }
    }

    public void expandAllFolders() {
        for (FolderInfo fi : folders) fi.expanded = true;
        refreshDisplay();
    }

    public void collapseAllFolders() {
        for (FolderInfo fi : folders) fi.expanded = false;
        refreshDisplay();
    }

    // ========== 数据获取 ==========

    public Song getSong(int position) {
        Object item = (position >= 0 && position < displayItems.size())
                ? displayItems.get(position) : null;
        return item instanceof Song ? (Song) item : null;
    }

    public int getSongCount() {
        int count = 0;
        for (Object item : displayItems) {
            if (item instanceof Song) count++;
        }
        return count;
    }

    public int getFolderCount() {
        return folders.size();
    }

    public int getDisplayItemCount() {
        return displayItems.size();
    }

    // ========== 位置计算（传给播放页的 position） ==========

    public int getSongPositionInAll(Song song) {
        if (song == null) return 0;
        for (int i = 0; i < allSongs.size(); i++) {
            Song s = allSongs.get(i);
            if (s != null && s.filePath != null && s.filePath.equals(song.filePath)) {
                return i;
            }
        }
        return 0;
    }

    public int getSongPositionInFavorites(Song song) {
        if (song == null) return 0;
        for (int i = 0; i < favoriteSongs.size(); i++) {
            Song s = favoriteSongs.get(i);
            if (s != null && s.filePath != null && s.filePath.equals(song.filePath)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 获取歌曲在所属目录歌曲列表中的位置
     * @param song 目标歌曲
     * @param outFolderSongs 输出参数，返回该目录的歌曲列表
     */
    public int getSongPositionInFolder(Song song, List<Song> outFolderSongs) {
        if (song == null || outFolderSongs == null) return 0;
        String songFolder = getFolderPath(song);
        if (songFolder == null) return 0;
        outFolderSongs.clear();
        for (FolderInfo fi : folders) {
            if (songFolder.equals(fi.folderPath)) {
                outFolderSongs.addAll(fi.songs);
                break;
            }
        }
        for (int i = 0; i < outFolderSongs.size(); i++) {
            Song s = outFolderSongs.get(i);
            if (s != null && s.filePath != null && s.filePath.equals(song.filePath)) {
                return i;
            }
        }
        return 0;
    }

    private static String getFolderPath(Song song) {
        if (song == null || song.filePath == null) return null;
        int idx = song.filePath.lastIndexOf('/');
        return idx > 0 ? song.filePath.substring(0, idx) : null;
    }

    // ========== Adapter 标准方法 ==========

    @Override
    public int getItemViewType(int position) {
        Object item = displayItems.get(position);
        if (item instanceof FolderInfo) return TYPE_FOLDER;
        if (item instanceof Song) return TYPE_SONG;
        return TYPE_EMPTY;
    }

    @Override
    public int getItemCount() {
        return displayItems.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        switch (viewType) {
            case TYPE_FOLDER:
                return new FolderViewHolder(inflater.inflate(R.layout.item_folder, parent, false));
            case TYPE_SONG:
                return new SongViewHolder(inflater.inflate(R.layout.item_song, parent, false));
            default:
                // 空状态
                View emptyView = new TextView(context);
                emptyView.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                ((TextView) emptyView).setText("暂无收藏");
                ((TextView) emptyView).setTextColor(ThemeColors.emptyStateText());
                ((TextView) emptyView).setTextSize(14);
                ((TextView) emptyView).setGravity(android.view.Gravity.CENTER);
                int pad = (int) (48 * context.getResources().getDisplayMetrics().density);
                emptyView.setPadding(0, pad, 0, pad);
                return new RecyclerView.ViewHolder(emptyView) {};
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = displayItems.get(position);
        if (holder instanceof FolderViewHolder && item instanceof FolderInfo) {
            bindFolder((FolderViewHolder) holder, (FolderInfo) item);
        } else if (holder instanceof SongViewHolder && item instanceof Song) {
            bindSong((SongViewHolder) holder, (Song) item);
        }
    }

    private void bindFolder(FolderViewHolder holder, FolderInfo fi) {
        holder.tvName.setText(fi.folderName);
        holder.tvCount.setText(fi.songs.size() + " 首");
        // 箭头旋转
        holder.ivArrow.setRotation(fi.expanded ? 90 : 0);
        // 主题颜色
        if (isNightMode) {
            holder.itemView.setBackgroundColor(ThemeColors.nightItemBg());
            holder.tvName.setTextColor(ThemeColors.nightTextPrimary());
            holder.tvCount.setTextColor(ThemeColors.nightTextSecondary());
            holder.ivArrow.setColorFilter(ThemeColors.nightTextSecondary());
        } else {
            holder.itemView.setBackgroundColor(ThemeColors.dayItemBg());
            holder.tvName.setTextColor(ThemeColors.dayTextPrimary());
            holder.tvCount.setTextColor(ThemeColors.dayTextSecondary());
            holder.ivArrow.setColorFilter(ThemeColors.dayTextSecondary());
        }
        // 封面：内嵌封面 → 本地缓存文件 → 在线获取
        holder.ivCover.setImageResource(R.drawable.bg_folder_cover);
        Bitmap localCover = null;
        for (Song s : fi.songs) {
            // 1. 尝试提取内嵌封面
            if (localCover == null && s.filePath != null) {
                localCover = CoverFetcher.extractEmbeddedCover(s.filePath);
            }
            // 2. 尝试本地缓存
            if (localCover == null) {
                File f = getCoverFile(context, s);
                if (f != null && f.exists() && f.length() > 0) {
                    localCover = BitmapFactory.decodeFile(f.getAbsolutePath());
                }
            }
            if (localCover != null) break;
        }
        if (localCover != null) {
            holder.ivCover.setImageBitmap(localCover);
        } else if (!fi.coverLoaded && !fi.songs.isEmpty()) {
            fi.coverLoaded = true;
            Song first = fi.songs.get(0);
            String artist = "<unknown>".equals(first.artist) ? "" : first.artist;
            CoverFetcher.fetchCover(first.title, artist, new CoverFetcher.CoverCallback() {
                @Override
                public void onCoverFetched(Bitmap bmp) {
                    saveCoverFile(context, first, bmp);
                    mainHandler.post(() -> holder.ivCover.setImageBitmap(bmp));
                }
                @Override
                public void onError(String msg) {}
            });
        }
        holder.itemView.setOnClickListener(v -> {
            fi.expanded = !fi.expanded;
            refreshDisplay();
            if (folderListener != null) folderListener.onFolderClick(fi, fi.expanded);
        });
    }

    private void bindSong(SongViewHolder holder, Song song) {
        holder.tvTitle.setText(song.title);
        holder.tvArtist.setText(song.artist);
        holder.tvDuration.setText(Song.formatDuration(song.duration));
        // 主题颜色
        if (isNightMode) {
            holder.itemView.setBackgroundColor(ThemeColors.nightItemBg());
            holder.tvTitle.setTextColor(ThemeColors.nightTextPrimary());
            holder.tvArtist.setTextColor(ThemeColors.nightTextSecondary());
            holder.tvDuration.setTextColor(ThemeColors.nightTextTertiary());
        } else {
            holder.itemView.setBackgroundColor(ThemeColors.dayItemBg());
            holder.tvTitle.setTextColor(ThemeColors.dayTextPrimary());
            holder.tvArtist.setTextColor(ThemeColors.dayTextSecondary());
            holder.tvDuration.setTextColor(ThemeColors.dayTextSecondary());
        }
        holder.itemView.setOnClickListener(v -> {
            if (songListener != null) songListener.onSongClick(song);
        });
    }

    // ========== ViewHolder ==========

    static class FolderViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCover;
        TextView tvName;
        TextView tvCount;
        ImageView ivArrow;

        FolderViewHolder(@NonNull View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.iv_folder_cover);
            tvName = itemView.findViewById(R.id.tv_folder_name);
            tvCount = itemView.findViewById(R.id.tv_folder_count);
            ivArrow = itemView.findViewById(R.id.iv_folder_arrow);
        }
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvArtist;
        TextView tvDuration;

        SongViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_song_title);
            tvArtist = itemView.findViewById(R.id.tv_song_artist);
            tvDuration = itemView.findViewById(R.id.tv_song_duration);
        }
    }

    // ========== 监听器 ==========

    public void setOnSongClickListener(OnSongClickListener listener) {
        this.songListener = listener;
    }

    public void setOnFolderClickListener(OnFolderClickListener listener) {
        this.folderListener = listener;
    }
}
