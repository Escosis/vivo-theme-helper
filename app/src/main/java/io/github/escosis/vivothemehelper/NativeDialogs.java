package io.github.escosis.vivothemehelper;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** The host owns every dialog and its ROM-dependent rendering; this class never constructs one. */
final class NativeDialogs {
    private static final String TOOLS = "com.bbk.theme.os.app.VDialogToolUtils";
    private static final Class<?>[] NONE = new Class<?>[0];
    private static final int LOADING_DESCRIPTION_ID = 0x7f0a02d6;
    private static final int LOADING_PROGRESS_ID = 0x7f0a02d7;
    private static final int LOADING_GROUP_ID = 0x7f0a0a71;
    private static volatile HostApi host;

    private NativeDialogs() {}

    static void bind(HostApi api) {
        if (api == null) throw new IllegalArgumentException("HostApi is null");
        host = api;
    }

    static HostDialog dialog(Context context) throws Exception {
        requireUi();
        if (context == null) throw new IllegalArgumentException("Dialog context is null");
        HostApi api = host;
        if (api == null) throw new IllegalStateException("NativeDialogs is not bound");
        Class<?> toolsClass = api.clazz(TOOLS);
        Method factory = fluent(toolsClass, "newInstance", NONE);
        if (!Modifier.isStatic(factory.getModifiers()))
            throw new NoSuchMethodException(TOOLS + "#newInstance must be static");
        Object tools = HostApi.invoke(factory, null);
        if (tools == null || !toolsClass.isInstance(tools))
            throw new IllegalStateException("Native dialog factory returned no tools");
        Object built = HostApi.invoke(fluent(toolsClass, "buildVigourDialogBuilder",
                boolean.class, Context.class, int.class), tools, true, context, -1);
        if (built != tools) throw new IllegalStateException("Unexpected native builder identity");
        return new HostDialog(toolsClass, tools);
    }

    static void toast(final Context context, final String text) {
        UiHooks.onUi(new Runnable() {
            @Override public void run() {
                HostApi api = host;
                if (api == null) {
                    ModuleLog.log("宿主 toast 尚未绑定：" + text);
                    return;
                }
                try {
                    api.callStatic("com.bbk.theme.utils.mj", "showToast",
                            new Class<?>[]{Context.class, String.class}, context, text);
                } catch (Throwable error) {
                    ModuleLog.error("宿主 mj.showToast 不可用：" + text, error);
                }
            }
        });
    }

    static final class HostDialog {
        private final Class<?> toolsClass;
        private final Object tools;
        // Only the object returned by the host's create()/getDialog() may be used here.
        private Dialog existingDialog;
        private Activity owner;
        private boolean createAttempted;
        private boolean showAttempted;
        private boolean dismissed;
        private TextView loadingDescription;
        private View loadingGroup;
        private View loadingProgress;

        private HostDialog(Class<?> toolsClass, Object tools) {
            this.toolsClass = toolsClass;
            this.tools = tools;
        }

        HostDialog title(String text) {
            requireUi();
            if (existingDialog != null) existingDialog.setTitle(text);
            else { requireDraft(); call("setTitle", new Class<?>[]{String.class}, text); }
            return this;
        }

        HostDialog message(String text) {
            updateMessage(text);
            return this;
        }

        HostDialog items(CharSequence[] values, DialogInterface.OnClickListener listener) {
            requireDraft();
            call("setItems", new Class<?>[]{CharSequence[].class, DialogInterface.OnClickListener.class},
                    values, listener);
            return this;
        }

        HostDialog positive(CharSequence text, DialogInterface.OnClickListener listener) {
            requireDraft();
            call("setPositiveButton", new Class<?>[]{CharSequence.class, DialogInterface.OnClickListener.class},
                    text, listener);
            return this;
        }

        HostDialog negative(CharSequence text, DialogInterface.OnClickListener listener) {
            requireDraft();
            call("setNegativeButton", new Class<?>[]{CharSequence.class, DialogInterface.OnClickListener.class},
                    text, listener);
            return this;
        }

        HostDialog cancelable(boolean value) {
            requireUi();
            if (existingDialog != null) {
                existingDialog.setCancelable(value);
                existingDialog.setCanceledOnTouchOutside(value);
            } else {
                requireDraft();
                call("setCancelable", new Class<?>[]{boolean.class}, value);
            }
            return this;
        }

        HostDialog onCancel(DialogInterface.OnCancelListener listener) {
            requireUi();
            if (existingDialog != null) existingDialog.setOnCancelListener(listener);
            else {
                requireDraft();
                call("setOnCancelListener", new Class<?>[]{DialogInterface.OnCancelListener.class}, listener);
            }
            return this;
        }

        HostDialog onDismiss(DialogInterface.OnDismissListener listener) {
            requireUi();
            if (existingDialog != null) existingDialog.setOnDismissListener(listener);
            else {
                requireDraft();
                call("setOnDismissListener", new Class<?>[]{DialogInterface.OnDismissListener.class}, listener);
            }
            return this;
        }

        HostDialog owner(Activity activity) {
            requireUi();
            owner = activity;
            if (existingDialog != null && activity != null) existingDialog.setOwnerActivity(activity);
            return this;
        }

        HostDialog loading(String text) {
            requireUi();
            if (loadingDescription != null) {
                setLoadingMessage(text);
                return this;
            }
            requireDraft();
            call("setVigourLoadingLayout", new Class<?>[]{String.class}, text);
            try {
                // b.F inflates this very view immediately, attaches it to b.d, and b.B supplies
                // that ScrollView to create(). Repeating setVigourLoadingLayout would add a NEW
                // hidden/duplicate layout, not update the one already on the screen.
                Object builder = HookInit.findField(toolsClass, "mVigourDialogBuilder").get(tools);
                Object nativeBuilder = HookInit.findField(builder.getClass(), "a").get(builder);
                Object label = HookInit.findField(nativeBuilder.getClass(), "g").get(nativeBuilder);
                Object scroll = HookInit.findField(nativeBuilder.getClass(), "d").get(nativeBuilder);
                if (!(label instanceof TextView) || !(scroll instanceof View))
                    throw new IllegalStateException("Native loading layout/view is missing");
                loadingDescription = (TextView) label;
                View tree = (View) scroll;
                if (tree.findViewById(LOADING_DESCRIPTION_ID) != loadingDescription)
                    throw new IllegalStateException("Native loading description identity changed");
                loadingGroup = tree.findViewById(LOADING_GROUP_ID);
                loadingProgress = tree.findViewById(LOADING_PROGRESS_ID);
                if (loadingGroup == null || loadingProgress == null)
                    throw new IllegalStateException("Native loading accessibility views are missing");
                setLoadingMessage(text);
            } catch (Exception error) {
                throw failure("setVigourLoadingLayout/native b.g,b.d", error);
            }
            return this;
        }

        HostDialog show() {
            requireUi();
            if (dismissed || showAttempted) return this;
            if (createAttempted) throw new IllegalStateException("A native create() attempt already failed");
            createAttempted = true;
            try {
                call("create", NONE);
                Method getter = HostApi.exact(toolsClass, "getDialog");
                if (getter.getReturnType() != Dialog.class)
                    throw new NoSuchMethodException(TOOLS + "#getDialog must return Dialog");
                Object nativeObject = HostApi.invoke(getter, tools);
                if (!(nativeObject instanceof Dialog))
                    throw new IllegalStateException("Native create() returned no dialog");
                existingDialog = (Dialog) nativeObject;
                // getDialog() is null in the builder phase: owner belongs HERE, not to dialog().
                if (owner != null) existingDialog.setOwnerActivity(owner);
                showAttempted = true;
                call("show", NONE);
                if (loadingDescription != null
                        && existingDialog.findViewById(LOADING_DESCRIPTION_ID) != loadingDescription)
                    throw new IllegalStateException("Shown native loading view differs from b.g");
                // No private onCancel/onDismiss listener is installed over the caller's listener.
                return this;
            } catch (Exception error) {
                dismissed = true;
                if (existingDialog != null) {
                    try { call("dismiss", NONE); }
                    catch (RuntimeException cleanup) { ModuleLog.error("清理宿主显示失败窗口", cleanup); }
                }
                throw failure("create/getDialog/owner/show", error);
            }
        }

        void dismiss() {
            requireUi();
            if (dismissed) return;
            dismissed = true;
            if (existingDialog != null) call("dismiss", NONE);
        }

        boolean isShowing() {
            requireUi();
            if (existingDialog == null || dismissed) return false;
            try {
                Method method = HostApi.exact(toolsClass, "isShowing");
                if (method.getReturnType() != boolean.class)
                    throw new NoSuchMethodException(TOOLS + "#isShowing must return boolean");
                return (Boolean) HostApi.invoke(method, tools);
            } catch (Exception error) { throw failure("isShowing", error); }
        }

        void updateMessage(String text) {
            requireUi();
            if (dismissed) return;
            if (loadingDescription != null) {
                setLoadingMessage(text);
            } else if (!createAttempted) {
                call("setMessage", new Class<?>[]{String.class}, text);
            } else {
                if (existingDialog == null) throw new IllegalStateException("Native create() failed");
                try {
                    // VDialogToolUtils.setMessage only mutates builder state. In contrast the
                    // created host VDialog.setMessage(CharSequence) updates both the model and TextView.
                    // The host may itself choose an older ROM Dialog subclass; only its existing
                    // object's exact live setter is used, never a separately constructed dialog.
                    Method live = HostApi.exact(existingDialog.getClass(), "setMessage", CharSequence.class);
                    if (live.getReturnType() != void.class)
                        throw new NoSuchMethodException("Native live setMessage must return void");
                    HostApi.invoke(live, existingDialog, (CharSequence) text);
                } catch (Exception error) { throw failure("existingDialog#setMessage(CharSequence)", error); }
            }
        }

        private void setLoadingMessage(String text) {
            String value = text == null ? "" : text;
            loadingDescription.setText(value);
            boolean hasText = value.length() != 0;
            loadingGroup.setImportantForAccessibility(hasText
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            loadingProgress.setImportantForAccessibility(hasText
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            loadingGroup.setContentDescription(hasText ? value : null);
        }

        private void requireDraft() {
            requireUi();
            if (createAttempted || dismissed)
                throw new IllegalStateException("Configure native builder before show()");
        }

        private void call(String name, Class<?>[] types, Object... values) {
            try {
                Object result = HostApi.invoke(fluent(toolsClass, name, types), tools, values);
                if (result != tools) throw new IllegalStateException("Native fluent identity: " + name);
            } catch (Exception error) { throw failure(name, error); }
        }
    }

    private static Method fluent(Class<?> owner, String name, Class<?>... types) throws Exception {
        Method method = HostApi.exact(owner, name, types);
        if (method.getReturnType() != owner)
            throw new NoSuchMethodException(owner.getName() + "#" + name + " must return host tools");
        return method;
    }

    private static IllegalStateException failure(String operation, Exception cause) {
        return new IllegalStateException(TOOLS + "#" + operation + " failed", cause);
    }

    private static void requireUi() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Native dialogs must run on the host UI thread");
    }
}
