package io.github.escosis.vivothemehelper;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 24.2.8.0 only. Exact ABI, isolated feature registration and no pretend server authorization. */
public final class HookInit implements IXposedHookLoadPackage {
    private final AtomicBoolean attached = new AtomicBoolean();

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!ModuleInfo.TARGET_PACKAGE.equals(lp.packageName)) return;
        RestoreGuard.install(); // inert owned-class recovery is safe even if target version/hash is later rejected
        Method attach = HostApi.exact(Application.class, "attach", Context.class);
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                if (!ModuleInfo.TARGET_PACKAGE.equals(context.getPackageName())) return;
                if (!attached.compareAndSet(false, true)) return;
                try {
                    String mismatch = Compatibility.verify(context, true);
                    if (mismatch.length() != 0) {
                        ModuleLog.log("拒绝安装 Hook：" + mismatch);
                        notice(context, mismatch);
                        return;
                    }
                    HostApi api = new HostApi(context, lp.classLoader);
                    NativeDialogs.bind(api);
                    ModuleLog.log("accepted " + ModuleInfo.RELEASE + ", process=" + lp.processName);
                    installCore(api);
                    boolean keyless = KeylessSupport.install(api);
                    // PL_2 parity: these are precise client decisions, not fabricated server responses or cryptographic keys.
                    installBoolean(api, "com.bbk.theme.pay.payment.f", "themeHasPayed", true,
                            Context.class, String.class, int.class);
                    installBoolean(api, "com.bbk.theme.rich.payment.entry.GetAuthorizeEntry", "authorizeSuccess", true);
                    boolean downloads = keyless && DownloadCompat.install(api);
                    ModuleLog.log("PL_2客户端判断已启用；受限完整空key下载链注册=" + downloads);
                    boolean mainProcess = lp.appInfo != null && lp.processName.equals(lp.appInfo.processName);
                    if (mainProcess) UiHooks.install(api, keyless);
                    ModuleLog.log("feature registration finished (not a runtime import/apply test)");
                } catch (Throwable error) {
                    ModuleLog.error("模块初始化失败，未接管该进程", error);
                }
            }
        });
    }

    private static void installCore(final HostApi api) {
        installBoolean(api, "com.bbk.theme.task.MemberInformationQuery$MemberData", "isActivated", true);
        installBoolean(api, "com.bbk.theme.task.MemberInformationQuery$MemberData", "isValid", true);
        installBoolean(api, "com.bbk.theme.utils.ThemeUtils", "isTryuseRes", false, String.class);
        installBoolean(api, "c5.a", "showSplashIfNeed", true);
        try {
            Method method = HostApi.exact(api.clazz("c5.a"), "showVivoAdView",
                    api.clazz("com.bbk.theme.splash.SplashInfo"), boolean.class);
            requireConcrete(method, void.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) { param.setResult(null); }
            });
            ModuleLog.log("before skip b5.a#showVivoAdView");
        } catch (Throwable error) { ModuleLog.error("开屏SDK入口未安装", error); }
        installWholeTheme(api);
        installUpdate(api);
    }

    private static void installBoolean(HostApi api, String owner, String name, final boolean value, Class<?>... parameters) {
        try {
            Method method = HostApi.exact(api.clazz(owner), name, parameters);
            requireConcrete(method, boolean.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) { param.setResult(value); }
            });
            ModuleLog.log("before " + owner + "#" + name + "=" + value);
        } catch (Throwable error) { ModuleLog.error("独立功能未安装：" + owner + "#" + name, error); }
    }

    private static void installWholeTheme(final HostApi api) {
        try {
            Method method = HostApi.exact(api.clazz("com.bbk.theme.apply.ApplyThemeUtils"), "isWholeTheme", api.itemClass);
            requireConcrete(method, boolean.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || Boolean.TRUE.equals(param.getResult()) || param.args[0] == null) return;
                    try {
                        Object item = param.args[0];
                        if (api.integer(item, "getCategory") != 1 || !api.bool(item, "getFlagDownload")) return;
                        String style = api.text(item, "getThemeStyle");
                        if (style.length() != 0 && !"whole".equals(style)) return; // preserve newer non-whole styles
                        String path = api.text(item, "getFilePath");
                        if (path.length() == 0) return;
                        File file = new File(path);
                        if (!file.isFile()) return;
                        try (ZipFile zip = new ZipFile(file)) {
                            ZipEntry core = zip.getEntry("vivo");
                            if (core != null && !core.isDirectory() && core.getSize() > 0) param.setResult(true);
                        }
                    } catch (Throwable error) { ModuleLog.error("整主题兜底失败，保留原结果", error); }
                }
            });
            ModuleLog.log("guarded isWholeTheme(ThemeItem) only; ()/boolean overloads unchanged");
        } catch (Throwable error) { ModuleLog.error("整主题兜底未安装", error); }
    }

    private static void installUpdate(final HostApi api) {
        try {
            final Class<?> infoClass = api.clazz("com.vivo.upgradelibrary.upmode.appdialog.AppUpdateInfo");
            Method method = HostApi.exact(api.clazz("com.vivo.upgradelibrary.common.upgraderequest.app.c"), "request", infoClass);
            requireConcrete(method, infoClass);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object info = param.args[0];
                        if (info == null) {
                            try {
                                Field bridge = findField(api.clazz("com.vivo.upgradelibrary.common.modulebridge.h"), "a");
                                Object factory = bridge.get(null);
                                if (factory != null) info = HostApi.invoke(HostApi.exact(factory.getClass(), "e"), factory);
                            } catch (Throwable failure) { ModuleLog.error("升级info工厂失败，将尝试具体类型", failure); }
                        }
                        if (info == null) {
                            Class<?> concrete = api.clazz("com.vivo.upgradelibrary.normal.NormalAppUpdateInfo");
                            if (Modifier.isAbstract(concrete.getModifiers())) throw new IllegalStateException("Concrete update info became abstract");
                            info = concrete.getDeclaredConstructor().newInstance();
                            try { HostApi.invoke(HostApi.exact(concrete, "setPkgName", String.class), info, ModuleInfo.TARGET_PACKAGE); }
                            catch (NoSuchMethodException ignored) { }
                        }
                        if (!infoClass.isInstance(info)) throw new IllegalStateException("Wrong update info type");
                        setField(info, "stat", int.class, 200);
                        setField(info, "needUpdate", boolean.class, false);
                        setField(info, "willShowDialog", boolean.class, false);
                        param.setResult(info);
                    } catch (Throwable failure) {
                        // Native request itself can return null on network failure. Never run its network body here.
                        ModuleLog.error("升级拦截返回空结果，不执行原网络请求", failure);
                        param.setResult(null);
                    }
                }
            });
            ModuleLog.log("typed update request intercepted; input/factory/concrete info, not new abstract base");
        } catch (Throwable error) { ModuleLog.error("升级拦截未安装", error); }
    }

    private static void requireConcrete(Method method, Class<?> returnType) throws NoSuchMethodException {
        if (Modifier.isAbstract(method.getModifiers()) || method.getReturnType() != returnType)
            throw new NoSuchMethodException("Not the exact concrete target: " + method);
    }

    static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
        Class<?> current = owner;
        while (current != null) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        throw new NoSuchFieldException(owner.getName() + "#" + name);
    }

    private static void setField(Object receiver, String name, Class<?> type, Object value) throws Exception {
        Field field = findField(receiver.getClass(), name);
        if (field.getType() != type) throw new NoSuchFieldException("Wrong field type: " + name);
        field.set(receiver, value);
    }

    private static void notice(final Context context, final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { NativeDialogs.toast(context, "vivo主题增强：" + message); }
        });
    }
}
