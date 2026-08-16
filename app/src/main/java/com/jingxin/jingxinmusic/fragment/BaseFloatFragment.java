package com.jingxin.jingxinmusic.fragment;

import androidx.fragment.app.Fragment;

/**
 * 悬浮窗兼容 Fragment 基类
 *
 * 所有 Fragment 的 onCreateView 中使用 view.findViewById 查找视图，
 * 悬浮时通过 LecoFloatManager 管理覆盖窗口容器。
 */
public class BaseFloatFragment extends Fragment {
}
