package com.jingxin.jingxinmusic.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jingxin.jingxinmusic.R;
import com.jingxin.jingxinmusic.util.ThemeColors;
import com.jingxin.jingxinmusic.util.UpdateHelper;

public class HelpFragment extends BaseFloatFragment {

    private boolean isNightMode;
    private LinearLayout helpContent;
    private TextView tvTitle, tvVersion;
    private View dividerTop;
    private ScrollView rootScroll;
    private ImageView btnBack, btnSettings;
    private float density;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_help, container, false);

        density = getResources().getDisplayMetrics().density;
        isNightMode = requireContext().getSharedPreferences("theme", android.content.Context.MODE_PRIVATE)
                .getBoolean("isNight", true);

        initViews(view);
        applyTheme();
        buildHelpContent();

        Button btnUpdate = view.findViewById(R.id.btn_check_update);
        btnUpdate.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "正在检查更新...", Toast.LENGTH_SHORT).show();
            UpdateHelper.getInstance(requireContext()).checkManually(requireActivity());
        });

        Button btnContact = view.findViewById(R.id.btn_contact);
        btnContact.setOnClickListener(v -> showContactDialog());

        Button btnReturn = view.findViewById(R.id.btn_return);
        btnReturn.setOnClickListener(v -> requireActivity().onBackPressed());

        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());

        applyTopInset(rootScroll);

        return view;
    }

    private void initViews(View view) {
        rootScroll = view.findViewById(R.id.root_scroll);
        helpContent = view.findViewById(R.id.help_content);
        tvTitle = view.findViewById(R.id.tv_title);
        dividerTop = view.findViewById(R.id.divider_top);
        tvVersion = view.findViewById(R.id.tv_version);
        btnBack = view.findViewById(R.id.btn_back);
        btnSettings = view.findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> {
            if (getActivity() instanceof com.jingxin.jingxinmusic.HostActivity) {
                ((com.jingxin.jingxinmusic.HostActivity) getActivity())
                        .navigateTo(new com.jingxin.jingxinmusic.fragment.SettingsFragment(), true);
            }
        });
        try {
            String v = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionName;
            tvVersion.setText("静心音乐 v" + v);
        } catch (Exception e) {
            tvVersion.setText("静心音乐");
        }
    }

    private void applyTheme() {
        rootScroll.setBackground(ThemeColors.bgGradient(isNightMode));
        if (isNightMode) {
            tvTitle.setTextColor(ThemeColors.nightTextPrimary());
            dividerTop.setBackgroundColor(ThemeColors.nightDivider());
            tvVersion.setTextColor(ThemeColors.nightTextTertiary());
            btnBack.clearColorFilter();
            btnSettings.clearColorFilter();
        } else {
            tvTitle.setTextColor(ThemeColors.dayTextPrimary());
            dividerTop.setBackgroundColor(ThemeColors.dayDivider());
            tvVersion.setTextColor(ThemeColors.dayTextSecondary());
            btnBack.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
            btnSettings.setColorFilter(ThemeColors.dayTextPrimary(), PorterDuff.Mode.SRC_IN);
        }
    }

    private void showContactDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("联系我们")
                .setMessage("静心音乐\n作者：静心\n\n交流群：乐酷桌面群\n群号：812753974、651547480\n\n欢迎加入交流群反馈建议和问题！")
                .setPositiveButton("知道了", null)
                .show();
    }

    // ========== 帮助内容 ==========

    private int textColor() { return isNightMode ? ThemeColors.nightTextPrimary() : ThemeColors.dayTextPrimary(); }
    private int secColor() { return isNightMode ? ThemeColors.nightTextSecondary() : ThemeColors.dayTextSecondary(); }
    private int accentColor() { return isNightMode ? ThemeColors.nightTabActive() : ThemeColors.dayTabActive(); }
    private int divColor() { return isNightMode ? ThemeColors.nightDivider() : ThemeColors.dayDivider(); }

    private void addSection(String title) {
        TextView tv = new TextView(requireContext());
        tv.setText(title);
        tv.setTextColor(accentColor());
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = (int)(20 * density);
        p.bottomMargin = (int)(6 * density);
        helpContent.addView(tv, p);
    }

    /**
     * 创建带通用样式的段落 TextView
     */
    private TextView createParaTextView(CharSequence text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(textColor());
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setLineSpacing((int)(2 * density), 1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = (int)(4 * density);
        tv.setLayoutParams(p);
        return tv;
    }

    /**
     * 创建带通用样式的要点 TextView（带左缩进）
     */
    private TextView createBulletTextView(CharSequence text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(textColor());
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setLineSpacing((int)(2 * density), 1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.leftMargin = (int)(12 * density);
        p.bottomMargin = (int)(3 * density);
        tv.setLayoutParams(p);
        return tv;
    }

    /** 带冒号加粗的段落 */
    private void addPara(String bold, String rest) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        int start = ssb.length();
        ssb.append(bold);
        ssb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.append(rest);
        helpContent.addView(createParaTextView(ssb));
    }

    /** 普通段落 */
    private void addPara(String text) {
        helpContent.addView(createParaTextView(text));
    }

    /** 带冒号加粗的要点 */
    private void addBullet(String bold, String rest) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append("• ");
        int start = ssb.length();
        ssb.append(bold);
        ssb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.append(rest);
        helpContent.addView(createBulletTextView(ssb));
    }

    /** 普通要点 */
    private void addBullet(String text) {
        helpContent.addView(createBulletTextView("• " + text));
    }

    private void addDivider() {
        View v = new View(requireContext());
        v.setBackgroundColor(divColor());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        p.topMargin = (int)(12 * density);
        p.bottomMargin = (int)(4 * density);
        helpContent.addView(v, p);
    }

    private void buildHelpContent() {
        // ========== 一、首页功能 ==========
        addSection("一、首页功能");
        addPara("首页分为四个标签页，点击标签切换不同音源：");
        addBullet("本地：", "扫描设备中的本地音乐文件，按文件夹目录浏览，点击文件夹进入子目录，点击歌曲播放。");
        addBullet("WebDAV：", "连接 WebDAV 云端服务器浏览和播放云端音乐。首次使用需点击右上角设置按钮配置服务器地址和账号。");
        addBullet("B站：", "登录 B站账号后浏览收藏夹中的视频音频。支持收藏夹列表和视频列表两级浏览，支持多 P 选集。");
        addBullet("收藏：", "收藏过的歌曲列表，支持搜索过滤。在播放页点击收藏按钮可收藏当前歌曲。");
        addPara("");
        addBullet("搜索：", "首页顶部搜索框实时过滤当前列表中的歌曲。");
        addBullet("迷你播放条：", "底部显示当前播放歌曲信息和播放/暂停按钮，封面随播放旋转，点击播放条跳转到播放页。");

        // ========== 二、播放页功能 ==========
        addSection("二、播放页功能");
        addPara("点击迷你播放条进入播放页，提供丰富的播放体验：");
        addBullet("封面风格：", "点击封面区域循环切换四种风格 — 经典（圆形旋转封面）、沉浸（封面铺满背景）、唱片机（黑胶唱片+唱臂动画）、轮播（3D卡片轮播切歌）。");
        addBullet("歌词显示：", "自动获取在线歌词，支持 KRC 逐字高亮和 LRC 整行高亮。单击歌词区域切换显示模式：双行 → 多行 → 全屏。沉浸模式和轮播模式下歌词仅双行和多行。");
        addBullet("播放控制：", "播放/暂停、上一曲、下一曲、进度条拖动定位。");
        addBullet("播放顺序：", "点击播放顺序按钮循环切换 — 顺序播放、随机播放、单曲循环。");
        addBullet("收藏：", "点击收藏按钮收藏/取消收藏当前歌曲。");
        addBullet("播放历史：", "点击历史按钮查看播放历史，点击历史项跳转播放。");
        addBullet("B站下载：", "B站音源播放时显示下载按钮，可将音频保存为 m4a 文件到 Download/music/ 目录。");
        addPara("");
        addPara("播放页支持横竖屏自适应，横屏时左右分区布局。");

        // ========== 三、频谱可视化 ==========
        addSection("三、频谱可视化");
        addPara("播放页提供 10 种频谱样式，点击频谱按钮弹出选择面板切换样式。竖屏面板 2 列 5 行，横屏 5 列 2 行，选中样式青色发光高亮。");
        addDivider();
        addBullet("1. 竖条（STYLE_BAR）：", "经典竖直柱状频谱。柱数为 16 / 屏幕宽度，范围 32~256 根，柱间距 15px，渐变色从低到高。");
        addBullet("2. 圆点（STYLE_DOT）：", "64 个圆点矩阵。取低中高三个频段能量均值，每 30 帧随机 20 个圆点交替跳动，形成跳跃点阵效果，背景底色 60% 透明度。");
        addBullet("3. 波浪线（STYLE_WAVE）：", "平滑波浪曲线。将 64 个频域数据点以贝塞尔曲线连成连续波形，2 像素线宽，下方半透明渐变填充，柔美流畅。");
        addBullet("4. 网易圆环（STYLE_RING）：", "圆环形态频谱，FFT 输入 256 点。支持 3 种子模式切换 — 柱状（放射白线）、爆炸（半透明白线 + 末端发光圆点）、波形（端点平滑连线）。沉浸模式下不可选。");
        addBullet("5. 柱状（STYLE_COLUMNAR）：", "原生 ColumnarView 柱状频谱。横屏 128 根、竖屏 64 根，经典对称柱状图，每根宽 5px、间距 1px。");
        addBullet("6. 酷狗柱状（STYLE_KUGOU）：", "酷狗风格柱状频谱。128 根竖条，带镜像反射和渐变效果，能量块每帧下落 3dp，视觉冲击力强。");
        addBullet("7. AI 语音（STYLE_AIVOICE）：", "AI 语音波形风格。FFT 输入 256 点，3 条贝塞尔曲线叠加动画，模拟智能语音助手的动态波形条。");
        addBullet("8. 波形柱（STYLE_WAVECOLUMN）：", "波形柱状混合模式。横屏 128 根、竖屏 64 根，圆角矩形柱（宽 10px）+ 波形叠加，放大偏移 10px。");
        addBullet("9. 扩散圆环（STYLE_DIFFUSION_RING）：", "扩散圆环频谱。FFT 输入 256 点计算总能量，能量超过阈值时从中心向外扩散圆环，沉浸模式下不可选。");
        addBullet("10. 波浪圆环（STYLE_WAVE_RING）：", "波浪圆环频谱。FFT 输入 256 点，内环距封面外沿 10px，能量点沿圆环动态扩散，沉浸模式下不可选。");
        addDivider();
        addPara("");
        addBullet("显示/隐藏：", "长按频谱按钮切换频谱显示和隐藏。");
        addBullet("样式切换：", "频谱可见时点击频谱按钮弹出样式选择面板。");
        addBullet("圆环子模式：", "网易圆环模式下点击再次切换可循环柱状、爆炸、波形三种子模式。");

        // ========== 四、悬浮迷你播放窗 ==========
        addSection("四、悬浮迷你播放窗");
        addPara("悬浮窗提供三种显示模式，长按封面循环切换：");
        addBullet("经典模式：", "水平卡片布局 — 左侧圆形旋转封面，右侧歌曲名、歌词、进度条和控制按钮。支持透明度调节。");
        addBullet("胶囊模式（灵动岛）：", "药丸形布局 — 封面 + 歌词频谱区 + 播放按钮。支持透明度和歌词区宽度调节。");
        addBullet("卡拉OK模式：", "三行竖向布局 — 第一行当前歌词居左，第二行下一句居右，封面居中跨两行，底部全宽竖条频谱。背景固定全透明，双击频谱可隐藏。");
        addPara("");
        addPara("悬浮窗通用操作：");
        addBullet("拖动：", "任意位置拖动移动悬浮窗位置。");
        addBullet("单击：", "跳回首页。");
        addBullet("双击：", "关闭悬浮窗。");
        addBullet("点击封面：", "弹出尺寸调节面板（+/- 缩放、透明度滑条等）。");
        addBullet("长按封面：", "切换悬浮窗模式。");
        addPara("");
        addPara("所有参数（位置、尺寸、透明度、模式）横竖屏分别记忆。");

        // ========== 五、设置 ==========
        addSection("五、设置");
        addPara("点击首页右上角设置按钮进入设置页面，提供以下选项：");
        addDivider();
        addBullet("重新扫描音乐：", "手动扫描本地音乐文件，包括内置存储和U盘中的音乐。扫描完成后列表自动刷新。");
        addBullet("文件访问权限：", "Android 11及以上系统扫描U盘音乐需要此权限。点击\"去授权\"跳转系统授权页面，授权后可扫描U盘中的音乐文件。");
        addBullet("夜间模式：", "开启后切换为夜间深色界面，关闭则使用日间浅色界面。");
        addBullet("界面风格：", "选择应用配色方案 — 春意盎然（绿色系）、蔚蓝天地（蓝色系）、万紫千红（粉紫系）、高级灰（灰色系，日间歌词高亮为红色）。每种风格在日间和夜间模式下有不同配色。");
        addBullet("悬浮播放窗：", "开启后应用退到后台时自动显示悬浮迷你播放窗。关闭则退到后台时不显示。");
        addBullet("频谱显示：", "开启后播放页和悬浮窗（胶囊/卡拉OK模式）显示音频频谱动画。关闭则全部隐藏。");
        addBullet("启动直达播放：", "开启后打开应用自动跳转到上次播放的歌曲播放页。关闭则启动后停在列表页。");
        addBullet("优先读取本地封面：", "开启后列表和播放页优先从音频文件内嵌封面和本地缓存读取封面，未找到再在线获取。关闭后跳过内嵌封面和缓存，直接在线获取（本地缓存和MediaStore仍会查找）。仅对本地音乐生效，WebDAV和B站音乐不受影响。");
        addBullet("优先读取本地歌词：", "开启后优先使用歌曲同目录下的LRC歌词文件，跳过KRC缓存。关闭后走完整在线获取流程。仅对本地音乐生效。");
        addBullet("歌词高亮颜色：", "自定义播放页高亮歌词的颜色，白天和夜间可分别设置。点击色条选择颜色，点击\"默认\"恢复风格默认色。");

        // ========== 六、权限说明 ==========
        addSection("六、权限说明");
        addBullet("音频读取：", "扫描和读取设备中的音乐文件。");
        addBullet("通知：", "显示播放控制通知栏。");
        addBullet("悬浮窗：", "显示悬浮迷你播放窗（需手动授权）。");
        addBullet("音频设置：", "获取频谱数据用于可视化效果（不录音）。");
        addBullet("网络：", "获取在线歌词、封面和检查更新。");

        // ========== 七、快捷操作汇总 ==========
        addSection("七、快捷操作汇总");
        addDivider();
        addPara("首页：");
        addBullet("点击标签 — 切换音源");
        addBullet("搜索框 — 实时搜索歌曲");
        addBullet("关闭按钮 — 退出应用");
        addBullet("帮助按钮 — 查看帮助");
        addBullet("设置按钮 — 进入设置页面（WebDAV/B站标签页右上角齿轮）");
        addPara("");
        addPara("播放页：");
        addBullet("点击封面区域 — 切换封面风格");
        addBullet("点击歌词区域 — 切换歌词显示模式");
        addBullet("点击频谱按钮 — 弹出样式选择");
        addBullet("长按频谱按钮 — 显示/隐藏频谱");
        addPara("");
        addPara("悬浮窗：");
        addBullet("长按封面 — 切换模式");
        addBullet("点击封面 — 调节面板");
        addBullet("双击 — 关闭悬浮窗");
        addBullet("双击频谱(卡拉OK) — 隐藏频谱");
    }
}
