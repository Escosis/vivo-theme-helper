package io.github.escosis.vivothemehelper;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** One owned session from dialog/picker through commit; never guesses a host request code. */
final class ImportCoordinator {
    private static final AtomicLong ACTIVE = new AtomicLong();
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable action) { return new Thread(action, "VivoTheme24-IO"); }
    });
    private static final Map<Activity, Handled> HANDLED = new WeakHashMap<Activity, Handled>();
    private static final Map<Activity, TopSelection> TOP_DIALOGS = new WeakHashMap<Activity, TopSelection>();
    private static HostApi host;
    private ImportCoordinator() {}

    static boolean install(final HostApi api) {
        host = api;
        NativeDialogs.bind(api);
        try {
            if (!RestoreGuard.install()) return false;
            Method result = HostApi.exact(api.clazz("com.bbk.theme.Theme"), "onActivityResult", int.class, int.class, Intent.class);
            XposedBridge.hookMethod(result, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    if (ownedResult((Activity) param.thisObject, (Integer) param.args[0],
                            (Integer) param.args[1], (Intent) param.args[2])) param.setResult(null);
                }
            });
            XposedBridge.hookMethod(HostApi.exact(Activity.class, "onDestroy"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    TopSelection selection;
                    synchronized (TOP_DIALOGS) { selection = TOP_DIALOGS.remove((Activity) param.thisObject); }
                    if (selection == null) return; // never affects another Activity or another dialog
                    NativeDialogs.HostDialog dialog = selection.dialog.get();
                    if (dialog != null) {
                        try { dialog.dismiss(); } catch (Throwable error) { ModuleLog.error("清理本模块选择窗口失败", error); }
                    }
                    if (!selection.handedOff.get()) release(selection.session);
                }
            });
            RestoreGuard.enableUi();
            return true;
        } catch (Throwable error) { ModuleLog.error("仅模块所有的Fragment/结果路由安装失败", error); return false; }
    }

    static HostApi api() {
        if (host == null) throw new IllegalStateException("Module not attached to host");
        return host;
    }

    static void showTypePicker(final Activity activity) {
        if (!usable(activity)) return;
        final long session = acquire();
        if (session == 0) { UiHooks.toast(activity, "已有导入/导出会话，请完成或取消后再试"); return; }
        try {
            final AtomicBoolean handedOff = new AtomicBoolean();
            NativeDialogs.HostDialog dialog = NativeDialogs.dialog(activity).title("选择导入类型")
                    .items(ModuleInfo.TYPE_NAMES, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            if (which < 0 || which >= ModuleInfo.TYPE_VALUES.length) { release(session); return; }
                            ImportFragment fragment = new ImportFragment();
                            Bundle args = new Bundle();
                            args.putLong("sessionId", session);
                            args.putString("mode", "import");
                            args.putInt("type", ModuleInfo.TYPE_VALUES[which]);
                            args.putString("state", "new");
                            fragment.setArguments(args);
                            if (add(activity, fragment)) {
                                handedOff.set(true);
                                clearTop(activity, session);
                                fragment.openImportPicker();
                            }
                        }
                    }).negative("取消", null);
            showTop(activity, dialog, handedOff, session);
        } catch (Throwable error) { clearTop(activity, session); release(session); ModuleLog.error("选择器对话框失败", error); UiHooks.toast(activity, "无法打开导入对话框"); }
    }

    static void startExport(final Activity activity, final File source, final String title, final int type) {
        if (!usable(activity)) return;
        final long session = acquire();
        if (session == 0) { UiHooks.toast(activity, "已有导入/导出会话，请完成或取消后再试"); return; }
        try {
            final AtomicBoolean handedOff = new AtomicBoolean();
            NativeDialogs.HostDialog dialog = NativeDialogs.dialog(activity).title("导出当前资源")
                    .message("先创建只读内容快照，再由系统文件选择器决定保存位置；不直接覆盖公共下载目录。")
                    .positive("继续", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            ImportFragment fragment = new ImportFragment();
                            Bundle args = new Bundle();
                            args.putLong("sessionId", session);
                            args.putString("mode", "export");
                            args.putInt("type", type);
                            args.putString("source", source.getAbsolutePath());
                            args.putString("title", title == null ? "" : title);
                            args.putString("state", "new");
                            fragment.setArguments(args);
                            if (add(activity, fragment)) {
                                handedOff.set(true);
                                clearTop(activity, session);
                                fragment.prepareExport();
                            }
                        }
                    }).negative("取消", null);
            showTop(activity, dialog, handedOff, session);
        } catch (Throwable error) { clearTop(activity, session); release(session); ModuleLog.error("导出对话框失败", error); }
    }

    private static void showTop(final Activity activity, final NativeDialogs.HostDialog dialog, final AtomicBoolean handedOff, final long session) {
        dialog.owner(activity);
        synchronized (TOP_DIALOGS) { TOP_DIALOGS.put(activity, new TopSelection(dialog, handedOff, session)); }
        dialog.onDismiss(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface ignored) {
                clearTop(activity, session);
                if (!handedOff.get()) release(session);
            }
        });
        dialog.onCancel(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface ignored) { if (!handedOff.get()) release(session); }
        });
        dialog.show();
    }

    private static void clearTop(Activity activity, long session) {
        synchronized (TOP_DIALOGS) {
            TopSelection selection = TOP_DIALOGS.get(activity);
            if (selection != null && selection.session == session) TOP_DIALOGS.remove(activity);
        }
    }

    private static boolean add(Activity activity, ImportFragment fragment) {
        try {
            FragmentManager manager = activity.getFragmentManager();
            if (manager.isStateSaved()) throw new IllegalStateException("Window state is already saved");
            Fragment old = manager.findFragmentByTag(ModuleInfo.FRAGMENT_TAG);
            if (old != null) throw new IllegalStateException("A restored session is still attached");
            synchronized (HANDLED) { HANDLED.remove(activity); }
            manager.beginTransaction().add(fragment, ModuleInfo.FRAGMENT_TAG).commit();
            manager.executePendingTransactions();
            if (!fragment.isAdded()) throw new IllegalStateException("Session did not attach");
            return true;
        } catch (Throwable error) {
            release(fragment.session()); ModuleLog.error("导入/导出宿主已离开或状态无法提交", error);
            UiHooks.toast(activity, "当前页面已保存/切换，请回到主题页面重试"); return false;
        }
    }

    static void execute(final ImportFragment fragment, final Runnable action) {
        fragment.workerScheduled();
        try {
            WORKER.execute(new Runnable() {
                @Override public void run() {
                    fragment.workerStarted(Thread.currentThread());
                    try {
                        if (fragment.cancelled()) { fragment.finishCancelled(); return; }
                        action.run();
                    } catch (Throwable error) { fragment.fail("操作失败", error); }
                    finally { fragment.workerStopped(); }
                }
            });
        } catch (Throwable failure) {
            fragment.workerStopped();
            fragment.fail("无法启动后台工作", failure);
        }
    }

    static void markHandled(Activity activity, int request) {
        if (activity != null) synchronized (HANDLED) { HANDLED.put(activity, new Handled(request, SystemClock.elapsedRealtime())); }
    }

    private static boolean ownedResult(Activity activity, int request, int result, Intent data) {
        if (request != ModuleInfo.PICK_REQUEST && request != ModuleInfo.EXPORT_REQUEST) return false;
        try {
            Fragment fragment = activity.getFragmentManager().findFragmentByTag(ModuleInfo.FRAGMENT_TAG);
            if (fragment instanceof ImportFragment && ((ImportFragment) fragment).expects(request)) {
                ((ImportFragment) fragment).acceptResult(request, result, data);
                return true;
            }
            synchronized (HANDLED) {
                Handled handled = HANDLED.get(activity);
                if (handled != null && handled.request == request && SystemClock.elapsedRealtime() - handled.when < 3000)
                    return true; // some vendor dispatchers may also call Activity after the owned native result
            }
        } catch (Throwable error) { ModuleLog.error("模块结果后备路由失败", error); }
        return false;
    }

    private static long acquire() {
        long session;
        do { session = SESSION_RANDOM.nextLong() & Long.MAX_VALUE; } while (session == 0);
        return ACTIVE.compareAndSet(0, session) ? session : 0;
    }
    static boolean adoptRestored(long session) {
        if (session <= 0) return false;
        long active = ACTIVE.get();
        return active == session || (active == 0 && ACTIVE.compareAndSet(0, session));
    }
    static void release(long session) { if (session > 0) ACTIVE.compareAndSet(session, 0); }
    static boolean usable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }
    private static final class TopSelection {
        final WeakReference<NativeDialogs.HostDialog> dialog;
        final AtomicBoolean handedOff;
        final long session;
        TopSelection(NativeDialogs.HostDialog dialog, AtomicBoolean handedOff, long session) {
            this.dialog = new WeakReference<NativeDialogs.HostDialog>(dialog);
            this.handedOff = handedOff;
            this.session = session;
        }
    }
    private static final class Handled {
        final int request; final long when;
        Handled(int request, long when) { this.request = request; this.when = when; }
    }
}
