package io.github.escosis.vivothemehelper;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Install before target hash verification so saved module classes can be abandoned safely on an unsupported update. */
final class RestoreGuard {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static volatile boolean uiReady;
    private RestoreGuard() {}
    static boolean install() {
        if (INSTALLED.get()) return true;
        try {
            XposedBridge.hookMethod(HostApi.exact(Fragment.class, "instantiate", Context.class, String.class, Bundle.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String name = (String) param.args[1];
                    boolean owned = ImportFragment.class.getName().equals(name);
                    if (!owned && !AbandonedSessionFragment.class.getName().equals(name)) return;
                    Context context = (Context) param.args[0];
                    if (!ModuleInfo.TARGET_PACKAGE.equals(context.getPackageName())) return;
                    Fragment result = owned && uiReady ? new ImportFragment() : new AbandonedSessionFragment();
                    Bundle args = (Bundle) param.args[2];
                    if (args != null) result.setArguments(args);
                    param.setResult(result);
                }
            });
            INSTALLED.set(true);
            return true;
        } catch (Throwable failure) { ModuleLog.error("仅模块自身类的恢复保护未安装", failure); return false; }
    }
    static void enableUi() { uiReady = true; }
}
