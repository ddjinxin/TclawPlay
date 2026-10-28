package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.EffectWallpaperStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 歌词动效壁纸选择器（应用内文件浏览器 View 版，移植乐酷驾驶 FilePickerView 叠层方案）
 *
 * 为什么是 View 叠层而不是 Activity/Dialog：
 * 乐酷桌面悬浮模式下，应用内容被剥离进 APPLICATION_OVERLAY 窗口显示，原 Activity 窗口隐藏——
 * 新 Activity 会被压后台，Dialog 挂在隐藏的 Activity token 上同样不可见。
 * 乐酷自己的解法：FilePickerView 作为普通子 View 叠进当前可见视图树（addOverlay），完全绕开窗口系统。
 * 本 View 由调用方 addView 到设置页根布局（MATCH_PARENT 铺满），全屏/悬浮/多窗口通吃。
 *
 * 布局复用 activity_file_picker.xml；选中即复制为壁纸并自动启用壁纸模式。
 */
public class FilePickerView extends FrameLayout {

    /** 选完壁纸回调（已复制+已启用壁纸模式） */
    public interface OnWallpaperPicked { void onPicked(String fileName); }

    private GridView gridView;
    private TextView pathText;
    private ExecutorService thumbnailExecutor;
    private Handler mainHandler;
    private File currentDir;
    private FileAdapter adapter;
    private final OnWallpaperPicked pickedListener;

    public FilePickerView(Context context, OnWallpaperPicked listener) {
        super(context);
        this.pickedListener = listener;
        LayoutInflater.from(context).inflate(R.layout.activity_file_picker, this, true);
        init();
    }

    private void init() {
        mainHandler = new Handler(Looper.getMainLooper());
        thumbnailExecutor = Executors.newFixedThreadPool(3);

        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setImageBitmap(createBackIcon());
        pathText = findViewById(R.id.tv_path);
        gridView = findViewById(R.id.grid_files);

        // 乐酷 findDownloadDir 同款（车机多存储兼容）
        File startDir = findStartDir();
        currentDir = startDir != null ? startDir : Environment.getExternalStorageDirectory();

        btnBack.setOnClickListener(v -> {
            File parent = currentDir.getParentFile();
            if (parent == null || !parent.canRead()) {
                close();
            } else {
                currentDir = parent;
                loadFiles();
            }
        });

        gridView.setOnItemClickListener((parent, view, position, id) -> {
            FileItem item = adapter.getItem(position);
            if (item.isDirectory) {
                currentDir = item.file;
                loadFiles();
            } else {
                // 选中即复制为壁纸（覆盖旧文件）并自动切到壁纸模式
                boolean ok = EffectWallpaperStore.copy(getContext(), item.file.getAbsolutePath());
                if (ok) EffectWallpaperStore.setMode(getContext(), true);
                Toast.makeText(getContext(), ok
                        ? "已设置为动效壁纸：" + item.file.getName()
                        : "该文件类型不支持", Toast.LENGTH_SHORT).show();
                if (ok && pickedListener != null) pickedListener.onPicked(item.file.getName());
                close();
            }
        });

        loadFiles();
    }

    /** 从父容器移除自己（返回键/选完/关闭时调用） */
    public void close() {
        if (thumbnailExecutor != null) {
            thumbnailExecutor.shutdownNow();
            thumbnailExecutor = null;
        }
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
    }

    /** 乐酷 findDownloadDir 同款 */
    private File findStartDir() {
        try {
            File sysDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (sysDir != null && sysDir.exists()) return sysDir;
        } catch (Exception ignored) {}

        String[] fallbackPaths = {
                "/sdcard/Download", "/storage/emulated/0/Download",
                "/mnt/internal_sd/Download", "/mnt/sdcard/Download",
                "/sdcard/下载", "/storage/emulated/0/下载",
        };
        for (String p : fallbackPaths) {
            File f = new File(p);
            if (f.exists() && f.isDirectory()) return f;
        }

        File external = Environment.getExternalStorageDirectory();
        if (external != null && external.exists()) {
            for (String name : new String[]{"Download", "下载"}) {
                File sub = new File(external, name);
                if (sub.exists() && sub.isDirectory()) return sub;
            }
        }
        return null;
    }

    private void loadFiles() {
        pathText.setText(currentDir.getAbsolutePath());

        File[] files = currentDir.listFiles();
        List<FileItem> items = new ArrayList<>();

        if (files != null) {
            List<File> dirs = new ArrayList<>();
            List<File> mediaFiles = new ArrayList<>();
            for (File f : files) {
                if (f.isHidden()) continue;
                if (f.isDirectory()) dirs.add(f);
                else if (EffectWallpaperStore.getSupportedExt(f.getName()) != null) mediaFiles.add(f);
            }
            Collections.sort(dirs, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
            Collections.sort(mediaFiles, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
            for (File d : dirs) items.add(new FileItem(d, true));
            for (File f : mediaFiles) items.add(new FileItem(f, false));
        }

        adapter = new FileAdapter(items);
        gridView.setAdapter(adapter);
    }

    private boolean isVideoFile(File f) {
        String name = f.getName().toLowerCase();
        for (String ext : EffectWallpaperStore.VIDEO_EXTS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        // 兜底：快速移除/页面关闭时确保线程池释放
        if (thumbnailExecutor != null) {
            thumbnailExecutor.shutdownNow();
            thumbnailExecutor = null;
        }
        super.onDetachedFromWindow();
    }

    // ==================== 数据模型 ====================

    private static class FileItem {
        final File file;
        final boolean isDirectory;
        FileItem(File file, boolean isDirectory) {
            this.file = file;
            this.isDirectory = isDirectory;
        }
    }

    // ==================== 适配器（乐酷同款） ====================

    private class FileAdapter extends BaseAdapter {
        private final List<FileItem> items;
        private final LayoutInflater inflater;
        private final Bitmap folderIcon;

        FileAdapter(List<FileItem> items) {
            this.items = items;
            this.inflater = LayoutInflater.from(getContext());
            this.folderIcon = createFolderIcon();
        }

        @Override public int getCount() { return items.size(); }
        @Override public FileItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_file_picker, parent, false);
            }

            FileItem item = items.get(position);
            ImageView imageView = convertView.findViewById(R.id.img_thumb);
            TextView textView = convertView.findViewById(R.id.tv_filename);
            TextView badge = convertView.findViewById(R.id.tv_badge);

            textView.setText(item.file.getName());

            if (item.isDirectory) {
                badge.setVisibility(View.GONE);
                imageView.setBackgroundColor(0xFF1A1A2E);
                imageView.setImageBitmap(folderIcon);
            } else {
                if (isVideoFile(item.file)) {
                    badge.setVisibility(View.VISIBLE);
                    badge.setText("视频");
                } else {
                    badge.setVisibility(View.GONE);
                }

                final String path = item.file.getAbsolutePath();
                imageView.setTag(path);
                imageView.setBackgroundColor(0xFF2A2A4A);
                imageView.setImageBitmap(null);

                if (thumbnailExecutor != null) {
                    thumbnailExecutor.execute(() -> {
                        Bitmap thumb = loadThumbnail(item.file);
                        if (thumb != null && path.equals(imageView.getTag())) {
                            mainHandler.post(() -> {
                                if (path.equals(imageView.getTag())) {
                                    imageView.setBackgroundColor(0xFF000000);
                                    imageView.setImageBitmap(thumb);
                                }
                            });
                        }
                    });
                }
            }

            return convertView;
        }
    }

    // ==================== 缩略图加载（乐酷同款） ====================

    private Bitmap loadThumbnail(File file) {
        try {
            if (isVideoFile(file)) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(file.getAbsolutePath());
                    Bitmap frame = retriever.getFrameAtTime();
                    if (frame != null) return scaleBitmap(frame, 240);
                } finally {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            } else {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                if (opts.outWidth > 0) {
                    opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, 240, 240);
                    opts.inJustDecodeBounds = false;
                    Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                    if (bmp != null) return scaleBitmap(bmp, 240);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Bitmap scaleBitmap(Bitmap src, int maxSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxSize && h <= maxSize) return src;
        float scale = Math.min((float) maxSize / w, (float) maxSize / h);
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), false);
    }

    private int calculateSampleSize(int w, int h, int reqW, int reqH) {
        int sampleSize = 1;
        while (w / (sampleSize * 2) >= reqW && h / (sampleSize * 2) >= reqH) sampleSize *= 2;
        return sampleSize;
    }

    // ==================== 图标绘制（纯代码，乐酷同款） ====================

    private Bitmap createBackIcon() {
        int size = 88;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFF00D4E8);
        paint.setStrokeWidth(5);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        Path arrow = new Path();
        arrow.moveTo(56, 20);
        arrow.lineTo(32, 44);
        arrow.lineTo(56, 68);
        canvas.drawPath(arrow, paint);

        paint.setStrokeWidth(4);
        canvas.drawLine(32, 44, 68, 44, paint);
        return bmp;
    }

    private Bitmap createFolderIcon() {
        int size = 60;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(0xFF2A4A6A);
        paint.setStyle(Paint.Style.FILL);
        RectF folderBody = new RectF(10, 18, 50, 45);
        canvas.drawRoundRect(folderBody, 4, 4, paint);

        paint.setColor(0xFF3A5A7A);
        RectF tab = new RectF(10, 12, 25, 19);
        canvas.drawRoundRect(tab, 3, 3, paint);

        paint.setColor(0xFF00D4E8);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(folderBody, 4, 4, paint);
        canvas.drawRoundRect(tab, 3, 3, paint);
        return bmp;
    }
}
