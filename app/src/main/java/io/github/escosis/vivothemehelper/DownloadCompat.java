package io.github.escosis.vivothemehelper;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Three hooks only: native completion scope, the actual outer JSON result, and addKey's false gate.
 * No network/transfer/rename/wrapper hooks, no module retries, no fabricated key or I/O result.
 */
final class DownloadCompat {
    private static final String FRAME = "io.github.escosis.vivothemehelper.download.scope";
    private static final String ADD = "io.github.escosis.vivothemehelper.download.add";
    private static volatile Installation installed;
    private DownloadCompat() {}

    static synchronized boolean install(HostApi api) {
        if (api == null || !KeylessSupport.installedFor(api)) return false;
        if (installed != null) return installed.enabled && installed.api.loader == api.loader;
        Installation candidate = null;
        try {
            PackageInfo info = api.context.getPackageManager().getPackageInfo(ModuleInfo.TARGET_PACKAGE, 0);
            if (!ModuleInfo.TARGET_PACKAGE.equals(api.context.getPackageName())
                    || !DownloadEligibility.versionAllowed(info.versionName, info.getLongVersionCode()))
                throw new IllegalStateException("Download host version/package mismatch");
            candidate = new Installation(api);
            candidate.hook();
            candidate.enabled = true;
            installed = candidate;
            ModuleLog.log("download compat installed: 3 hooks; no module retries; native transfer/rename/parser results retained");
            return true;
        } catch (Throwable error) {
            if (candidate != null) candidate.rollback();
            ModuleLog.error("下载兼容未启用，保留原生流程", error);
            return false;
        }
    }

    /** Claimed once by the existing seven-argument parser hook, not by another h()/copy hook. */
    static ParseTicket claimParse(HostApi api, Object[] args) throws Exception {
        Installation owner = installed;
        if (owner == null || !owner.enabled || owner.api.loader != api.loader
                || !DownloadEligibility.downloadFlags(args)) return null;
        Scope scope = owner.current();
        if (scope == null || !scope.continued || scope.claimed || scope.source == null) return null;
        scope.claimed = true;
        Snapshot current = owner.snapshot(scope.input);
        if (!scope.input.matches(current, false) || !current.path.equals(args[0])
                || !Integer.valueOf(current.type).equals(args[1])
                || !MissingKeyProbe.isFileUnderRoot(new File(current.path), new File(api.savePath(current.type)))) return null;
        return new ParseTicket(owner, scope, current.path);
    }

    static final class ParseTicket {
        final Installation owner;
        final Scope scope;
        final String path;
        final String packageId;
        final String resId;
        private ParseTicket(Installation owner, Scope scope, String path) {
            this.owner = owner; this.scope = scope; this.path = path;
            packageId = scope.input.meta[0]; resId = scope.input.meta[1];
        }
        boolean active() {
            if (owner.current() != scope || !scope.continued) return false;
            try {
                Snapshot current = owner.snapshot(scope.input);
                return scope.input.matches(current, false) && path.equals(current.path);
            } catch (Exception error) { scope.disabled = true; return false; }
        }
        boolean matchesProbe(MissingKeyProbe.Result probe) throws Exception {
            return active() && scope.source.matches(probe) && probe.stamp.matches(new File(path));
        }
    }

    private static final class Scope {
        Snapshot input;
        DownloadEligibility.Source source;
        boolean disabled, addActive, jsonEmpty, continued, claimed;
        int adds, jsonCalls;
    }

    private static final class Snapshot {
        final Context context;
        final Object extras, result;
        final int type, status, deleted;
        final long id;
        final boolean success;
        final String path;
        final String[] meta; // packageId, resId, native name, edition, original extras.
        Snapshot(Context context, Object extras, Object result, int type, int status, int deleted, long id,
                 boolean success, String path, String[] meta) {
            this.context = context; this.extras = extras; this.result = result; this.type = type;
            this.status = status; this.deleted = deleted; this.id = id; this.success = success;
            this.path = path; this.meta = meta;
        }
        boolean valid() {
            if (!DownloadEligibility.completed(type, id, success, status, deleted, path)
                    || !ModuleInfo.TARGET_PACKAGE.equals(context.getPackageName())) return false;
            for (String value : meta) if (value != null && value.length() > 8192) return false;
            return meta[0] != null && !meta[0].trim().isEmpty() && meta[0].length() <= 256
                    && meta[1] != null && !meta[1].trim().isEmpty() && meta[1].length() <= 256;
        }
        boolean matches(Snapshot other, boolean samePath) {
            return other != null && valid() && other.valid() && context == other.context
                    && extras == other.extras && result == other.result && type == other.type && id == other.id
                    && status == other.status && deleted == other.deleted
                    && Arrays.equals(meta, other.meta) && (!samePath || path.equals(other.path));
        }
        boolean keyArgs(Object[] args) {
            return args != null && args.length == 5 && context == args[0] && path.equals(args[1])
                    && Integer.valueOf(type).equals(args[2]) && meta[0].equals(args[3]) && Integer.valueOf(2).equals(args[4]);
        }
    }

    private static final class Installation {
        final HostApi api;
        final Class<?> extrasClass, resultClass;
        final Method deal, addKey, json, storageInstance, downloadPath;
        final Field[] metadata = new Field[5];
        final Field success, id, status, deleted, path;
        final ThreadLocal<ArrayDeque<Scope>> scopes = new ThreadLocal<ArrayDeque<Scope>>();
        final List<XC_MethodHook.Unhook> unhooks = new ArrayList<XC_MethodHook.Unhook>();
        volatile boolean enabled;

        Installation(HostApi api) throws Exception {
            this.api = api;
            extrasClass = api.clazz("de.a"); resultClass = api.clazz("de.b");
            deal = method(api.clazz("com.bbk.theme.utils.i2"), "d", void.class,
                    int.class, int.class, int.class, Context.class, extrasClass, resultClass);
            Class<?> key = api.clazz("com.bbk.theme.rich.payment.utils.j0");
            addKey = method(key, "addKeyToZip", boolean.class, Context.class, String.class, int.class, String.class, int.class);
            json = method(key, "getAuthorizeJson", String.class, Context.class, String.class, int.class, int.class);
            Class<?> storage = api.clazz(HostApi.STORAGE_CLASS);
            storageInstance = method(storage, "getInstance", storage);
            downloadPath = HostApi.exact(storage, "getResDownloadPath", int.class);
            if (Modifier.isStatic(downloadPath.getModifiers()) || downloadPath.getReturnType() != String.class)
                throw new NoSuchMethodException("Native download root ABI");
            String[] names = {"a", "e", "b", "c", "d"};
            for (int i = 0; i < names.length; i++) metadata[i] = field(extrasClass, names[i], String.class);
            success = field(resultClass, "a", boolean.class); id = field(resultClass, "b", long.class);
            status = field(resultClass, "c", int.class); deleted = field(resultClass, "e", int.class);
            path = field(resultClass, "f", String.class);
        }

        void hook() {
            unhooks.add(XposedBridge.hookMethod(deal, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Scope scope = new Scope();
                    ArrayDeque<Scope> stack = scopes.get();
                    if (stack == null) { stack = new ArrayDeque<Scope>(); scopes.set(stack); }
                    stack.push(scope); param.setObjectExtra(FRAME, scope); // Rejected calls remain nesting barriers.
                    try {
                        Object[] a = param.args;
                        if (!enabled || stack.size() > 16 || a == null || a.length != 6 || !(a[0] instanceof Integer)
                                || !(a[3] instanceof Context) || !extrasClass.isInstance(a[4])
                                || !resultClass.isInstance(a[5])) { scope.disabled = true; return; }
                        // a[1]/a[2] are native AIGC style/source; leave both untouched.
                        scope.input = snapshot((Context) a[3], a[4], a[5], (Integer) a[0]);
                        scope.disabled = !scope.input.valid(); // No full ZIP/HTTP work before original authorization.
                    } catch (Throwable error) { scope.disabled = true; ModuleLog.error("下载完成状态读取失败", error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Scope scope = (Scope) param.getObjectExtra(FRAME);
                    ArrayDeque<Scope> stack = scopes.get();
                    try {
                        if (scope != null && scope.input != null && scope.adds > 0)
                            ModuleLog.log("download final: type=" + scope.input.type
                                    + ", nativeSuccess=" + success.getBoolean(scope.input.result)
                                    + ", keylessTicket=" + scope.continued + ", parserClaimed=" + scope.claimed);
                    } catch (Throwable ignored) { /* Diagnostics must not change the native result or cleanup. */ }
                    if (scope != null) scope.disabled = true;
                    if (stack == null || stack.peek() != scope) scopes.remove();
                    else { stack.pop(); if (stack.isEmpty()) scopes.remove(); }
                }
            }));
            unhooks.add(XposedBridge.hookMethod(json, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Scope scope = current();
                    if (scope == null || !scope.addActive) return;
                    Object[] a = param.args;
                    if (a == null || a.length != 4 || a[0] != scope.input.context || !scope.input.meta[0].equals(a[1])
                            || !Integer.valueOf(scope.input.type).equals(a[2]) || !Integer.valueOf(2).equals(a[3])) return;
                    scope.jsonCalls++;
                    Object value = param.getResult();
                    scope.jsonEmpty = !param.hasThrowable() && (value == null || "".equals(value));
                }
            }));
            unhooks.add(XposedBridge.hookMethod(addKey, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Scope scope = current();
                    if (scope == null) return;
                    if (scope.addActive) { scope.disabled = true; return; }
                    if (!scope.input.keyArgs(param.args)) return;
                    if (++scope.adds != 1) { scope.disabled = true; return; }
                    scope.addActive = true; scope.jsonCalls = 0; scope.jsonEmpty = false;
                    param.setObjectExtra(ADD, scope);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Scope scope = (Scope) param.getObjectExtra(ADD);
                    if (scope == null) return;
                    try {
                        boolean emptyFailure = Boolean.FALSE.equals(param.getResult()) && scope.jsonCalls == 1 && scope.jsonEmpty;
                        boolean cachedSuccess = Boolean.TRUE.equals(param.getResult()) && scope.jsonCalls == 0;
                        if (current() != scope || param.hasThrowable() || (!emptyFailure && !cachedSuccess)
                                || !scope.input.keyArgs(param.args) || !scope.input.matches(snapshot(scope.input), true)) return;
                        if (Boolean.TRUE.equals(api.callStatic("com.bbk.theme.utils.ThemeUtils", "isOverseas", new Class<?>[0]))
                                || Boolean.TRUE.equals(api.callStatic("com.bbk.theme.utils.DeviceTypeUtils", "isOverseas", new Class<?>[0]))
                                || Boolean.TRUE.equals(api.callStatic("com.bbk.theme.utils.i2", "isOverseasLiveWallpaper",
                                        new Class<?>[]{int.class}, scope.input.type))) return;
                        File file = new File(scope.input.path);
                        File save = new File(api.savePath(scope.input.type));
                        File root = MissingKeyProbe.isFileUnderRoot(file, save) ? save
                                : new File((String) HostApi.invoke(downloadPath, HostApi.invoke(storageInstance, null), scope.input.type));
                        DownloadEligibility.Source source = DownloadEligibility.Source.capture(file, scope.input.type, root);
                        // Cached empty key files take the native true/no-getter branch. They still need a parser ticket.
                        if (source == null || (cachedSuccess && !"zero-byte key".equals(source.proof.reason))
                                || !identity(scope.input, KeylessSupport.probeDownloadBasic(api, file, scope.input.type))
                                || !identity(scope.input, api.callStatic("com.bbk.theme.base.ResDbUtils", "queryThemeItemByDownloadId",
                                        new Class<?>[]{Context.class, long.class}, scope.input.context, scope.input.id))
                                || !DownloadEligibility.targetsWithinRoots(source, root, save, scope.input.meta[2])
                                || !source.proof.stamp.matches(file) || current() != scope
                                || !scope.input.matches(snapshot(scope.input), true)) return;
                        File renamed = file.getName().endsWith("_temp.itz")
                                ? new File(file.getParentFile(), scope.input.meta[2] + ".itz") : file;
                        File destination = new File(save, renamed.getName());
                        File[] targets = renamed.getAbsolutePath().equals(destination.getAbsolutePath())
                                ? new File[]{renamed} : new File[]{renamed, destination};
                        for (File target : targets) {
                            if (target.exists() && !target.getAbsolutePath().equals(file.getAbsolutePath())
                                    && !identity(scope.input, KeylessSupport.probeDownloadBasic(api, target, scope.input.type))) return;
                        }
                        if (!source.proof.stamp.matches(file) || current() != scope
                                || !scope.input.matches(snapshot(scope.input), true)) return;
                        ModuleLog.log((cachedSuccess ? "download cached-zero-key ticket" : "download empty-material hand-off")
                                + ": type=" + scope.input.type + "; no module retries");
                        scope.source = source; scope.continued = true;
                        if (emptyFailure) param.setResult(Boolean.TRUE); // Preserve an original true; only the false-empty gate is changed.
                    } catch (Throwable error) { scope.disabled = true; ModuleLog.error("空材料下载兼容未放行，保留原结果", error); }
                    finally { scope.addActive = false; }
                }
            }));
        }

        Scope current() {
            ArrayDeque<Scope> stack = scopes.get();
            Scope scope = stack == null ? null : stack.peek();
            return enabled && scope != null && !scope.disabled && scope.input != null
                    && !Thread.currentThread().isInterrupted() ? scope : null;
        }
        Snapshot snapshot(Snapshot input) throws Exception {
            return snapshot(input.context, input.extras, input.result, input.type);
        }
        Snapshot snapshot(Context context, Object extras, Object result, int type) throws Exception {
            String[] info = new String[metadata.length];
            for (int i = 0; i < info.length; i++) info[i] = (String) metadata[i].get(extras);
            return new Snapshot(context, extras, result, type, status.getInt(result), deleted.getInt(result), id.getLong(result),
                    success.getBoolean(result), (String) path.get(result), info);
        }
        boolean identity(Snapshot input, Object item) throws Exception {
            if (!api.itemClass.isInstance(item) || api.integer(item, "getCategory") != input.type
                    || !input.meta[0].equals(api.text(item, "getPackageId")) || !input.meta[1].equals(api.text(item, "getResId"))
                    || api.bool(item, "isAiPet") || api.bool(item, "isAiFont")) return false;
            String live = input.type == 2 ? api.text(item, "getLWPackageType") : "";
            return live.length() == 0 || "mp4".equals(live);
        }
        void rollback() {
            enabled = false;
            for (int i = unhooks.size() - 1; i >= 0; i--) {
                try { unhooks.get(i).unhook(); } catch (Throwable error) { ModuleLog.error("下载Hook撤回失败", error); }
            }
        }
    }

    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args) throws Exception {
        Method method = HostApi.exact(owner, name, args);
        if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers()) || method.getReturnType() != result)
            throw new NoSuchMethodException("Native static ABI: " + owner.getName() + "#" + name);
        return method;
    }
    private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers()) || !Modifier.isPublic(field.getModifiers()))
            throw new NoSuchFieldException("Native DTO ABI: " + owner.getName() + "#" + name);
        field.setAccessible(true); return field;
    }
}
