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
    /** 冻结帧层：reparent 视频重建窗口期（~100ms）定格上一帧，消除黑闪 */
    private android.widget.ImageView freezeFrame;
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
        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "setupVideo");
        wantPlaying = true;
        videoView = new TextureView(getContext());
        videoView.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(videoView);

        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "onSurfaceTextureAvailable");
                startVideo(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "onSurfaceTextureSizeChanged " + width + "x" + height);
                updateVideoLayout();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                // ★ reparent 断流根治：返回 false 保留底层 SurfaceTexture——
                // 乐酷悬浮/分屏切换是 View reparent（纹理属于 app 进程 GL 资源，跨窗口有效），
                // 播放器持续解码渲染到纹理，重挂后无缝继续，无黑窗期。
                // 缺点：真释放（退效果页）需在 release() 手动 release 纹理。
                // 返回 true 的旧行为=每次 reparent 摧毁重建播放链，中间必黑一截
                com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "onSurfaceTextureDestroyed -> keep texture");
                captureFreezeFrame();
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        return true;
    }

    private void startVideo(SurfaceTexture surface) {
        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "startVideo surface=" + (surface != null));
        if (wallpaperPath == null) return;
        stopVideo();
        try {
            mediaPlayer = new MediaPlayer();
            // 音轨分轨防御：壁纸视频不能占用媒体通道——车机 8.1 NXP 音频系统上，
            // 与主播放器（ExoPlayer/MediaPlayer 兜底）同走 USAGE_MEDIA 会触发混音/路由竞争
            //（多声道切换静音坑）。归入 UI 提示音通道，setVolume(0) 再双保险。
            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            mediaPlayer.setAudioAttributes(attrs);
            mediaPlayer.setSurface(new android.view.Surface(surface));
            mediaPlayer.setDataSource(wallpaperPath);
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f); // 静音，不干扰音乐
            mediaPlayer.setOnPreparedListener(mp -> {
                videoPrepared = true;
                // 无条件 start：壁画视频静音循环，无暂停语义——不依赖窗口可见性判断
                //（reparent 中间态窗口可见性不可靠，曾致 prepared 后不 start → 永久黑）
                mp.start();
                com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "onPrepared -> start " + mp.getVideoWidth() + "x" + mp.getVideoHeight());
                if (videoView != null) videoView.post(this::updateVideoLayout);
                // 新视频出画后揭掉末帧定格（延迟两帧确保首帧已渲染上屏）
                if (videoView != null) {
                    videoView.post(() -> videoView.post(this::clearFreezeFrame));
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "onError what=" + what + " extra=" + extra);
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

    /** 末帧定格：纹理销毁瞬间截取当前画面盖上，视频重建窗口期无黑闪 */
    private void captureFreezeFrame() {
        if (videoView == null || mediaPlayer == null || !videoPrepared) return;
        try {
            Bitmap frame = videoView.getBitmap();
            if (frame == null) return;
            com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "freezeFrame captured " + frame.getWidth() + "x" + frame.getHeight());
            if (freezeFrame == null) {
                freezeFrame = new android.widget.ImageView(getContext());
                freezeFrame.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                addView(freezeFrame, new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            freezeFrame.setImageBitmap(frame);
            freezeFrame.setVisibility(VISIBLE);
        } catch (Exception ignored) {}
    }

    /** 揭掉末帧定格（保留 View 复用，GONE 即可） */
    private void clearFreezeFrame() {
        if (freezeFrame != null && freezeFrame.getVisibility() == VISIBLE) {
            freezeFrame.setVisibility(GONE);
            com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "freezeFrame cleared");
        }
    }

    private void stopVideo() {
        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "stopVideo player=" + (mediaPlayer != null));
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

        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "updateVideoLayout parent=" + parentW + "x" + parentH + " video=" + videoW + "x" + videoH);

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
        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "onWindowVisibilityChanged " + visibility);
        if (visibility == VISIBLE) {
            resumeVideo();
        } else {
            pauseVideo();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "onVisibilityChanged " + visibility);
        if (visibility == VISIBLE) {
            resumeVideo();
        } else {
            pauseVideo();
        }
    }

    private void pauseVideo() {
        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "pauseVideo player=" + (mediaPlayer != null) + " prepared=" + videoPrepared);
        wantPlaying = false;
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            try { mediaPlayer.pause(); } catch (Exception ignored) {}
        }
    }

    private void resumeVideo() {
        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "resumeVideo player=" + (mediaPlayer != null) + " prepared=" + videoPrepared);
        if (mediaPlayer != null && videoPrepared) {
            wantPlaying = true;
            try {
                if (!mediaPlayer.isPlaying()) mediaPlayer.start();
            } catch (Exception ignored) {}
        }
    }

    // ==================== 释放 ====================

    /** 全量释放（仅 Manager detach 显式调用；reparent 不得走此路径） */
    public void release() {
        // 冻结帧
        if (freezeFrame != null) {
            freezeFrame.setImageDrawable(null);
            removeView(freezeFrame);
            freezeFrame = null;
        }
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
            // return false 保留策略的代价补偿：系统不再自动释放纹理，退场时手动释放
            try {
                SurfaceTexture st = videoView.getSurfaceTexture();
                if (st != null) st.release();
            } catch (Exception ignored) {}
            removeView(videoView);
            videoView = null;
        }
        videoPrepared = false;
        wantPlaying = true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "onAttached path=" + (wallpaperPath != null) + " img=" + (imageView != null) + " vid=" + (videoView != null) + " avail=" + (videoView != null && videoView.isAvailable()) + " player=" + (mediaPlayer != null) + " prepared=" + videoPrepared);
        // 自愈：reparent 重挂后若壁纸层已失效（防御），按扩展名重建渲染层
        if (wallpaperPath == null) return;
        boolean imageDead = imageView != null
                && (wallpaperBitmap == null || wallpaperBitmap.isRecycled());
        boolean layerGone = imageView == null && videoView == null;
        if (imageDead || layerGone) {
            String name = wallpaperPath.toLowerCase();
            boolean isVideo = false;
            for (String ext : EffectWallpaperStore.VIDEO_EXTS) {
                if (name.endsWith(ext)) { isVideo = true; break; }
            }
            if (imageDead && imageView != null) {
                removeView(imageView);
                imageView = null;
            }
            if (isVideo) setupVideo();
            else setupImage();
            return; // 新建层自带启动链路
        }
        // ★ 视频复活：reparent 后 TextureView 缓存重用旧 SurfaceTexture 时不回调
        // onSurfaceTextureAvailable（仅首建回调），视频停在暂停黑屏——用 isAvailable
        // 判定纹理已就绪而播放器不在，直接重拉起
        if (videoView != null && videoView.isAvailable() && (mediaPlayer == null || !videoPrepared)) {
            wantPlaying = true;
            startVideo(videoView.getSurfaceTexture());
        } else if (videoView != null) {
            resumeVideo();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // ★ reparent 关键修复：乐酷分屏/悬浮调整大小时 View 树被剥离重挂，
        // detach/attach 是正常流程——绝不能在此销毁资源（旧版 release 致 bitmap 被回收、
        // videoView 被移除，重挂后图片画不出/视频层缺失 → 壁纸变黑或透出播放页）。
        // 只轻量暂停；真正释放仅由 Manager detach 显式调用 release()
        com.jingxin.jingxinmusic.util.DiagLog.d("EWV", "onDetached");
        pauseVideo();
        super.onDetachedFromWindow();
    }
}
