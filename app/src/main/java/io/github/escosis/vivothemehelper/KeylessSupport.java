package io.github.escosis.vivothemehelper;

import android.content.pm.PackageInfo;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 24.2.8.0-only, domestic keyless ITZ compatibility: local FTFFF or leased download FFFFT.
 * No parser short-circuit, core=false, File.delete hook, digest override or entitlement fabrication.
 * Real nonempty keys and all original parser exceptions/failure results remain native.
 */
final class KeylessSupport {
    private static final String SCOPE_EXTRA = "io.github.escosis.vivothemehelper.keyless.scope";
    private static final int MAX_ACTIVE_DEPTH = 32;
    private static final ThreadLocal<ArrayDeque<LocalParsePermit>> LOCAL_PERMITS =
            new ThreadLocal<ArrayDeque<LocalParsePermit>>();
    private static volatile Installation installed;

    private KeylessSupport() { }

    /** Installs as one rollback-able unit; parent must also retain its exact APK/version gate. */
    static synchronized boolean install(HostApi api) {
        if (api == null) return false;
        if (installed != null) return installed.api.loader == api.loader && installed.enabled;
        Installation candidate = null;
        try {
            PackageInfo info = api.context.getPackageManager().getPackageInfo(ModuleInfo.TARGET_PACKAGE, 0);
            if (!ModuleInfo.TARGET_PACKAGE.equals(api.context.getPackageName())
                    || !ModuleInfo.TARGET_VERSION.equals(info.versionName)
                    || info.getLongVersionCode() != ModuleInfo.TARGET_CODE)
                throw new IllegalStateException("Keyless host version/package mismatch");
            candidate = new Installation(api);
            candidate.installHooks();
            candidate.enabled = true;
            installed = candidate;
            safeLog("keyless installed: domestic native empty-rule pipeline; local FTFFF + explicitly leased download FFFFT; real nonempty keys remain native");
            return true;
        } catch (Throwable error) {
            if (candidate != null) candidate.rollback();
            safeError("keyless installation disabled; original host behavior retained", error);
            return false;
        }
    }

    static boolean installedFor(HostApi api) {
        Installation owner = installed;
        return api != null && owner != null && owner.enabled && owner.api.loader == api.loader
                && owner.api.context == api.context;
    }

    /** Read native k(path,type,false,false) without accidentally feeding an enclosing parser observer. */
    static Object probeDownloadBasic(HostApi api, File path, int type) throws Exception {
        Installation owner = installed;
        if (!installedFor(api)) throw new IllegalStateException("Keyless observer isolation is unavailable");
        Scope barrier = new Scope();
        barrier.disabled = true;
        ArrayDeque<Scope> stack = owner.scopes.get();
        if (stack == null) { stack = new ArrayDeque<Scope>(); owner.scopes.set(stack); }
        if (stack.size() >= MAX_ACTIVE_DEPTH) throw new IllegalStateException("Basic identity probe nesting limit");
        stack.push(barrier);
        try { return api.parseBasic(path, type, false, false); }
        finally {
            if (stack.peek() != barrier) {
                owner.invalidateFrames();
                throw new IllegalStateException("Native basic identity probe scope nesting mismatch");
            }
            stack.pop();
            if (stack.isEmpty()) owner.scopes.remove();
        }
    }

    /** Only this thread's next exact FTFFF parse of this cache file/type may use the permit. */
    static LocalParsePermit allowLocalParse(File path, int resType) throws Exception {
        Installation owner = installed;
        if (owner == null || !owner.enabled) return new LocalParsePermit(null, null, resType);
        if (!MissingKeyProbe.knownType(resType) || path == null || !path.getName().endsWith(".itz")
                || !MissingKeyProbe.isFileUnderRoot(path, owner.cacheRoot))
            throw new IOException("Local parse permit requires a regular ITZ inside the host cache root");
        LocalParsePermit permit = new LocalParsePermit(owner, path.getAbsolutePath(), resType);
        ArrayDeque<LocalParsePermit> permits = LOCAL_PERMITS.get();
        if (permits == null) {
            permits = new ArrayDeque<LocalParsePermit>();
            LOCAL_PERMITS.set(permits);
        }
        if (permits.size() >= MAX_ACTIVE_DEPTH) throw new IllegalStateException("Local permit nesting limit");
        permits.push(permit);
        return permit;
    }

    static final class LocalParsePermit implements AutoCloseable {
        final Installation installation;
        final String path;
        final int type;
        final Thread thread = Thread.currentThread();
        volatile boolean invalidated;
        boolean claimed;
        boolean closed;

        private LocalParsePermit(Installation installation, String path, int type) {
            this.installation = installation;
            this.path = path;
            this.type = type;
        }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != thread) {
                invalidated = true;
                throw new IllegalStateException("Local parse permit must close on its creating thread");
            }
            closed = true;
            invalidated = true;
            if (installation == null) return;
            ArrayDeque<LocalParsePermit> permits = LOCAL_PERMITS.get();
            if (permits == null || permits.peek() != this) {
                if (permits != null) for (LocalParsePermit permit : permits) permit.invalidated = true;
                LOCAL_PERMITS.remove();
                installation.invalidateFrames();
                throw new IllegalStateException("Local parse permits closed out of nesting order");
            }
            permits.pop();
            if (permits.isEmpty()) LOCAL_PERMITS.remove();
        }
    }

    private static final class Scope {
        boolean eligible;
        boolean disabled;
        String rawPath;
        File path;
        File allowedRoot;
        String source;
        int type;
        MissingKeyProbe.FileStamp initial;
        DownloadCompat.ParseTicket download;
        Object item;
        String initialStyle;
        boolean basicSeen;
        boolean xmlSeen;
        boolean coreSeen;
        boolean ruleSeen;
        boolean synthetic;
        String xmlHash;
        String coreHash;
        MissingKeyProbe.Result probe;
    }

    private static final class Installation {
        final HostApi api;
        final File cacheRoot;
        final Class<?> ruleClass;
        final Constructor<?> ruleConstructor;
        final Method parser;
        final Method basic;
        final Method rule;
        final Method xmlHash;
        final Method coreHash;
        final Method overseas;
        final Method getCategory;
        final Method getPackageId;
        final Method getResId;
        final Method getStyle;
        final Method setStyle;
        final Method isAiPet;
        final Method isAiFont;
        final Method getDigest;
        final Method getDigest2;
        final Method getDigest3;
        final Method[] itemMetadata;
        final Method[] ruleMetadata;
        final List<XC_MethodHook.Unhook> unhooks = new ArrayList<XC_MethodHook.Unhook>();
        final ThreadLocal<ArrayDeque<Scope>> scopes = new ThreadLocal<ArrayDeque<Scope>>();
        volatile boolean enabled;

        Installation(HostApi api) throws Exception {
            this.api = api;
            if (api.parserClass != api.clazz(HostApi.PARSER_CLASS)
                    || api.itemClass != api.clazz("com.bbk.theme.common.ThemeItem"))
                throw new NoSuchMethodException("Unexpected host class identity");
            File cache = api.context.getCacheDir();
            if (cache == null) throw new IOException("Host cache directory unavailable");
            // Match ImportEngine's canonical staging path, not a different Context alias.
            cacheRoot = cache.getCanonicalFile();
            ruleClass = api.clazz("com.bbk.theme.rich.payment.entry.RuleEntry");
            if (ruleClass.isInterface() || Modifier.isAbstract(ruleClass.getModifiers()))
                throw new NoSuchMethodException("Native RuleEntry is not concrete");
            ruleConstructor = ruleClass.getConstructor();
            parser = exact(api.parserClass, "parseResItzFile", api.itemClass, false, String.class,
                    int.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
            basic = exact(api.parserClass, "k", api.itemClass, true, String.class, int.class,
                    boolean.class, boolean.class);
            rule = exact(api.parserClass, "getRuleEntry", ruleClass, true, String.class);
            xmlHash = exact(api.parserClass, "d", String.class, true,
                    String.class, String.class, String.class);
            coreHash = exact(api.parserClass, "g", String.class, true, int.class, String.class, boolean.class);
            overseas = exact(api.clazz("com.bbk.theme.utils.ThemeUtils"), "isOverseas", boolean.class, true);
            getCategory = exact(api.itemClass, "getCategory", int.class, false);
            getPackageId = exact(api.itemClass, "getPackageId", String.class, false);
            getResId = exact(api.itemClass, "getResId", String.class, false);
            getStyle = exact(api.itemClass, "getThemeStyle", String.class, false);
            setStyle = exact(api.itemClass, "setThemeStyle", void.class, false, String.class);
            isAiPet = exact(api.itemClass, "isAiPet", boolean.class, false);
            isAiFont = exact(api.itemClass, "isAiFont", boolean.class, false);
            getDigest = exact(ruleClass, "getDigest", String.class, false);
            getDigest2 = exact(ruleClass, "getDigest2", String.class, false);
            getDigest3 = exact(ruleClass, "getDigest3", LinkedHashMap.class, false);
            String[] getters = {"getPackageId", "getResId", "getName", "getPrice", "getOpenId",
                    "getRight", "getGooglePrice", "getLocalSymbol", "getGooglePriceStr"};
            String[] setters = {"setId", "setUid", "setName", "setPrice", "setOpenId",
                    "setRight", "setGooglePrice", "setGoogleCurrencySymbol", "setGooglePriceStr"};
            itemMetadata = new Method[getters.length];
            ruleMetadata = new Method[setters.length];
            for (int i = 0; i < getters.length; i++) {
                itemMetadata[i] = exact(api.itemClass, getters[i], String.class, false);
                ruleMetadata[i] = exact(ruleClass, setters[i], void.class, false, String.class);
            }
            verifyEmptyDefaults(ruleConstructor.newInstance());
        }

        void installHooks() {
            // Passive observers first; enabled remains false until the entire installation succeeds.
            unhooks.add(XposedBridge.hookMethod(basic, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Scope frame = current();
                    if (!matches(frame, param, 0, 1) || param.args.length != 4
                            || !Boolean.FALSE.equals(param.args[2]) || !Boolean.FALSE.equals(param.args[3])) return;
                    try {
                        if (param.hasThrowable() || frame.basicSeen || !api.itemClass.isInstance(param.getResult())) {
                            frame.disabled = true;
                            return;
                        }
                        Object item = param.getResult();
                        if (((Integer) HostApi.invoke(getCategory, item)).intValue() != frame.type
                                || empty((String) HostApi.invoke(getPackageId, item))
                                || empty((String) HostApi.invoke(getResId, item))
                                || Boolean.TRUE.equals(HostApi.invoke(isAiPet, item))
                                || Boolean.TRUE.equals(HostApi.invoke(isAiFont, item))) {
                            frame.disabled = true;
                            return;
                        }
                        if (frame.download != null && (!frame.download.active()
                                || !frame.download.packageId.equals(HostApi.invoke(getPackageId, item))
                                || !frame.download.resId.equals(HostApi.invoke(getResId, item)))) {
                            frame.disabled = true;
                            return;
                        }
                        frame.item = item;
                        frame.initialStyle = (String) HostApi.invoke(getStyle, item);
                        frame.basicSeen = true;
                    } catch (Throwable error) { fail("keyless basic observer failed", error); }
                }
            }));
            unhooks.add(XposedBridge.hookMethod(xmlHash, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Scope frame = current();
                    if (!matchesPath(frame, param.args, 0) || param.args.length != 3
                            || !"description.xml".equals(param.args[1])) return;
                    try {
                        boolean sha = frame.type == 18 || frame.type == 20;
                        if (param.hasThrowable() || frame.xmlSeen
                                || !(sha ? "SHA-256" : "").equals(param.args[2])
                                || !hexHash(param.getResult(), sha ? 64 : 32)) {
                            frame.disabled = true;
                            return;
                        }
                        frame.xmlHash = (String) param.getResult();
                        frame.xmlSeen = true;
                    } catch (Throwable error) { fail("keyless XML hash observer failed", error); }
                }
            }));
            unhooks.add(XposedBridge.hookMethod(coreHash, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Scope frame = current();
                    if (!matches(frame, param, 1, 0) || param.args.length != 3) return;
                    try {
                        Object result = param.getResult();
                        if (param.hasThrowable() || frame.coreSeen || !Boolean.FALSE.equals(param.args[2])
                                || !(result instanceof String)
                                || (!"".equals(result) && !hexHash(result,
                                        frame.type == 18 || frame.type == 20 ? 64 : 32))) {
                            frame.disabled = true;
                            return;
                        }
                        frame.coreHash = (String) result;
                        frame.coreSeen = true;
                    } catch (Throwable error) { fail("keyless core hash observer failed", error); }
                }
            }));
            unhooks.add(XposedBridge.hookMethod(rule, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Scope frame = current();
                    if (!matchesPath(frame, param.args, 0)) return;
                    try {
                        if (param.hasThrowable() || frame.ruleSeen) return;
                        frame.ruleSeen = true;
                        if (param.getResult() != null) return;
                        if (!frame.basicSeen || !frame.xmlSeen || !frame.coreSeen
                                || Boolean.TRUE.equals(HostApi.invoke(overseas, null))) return;
                        if (!frame.initial.matches(frame.path)
                                || !MissingKeyProbe.isFileUnderRoot(frame.path, frame.allowedRoot)) return;
                        MissingKeyProbe.Result probe = frame.download == null
                                ? MissingKeyProbe.inspect(frame.path, frame.type)
                                : MissingKeyProbe.inspectForDownload(frame.path, frame.type);
                        if (!probe.eligible || !frame.initial.matches(frame.path)
                                || !probe.stamp.matches(frame.path)
                                || (frame.download != null && (!frame.download.active()
                                        || !frame.download.matchesProbe(probe)))) return;
                        String expectedXml = frame.type == 18 || frame.type == 20
                                ? probe.xmlSha256 : probe.xmlMd5;
                        if (!expectedXml.equalsIgnoreCase(frame.xmlHash)
                                || !probe.nativeCoreHash.equalsIgnoreCase(frame.coreHash)
                                || (probe.requiresCoreHash && !hexHash(frame.coreHash,
                                        probe.sha256Core ? 64 : 32))) return;
                        Object nativeRule = ruleConstructor.newInstance();
                        verifyEmptyDefaults(nativeRule);
                        // Preserve native model values exactly, including null. Never default price/openid/right.
                        for (int i = 0; i < itemMetadata.length; i++) {
                            Object value = HostApi.invoke(itemMetadata[i], frame.item);
                            HostApi.invoke(ruleMetadata[i], nativeRule, value);
                        }
                        if (Thread.currentThread().isInterrupted() || !probe.stamp.matches(frame.path)
                                || (frame.download != null && !frame.download.active())) return;
                        frame.probe = probe;
                        param.setResult(nativeRule); // Only actual null getRuleEntry result is replaced.
                        frame.synthetic = true;
                    } catch (Throwable error) { fail("keyless rule probe declined; native failure retained", error); }
                }
            }));
            unhooks.add(XposedBridge.hookMethod(parser, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Scope frame = new Scope();
                        ArrayDeque<Scope> stack = scopes.get();
                        if (stack == null) { stack = new ArrayDeque<Scope>(); scopes.set(stack); }
                        stack.push(frame); // Every invocation, including rejected ones, is a nesting barrier.
                        param.setObjectExtra(SCOPE_EXTRA, frame);
                        if (stack.size() <= MAX_ACTIVE_DEPTH) prepare(frame, param.args);
                    } catch (Throwable error) { fail("keyless parser scope disabled", error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Scope frame = null;
                    try {
                        frame = (Scope) param.getObjectExtra(SCOPE_EXTRA);
                        if (frame != null && !frame.disabled && frame.synthetic && !param.hasThrowable()
                                && param.getResult() == frame.item && frame.probe != null
                                && frame.probe.stamp.matches(frame.path)
                                && (frame.download == null || (frame.download.active()
                                        && frame.download.matchesProbe(frame.probe)))) {
                            // Native empty-digest branch does not infer whole. Restore only the old guarded fallback.
                            if (frame.type == 1 && frame.probe.wholePayload && empty(frame.initialStyle)
                                    && empty((String) HostApi.invoke(getStyle, frame.item)))
                                HostApi.invoke(setStyle, frame.item, "whole");
                            safeLog("keyless native parse accepted: type=" + frame.type + ", source="
                                    + frame.source + ", " + frame.probe.reason);
                        }
                        // Never revive null, replace a Throwable or return parseBasic() as a fake parser success.
                    } catch (Throwable error) { safeError("keyless post-parse annotation skipped", error); }
                    finally {
                        ArrayDeque<Scope> stack = scopes.get();
                        if (stack == null || stack.peek() != frame) {
                            invalidateFrames();
                        } else {
                            stack.pop();
                            if (stack.isEmpty()) scopes.remove();
                        }
                    }
                }
            }));
        }

        private void prepare(Scope frame, Object[] args) throws Exception {
            if (!enabled || args == null || args.length != 7 || !(args[0] instanceof String)
                    || !(args[1] instanceof Integer) || Thread.currentThread().isInterrupted()) return;
            int type = ((Integer) args[1]).intValue();
            String path = (String) args[0];
            if (!MissingKeyProbe.knownType(type) || !path.endsWith(".itz")) return;
            File file = new File(path);
            ArrayDeque<LocalParsePermit> permits = LOCAL_PERMITS.get();
            LocalParsePermit permit = permits == null ? null : permits.peek();
            File root;
            String source;
            if (DownloadEligibility.downloadFlags(args)) {
                // The same native completion must still be successful. Copy/rename are not hooked.
                if (permit != null) return;
                DownloadCompat.ParseTicket download = DownloadCompat.claimParse(api, args);
                if (download == null || !download.active()) return;
                root = new File(api.savePath(type));
                source = "completed-download";
                frame.download = download;
            } else {
                if (!DownloadEligibility.localFlags(args)) return;
                if (permit != null) {
                    // A mismatching/used nested permit may NOT fall back to broad native-root authorization.
                    if (permit.installation != this || permit.invalidated || permit.closed || permit.claimed
                            || permit.type != type || !permit.path.equals(path)) return;
                    permit.claimed = true;
                    root = cacheRoot;
                    source = "module-cache";
                } else {
                    root = new File(api.savePath(type)); // Current user/volume-native decision, not hardcoded /data.
                    source = "host-save";
                }
            }
            if (!MissingKeyProbe.isFileUnderRoot(file, root)
                    || Boolean.TRUE.equals(HostApi.invoke(overseas, null))) return;
            frame.type = type;
            frame.rawPath = path;
            frame.path = file;
            frame.allowedRoot = root;
            frame.source = source;
            frame.initial = MissingKeyProbe.FileStamp.capture(file);
            frame.eligible = true;
        }

        private void verifyEmptyDefaults(Object object) throws Exception {
            if (!ruleClass.isInstance(object) || !"".equals(HostApi.invoke(getDigest, object))
                    || !"".equals(HostApi.invoke(getDigest2, object))
                    || HostApi.invoke(getDigest3, object) != null)
                throw new IllegalStateException("Native RuleEntry empty defaults differ from verified dex");
        }

        private Scope current() {
            if (!enabled) return null;
            ArrayDeque<Scope> stack = scopes.get();
            Scope top = stack == null ? null : stack.peek();
            return top != null && top.eligible && !top.disabled
                    && (top.download == null || top.download.active()) ? top : null;
        }

        private boolean matches(Scope frame, XC_MethodHook.MethodHookParam param, int pathIndex, int typeIndex) {
            return matchesPath(frame, param.args, pathIndex) && param.args.length > typeIndex
                    && Integer.valueOf(frame.type).equals(param.args[typeIndex]);
        }

        private boolean matchesPath(Scope frame, Object[] args, int index) {
            return frame != null && args != null && args.length > index && frame.rawPath.equals(args[index]);
        }

        void invalidateFrames() {
            ArrayDeque<Scope> stack = scopes.get();
            if (stack != null) for (Scope frame : stack) frame.disabled = true;
            scopes.remove();
        }

        private void fail(String message, Throwable error) {
            invalidateFrames();
            safeError(message, error);
        }

        void rollback() {
            enabled = false;
            invalidateFrames();
            for (int i = unhooks.size() - 1; i >= 0; i--) {
                try { unhooks.get(i).unhook(); }
                catch (Throwable error) { safeError("keyless partial hook rollback failed", error); }
            }
            unhooks.clear();
        }
    }

    private static Method exact(Class<?> owner, String name, Class<?> result, boolean isStatic,
            Class<?>... arguments) throws NoSuchMethodException {
        Method method = HostApi.exact(owner, name, arguments);
        int modifiers = method.getModifiers();
        if (method.getReturnType() != result || Modifier.isStatic(modifiers) != isStatic
                || Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)
                || method.isBridge() || method.isSynthetic())
            throw new NoSuchMethodException("Unexpected 24.2.8.0 ABI: " + owner.getName() + "#" + name);
        return method;
    }

    private static boolean empty(String value) { return value == null || value.length() == 0; }

    private static boolean hexHash(Object value, int length) {
        if (!(value instanceof String) || ((String) value).length() != length) return false;
        String text = (String) value;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    private static void safeLog(String message) {
        try { ModuleLog.log(message); } catch (Throwable ignored) { /* Logging must not affect the host. */ }
    }

    private static void safeError(String message, Throwable error) {
        try { ModuleLog.error(message, error); } catch (Throwable ignored) { /* Leave original host result alone. */ }
    }
}
