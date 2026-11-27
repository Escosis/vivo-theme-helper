package io.github.escosis.vivothemehelper;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact target ABI. No boolean/name-only overload selection and no fail-open DB query. */
final class HostApi {
    static final String PARSER_CLASS = "com.bbk.theme.utils.lg";
    static final String STORAGE_CLASS = "com.bbk.theme.utils.dc";
    final ClassLoader loader;
    final Context context;
    final Class<?> itemClass;
    final Class<?> parserClass;
    private final Class<?> dbClass;
    private final Method parse;
    private final Method parseBasic;
    private final Method rule;
    private final Method singleton;
    private final Method storageSingleton;
    private final Method savePath;
    private final Method getDbUri;
    private final Method insert;

    HostApi(Context context, ClassLoader loader) throws Exception {
        this.context = context;
        this.loader = loader;
        itemClass = clazz("com.bbk.theme.common.ThemeItem");
        parserClass = clazz(PARSER_CLASS);
        dbClass = clazz("com.bbk.theme.base.ResDbUtils");
        singleton = exact(parserClass, "getInstances");
        parse = exact(parserClass, "parseResItzFile", String.class, int.class,
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
        parseBasic = exact(parserClass, "k", String.class, int.class, boolean.class, boolean.class);
        rule = exact(parserClass, "getRuleEntry", String.class);
        if (parse.getReturnType() != itemClass || parseBasic.getReturnType() != itemClass)
            throw new NoSuchMethodException("Unexpected parser return type");
        Class<?> storage = clazz(STORAGE_CLASS);
        storageSingleton = exact(storage, "getInstance");
        savePath = exact(storage, "getResSavePath", int.class);
        getDbUri = exact(dbClass, "getDbUriByType", int.class);
        insert = exact(dbClass, "insertDb", Context.class, int.class, ContentValues.class);
        if (insert.getReturnType() != boolean.class || getDbUri.getReturnType() != Uri.class)
            throw new NoSuchMethodException("Unexpected provider ABI");
        // These setters are required for a staged import, not guessed optional attributes.
        exact(itemClass, "setPath", String.class);
        exact(itemClass, "setFlagDownload", boolean.class);
        exact(itemClass, "setCategory", int.class);
    }

    Class<?> clazz(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    Object parser() throws Exception {
        Object result = invoke(singleton, null);
        if (result == null || !parserClass.isInstance(result))
            throw new IllegalStateException("Native parser singleton unavailable");
        return result;
    }

    Object parse(File file, int type) throws Exception {
        try (KeylessSupport.LocalParsePermit permit = KeylessSupport.allowLocalParse(file, type)) {
            return invoke(parse, parser(), file.getAbsolutePath(), type,
                    false, true, false, false, false);
        }
    }

    Object parseBasic(File file, int type, boolean inner, boolean flag) throws Exception {
        return invoke(parseBasic, null, file.getAbsolutePath(), type, inner, flag);
    }

    Object rule(File file) throws Exception {
        return invoke(rule, null, file.getAbsolutePath());
    }

    Object callStatic(String owner, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = exact(clazz(owner), name, types);
        if (!Modifier.isStatic(method.getModifiers()))
            throw new NoSuchMethodException(owner + "#" + name + " is not static");
        return invoke(method, null, values);
    }

    String text(Object item, String getter) throws Exception {
        requireItem(item);
        Method method = exact(item.getClass(), getter);
        if (method.getReturnType() != String.class)
            throw new NoSuchMethodException("Not a String getter: " + getter);
        Object result = invoke(method, item);
        return result == null ? "" : (String) result;
    }

    int integer(Object item, String getter) throws Exception {
        requireItem(item);
        Method method = exact(item.getClass(), getter);
        if (method.getReturnType() != int.class)
            throw new NoSuchMethodException("Not an int getter: " + getter);
        return (Integer) invoke(method, item);
    }

    boolean bool(Object item, String getter) throws Exception {
        requireItem(item);
        Method method = exact(item.getClass(), getter);
        if (method.getReturnType() != boolean.class)
            throw new NoSuchMethodException("Not a boolean getter: " + getter);
        return (Boolean) invoke(method, item);
    }

    void set(Object item, String setter, Class<?> type, Object value) throws Exception {
        requireItem(item);
        Method method = exact(item.getClass(), setter, type);
        if (method.getReturnType() != void.class)
            throw new NoSuchMethodException("Not a void setter: " + setter);
        invoke(method, item, value);
    }

    String savePath(int type) throws Exception {
        ModuleInfo.requireType(type);
        Object storage = invoke(storageSingleton, null);
        if (storage == null) throw new IllegalStateException("Native storage singleton unavailable");
        String path = (String) invoke(savePath, storage, type);
        if (path == null || path.length() == 0) throw new IllegalStateException("Native save directory empty");
        return path;
    }

    Uri dbUri(int type) throws Exception {
        ModuleInfo.requireType(type);
        Uri uri = (Uri) invoke(getDbUri, null, type);
        if (uri == null) throw new IllegalStateException("No provider URI for resource type " + type);
        return uri;
    }

    boolean exists(int type, String selection, String... args) throws Exception {
        try (Cursor cursor = context.getContentResolver().query(dbUri(type),
                new String[]{"_id"}, selection, args, null)) {
            if (cursor == null) throw new IllegalStateException("Provider returned no query cursor");
            return cursor.moveToFirst();
        }
    }

    boolean insert(int type, ContentValues values) throws Exception {
        ModuleInfo.requireType(type);
        Object result = invoke(insert, null, context, type, values);
        return Boolean.TRUE.equals(result);
    }

    void setDownloaded(Object item, File path) throws Exception {
        set(item, "setPath", String.class, path.getAbsolutePath());
        set(item, "setFlagDownload", boolean.class, true);
    }

    void postResourceChanged(Object item) throws Exception {
        requireItem(item);
        Class<?> eventClass = clazz("com.bbk.theme.eventbus.ResChangedEventMessage");
        Object event = eventClass.getConstructor(int.class, itemClass).newInstance(8, item);
        Class<?> busClass = clazz("e30.d");
        Object bus = invoke(exact(busClass, "b"), null);
        if (bus == null) throw new IllegalStateException("Event bus unavailable");
        invoke(exact(busClass, "f", Object.class), bus, event);
    }

    private void requireItem(Object item) {
        if (item == null || !itemClass.isInstance(item)) throw new IllegalArgumentException("Invalid ThemeItem");
    }

    static Method exact(Class<?> owner, String name, Class<?>... types) throws NoSuchMethodException {
        Class<?> current = owner;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name);
    }

    static Object invoke(Method method, Object receiver, Object... values) throws Exception {
        try {
            return method.invoke(receiver, values);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Native call failed: " + method.getName(), cause);
        }
    }
}
