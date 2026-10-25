package com.jingxin.jingxinmusic.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.jingxin.jingxinmusic.util.EffectWallpaperStore;

import java.io.File;
import java.io.IOException;

/**
 * 歌词动效壁纸层（移植乐酷驾驶 GridBackgroundView 图片/视频壁纸机制）
 *
 * 图片模式：采样解码（RGB_565）+ ImageView CENTER_CROP 铺满。
 *           不可用 onDraw 自绘——ViewGroup 默认 willNotDraw=true，onDraw 不执行（首版透明 bug 即此因）。
 * 视频模式：TextureView + MediaPlayer（静音循环），center-crop 靠改 LayoutParams。
 *           TextureView 是普通 View，跟随效果页淡入淡出/移除（SurfaceView 不跟随）。
 * 生命周期：随效果页 addView(0) 挂载；visibility GONE 自动暂停视频（省电省解码）；
 *           onDetachedFromWindow 兜底 release（快速退出不泄漏）。
 * 失败兑底：同步失败 load() 返回 false；异步失败（视频 error）经 OnWallpaperErrorListener
 *           通知 Manager 恢复默认背景（星空 VISIBLE + 移除本层），绝不透出播放页。
 * 不压暗：壁纸原始亮度完整呈现，不加 scrim（用户确认项）。
 */
public class EffectWallpaperView extends FrameLayout {

    /** 异步加载失败回调（Manager 注入：恢复效果页默认背景并移除本层） */
    public interface OnWallpaperErrorListener { void onWallpaperError(); }

    // ==================== 图片模式 ====================
    private ImageView imageView;
    private Bitmap wallpaperBitmap;

    // ==================== 视频模式 ====================
    private TextureView videoView;
    private MediaPlayer mediaPlayer;
    private boolean videoPrepared = false;
    private boolean wantPlaying = true;     // 视图可见+页面激活期间期望播放

    private String wallpaperPath;
    private OnWallpaperErrorListener errorListener;

    public EffectWallpaperView(Context context) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setOnWallpaperErrorListener(OnWallpaperErrorListener l) { this.errorListener = l; }

    /** 入口：加载壁纸文件。视频/图片自动分派。失败返回 false（Manager 回退默认背景） */
    public boolean load(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        wallpaperPath = file.getAbsolutePath();
        String name = file.getName().toLowerCase();

        boolean isVideo = false;
        for (String ext : EffectWallpaperStore.VIDEO_EXTS) {
            if (name.endsWith(ext)) { isVideo = true; break; }
        }
        release();
        return isVideo ? setupVideo() : setupImage();
    }

    // ==================== 图片模式实现 ====================

    private boolean setupImage() {
        // 先探尺寸，按屏幕2倍长边降采样防 OOM（效果页全屏，无需 View 尺寸）
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(wallpaperPath, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return false;

        int targetMax = Math.max(
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 2;
        int maxDim = Math.max(opts.outWidth, opts.outHeight);
        int sampleSize = 1;
        while (maxDim / (sampleSize * 2) >= targetMax) sampleSize *= 2;

        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sampleSize;
        opts.inPreferredConfig = Bitmap.Config.RGB_565; // 壁纸无需透明通道

        wallpaperBitmap = BitmapFactory.decodeFile(wallpaperPath, opts);
        if (wallpaperBitmap == null) return false;

        imageView = new ImageView(getContext());
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setImageBitmap(wallpaperBitmap);
        addView(imageView, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return true;
    }

    // ==================== 视频模式实现 ====================

    private boolean setupVideo() {
        wantPlaying = true;
        videoView = new TextureView(getContext());
        videoView.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(videoView);

        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                startVideo(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                updateVideoLayout();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                stopVideo();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        return true;
    }

    private void startVideo(SurfaceTexture surface) {
        if (wallpaperPath == null) return;
        stopVideo();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setSurface(new android.view.Surface(surface));
            mediaPlayer.setDataSource(wallpaperPath);
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f); // 静音，不干扰音乐
            mediaPlayer.setOnPreparedListener(mp -> {
                videoPrepared = true;
                if (wantPlaying && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE) {
                    mp.start();
                } else {
                    wantPlaying = false;
                }
                if (videoView != null) videoView.post(this::updateVideoLayout);
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                notifyError();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            notifyError();
        }
    }

    /** 异步失败：自清理 + 通知 Manager（恢复默认背景并移除本层） */
    private void notifyError() {
        release();
        if (errorListener != null) errorListener.onWallpaperError();
    }

    private void stopVideo() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        videoPrepared = false;
    }

    /** 乐酷同款：改 TextureView LayoutParams 实现 center-crop（取 scaleX/scaleY 大值居中） */
    private void updateVideoLayout() {
        if (videoView == null || mediaPlayer == null) return;
        int parentW = getWidth();
        int parentH = getHeight();
        if (parentW <= 0 || parentH <= 0) return;

        int videoW = mediaPlayer.getVideoWidth();
        int videoH = mediaPlayer.getVideoHeight();
        if (videoW <= 0 || videoH <= 0) return;

        float scale = Math.max((float) parentW / videoW, (float) parentH / videoH);
        int scaledW = (int) (videoW * scale);
        int scaledH = (int) (videoH * scale);

        LayoutParams lp = (LayoutParams) videoView.getLayoutParams();
        lp.width = scaledW;
        lp.height = scaledH;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = (parentW - scaledW) / 2;
        lp.topMargin = (parentH - scaledH) / 2;
        videoView.setLayoutParams(lp);
    }

    // 可见性联动：app 切后台/效果页淡出期间暂停视频，回来续播（TextureView 无 Double-Tap）
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            resumeVideo();
        } else {
            pauseVideo();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            resumeVideo();
        } else {
            pauseVideo();
        }
    }

    private void pauseVideo() {
        wantPlaying = false;
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            try { mediaPlayer.pause(); } catch (Exception ignored) {}
        }
    }

    private void resumeVideo() {
        if (mediaPlayer != null && videoPrepared) {
            wantPlaying = true;
            try {
                if (!mediaPlayer.isPlaying()) mediaPlayer.start();
            } catch (Exception ignored) {}
        }
    }

    // ==================== 释放 ====================

    /** 全量释放（Manager detach 或换壁纸时调用） */
    public void release() {
        // 图片（先解引用再 recycle，防绘制 recycled bitmap 崩溃）
        if (imageView != null) {
            imageView.setImageDrawable(null);
            removeView(imageView);
            imageView = null;
        }
        if (wallpaperBitmap != null) {
            wallpaperBitmap.recycle();
            wallpaperBitmap = null;
        }
        // 视频
        stopVideo();
        if (videoView != null) {
            removeView(videoView);
            videoView = null;
        }
        videoPrepared = false;
        wantPlaying = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        // 兜底：效果页快速退出时确保 MediaPlayer 释放
        release();
        super.onDetachedFromWindow();
    }
}
