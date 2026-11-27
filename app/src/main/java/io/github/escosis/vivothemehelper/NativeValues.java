package io.github.escosis.vivothemehelper;

import android.content.ContentValues;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The native conversion for the supported categories is in LocalScanManager.insertNewData.
 * Mirror its model-to-values conversion without relying on the void wrapper for insert success.
 * All commercial metadata and type-specific attributes come from the parsed native model.
 */
final class NativeValues {
    private static final String UTILS = "com.bbk.theme.utils.ThemeUtils";
    private final HostApi api;

    NativeValues(HostApi api) { this.api = api; }

    ContentValues fromItem(Object item, int type) throws Exception {
        ContentValues values = new ContentValues();
        text(values, "uid", item, "getPackageId");
        text(values, "resId", item, "getResId");
        text(values, "filename", item, "getPath");
        String name = api.text(item, "getName");
        if (api.bool(item, "isAiPet")) {
            Object nativeName = api.callStatic("com.bbk.theme.utils.i", "getResName",
                    new Class<?>[]{String.class}, api.text(item, "getResId"));
            if (nativeName instanceof String && !((String) nativeName).isEmpty()) name = (String) nativeName;
        }
        values.put("name", name);
        values.put("edition", Math.max(1, api.integer(item, "getEdition")));
        text(values, "author", item, "getAuthor");
        text(values, "size", item, "getSize");
        values.put("state", 3); // LocalScanManager native DOWNLOAD_FINISHED state, not a purchase state.
        values.put("download_time", longValue(item, "getDownloadTime"));
        boolean inner = api.bool(item, "getIsInnerRes");
        values.put("type", inner ? 0 : 1); // SOURCE BIT, never category/resType/AItype.

        if (type == 4 && api.bool(item, "isAiFont")) text(values, "openid", item, "getTaskId");
        if (gate("isResCharge", type)) {
            String price = nullableText(item, "getPrice");
            int amount = staticInt("com.bbk.theme.utils.y7", "parseInt",
                    new Class<?>[]{String.class}, price);
            boolean minusOne = staticBool("ee.n", "numAEqualsNumB",
                    new Class<?>[]{String.class, String.class}, price, "-1");
            String account = "";
            if (!minusOne) {
                Object raw = api.callStatic("fb.a", "getAccountInfo", new Class<?>[]{String.class}, "openid");
                account = raw == null ? null : requireString(raw, "current openid");
            }
            values.put("price", amount);
            if (isOverseas()) {
                text(values, "googlePrice", item, "getGooglePrice");
                text(values, "googlePrePrice", item, "getGooglePrePrice");
                text(values, "googleCurrencySymbol", item, "getLocalSymbol");
                text(values, "googlePriceStr", item, "getGooglePriceStr");
                text(values, "googlePrePriceStr", item, "getGooglePrePriceStr");
            }
            text(values, "olduid", item, "getOldPackageId");
            values.put("before_tax_price", floatValue(item, "getBeforeTaxprice"));
            text(values, "openid", item, "getOpenId");
            text(values, "right", item, "getRight");
            values.put("verify", minusOne || java.util.Objects.equals(nullableText(item, "getOpenId"), account) ? 1 : 0);
        }
        if (gate("isResHasThemeStyle", type)) text(values, "theme_style", item, "getThemeStyle");
        if (gate("isResHasControlCenter", type)) text(values, "controlCenterStyleVersion", item, "getControlCenterStyleVersion");
        if (gate("isResHasScreenRatio", type)) text(values, "screen_ratio", item, "getScreenRatio");

        if (type == 4 && !api.text(item, "getSysFontResPath").isEmpty()) {
            text(values, "inner_font_path", item, "getSysFontResPath");
        } else if (type == 1 || type == 5) {
            text(values, "lock_id", item, "getLockId");
            text(values, "lock_cid", item, "getCId");
            if (type == 5) integer(values, "offset_y", item, "getOffestY");
            if (type == 1) {
                text(values, "desktopLayout", item, "getDesktopLayout");
                Object config = api.callStatic("com.bbk.theme.utils.k0", "getInstance", new Class<?>[0]);
                if (config == null) throw new IOException("原生设备能力配置不可用");
                Method support = HostApi.exact(config.getClass(), "isSupportAiPetTheme");
                if (support.getReturnType() != boolean.class) throw new NoSuchMethodException("AiPet capability ABI");
                if (Boolean.TRUE.equals(HostApi.invoke(support, config)) && api.integer(item, "getAigcStyle") == 1) {
                    integer(values, "interactiveStyle", item, "getAigcStyle");
                    if (api.integer(item, "getAigcObject") != 0) integer(values, "interactiveObject", item, "getAigcObject");
                    if (api.integer(item, "getAigcSource") != 0) integer(values, "interactiveSource", item, "getAigcSource");
                    text(values, "interactiveConfig", item, "getAigcContentConfig");
                }
            }
            integer(values, "lock_engine_version", item, "getLockEngineVer");
        } else if (type == 16) {
            text(values, "typeTags", item, "getTypeTags");
            text(values, "subTypeTags", item, "getSubTypeTags");
            text(values, "darkTags", item, "getDarkTags");
            text(values, "widget_engine_version", item, "getEngineVersion");
            Object list = object(item, "getWidgetExtraList");
            if (list != null && !(list instanceof List)) throw new NoSuchMethodException("WidgetExtra list ABI");
            String json = requireString(api.callStatic("com.bbk.theme.utils.j5", "getJsonFromWidgetExtraList",
                    new Class<?>[]{List.class}, list), "widgetExtra JSON");
            if (list != null && !((List<?>) list).isEmpty()) {
                if (json.isEmpty()) throw new IOException("原生组件附加信息序列化失败");
                new JSONArray(json);
            }
            values.put("widgetExtra", json);
        } else if (type == 2) {
            liveWallpaper(values, item, inner);
        } else if (type == 7) {
            integer(values, "offset_y", item, "getOffestY");
            if (inner) {
                integer(values, "is_night_pear_pallpaper", item, "getIsNightPearWallpaper");
                text(values, "is_night_pear_live", item, "getNightPearlive");
                text(values, "thumbnail", item, "getThumbnail");
            }
        } else if (type == 13) {
            integer(values, "innerid", item, "getInnerId");
            integer(values, "behaviortype", item, "getBehaviortype");
            integer(values, "behaviorversion", item, "getBehaviorversion");
            text(values, "thumbnail", item, "getThumbnail");
            values.put("systemres", inner);
        } else if (type == 9) {
            String path = api.text(item, "getPath");
            values.put("package_type", path.endsWith(".itz") ? "itz" : "image");
            integer(values, "secondarySubtype", item, "getSecondarySubtype");
            values.put("isOfficial", api.bool(item, "isOfficial"));
            integer(values, "wallpaperCustomProperty", item, "getWallpaperCustomProperty");
        }
        // 18/20/14/12 deliberately use the native common/feature-gated protocol above.
        // Do not invent points, trial flags, openid, price, whole style, or lock metadata.
        return values;
    }

    private void liveWallpaper(ContentValues values, Object item, boolean inner) throws Exception {
        integer(values, "extra_1", item, "getUnfoldType");
        text(values, "packagename", item, "getPackageName");
        values.put("is_systemapp", inner);
        values.put("isOfficial", api.bool(item, "isOfficial"));
        text(values, "lw_packageType", item, "getLWPackageType");
        text(values, "extra_pkgService", item, "getServiceName");
        integer(values, "extra_wallpaperCanNotLauncherOnly", item, "getWallpaperCanNotLauncherOnly");
        text(values, "thumbnail", item, "getThumbnail");
        text(values, "extra_2", item, "getExtraThumbnail");
        values.put("extra_innerId", Math.max(0, api.integer(item, "getInnerId")));
        Object related = object(item, "getExtraRelatedData");
        // Use the host's exact default Gson call, as the inlined converter does. No Gson jar
        // is linked/packaged by this module; this is an already-present native dependency.
        Class<?> gsonClass = api.clazz("com.google.gson.Gson");
        Object gson = gsonClass.getConstructor().newInstance();
        Method jsonMethod = HostApi.exact(gsonClass, "toJson", Object.class);
        if (jsonMethod.getReturnType() != String.class) throw new NoSuchMethodException("Native Gson ABI");
        String relatedJson = requireString(HostApi.invoke(jsonMethod, gson, related), "extraRelatedData JSON");
        if (relatedJson.length() > 256 * 1024 || relatedJson.isEmpty())
            throw new IOException("动态壁纸关联信息无效或过大");
        if (related != null) new JSONObject(relatedJson);
        values.put("extra_relatedData", relatedJson);
        text(values, "description", item, "getDescription");
        if ("apk_res".equals(api.text(item, "getLWPackageType"))) {
            JSONArray paths = new JSONArray();
            paths.put(api.text(item, "getResourcePathType"));
            paths.put(api.text(item, "getResourcePackageName"));
            paths.put(api.text(item, "getThumbnail"));
            values.put("extra_apk_path_type", paths.toString());
        }
        text(values, "extra_4", item, "getOneShotExtraStr");
        int support = api.integer(item, "getSupportApplyType");
        Class<?> serviceClass = api.clazz("com.bbk.individualize.service.WallpaperOperateService");
        Object service = api.callStatic("i5.a", "getService", new Class<?>[]{Class.class}, serviceClass);
        if (service != null) {
            Method method = HostApi.exact(serviceClass, "getSupportApplyType",
                    int.class, int.class, int.class, String.class, String.class);
            if (method.getReturnType() != int.class) throw new NoSuchMethodException("WallpaperOperateService ABI");
            support = (Integer) HostApi.invoke(method, service, 2,
                    api.integer(item, "getSubType"), api.integer(item, "getWallpaperCanNotLauncherOnly"),
                    api.text(item, "getPackageName"), api.text(item, "getServiceName"));
            api.set(item, "setSupportApplyType", int.class, support);
        }
        values.put("extra_5", String.valueOf(support));
        integer(values, "wallpaperCustomProperty", item, "getWallpaperCustomProperty");
        integer(values, "subType", item, "getSubType");
        integer(values, "secondarySubtype", item, "getSecondarySubtype");
        integer(values, "isDeepOpened", item, "getIsDeepOpened");
        integer(values, "isBlurOpen", item, "getIsBlurOpen");
        integer(values, "extraStyleCount", item, "getExtraStyleCount");
        JSONObject previews = new JSONObject();
        previews.put("preview", api.text(item, "getPreview"));
        previews.put("preview_lock", api.text(item, "getLockPreview"));
        values.put("preview", previews.toString());
    }

    /** Check actual provider columns before file publication; errors must not become pruning. */
    void requireProviderProtocol(int type, ContentValues values) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Cursor cursor = api.context.getContentResolver().query(api.dbUri(type), null,
                "1=0", null, null)) {
            if (cursor == null) throw new IOException("资源数据库未返回 schema 查询结果");
            columns.addAll(Arrays.asList(cursor.getColumnNames()));
        }
        for (String required : Arrays.asList("uid", "resId", "filename", "name", "state", "type", "edition"))
            if (!columns.contains(required) || !values.containsKey(required))
                throw new IOException("宿主数据库缺少必需列：" + required);
        for (String field : values.keySet())
            if (!columns.contains(field)) throw new IOException("宿主数据库列协议不匹配：" + field);
    }

    boolean isOverseas() throws Exception {
        return staticBool(UTILS, "isOverseas", new Class<?>[0]);
    }

    private boolean gate(String name, int type) throws Exception {
        return staticBool(UTILS, name, new Class<?>[]{int.class}, type);
    }

    private boolean staticBool(String owner, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = HostApi.exact(api.clazz(owner), name, types);
        if (method.getReturnType() != boolean.class) throw new NoSuchMethodException(owner + "#" + name);
        return (Boolean) HostApi.invoke(method, null, values);
    }

    private int staticInt(String owner, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = HostApi.exact(api.clazz(owner), name, types);
        if (method.getReturnType() != int.class) throw new NoSuchMethodException(owner + "#" + name);
        return (Integer) HostApi.invoke(method, null, values);
    }

    private void text(ContentValues values, String key, Object item, String getter) throws Exception {
        // Preserve SQL nulls as the native converter does. In particular null account IDs
        // must not become an accidental equality with an empty model openid/verify flag.
        values.put(key, nullableText(item, getter));
    }

    private String nullableText(Object item, String getter) throws Exception {
        Method method = HostApi.exact(item.getClass(), getter);
        if (method.getReturnType() != String.class) throw new NoSuchMethodException(getter);
        return (String) HostApi.invoke(method, item);
    }

    private void integer(ContentValues values, String key, Object item, String getter) throws Exception {
        values.put(key, api.integer(item, getter));
    }

    private Object object(Object item, String getter) throws Exception {
        return HostApi.invoke(HostApi.exact(item.getClass(), getter), item);
    }

    private long longValue(Object item, String getter) throws Exception {
        Method method = HostApi.exact(item.getClass(), getter);
        if (method.getReturnType() != long.class) throw new NoSuchMethodException(getter);
        return (Long) HostApi.invoke(method, item);
    }

    private float floatValue(Object item, String getter) throws Exception {
        Method method = HostApi.exact(item.getClass(), getter);
        if (method.getReturnType() != float.class) throw new NoSuchMethodException(getter);
        return (Float) HostApi.invoke(method, item);
    }

    private static String requireString(Object value, String meaning) throws IOException {
        if (!(value instanceof String)) throw new IOException("原生 " + meaning + " 返回值无效");
        return (String) value;
    }
}
