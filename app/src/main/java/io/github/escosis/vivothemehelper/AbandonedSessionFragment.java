package io.github.escosis.vivothemehelper;

import android.app.Fragment;
import android.os.Bundle;

/** Inert restoration rescue: no HostApi, background work, file deletion or result replay. */
@SuppressWarnings("deprecation")
public final class AbandonedSessionFragment extends Fragment {
    public AbandonedSessionFragment() {}
    @Override public void onCreate(Bundle saved) { super.onCreate(saved); setRetainInstance(false); }
    @Override public void onResume() {
        super.onResume();
        if (getActivity() != null) NativeDialogs.toast(getActivity(),
                "vivo主题版本/模块状态已变化，旧导入导出会话不再执行。请先检查此前结果，再重新选择。");
        try {
            if (isAdded() && getFragmentManager() != null && !getFragmentManager().isStateSaved())
                getFragmentManager().beginTransaction().remove(this).commit();
        } catch (Throwable failure) { ModuleLog.error("旧会话恢复提示等待下一次页面恢复", failure); }
    }
}
