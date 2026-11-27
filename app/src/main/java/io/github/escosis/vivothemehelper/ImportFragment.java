package io.github.escosis.vivothemehelper;

import android.app.Activity;
import android.app.Fragment;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owned native Fragment; arguments survive picker/process recreation and retained jobs survive rotation. */
@SuppressWarnings("deprecation")
public final class ImportFragment extends Fragment {
    private volatile String state = "new";
    private volatile boolean cancel;
    private volatile Thread worker;
    private volatile boolean scheduled;
    private volatile CancellationSignal exportSignal;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private NativeDialogs.HostDialog progress;
    private NativeDialogs.HostDialog decisionDialog;
    private volatile Decision decision;
    private String progressText = "正在处理…";

    public ImportFragment() {}

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setRetainInstance(true);
        Bundle args = getArguments();
        if (args == null || !ModuleInfo.hasType(args.getInt("type", 0)) || args.getLong("sessionId", 0) <= 0) {
            state = "interrupted";
            return;
        }
        state = args.getString("state", "new");
        if ("finished".equals(state)) { terminal.set(true); return; } // a fresh finished restoration never releases a current session
        if (SessionState.shouldAdopt(state) && !ImportCoordinator.adoptRestored(session())) {
            state = "interrupted";
            args.putString("state", state);
        }
        // A worker cannot survive process death; never restart a commit from an old serialized state.
        if (SessionState.lostWorker(state, worker != null)) {
            state = "interrupted";
            args.putString("state", state);
        }
    }

    @Override public void onSaveInstanceState(Bundle output) {
        super.onSaveInstanceState(output);
        output.putString("vt24Release", ModuleInfo.RELEASE);
        output.putString("vt24State", state);
    }

    @Override public void onResume() {
        super.onResume();
        if ("interrupted".equals(state)) {
            String message = "上次工作已随进程结束；不会自动重复提交。请先检查资源是否已入库，再重新选择。";
            if (getArguments() != null && getArguments().getBoolean("documentSelected", false))
                message += "\n导出位置可能已有完整或不完整文档，请检查所选位置。";
            fail(message, null);
            return;
        }
        if (terminal.get() || "finished".equals(state)) { removeIfPossible(); return; }
        if ("working".equals(state) || "preparing".equals(state) || "cancelling".equals(state)) showProgress();
        if (decision != null) showDecision();
    }

    @Override public void onDetach() {
        dismissWindows();
        Activity activity = getActivity();
        if (activity == null || !activity.isChangingConfigurations()) requestCancel();
        super.onDetach();
    }

    void openImportPicker() {
        try {
            Activity activity = getActivity();
            if (!ImportCoordinator.usable(activity) || !isAdded()) throw new IllegalStateException("Host window unavailable");
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*"); // every original category accepts ITZ; do not hide ZIP behind a video/font-only MIME filter
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            setState("picker");
            startActivityForResult(intent, ModuleInfo.PICK_REQUEST);
        } catch (Throwable error) { fail("无法打开系统文件选择器", error); }
    }

    void prepareExport() {
        setState("preparing");
        progressText = "正在创建导出快照…";
        showProgress();
        ImportCoordinator.execute(this, new Runnable() {
            @Override public void run() {
                try {
                    final Context context = ImportCoordinator.api().context;
                    Bundle args = getArguments();
                    int type = args.getInt("type");
                    File source = new File(args.getString("source", "")).getCanonicalFile();
                    File allowed = new File(ImportCoordinator.api().savePath(type)).getCanonicalFile();
                    if (!source.isFile() || !source.canRead() || !UiHooks.isInside(allowed, source))
                        throw new IllegalArgumentException("只能导出当前用户主题保存目录内的单个资源");
                    long size = source.length(), modified = source.lastModified();
                    if (size <= 0 || size > 1024L * 1024 * 1024) throw new IllegalArgumentException("资源为空或超过1GiB导出上限");
                    final File folder = new File(context.getCacheDir(), "dsh_v24_export_" + UUID.randomUUID());
                    if (!folder.mkdir()) throw new IllegalStateException("无法创建导出缓存");
                    UiHooks.onUi(new Runnable() { @Override public void run() { getArguments().putString("snapshotRoot", folder.getAbsolutePath()); } });
                    File snapshot = new File(folder, "snapshot.bin");
                    try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(snapshot)) {
                        copy(input, output, 1024L * 1024 * 1024);
                        output.getFD().sync();
                    }
                    if (source.length() != size || source.lastModified() != modified || snapshot.length() != size)
                        throw new IllegalStateException("原文件在复制期间发生变化，请重试");
                    checkCancel();
                    final String filename = exportName(source, getArguments().getString("title", ""));
                    UiHooks.onUi(new Runnable() {
                        @Override public void run() {
                            if (cancelled()) { finishCancelled(); return; }
                            getArguments().putString("snapshotRoot", folder.getAbsolutePath());
                            getArguments().putString("filename", filename);
                            openExportPicker();
                        }
                    });
                } catch (Throwable error) { fail("创建导出快照失败", error); }
            }
        });
    }

    private void openExportPicker() {
        try {
            dismissProgress();
            if (!isAdded() || !ImportCoordinator.usable(getActivity())) throw new IllegalStateException("Host window unavailable");
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, getArguments().getString("filename", "theme.itz"));
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            setState("export_picker");
            startActivityForResult(intent, ModuleInfo.EXPORT_REQUEST);
        } catch (Throwable error) { fail("无法选择导出位置", error); }
    }

    boolean expects(int request) {
        return !terminal.get() && SessionState.picker(state, request);
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        if (expects(request)) acceptResult(request, result, data);
    }

    void acceptResult(int request, int result, Intent data) {
        if (!expects(request)) return;
        ImportCoordinator.markHandled(getActivity(), request);
        if (result != Activity.RESULT_OK || data == null) { finishCancelled(); return; }
        Uri uri = data.getData();
        ClipData clips = data.getClipData();
        if (uri == null && clips != null && clips.getItemCount() == 1) uri = clips.getItemAt(0).getUri();
        if (uri == null || !"content".equals(uri.getScheme())) { fail("选择器没有返回可授权的文档URI", null); return; }
        final Uri selected = uri;
        if (request == ModuleInfo.EXPORT_REQUEST) getArguments().putBoolean("documentSelected", true);
        setState("working");
        if (request == ModuleInfo.PICK_REQUEST) {
            progressText = "正在复制并验证资源…";
            showProgress();
            ImportCoordinator.execute(this, new Runnable() {
                @Override public void run() {
                    new ImportEngine(ImportCoordinator.api()).run(ImportCoordinator.api().context, selected,
                            getArguments().getInt("type"), new ImportEngine.Observer() {
                        @Override public void progress(final String message) {
                            UiHooks.onUi(new Runnable() { @Override public void run() {
                                progressText = message;
                                if (progress != null) progress.updateMessage(message);
                            } });
                        }
                        @Override public boolean confirmIdRewrite(String message) throws Exception { return confirm(message); }
                        @Override public void success(ImportEngine.ImportResult value) {
                            String text = (cancelled() ? "已导入（取消请求晚于提交）" : "导入成功") + "：" + value.name;
                            if (value.warning != null && value.warning.length() != 0) text += "\n" + value.warning;
                            finish(text, false);
                        }
                        @Override public void failure(String message, Throwable error) { fail(message, error); }
                    });
                }
            });
        } else {
            progressText = "正在写入所选位置…";
            showProgress();
            ImportCoordinator.execute(this, new Runnable() {
                @Override public void run() {
                    try {
                        File snapshot = snapshotFile();
                        if (snapshot == null || !snapshot.isFile()) throw new IllegalStateException("导出快照不存在，请重新导出");
                        SafExport.write(ImportCoordinator.api().context, selected, snapshot, new SafExport.Control() {
                            @Override public boolean cancelled() { return ImportFragment.this.cancelled(); }
                            @Override public void signal(CancellationSignal value) {
                                exportSignal = value;
                                if (value != null && cancel) value.cancel();
                            }
                        });
                        finish("导出完成，已保存到系统选择的位置", false);
                    } catch (Throwable error) { fail("导出写入失败（所选位置可能有不完整副本）", error); }
                }
            });
        }
    }

    private boolean confirm(String message) throws Exception {
        checkCancel();
        final Decision gate = new Decision(message);
        decision = gate;
        UiHooks.onUi(new Runnable() { @Override public void run() { dismissProgress(); showDecision(); } });
        try {
            if (!gate.latch.await(180, TimeUnit.SECONDS)) { gate.answer(false); return false; }
            checkCancel();
            return gate.accepted;
        } finally {
            decision = null;
            UiHooks.onUi(new Runnable() { @Override public void run() {
                if (decisionDialog != null) { decisionDialog.dismiss(); decisionDialog = null; }
                if (!terminal.get() && !cancelled()) showProgress();
            } });
        }
    }

    private void showDecision() {
        final Decision gate = decision;
        if (gate == null || decisionDialog != null || !isResumed() || !ImportCoordinator.usable(getActivity())) return;
        try {
            decisionDialog = NativeDialogs.dialog(getActivity()).title("资源ID已存在")
                    .message(gate.message).owner(getActivity())
                    .positive("生成新ID导入", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) { gate.answer(true); decisionDialog = null; }
                    }).negative("取消", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) { gate.answer(false); decisionDialog = null; }
                    }).onCancel(new DialogInterface.OnCancelListener() {
                        @Override public void onCancel(DialogInterface dialog) { gate.answer(false); decisionDialog = null; }
                    }).show();
        } catch (Throwable failure) {
            decisionDialog = null;
            gate.answer(false);
            ModuleLog.error("确认窗口不可用，取消ID改写", failure);
        }
    }

    private void showProgress() {
        if (!isResumed() || terminal.get() || decision != null || !ImportCoordinator.usable(getActivity())) return;
        if (progress != null && progress.isShowing()) { progress.updateMessage(progressText); return; }
        progress = null;
        try {
            progress = NativeDialogs.dialog(getActivity())
                    .title("export".equals(getArguments().getString("mode")) ? "导出资源" : "本地导入")
                    .loading(progressText).cancelable(false).owner(getActivity())
                    .negative("取消", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) { requestCancel(); }
                    }).show();
        } catch (Throwable failure) {
            progress = null;
            ModuleLog.error("进度窗口暂不可用，工作仍在后台", failure);
        }
    }

    long session() { return getArguments() == null ? 0 : getArguments().getLong("sessionId", 0); }
    void workerScheduled() { scheduled = true; }
    void workerStarted(Thread thread) { worker = thread; scheduled = false; if (cancel) thread.interrupt(); }
    void workerStopped() {
        scheduled = false;
        worker = null;
        if (cancel && !terminal.get()) finishCancelled();
    }
    boolean cancelled() { return cancel; }
    private void checkCancel() throws InterruptedException {
        if (cancel || Thread.currentThread().isInterrupted()) throw new InterruptedException("Operation cancelled");
    }
    private void requestCancel() {
        if (terminal.get()) return;
        cancel = true;
        Decision gate = decision;
        if (gate != null) gate.answer(false);
        final CancellationSignal signal = exportSignal;
        if (signal != null) {
            Thread cancellation = new Thread(new Runnable() { @Override public void run() { signal.cancel(); } }, "VivoTheme24-Cancel");
            cancellation.setDaemon(true);
            cancellation.start(); // a remote provider cancellation Binder must not block the UI thread
        }
        Thread thread = worker;
        if (thread != null) { thread.interrupt(); setState("cancelling"); progressText = "正在取消并清理…"; }
        else if (!scheduled) finishCancelled();
    }
    void finishCancelled() {
        boolean documentSelected = getArguments() != null && "export".equals(getArguments().getString("mode"))
                && ("working".equals(state) || "cancelling".equals(state));
        finish("操作已取消" + (documentSelected ? "\n所选位置可能已创建或留下不完整副本，请检查；模块不会删除不明文档。" : ""), false);
    }
    void fail(String message, Throwable error) {
        if (error != null) ModuleLog.error(message, error);
        String text = ImportDiagnostics.failureMessage(message, error);
        if (cancel || error instanceof InterruptedException || error instanceof CancellationException) {
            // Preserve backend retained-file/unknown-query/partial-document warnings instead of promising total rollback.
            finish("已收到取消请求\n" + text, false);
            return;
        }
        finish(text, true);
    }

    private void finish(final String message, final boolean error) {
        if (!terminal.compareAndSet(false, true)) return;
        Decision gate = decision;
        if (gate != null) gate.answer(false);
        UiHooks.onUi(new Runnable() {
            @Override public void run() {
                setState("finished");
                dismissWindows();
                cleanupSnapshot();
                ImportCoordinator.release(session());
                Context context = ImportCoordinator.api().context;
                UiHooks.toast(context, message);
                if (error && ImportCoordinator.usable(getActivity())) {
                    try { NativeDialogs.dialog(getActivity()).title("操作未完成").message(message).positive("知道了", null).owner(getActivity()).show(); }
                    catch (Throwable failure) { ModuleLog.error("错误窗口已离开，保留Toast/日志", failure); }
                }
                removeIfPossible();
            }
        });
    }

    private void setState(final String value) {
        state = value;
        UiHooks.onUi(new Runnable() { @Override public void run() {
            if (getArguments() != null) getArguments().putString("state", value);
        } });
    }
    private void removeIfPossible() {
        try {
            if (isAdded() && getFragmentManager() != null && !getFragmentManager().isStateSaved())
                getFragmentManager().beginTransaction().remove(this).commit();
        } catch (Throwable failure) { ModuleLog.error("清理无界面Fragment失败，将在恢复时重试", failure); }
    }
    private void dismissProgress() {
        if (progress != null) {
            try { progress.dismiss(); } catch (Throwable failure) { ModuleLog.error("进度窗口已销毁", failure); }
            progress = null;
        }
    }
    private void dismissWindows() {
        dismissProgress();
        if (decisionDialog != null) {
            try { decisionDialog.dismiss(); } catch (Throwable failure) { ModuleLog.error("确认窗口已销毁", failure); }
            decisionDialog = null;
        }
    }

    private File snapshotFile() throws Exception {
        Bundle args = getArguments();
        if (args == null) return null;
        String root = args.getString("snapshotRoot", "");
        if (root.length() == 0) return null;
        File folder = new File(root);
        File cache = ImportCoordinator.api().context.getCacheDir().getCanonicalFile();
        if (!folder.getName().matches("dsh_v24_export_[0-9a-f-]{36}")
                || !folder.getCanonicalFile().getParentFile().equals(cache))
            throw new IllegalStateException("Invalid owned snapshot directory");
        return new File(folder.getCanonicalFile(), "snapshot.bin");
    }
    private void cleanupSnapshot() {
        try {
            File file = snapshotFile();
            if (file != null) {
                if (file.exists() && !file.delete()) ModuleLog.log("导出快照暂未清理，系统缓存回收时再处理");
                File folder = file.getParentFile();
                if (folder.exists() && !folder.delete()) ModuleLog.log("导出缓存目录仍有内容，未递归删除");
            }
        } catch (Throwable failure) { ModuleLog.error("快照边界检查/清理失败，不删除其它文件", failure); }
    }
    private void copy(InputStream input, OutputStream output, long limit) throws Exception {
        byte[] buffer = new byte[65536]; long total = 0; int n;
        while ((n = input.read(buffer)) != -1) {
            checkCancel();
            if (n == 0) continue;
            total += n;
            if (total > limit) throw new IllegalArgumentException("文件超过允许上限");
            output.write(buffer, 0, n);
        }
    }
    private static String exportName(File source, String title) {
        String original = source.getName();
        int dot = original.lastIndexOf('.');
        String extension = dot >= 0 ? original.substring(dot) : ".bin";
        String clean = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.length() > 100) clean = clean.substring(0, 100);
        if (clean.length() == 0) clean = dot > 0 ? original.substring(0, dot) : "theme";
        return clean + extension;
    }
    private static final class Decision {
        final String message; final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean done = new AtomicBoolean(); volatile boolean accepted;
        Decision(String message) { this.message = message; }
        void answer(boolean value) { if (done.compareAndSet(false, true)) { accepted = value; latch.countDown(); } }
    }
}
