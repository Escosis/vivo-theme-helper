package io.github.escosis.vivothemehelper;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Worker-only local import backend for the exact target host ABI. */
public final class ImportEngine {
    private static final int[] TYPES = {1, 16, 2, 9, 13, 4, 18, 5, 20, 7, 14, 12};
    private static final String UTILS = "com.bbk.theme.utils.ThemeUtils";
    private final HostApi api;

    public ImportEngine(HostApi api) {
        if (api == null) throw new IllegalArgumentException("HostApi required");
        this.api = api;
    }

    public interface Observer {
        void progress(String message);
        boolean confirmIdRewrite(String message) throws Exception;
        void success(ImportResult result);
        void failure(String message, Throwable error);
    }

    public static final class ImportResult {
        public final int type;
        public final String name;
        public final File file;
        public final Object item;
        /** Nonempty when data was truly inserted but a refresh/representation warning remains. */
        public final String warning;

        ImportResult(int type, String name, File file, Object item, String warning) {
            this.type = type;
            this.name = name;
            this.file = file;
            this.item = item;
            this.warning = warning == null ? "" : warning;
        }
    }

    /** Exactly one terminal callback is attempted for each run with a nonnull Observer. */
    public void run(Context context, Uri uri, int resType, Observer observer) {
        if (observer == null) {
            ModuleLog.error("Import requires an Observer", new IllegalArgumentException("Observer required"));
            return;
        }
        State state = new State();
        ImportResult result = null;
        Throwable failure = null;
        String message = "";
        String cleanup = "";
        try {
            if (context == null || uri == null) throw new IOException("导入会话缺少 Context 或文件 URI");
            requireType(resType);
            ArchiveIO.Deadline deadline = new ArchiveIO.Deadline(8, TimeUnit.MINUTES);
            result = execute(context, uri, resType, observer, state, deadline);
        } catch (Throwable error) {
            failure = error;
            if (error instanceof InterruptedException || error instanceof InterruptedIOException)
                Thread.currentThread().interrupt();
            message = error.getMessage();
            if (message == null || message.isEmpty()) message = error.getClass().getSimpleName() + "，请查看模块诊断日志";
            message = "导入失败（" + state.phase + "）：" + message;
            if (state.committed) message = "资源已真实入库，但后续处理失败；未删除已提交文件。" + message;
            ModuleLog.error("Local import failed (type=" + resType + ", phase=" + state.phase
                    + ", committed=" + state.committed + ")", error);
        } finally {
            try { cleanup = cleanup(state); }
            catch (Throwable error) {
                ModuleLog.error("Import cleanup could not complete", error);
                cleanup = "清理遇到异常，未进行不确定删除；请查看诊断日志";
            }
        }
        // Terminal callback exceptions are logged, never translated into a second terminal.
        if (failure == null && result != null) {
            ImportResult delivered = new ImportResult(result.type, result.name, result.file,
                    result.item, append(result.warning, cleanup));
            try { observer.success(delivered); }
            catch (Throwable callback) { ModuleLog.error("Import success observer failed", callback); }
        } else {
            try { observer.failure(append(message, cleanup), failure); }
            catch (Throwable callback) { ModuleLog.error("Import failure observer failed", callback); }
        }
    }

    private ImportResult execute(Context context, Uri uri, int type, Observer observer, State state,
                                 ArchiveIO.Checkpoint check) throws Exception {
        check.check();
        File cache = context.getCacheDir().getCanonicalFile();
        if (!cache.isDirectory()) throw new IOException("宿主缓存目录不可用");
        state.cacheBase = cache;
        state.root = new File(cache, "dsh_v24_import_" + UUID.randomUUID());
        Files.createDirectory(state.root.toPath());
        state.rootCreated = true;
        // kg.a infers wallpaper/control-centre categories only for /W/ and /C2/ paths.
        File stage = new File(state.root, stageLabel(type));
        Files.createDirectory(stage.toPath());
        File input = new File(stage, "incoming.bin");
        state.phase = "读取所选文件";
        progress(observer, "正在复制并验证所选文件…");
        UriStaging.Result document = UriStaging.copy(context, uri, input,
                value -> progress(observer, value), check);
        String title = ArchiveIO.title(document.displayName);
        state.phase = "识别和验证文件内容";
        ArchiveIO.Kind inputKind = ArchiveIO.sniff(input);
        ModuleLog.log("Local import input: type=" + type + ", kind=" + inputKind + ", bytes=" + document.copied
                + ", header=" + ImportDiagnostics.headerHex(input));
        Prepared prepared = prepare(input, stage, type, title, observer, check);

        // Identify IDs with native kg.k, not an invented uid/category relationship. Defer the
        // full parser's integrity preference writes until the initial duplicate check is done.
        state.phase = "解析资源清单和检查 ID";
        Object basic = prepared.image ? prepared.item : basic(prepared.file, type);
        requireIdentity(basic, type);
        if (duplicate(type, basic)) {
            check.check();
            boolean supported = prepared.image || prepared.manifest.supportsRewrite(type);
            if (!supported) {
                if (!prepared.image && !prepared.manifest.keyless)
                    throw new IOException("资源 ID 已存在：非空 key 的包不能修改 ID，以免破坏真实摘要");
                if (type == 16) throw new IOException("组件 ID 已存在：Widget 清单暂不支持安全修改 ID");
                throw new IOException("资源 ID 已存在：此清单不支持安全修改 id/uid/id3");
            }
            String originalName = api.text(basic, "getName");
            boolean accepted = observer.confirmIdRewrite("本地已存在相同 ID 的资源「"
                    + ArchiveIO.title(originalName) + "」。是否仅为这份缺 key 的本地包生成新 ID？原资源不会覆盖。");
            check.check();
            if (!accepted) throw new InterruptedIOException("未确认修改 ID，导入已取消");
            Ids ids = newIds(type);
            if (prepared.image) {
                File renamed = new File(stage, nativeWallpaperName(ids));
                ArchiveIO.publishAtomic(prepared.file, renamed);
                prepared.file = renamed;
                prepared.item = imageItem(renamed, title, ids, check);
            } else {
                File rewritten = new File(stage, "rewritten.itz");
                prepared.manifest = ArchiveIO.rewriteIds(prepared.file, rewritten, prepared.manifest,
                        type, ids.packageId, ids.resId, check);
                prepared.file = rewritten;
            }
            basic = prepared.image ? prepared.item : basic(prepared.file, type);
            requireIdentity(basic, type);
            if (!api.text(basic, "getPackageId").equals(ids.packageId)
                    || !api.text(basic, "getResId").equals(ids.resId) || duplicate(type, basic))
                throw new IOException("新 ID 的原生解析不一致或仍然冲突；已停止导入，不再重试");
        }

        check.check();
        state.phase = "宿主原生资源与摘要校验";
        progress(observer, prepared.image ? "正在检查图片模型与数据库协议…" : "正在执行宿主原生资源与摘要校验…");
        Object item = prepared.image ? prepared.item : api.parse(prepared.file, type);
        if (item == null) {
            boolean overseas = new NativeValues(api).isOverseas();
            if (prepared.manifest != null && prepared.manifest.keyless && overseas)
                throw new IOException("原生解析失败：本版本未启用海外缺 key 兼容，未放宽真实校验");
            throw new IOException("原生资源/摘要校验失败；非空 key 的失败不会放行，缺 key 包也必须满足资源检查");
        }
        requireIdentity(item, type);
        if (!prepared.file.isFile() || prepared.file.length() == 0)
            throw new IOException("原生校验后缓存文件已丢失，不能提交");
        if (!prepared.image && prepared.manifest.keyless
                && (!api.text(item, "getResId").equals(api.text(basic, "getResId"))
                || !api.text(item, "getPackageId").equals(api.text(basic, "getPackageId"))))
            throw new IOException("缺 key 包的原生 ID 在校验过程中异常变化");
        if (duplicate(type, item)) throw new IOException("原生最终资源 ID 已存在，未覆盖现有资源");
        if (api.text(item, "getName").isEmpty()) api.set(item, "setName", String.class, title);
        if (type == 4 && api.text(item, "getSysFontResPath").isEmpty() && !api.bool(item, "isAiFont")) {
            state.phase = "核对字体应用条目";
            prepared.manifest.requireFontApplyEntry(api.text(item, "getName"));
        }
        if (type == 2 && api.text(item, "getPackageName").isEmpty() && !api.bool(item, "isOfficial")) {
            Object packageName = api.callStatic("com.bbk.theme.common.ThemeConstants", "getOnlineLivePkgName", new Class<?>[0]);
            if (!(packageName instanceof String) || ((String) packageName).isEmpty())
                throw new IOException("原生动态壁纸包名配置不可用");
            api.set(item, "setPackageName", String.class, packageName);
        }
        if (type == 2 && !prepared.manifest.has("livewallpaper/video_livewallpaper.mp4")
                && !"apk_res".equals(api.text(item, "getLWPackageType")))
            throw new IOException("动态壁纸包缺少原生视频核心，且不是原生 apk_res 格式");

        check.check();
        state.phase = "准备宿主保存目录";
        File configuredDirectory = new File(api.savePath(type));
        if (!configuredDirectory.isAbsolute()) throw new IOException("宿主保存目录不是绝对路径");
        File directory = configuredDirectory.getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建宿主资源保存目录");
        if (!directory.isDirectory()) throw new IOException("宿主资源保存位置不是目录");
        File destination = new File(directory, prepared.image ? prepared.file.getName() : ArchiveIO.generatedItzName());
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("生成的目标文件已存在，未覆盖");
        File part = ArchiveIO.createExclusive(directory, "." + destination.getName() + "." + UUID.randomUUID() + ".part");
        state.parts.add(part);
        ArchiveIO.copyToOwnedFile(prepared.file, part, ArchiveIO.MAX_INPUT_BYTES, check);
        File sidecarDestination = null;
        File sidecarPart = null;
        if (prepared.image) {
            String stem = prepared.file.getName().substring(0, prepared.file.getName().length() - 4);
            File cacheSidecar = new File(stage, stem);
            sidecarDestination = new File(directory, stem);
            if (Files.exists(sidecarDestination.toPath(), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("图片 info 目标已存在，未覆盖");
            sidecarPart = ArchiveIO.createExclusive(directory, "." + stem + "." + UUID.randomUUID() + ".part");
            state.parts.add(sidecarPart);
            ArchiveIO.copyToOwnedFile(cacheSidecar, sidecarPart, 64 * 1024, check);
        }

        // Rebind the real model BEFORE conversion/event: temporary cache paths are never stored.
        api.setDownloaded(item, destination);
        api.set(item, "setSize", String.class, String.valueOf(part.length()));
        api.set(item, "setDownloadTime", long.class, part.lastModified());
        NativeValues converter = new NativeValues(api);
        state.phase = "转换并检查数据库协议";
        ContentValues values = converter.fromItem(item, type);
        converter.requireProviderProtocol(type, values);
        check.check();
        if (duplicate(type, item)) throw new IOException("提交前资源 ID 发生冲突，未覆盖");
        state.phase = "保存资源和写入数据库";
        progress(observer, "正在原子保存资源并写入宿主数据库…");
        if (sidecarPart != null) {
            ArchiveIO.publishAtomic(sidecarPart, sidecarDestination);
            state.published.add(sidecarDestination);
        }
        check.check();
        ArchiveIO.publishAtomic(part, destination);
        state.published.add(destination);
        state.primary = destination;
        state.type = type;
        check.check();
        if (duplicate(type, item)) throw new IOException("落盘后资源 ID 发生并发冲突，正在保守检查引用");
        boolean inserted = api.insert(type, values);
        if (!inserted) throw new IOException("宿主 insertDb 返回失败；不会发送成功消息");
        state.committed = true;
        // Cancellation after this commit is not a rollback request. Keep the true DB/file pair.
        String warning = prepared.notice;
        try { api.postResourceChanged(item); }
        catch (Throwable refreshFailure) {
            ModuleLog.error("Resource inserted but event 8 publication failed", refreshFailure);
            warning = append(warning, "资源已入库，但列表刷新事件发送失败；请返回本地页重新刷新");
        }
        ModuleLog.log("Local import inserted type=" + type + ", name=" + ArchiveIO.title(api.text(item, "getName")));
        return new ImportResult(type, api.text(item, "getName"), destination, item, warning);
    }

    private Prepared prepare(File input, File stage, int type, String title, Observer observer,
                             ArchiveIO.Checkpoint check) throws Exception {
        ArchiveIO.Kind kind = ArchiveIO.sniff(input);
        Prepared result = new Prepared();
        result.notice = "";
        if (kind == ArchiveIO.Kind.OTF) throw new IOException("OTF 尚未完成原生字体引擎验证，本版本不支持");
        if (kind == ArchiveIO.Kind.ZIP) {
            result.file = new File(stage, "source.itz");
            ArchiveIO.publishAtomic(input, result.file);
        } else if ((kind == ArchiveIO.Kind.TTF || kind == ArchiveIO.Kind.VIVO_FONT) && type == 4) {
            progress(observer, "正在验证真实 TTF 并生成三张字体预览…");
            File font = FontPreviews.prepareOwnedFont(api, input, stage, check);
            if (font != input) result.notice = "宿主加密字体仅在本次缓存副本中解密，用户原文件未改动";
            Map<String, File> previews = FontPreviews.generate(font, stage, title, check);
            Ids ids = newIds(type);
            result.file = new File(stage, "font.itz");
            ArchiveIO.buildMediaItz(result.file, font, type, title, ids.packageId, ids.resId, previews, check);
        } else if (kind == ArchiveIO.Kind.MP4 && type == 2) {
            progress(observer, "正在验证真实视频轨道和解码帧…");
            File preview = MediaChecks.videoPreview(input, new File(stage, "video-preview.jpg"), check);
            Map<String, File> previews = new LinkedHashMap<>();
            previews.put("preview/preview_livewallpaper_0.jpg", preview);
            Ids ids = newIds(type);
            result.file = new File(stage, "video.itz");
            ArchiveIO.buildMediaItz(result.file, input, type, title, ids.packageId, ids.resId, previews, check);
        } else if (type == 9 && (kind == ArchiveIO.Kind.OTHER || kind == ArchiveIO.Kind.MP4)) {
            // HEIF/HEIC also start with an ISO-BMFF ftyp box. Only actual Bitmap decoding
            // decides here; a video ftyp does not make a video acceptable as an image.
            progress(observer, "正在验证真实图片并按原生命名转换为 JPEG…");
            Ids ids = newIds(type);
            result.file = new File(stage, nativeWallpaperName(ids));
            MediaChecks.ImageResult converted = MediaChecks.toJpeg(input, result.file, check);
            result.notice = converted.notice;
            result.image = true;
            result.item = imageItem(result.file, title, ids, check);
            return result;
        } else {
            throw new IOException(ImportDiagnostics.inputMismatch(input, type, kind));
        }
        progress(observer, "正在进行有界 ZIP、CRC、路径和 XML 安全检查…");
        result.manifest = ArchiveIO.inspect(result.file, check);
        requireCoreEntries(result.manifest, type);
        verifyPackagedMedia(result.file, result.manifest, stage, type, check);
        return result;
    }

    /** Presence is not an application-engine test; the host still performs its native checks. */
    private void requireCoreEntries(ArchiveIO.Manifest manifest, int type) throws Exception {
        String exact = null;
        String prefix = null;
        switch (type) {
            case 1: exact = "vivo"; break; // kg.g(1,...), including normal whole theme core
            case 7: exact = "screenclock/screenclock.zip"; break;
            case 12: exact = "inputmethod/theme_info.json"; break;
            case 18: exact = nativeCoreConstant("ICONS_ZIP_PATH"); break;
            case 20: exact = nativeCoreConstant("CONTROL_CENTER_RES_PATH"); break;
            case 13:
                if (!manifest.hasData("behavior") && !manifest.hasData("behavior/video.mp4"))
                    throw new IOException("行为壁纸包缺少原生 behavior 核心");
                return;
            case 16: prefix = "widget/"; break;
            case 2: prefix = "livewallpaper/"; break;
            case 5: prefix = "lockscreen/"; break;
            case 14: prefix = "videoringtone/"; break;
            default: return; // Fonts/images receive actual media validation below.
        }
        if (exact != null) {
            if (!manifest.hasData(exact)) throw new IOException("ITZ 缺少非空原生核心：" + exact);
        } else {
            boolean present = false;
            for (String entry : manifest.entries)
                if (entry.startsWith(prefix) && manifest.hasData(entry)) { present = true; break; }
            if (!present) throw new IOException("ITZ 缺少原生类别目录下的资源正文：" + prefix);
        }
    }

    private String nativeCoreConstant(String name) throws Exception {
        java.lang.reflect.Field field = api.clazz("com.bbk.theme.common.ThemeConstants").getDeclaredField(name);
        if (field.getType() != String.class || !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
            throw new NoSuchFieldException("Native core constant ABI: " + name);
        field.setAccessible(true);
        Object value = field.get(null);
        if (!(value instanceof String) || ((String) value).isEmpty()) throw new IOException("原生核心常量不可用：" + name);
        return (String) value;
    }

    private void verifyPackagedMedia(File archive, ArchiveIO.Manifest manifest, File stage, int type,
                                     ArchiveIO.Checkpoint check) throws Exception {
        if (type == 4) {
            int count = 0;
            long total = 0;
            for (String name : manifest.entries) {
                if (!name.startsWith("fonts/")) continue;
                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".otf"))
                    throw new IOException("字体包包含尚未验证的 OTF，暂不支持");
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".ttf")) continue;
                if (++count > 16) throw new IOException("字体包字体数量超过验证上限");
                File extracted = ArchiveIO.extractOwned(archive, name,
                        new File(stage, "verify-font-" + count + ".ttf"), ArchiveIO.MAX_FONT_BYTES, check);
                total += extracted.length();
                if (total > 128L * 1024 * 1024) throw new IOException("字体包字体总量超过验证上限");
                File readable = FontPreviews.prepareOwnedFont(api, extracted, stage, check);
                FontPreviews.verifiedTypeface(readable, check);
                if (!readable.equals(extracted)) Files.delete(readable.toPath());
                Files.delete(extracted.toPath());
            }
            if (count == 0) throw new IOException("字体包没有 fonts/ 下的真实 TTF 字体");
        } else if (type == 2 && manifest.has("livewallpaper/video_livewallpaper.mp4")) {
            File extracted = ArchiveIO.extractOwned(archive, "livewallpaper/video_livewallpaper.mp4",
                    new File(stage, "verify-video.mp4"), ArchiveIO.MAX_VIDEO_BYTES, check);
            File preview = MediaChecks.videoPreview(extracted, new File(stage, "verify-video-frame.jpg"), check);
            Files.delete(extracted.toPath());
            Files.delete(preview.toPath());
        } else if (type == 9) {
            int count = 0;
            for (String name : manifest.entries) {
                if (!(name.startsWith("wallpaper/") || name.startsWith("wallpaper_second/")) || name.endsWith("/")) continue;
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                        || lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".heif"))) continue;
                if (++count > 32) throw new IOException("壁纸包图片数量超过验证上限");
                File extracted = ArchiveIO.extractOwned(archive, name,
                        new File(stage, "verify-image-" + count + ".bin"), ArchiveIO.MAX_IMAGE_BYTES, check);
                MediaChecks.verifyImage(extracted, check);
                Files.delete(extracted.toPath());
            }
            if (count == 0) throw new IOException("静态壁纸 ITZ 没有原生目录下的真实可解码图片");
        }
    }

    private Object imageItem(File image, String name, Ids ids, ArchiveIO.Checkpoint check) throws Exception {
        Object model = api.itemClass.getConstructor().newInstance();
        api.set(model, "setPackageId", String.class, ids.packageId);
        api.set(model, "setResId", String.class, ids.resId);
        api.set(model, "setCategory", int.class, 9);
        api.set(model, "setName", String.class, name);
        api.set(model, "setDescription", String.class, "用户选择的本地图片");
        api.set(model, "setWallpaperJumpParam", String.class, "");
        api.set(model, "setWallpaperJumpType", int.class, 0);
        api.set(model, "setWallpaperJumpAppVercode", String.class, "");
        api.set(model, "setWallpaperJumpPkgName", String.class, "");
        api.set(model, "setWallpaperJumpAppName", String.class, "");
        api.setDownloaded(model, image);
        String json = api.text(model, "toWallpaperInfoString");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 64 * 1024) throw new IOException("原生图片 info 序列化结果无效");
        JSONObject parsed = new JSONObject(json);
        if (!ids.packageId.equals(parsed.getString("packageId")) || !ids.resId.equals(parsed.getString("resId"))
                || !name.equals(parsed.getString("name")) || !parsed.has("wallpaperJumpParam")
                || parsed.getInt("wallpaperJumpType") != 0)
            throw new IOException("图片 info 字段不符合当前原生协议");
        String stem = image.getName().substring(0, image.getName().length() - 4);
        File sidecar = new File(image.getParentFile(), stem); // native reader/writer use no extension
        ArchiveIO.writeExclusive(sidecar, bytes);
        check.check();
        Object roundTrip = api.callStatic(UTILS, "getWallpaperItemFromFile", new Class<?>[]{File.class}, image);
        requireIdentity(roundTrip, 9);
        if (!api.text(roundTrip, "getPackageId").equals(ids.packageId)
                || !api.text(roundTrip, "getResId").equals(ids.resId)
                || !api.text(roundTrip, "getName").equals(name))
            throw new IOException("原生读取图片 info 后的模型不一致");
        api.set(roundTrip, "setSize", String.class, String.valueOf(image.length()));
        api.set(roundTrip, "setEdition", int.class, 1); // generated local record revision; CV native minimum is 1
        return roundTrip;
    }

    private String nativeWallpaperName(Ids ids) throws Exception {
        String expected = ArchiveIO.wallpaperName(ids.packageId, ids.resId);
        Object actual = api.callStatic("x4.e", "u", new Class<?>[]{String.class, String.class}, ids.packageId, ids.resId);
        if (!expected.equals(actual)) throw new IOException("原生图片命名协议不匹配");
        return expected;
    }

    private Object basic(File file, int type) throws Exception {
        if (!file.getName().endsWith(".itz")) throw new IOException("原生解析缓存必须有真实 .itz 后缀");
        Object result = api.parseBasic(file, type, false, false);
        if (result == null) throw new IOException("原生清单解析失败，未伪造 ThemeItem");
        return result;
    }

    private boolean duplicate(int type, Object item) throws Exception {
        return api.exists(type, "resId=? OR uid=?", api.text(item, "getResId"), api.text(item, "getPackageId"));
    }

    private void requireIdentity(Object item, int type) throws Exception {
        if (item == null || !api.itemClass.isInstance(item) || api.integer(item, "getCategory") != type)
            throw new IOException("原生模型与所选资源类别不一致");
        String packageId = api.text(item, "getPackageId");
        String resourceId = api.text(item, "getResId");
        if (packageId.trim().isEmpty() || resourceId.trim().isEmpty() || packageId.length() > 256 || resourceId.length() > 256)
            throw new IOException("原生模型缺少有效资源 ID");
    }

    private enum Reference { REFERENCED, ABSENT, UNKNOWN }

    private Reference reference(File file) {
        boolean unknown = false;
        try {
            File canonical = file.getCanonicalFile();
            for (int type : TYPES) {
                try {
                    if (api.exists(type, "filename=?", file.getAbsolutePath())) return Reference.REFERENCED;
                    // Native scanners can store an alias (e.g. /data/data vs /data/user/0).
                    // Canonical comparison prevents exact-text query misses becoming deletion.
                    try (Cursor cursor = api.context.getContentResolver().query(api.dbUri(type),
                            new String[]{"filename"}, null, null, null)) {
                        if (cursor == null) { unknown = true; continue; }
                        int count = 0;
                        while (cursor.moveToNext()) {
                            if (++count > 10000) { unknown = true; break; }
                            if (cursor.isNull(0)) continue;
                            String stored = cursor.getString(0);
                            if (stored == null || stored.isEmpty()) continue;
                            File storedFile = new File(stored);
                            if (storedFile.isAbsolute() && storedFile.getCanonicalFile().equals(canonical))
                                return Reference.REFERENCED;
                        }
                    }
                } catch (Exception failedQuery) {
                    unknown = true;
                    ModuleLog.error("Reference query unknown for type=" + type, failedQuery);
                }
            }
        } catch (Exception failedPath) {
            unknown = true;
            ModuleLog.error("Canonical reference query failed", failedPath);
        }
        return unknown ? Reference.UNKNOWN : Reference.ABSENT;
    }

    private String cleanup(State state) throws Exception {
        String note = "";
        for (File part : state.parts) {
            try { Files.deleteIfExists(part.toPath()); }
            catch (IOException error) { ModuleLog.error("Owned .part cleanup failed", error); note = append(note, "临时文件清理失败，请查看日志"); }
        }
        if (!state.committed && !state.published.isEmpty()) {
            boolean preservePair = state.primary != null && reference(state.primary) != Reference.ABSENT;
            List<File> reverse = new ArrayList<>(state.published);
            Collections.reverse(reverse);
            for (File file : reverse) {
                Reference ref = preservePair ? Reference.UNKNOWN : reference(file);
                if (ref == Reference.ABSENT) {
                    try { Files.deleteIfExists(file.toPath()); }
                    catch (IOException error) { ModuleLog.error("Unreferenced owned file cleanup failed", error); note = append(note, "未能删除未引用的导入文件"); }
                } else {
                    ModuleLog.log("Retained owned import file; DB reference=" + ref + ", file=" + file.getAbsolutePath());
                    note = append(note, "文件已保留（数据库已引用或查询结果不确定）：" + file.getAbsolutePath());
                }
            }
        }
        if (state.rootCreated && state.root != null && state.cacheBase != null) {
            // Delete only this freshly-created owned cache tree; no follow of symbolic links.
            File canonical = state.root.getCanonicalFile();
            if (!canonical.getParentFile().equals(state.cacheBase) || !canonical.getName().startsWith("dsh_v24_import_"))
                throw new IOException("拒绝清理非本会话缓存目录");
            try {
                Files.walkFileTree(state.root.toPath(), new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException failedCleanup) {
                ModuleLog.error("Owned cache cleanup failed", failedCleanup);
                note = append(note, "本会话缓存未完全清理，请查看日志");
            }
        }
        return note;
    }

    private static void progress(Observer observer, String value) {
        try { observer.progress(value); }
        catch (Throwable callback) { ModuleLog.error("Import progress observer failed", callback); }
    }

    private static Ids newIds(int type) throws IOException {
        long random = (UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE) % 1000;
        long stamp = Math.max(1000000L, System.currentTimeMillis()) * 1000L + random;
        String packageId = String.valueOf(stamp);
        String resId = String.valueOf(type) + packageId;
        ArchiveIO.requireNumericId(packageId);
        ArchiveIO.requireNumericId(resId);
        return new Ids(packageId, resId);
    }

    private static void requireType(int type) throws IOException {
        for (int supported : TYPES) if (supported == type) return;
        throw new IOException("不支持的资源类别：" + type);
    }

    private static String stageLabel(int type) throws IOException {
        switch (type) {
            case 1: return "T";
            case 16: return "Widget";
            case 2: return "L";
            case 9: return "W";
            case 13: return "B";
            case 4: return "F";
            case 18: return "I";
            case 5: return "U";
            case 20: return "C2";
            case 7: return "C";
            case 14: return "V";
            case 12: return "I2";
            default: throw new IOException("不支持的资源类别");
        }
    }

    private static String append(String first, String second) {
        if (second == null || second.isEmpty()) return first == null ? "" : first;
        return first == null || first.isEmpty() ? second : first + "；" + second;
    }

    private static final class Ids {
        final String packageId;
        final String resId;
        Ids(String packageId, String resId) { this.packageId = packageId; this.resId = resId; }
    }
    private static final class Prepared {
        File file;
        Object item;
        ArchiveIO.Manifest manifest;
        boolean image;
        String notice;
    }
    private static final class State {
        String phase = "检查导入参数与缓存";
        File cacheBase;
        File root;
        File primary;
        int type;
        boolean committed;
        boolean rootCreated;
        final List<File> parts = new ArrayList<>();
        final List<File> published = new ArrayList<>();
    }
}
