package io.github.escosis.vivothemehelper;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Old-mod cell/button specification, with the exact named 24.x DTO/listener ABI. */
final class UiHooks {
    private static final String LABEL = "本地导入";
    private static final String ONLINE_WIDGET = "com.bbk.theme.mine.widget.LocalDownloadHorScrollLayout";
    private static final String ROW_TAG_PREFIX = ModuleInfo.IMPORT_TAG + ".row.";
    private static final int EXPORT_VIEW_ID = 0x7f0effff; // old mod's own click id, not a host resource lookup
    private static final WeakHashMap<Object, Boolean> RESIZE_LISTENERS = new WeakHashMap<Object, Boolean>();
    private UiHooks() {}

    static void install(final HostApi api, boolean keylessReady) {
        NativeDialogs.bind(api);
        if (!ImportCoordinator.install(api)) {
            ModuleLog.log("原生Fragment恢复/结果路由未安装；不提供导入/导出按钮");
            return;
        }
        if (!keylessReady) ModuleLog.log("缺key兼容未安装：导入可能仅能使用有真实key的包/原图");
        installOffline(api);
        try {
            final Class<?> widget = api.clazz(ONLINE_WIDGET);
            installOwnedClick(api, widget);
            installGridPagingHooks(widget);
            XposedBridge.hookMethod(HostApi.exact(widget, "onFinishInflate"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    final Object layout = param.thisObject;
                    onUi(new Runnable() { @Override public void run() { injectOnline(api, layout); } });
                }
            });
            XposedBridge.hookMethod(HostApi.exact(widget, "onResume", boolean.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    final Object object = param.thisObject;
                    onUi(new Runnable() {
                        @Override public void run() { applyGridSafely(api, object); }
                    });
                }
            });
            Class<?> fragment = api.clazz("com.bbk.theme.fragment.LocalFragment");
            XposedBridge.hookMethod(HostApi.exact(fragment, "onViewCreated", View.class, Bundle.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try {
                        Object layout = HookInit.findField(param.thisObject.getClass(), "W0").get(param.thisObject);
                        if (layout != null) injectOnline(api, layout);
                    } catch (Throwable error) { ModuleLog.error("在线入口补充注入失败", error); }
                }
            });
            ModuleLog.log("online import widget hooks installed");
        } catch (Throwable error) { ModuleLog.error("在线导入入口未安装", error); }
        try {
            Class<?> preview = api.clazz("com.bbk.theme.ImmersionResBasePreview");
            XposedBridge.hookMethod(HostApi.exact(preview, "initView"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    final Object object = param.thisObject;
                    onUi(new Runnable() { @Override public void run() { injectExport(api, object); } });
                }
            });
            ModuleLog.log("preview SAF export hook installed");
        } catch (Throwable error) { ModuleLog.error("导出入口未安装", error); }
    }

    private static void installOffline(final HostApi api) {
        try {
            final Class<?> layoutClass = api.clazz("com.bbk.theme.mine.widget.LocalDownloadLayout");
            Method data = HostApi.exact(layoutClass, "a");
            if (data.getReturnType() != ArrayList.class) throw new NoSuchMethodException("a must return ArrayList");
            installOwnedClick(api, layoutClass); // no sentinel is exposed without its before-click hook
            XposedBridge.hookMethod(data, new XC_MethodHook() {
                @SuppressWarnings("unchecked")
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof ArrayList)) return;
                    try {
                        ArrayList<Object> values = (ArrayList<Object>) param.getResult();
                        for (Object item : values) if (isOurEntry(item)) return;
                        Context context = (Context) HookInit.findField(layoutClass, "q").get(param.thisObject);
                        // Old merged Runnable's LocalDownloadLayout branch performs ONLY List.add(DTO).
                        // Do not alter the unrelated native s names array or replace its adapter/init class.
                        values.add(importDto(api, context, 0x7f060b87));
                    } catch (Throwable error) { ModuleLog.error("离线数据条目注入失败", error); }
                }
            });
            ModuleLog.log("offline a():ArrayList append + native adapter/own sentinel click installed");
        } catch (Throwable error) { ModuleLog.error("离线导入入口未安装", error); }
    }

    private static void installOwnedClick(final HostApi api, final Class<?> layoutClass) throws Exception {
        Class<?> dto = api.clazz("com.bbk.theme.common.MyLocalAndDownload");
        XposedBridge.hookMethod(HostApi.exact(layoutClass, "itemOnClick", dto), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!isOurEntry(param.args[0])) return;
                    // Consume only our name+[-1] DTO before handing off. The host's native h9.f
                    // still performs its own cell click/red-dot work; other DTOs are untouched.
                    param.setResult(null);
                    Context context = (Context) HookInit.findField(layoutClass, "q").get(param.thisObject);
                    showPicker(context);
                } catch (Throwable error) { ModuleLog.error("本地导入条目点击失败", error); }
            }
        });
    }

    private static void installGridPagingHooks(final Class<?> widget) throws Exception {
        Method canScroll = HostApi.exact(widget, "isCanScroll");
        if (canScroll.getReturnType() != boolean.class)
            throw new NoSuchMethodException("isCanScroll must return boolean");
        XposedBridge.hookMethod(canScroll, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // The original flag is w.size()>8, not the actual laid-out scroll range. Only
                // THIS successfully injected grid is non-paged; uninjected host widgets keep it.
                if (managedRows(param.thisObject) != null) param.setResult(false);
            }
        });
        final Method updateDot = HostApi.exact(widget, "a", View.class, String.class, boolean.class);
        if (!Modifier.isStatic(updateDot.getModifiers()) || updateDot.getReturnType() != boolean.class)
            throw new NoSuchMethodException("native a(View,String,boolean):boolean must be static");
        XposedBridge.hookMethod(HostApi.exact(widget, "c", String.class, boolean.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                LinearLayout rows = managedRows(param.thisObject);
                if (rows == null) return;
                try {
                    // Native c() already visits s/t. Its exact native helper extends the same
                    // red-dot semantics to transferred children in our third/further rows.
                    for (int i = 0; i < rows.getChildCount(); i++) {
                        View row = rows.getChildAt(i);
                        if (!isExtraRow(row)) continue;
                        ViewGroup group = (ViewGroup) row;
                        for (int j = 0; j < group.getChildCount(); j++)
                            if ((Boolean) HostApi.invoke(updateDot, null, group.getChildAt(j),
                                    param.args[0], param.args[1])) return;
                    }
                } catch (Throwable error) { ModuleLog.error("新增原生行红点更新失败", error); }
            }
        });
    }

    private static boolean isOurEntry(Object item) throws Exception {
        if (item == null) return false;
        int[] types = (int[]) HostApi.invoke(HostApi.exact(item.getClass(), "getResType"), item);
        Object name = HostApi.invoke(HostApi.exact(item.getClass(), "getName"), item);
        return isOwnedSentinel(name instanceof String ? (String) name : null, types);
    }

    static boolean isOwnedSentinel(String name, int[] types) {
        return LABEL.equals(name) && types != null && types.length == 1 && types[0] == -1;
    }

    private static void dtoSet(Object item, String method, Class<?> type, Object value) throws Exception {
        HostApi.invoke(HostApi.exact(item.getClass(), method, type), item, value);
    }

    private static Object importDto(HostApi api, Context context, int titleColorId) throws Exception {
        Object item = api.clazz("com.bbk.theme.common.MyLocalAndDownload").getDeclaredConstructor().newInstance();
        dtoSet(item, "setName", String.class, LABEL);
        dtoSet(item, "setResType", int[].class, new int[]{-1});
        dtoSet(item, "setType", int.class, 1);
        dtoSet(item, "setUnicode", int.class, 0);
        // User correction: preserve the r1 IMPORT glyph, not the newly supplied EXPORT glyph.
        dtoSet(item, "setIcon", Drawable.class, context.getResources().getDrawable(ModuleInfo.IMPORT_ICON_ID));
        Class<?> service = api.clazz("com.bbk.theme.services.commercialize.SkinService");
        Object skin = api.callStatic("i5.a", "getService", new Class<?>[]{Class.class}, service);
        if (skin != null) dtoSet(item, "setTitleColor", int.class,
                HostApi.invoke(HostApi.exact(service, "getColor", int.class), skin, titleColorId));
        // The old mod leaves the DTO's default color alone when the native SkinService is null.
        return item;
    }

    @SuppressWarnings("unchecked")
    private static void injectOnline(final HostApi api, final Object object) {
        try {
            if (!(object instanceof ViewGroup)) return;
            final ViewGroup layout = (ViewGroup) object;
            LinearLayout first = (LinearLayout) HookInit.findField(object.getClass(), "s").get(object);
            LinearLayout second = (LinearLayout) HookInit.findField(object.getClass(), "t").get(object);
            if (first == null || second == null || first.getParent() != second.getParent()
                    || !(first.getParent() instanceof LinearLayout)) return;
            LinearLayout rows = (LinearLayout) first.getParent();
            if (rows.findViewWithTag(ModuleInfo.IMPORT_TAG) != null) {
                fitOnlineGrid(api, object);
                installGridResize(api, layout);
                return;
            }
            Context context = (Context) HookInit.findField(object.getClass(), "q").get(object);
            ArrayList<Object> names = (ArrayList<Object>) HookInit.findField(object.getClass(), "w").get(object);
            ArrayList<Object> values = (ArrayList<Object>) HookInit.findField(object.getClass(), "x").get(object);
            if (names.size() != values.size()) throw new IllegalStateException("Native w/x index alignment changed");
            for (Object item : values) if (isOurEntry(item))
                throw new IllegalStateException("Sentinel exists without its managed native cell");
            ArrayList<View> firstCells = children(first);
            ArrayList<View> secondCells = children(second);
            if (firstCells.size() + secondCells.size() != values.size())
                throw new IllegalStateException("Native cells/DTO count mismatch");
            final int index = values.size();
            Object entry = importDto(api, context, ModuleInfo.IMPORT_COLOR_ID);
            View cell = LayoutInflater.from(context).inflate(ModuleInfo.ITEM_LAYOUT_ID, null, false);
            ImageView icon = cell.findViewById(ModuleInfo.ITEM_ICON_ID);
            TextView title = cell.findViewById(ModuleInfo.ITEM_TITLE_ID);
            View click = cell.findViewById(ModuleInfo.ITEM_CLICK_ID);
            if (icon == null || title == null || click == null) throw new IllegalStateException("Unexpected native item XML");
            icon.setBackground((Drawable) HostApi.invoke(HostApi.exact(entry.getClass(), "getIcon"), entry));
            String name = (String) HostApi.invoke(HostApi.exact(entry.getClass(), "getName"), entry);
            title.setText(name);
            title.setTextColor((Integer) HostApi.invoke(HostApi.exact(entry.getClass(), "getTitleColor"), entry));
            // Keep the inflated native title/font/padding, just like the old wrapper: no custom
            // typeface, image scaling, arbitrary row padding or replacement root click listener.
            click.setTag(name);
            click.setContentDescription(name);
            View dot = cell.findViewById(ModuleInfo.ITEM_DOT_ID);
            if (dot != null) dot.setVisibility(View.GONE);
            Object nativeListener = api.clazz("h9.f").getConstructor(api.clazz(ONLINE_WIDGET), int.class)
                    .newInstance(object, index);
            click.setOnClickListener((View.OnClickListener) nativeListener);
            cell.setTag(ModuleInfo.IMPORT_TAG); // non-visual, idempotence; native click's tag stays name

            int width = nativeFourColumnWidth(api, context);
            cell.setLayoutParams(new ViewGroup.MarginLayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
            int rowGap = firstCells.isEmpty() ? 0 : bottomMargin(firstCells.get(0));
            ArrayList<ArrayList<View>> groups = oldFourColumnRows(firstCells, secondCells, cell);
            ArrayList<LinearLayout> nativeRows = new ArrayList<LinearLayout>();
            nativeRows.add(first);
            nativeRows.add(second); // retain both named fields even if a compact grid needs only one
            for (int i = 2; i < groups.size(); i++) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setTag(ROW_TAG_PREFIX + i);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = rowGap; // old third row takes first child's native bottom margin
                row.setLayoutParams(params);
                nativeRows.add(row);
            }
            // This is the native listener's actual array/index. Existing DTOs/listeners never
            // change index or identity; native h9.f will read this new x[index] sentinel.
            names.add(LABEL);
            values.add(entry);
            first.setOrientation(LinearLayout.HORIZONTAL);
            second.setOrientation(LinearLayout.HORIZONTAL);
            rows.setOrientation(LinearLayout.VERTICAL);
            first.removeAllViews();
            second.removeAllViews();
            int afterSecond = rows.indexOfChild(second) + 1;
            for (int i = 2; i < nativeRows.size(); i++) rows.addView(nativeRows.get(i), afterSecond + i - 2);
            for (int i = 0; i < groups.size(); i++) {
                LinearLayout row = nativeRows.get(i);
                for (View child : groups.get(i)) {
                    resizeCell(child, width);
                    row.addView(child); // transfer the SAME native cell and its existing click listener
                }
            }
            fitOnlineGrid(api, object);
            installGridResize(api, layout);
            rows.requestLayout();
            layout.requestLayout();
            ModuleLog.log("native import DTO appended; old visual order, " + groups.size()
                    + " rows, <=4 cells/row, no horizontal pages");
        } catch (Throwable error) { ModuleLog.error("在线原生四列导入网格注入失败", error); }
    }

    private static ArrayList<View> children(ViewGroup group) {
        ArrayList<View> result = new ArrayList<View>();
        for (int i = 0; i < group.getChildCount(); i++) result.add(group.getChildAt(i));
        return result;
    }

    // Pure-Java helpers used by the REAL View code above and by the layout/order fixtures.
    // Latest request requires <=4 in every row, including hosts with >12 entries. Preserve the
    // old wrapper's visual stream (first head, second head, first tail, second tail, import),
    // then partition it instead of leaving a third row that can itself overflow horizontally.
    static <T> ArrayList<ArrayList<T>> oldFourColumnRows(List<T> first, List<T> second, T localImport) {
        ArrayList<T> ordered = new ArrayList<T>();
        appendRange(ordered, first, 0, Math.min(4, first.size()));
        appendRange(ordered, second, 0, Math.min(4, second.size()));
        appendRange(ordered, first, Math.min(4, first.size()), first.size());
        appendRange(ordered, second, Math.min(4, second.size()), second.size());
        ordered.add(localImport);
        ArrayList<ArrayList<T>> result = new ArrayList<ArrayList<T>>();
        for (int i = 0; i < ordered.size(); i += 4)
            result.add(new ArrayList<T>(ordered.subList(i, Math.min(i + 4, ordered.size()))));
        return result;
    }

    private static <T> void appendRange(ArrayList<T> target, List<T> source, int from, int to) {
        for (int i = from; i < to; i++) target.add(source.get(i));
    }

    static int fourColumnWidth(int nativeRealWidth, int native48Dp) {
        return (nativeRealWidth - native48Dp) / 4;
    }

    static int exportIconTopMargin(int native24Dp, int shareMargin) {
        return shareMargin > 0 ? shareMargin : native24Dp;
    }

    private static int nativeFourColumnWidth(HostApi api, Context context) throws Exception {
        int real = (Integer) api.callStatic("com.bbk.theme.utils.DensityUtil", "getRealScreenWidth",
                new Class<?>[]{Context.class, boolean.class}, context, true);
        int width = fourColumnWidth(real, nativeDp(api, 48.0f));
        if (width <= 0) throw new IllegalStateException("Native four-column width is nonpositive");
        return width;
    }

    private static int nativeDp(HostApi api, float value) throws Exception {
        // dp2px(float) uses system density. The context overload is NOT the old mod's call.
        return (Integer) api.callStatic("com.bbk.theme.utils.DensityUtil", "dp2px",
                new Class<?>[]{float.class}, value);
    }

    private static int bottomMargin(View child) {
        ViewGroup.LayoutParams params = child.getLayoutParams();
        return params instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) params).bottomMargin : 0;
    }

    private static boolean isExtraRow(View row) {
        return row instanceof LinearLayout && row.getTag() instanceof String
                && ((String) row.getTag()).startsWith(ROW_TAG_PREFIX);
    }

    private static LinearLayout managedRows(Object object) {
        try {
            LinearLayout first = (LinearLayout) HookInit.findField(object.getClass(), "s").get(object);
            LinearLayout second = (LinearLayout) HookInit.findField(object.getClass(), "t").get(object);
            if (first == null || second == null || first.getParent() != second.getParent()
                    || !(first.getParent() instanceof LinearLayout)) return null;
            LinearLayout rows = (LinearLayout) first.getParent();
            return rows.findViewWithTag(ModuleInfo.IMPORT_TAG) == null ? null : rows;
        } catch (Exception ignored) { return null; } // uninjected widgets must retain their native flags
    }

    private static void fitOnlineGrid(HostApi api, Object object) throws Exception {
        LinearLayout rows = managedRows(object);
        if (rows == null) return;
        Context context = (Context) HookInit.findField(object.getClass(), "q").get(object);
        LinearLayout first = (LinearLayout) HookInit.findField(object.getClass(), "s").get(object);
        LinearLayout second = (LinearLayout) HookInit.findField(object.getClass(), "t").get(object);
        int width = nativeFourColumnWidth(api, context);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child != first && child != second && !isExtraRow(child)) continue;
            LinearLayout row = (LinearLayout) child;
            if (row.getChildCount() > 4) throw new IllegalStateException("Managed row exceeds four cells");
            if (row.getOrientation() != LinearLayout.HORIZONTAL) row.setOrientation(LinearLayout.HORIZONTAL);
            wrapSize(row, ViewGroup.LayoutParams.WRAP_CONTENT);
            for (int j = 0; j < row.getChildCount(); j++) resizeCell(row.getChildAt(j), width);
        }
        if (rows.getOrientation() != LinearLayout.VERTICAL) rows.setOrientation(LinearLayout.VERTICAL);
        // Replacing long native pages with WRAP rows of at most 4 bounds actual content width.
        wrapSize(rows, ViewGroup.LayoutParams.WRAP_CONTENT);
        Object scrollObject = HookInit.findField(object.getClass(), "r").get(object);
        if (scrollObject instanceof HorizontalScrollView) {
            HorizontalScrollView scroll = (HorizontalScrollView) scrollObject;
            wrapSize(scroll, ViewGroup.LayoutParams.MATCH_PARENT);
            scroll.setFillViewport(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            if (scroll.getScrollX() != 0 || scroll.getScrollY() != 0) scroll.scrollTo(0, 0);
            if (scroll.getParent() instanceof View) wrapHeight((View) scroll.getParent());
        }
        Object seek = HookInit.findField(object.getClass(), "v").get(object);
        if (seek instanceof SeekBar) {
            SeekBar bar = (SeekBar) seek;
            if (bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
            if (bar.getMax() != 0) bar.setMax(0);
            if (bar.getProgress() != 0) bar.setProgress(0);
        }
        if (object instanceof View) wrapHeight((View) object);
    }

    private static void resizeCell(View cell, int width) {
        ViewGroup.LayoutParams params = cell.getLayoutParams();
        if (params == null) cell.setLayoutParams(new ViewGroup.MarginLayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT));
        else if (params.width != width || params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.width = width;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            cell.setLayoutParams(params); // retain native margins, child structure and listener identity
        }
    }

    private static void wrapSize(View view, int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && (params.width != width || params.height != ViewGroup.LayoutParams.WRAP_CONTENT)) {
            params.width = width;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            view.setLayoutParams(params);
        }
    }

    private static void applyGridSafely(HostApi api, Object object) {
        try { fitOnlineGrid(api, object); }
        catch (Throwable error) { ModuleLog.error("原生四列网格尺寸更新失败", error); }
    }

    private static void installGridResize(final HostApi api, final ViewGroup layout) {
        synchronized (RESIZE_LISTENERS) {
            if (RESIZE_LISTENERS.put(layout, Boolean.TRUE) != null) return;
        }
        final WeakReference<Object> target = new WeakReference<Object>(layout);
        layout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                Object object = target.get();
                if (object != null) applyGridSafely(api, object);
            }
        });
        // onFinishInflate precedes the first traversal. Native Fragment posts/initial measurement
        // can run after it; apply again after attachment and every layout, not only at inflate.
        layout.post(new Runnable() {
            @Override public void run() {
                Object object = target.get();
                if (object != null) applyGridSafely(api, object);
            }
        });
    }

    private static void injectExport(final HostApi api, Object preview) {
        try {
            View share = (View) HookInit.findField(preview.getClass(), "v1").get(preview);
            if (share == null || !(share.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) share.getParent();
            // Old code searched the share subtree by mistake. The actual common parent owns
            // this adjacent button, so repeated initView() cannot add duplicate export widgets.
            if (parent.findViewWithTag(ModuleInfo.EXPORT_TAG) != null
                    || parent.findViewById(EXPORT_VIEW_ID) != null) return;
            final WeakReference<Object> target = new WeakReference<Object>(preview);
            final Context context = share.getContext();
            int iconSize = nativeDp(api, 24.0f);
            int labelTop = nativeDp(api, 4.0f);
            ImageView shareIcon = (ImageView) HookInit.findField(preview.getClass(), "G1").get(preview);
            ViewGroup.LayoutParams shareIconParams = shareIcon == null ? null : shareIcon.getLayoutParams();
            int nativeTop = shareIconParams instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams) shareIconParams).topMargin : 0;
            int iconTop = exportIconTopMargin(nativeDp(api, 24.0f), nativeTop);
            Object animated = api.clazz("com.bbk.theme.widget.AnimLinearLayout")
                    .getConstructor(Context.class).newInstance(context);
            if (!(animated instanceof LinearLayout)) throw new IllegalStateException("Native AnimLinearLayout ABI changed");
            LinearLayout button = (LinearLayout) animated;
            button.setTag(ModuleInfo.EXPORT_TAG);
            button.setId(EXPORT_VIEW_ID);
            button.setOrientation(LinearLayout.VERTICAL);
            button.setGravity(Gravity.CENTER);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    try {
                        Object object = target.get();
                        if (object == null) return;
                        Object item = HookInit.findField(object.getClass(), "q").get(object); // CURRENT ThemeItem
                        if (item == null) { toast(context, "没有可导出的当前资源"); return; }
                        int type = api.integer(item, "getCategory");
                        if (!ModuleInfo.hasType(type)) { toast(context, "该资源类型暂不支持导出"); return; }
                        File source = new File(api.text(item, "getPath"));
                        File root = new File(api.savePath(type)).getCanonicalFile();
                        File actual = source.getCanonicalFile();
                        if (!actual.isFile() || !actual.canRead() || !isInside(root, actual)) {
                            toast(context, "请先下载资源；仅导出当前用户主题保存目录内的单个文件"); return;
                        }
                        Activity activity = activity(context);
                        if (activity == null) { toast(context, "无法找到当前窗口"); return; }
                        // Preserve r1's safe source-root rule and parent-owned SAF snapshot/export.
                        // Never copy the old listener's public Downloads overwrite backend.
                        ImportCoordinator.startExport(activity, actual, api.text(item, "getName"), type);
                    } catch (Throwable error) { ModuleLog.error("导出启动失败", error); toast(context, "导出失败：" + error.getClass().getSimpleName()); }
                }
            });
            ImageView icon = new ImageView(context);
            icon.setImageResource(ModuleInfo.EXPORT_ICON_ID); // user's originui_vtoolbar_icon_export_rom13_5
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconParams.gravity = Gravity.CENTER_HORIZONTAL;
            iconParams.setMargins(0, iconTop, 0, 0);
            icon.setLayoutParams(iconParams);
            button.addView(icon);
            TextView label = new TextView(context);
            label.setText("导出");
            label.setTextSize(11.0f); // old TextView's SP overload and default typeface
            label.setTextColor(-1);
            label.setSingleLine(true);
            label.setFocusable(true);
            label.setFocusableInTouchMode(true);
            label.setMaxWidth(nativeDp(api, 42.0f));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.gravity = Gravity.CENTER_HORIZONTAL;
            labelParams.setMargins(0, labelTop, 0, 0);
            label.setLayoutParams(labelParams);
            button.addView(label);
            ViewGroup.LayoutParams originalParams = share.getLayoutParams();
            if (originalParams == null) throw new IllegalStateException("Native share layout params missing");
            LinearLayout.LayoutParams outerParams = new LinearLayout.LayoutParams(originalParams);
            int index = parent.indexOfChild(share) + 1;
            parent.addView(button, index, outerParams);
        } catch (Throwable error) { ModuleLog.error("导出原生图标按钮注入失败", error); }
    }

    static boolean isInside(File root, File file) throws Exception {
        String base = root.getCanonicalPath() + File.separator;
        return file.getCanonicalPath().startsWith(base);
    }

    private static void showPicker(Context context) {
        Activity activity = activity(context);
        if (activity == null) { toast(context, "无法找到当前窗口"); return; }
        ImportCoordinator.showTypePicker(activity);
    }

    static Activity activity(Context context) {
        for (int i = 0; context != null && i < 12; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }
    static void toast(final Context context, final String message) {
        NativeDialogs.toast(context, message);
    }
    static int dp(Context context, float value) { return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f); }
    private static void wrapHeight(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            view.setLayoutParams(params);
        }
    }
    static void onUi(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else new Handler(Looper.getMainLooper()).post(action);
    }
}
