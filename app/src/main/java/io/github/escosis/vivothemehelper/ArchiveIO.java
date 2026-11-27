package io.github.escosis.vivothemehelper;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Pure-Java, bounded outer-ITZ validation and construction. Never extracts untrusted paths. */
final class ArchiveIO {
    static final long MAX_INPUT_BYTES = 512L * 1024 * 1024;
    static final long MAX_FONT_BYTES = 32L * 1024 * 1024;
    static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    static final long MAX_VIDEO_BYTES = 256L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    static final int MAX_ENTRIES = 8192;
    static final int MAX_XML_BYTES = 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 256 * 1024;
    static final int MAX_PATH_LENGTH = 512;
    private static final int COPY_BUFFER = 32 * 1024;
    private static final int MAX_RATIO = 250;
    private static final Pattern ENCODING = Pattern.compile(
            "(?i)<\\?xml\\s+[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]");

    private ArchiveIO() {}

    interface Checkpoint { void check() throws IOException; }

    static final class Deadline implements Checkpoint {
        private final Thread owner;
        private final long started = System.nanoTime();
        private final long duration;

        Deadline(long time, TimeUnit unit) {
            owner = Thread.currentThread();
            duration = unit.toNanos(time);
        }

        boolean cancelled() { return owner.isInterrupted(); }
        boolean expired() { return System.nanoTime() - started >= duration; }

        @Override public void check() throws IOException {
            if (cancelled()) throw new InterruptedIOException("导入已取消");
            if (expired()) throw new IOException("导入处理超时，请使用较小的本地文件重试");
        }
    }

    enum Kind { ZIP, TTF, VIVO_FONT, OTF, MP4, OTHER }
    private static final byte[] VIVO_FONT_TRAILER = "VivoSettings".getBytes(StandardCharsets.US_ASCII);
    private static final long MAX_BMFF_HEADER_PREFIX = 1024L * 1024;

    static Kind sniff(File file) throws IOException {
        byte[] b = new byte[16];
        int length = 0;
        int stalled = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (length < b.length) {
                int count = in.read(b, length, b.length - length);
                if (count < 0) break;
                if (count == 0) { if (++stalled > 16) throw new IOException("文件头读取没有进展"); continue; }
                stalled = 0;
                length += count;
            }
        }
        if (length >= 4 && b[0] == 'P' && b[1] == 'K'
                && b[2] == 3 && b[3] == 4) return Kind.ZIP;
        if (length >= 4 && ((b[0] == 0 && b[1] == 1 && b[2] == 0 && b[3] == 0)
                || (b[0] == 't' && b[1] == 'r' && b[2] == 'u' && b[3] == 'e')))
            return Kind.TTF;
        if (length >= 4 && b[0] == 'O' && b[1] == 'T' && b[2] == 'T' && b[3] == 'O')
            return Kind.OTF;
        // ftyp is not required to be the FIRST box. Never search arbitrary payload bytes:
        // walk a bounded prefix of actual free/skip/wide/uuid boxes with real size boundaries.
        if (length >= 8 && hasBmffHeader(file)) return Kind.MP4;
        if (hasVivoFontTrailer(file)) return Kind.VIVO_FONT;
        return Kind.OTHER;
    }

    /** Same exact twelve-byte marker read by host ApplyThemeUtils, but read-only. */
    static boolean hasVivoFontTrailer(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < VIVO_FONT_TRAILER.length) return false;
            byte[] trailer = new byte[VIVO_FONT_TRAILER.length];
            input.seek(input.length() - trailer.length);
            input.readFully(trailer);
            return Arrays.equals(trailer, VIVO_FONT_TRAILER);
        }
    }

    private static boolean hasBmffHeader(File video) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(video, "r")) {
            long position = 0;
            for (int boxes = 0; boxes < 64 && position <= MAX_BMFF_HEADER_PREFIX; boxes++) {
                long[] box = bmffBox(file, position);
                if (box == null) return false;
                int type = (int) box[1];
                if (type == 0x66747970) return validFtyp(box);
                if (!bmffPrefixType(type) || (type == 0x75756964 && box[0] < box[2] + 16)) return false;
                position += box[0];
            }
            return false;
        }
    }

    private static boolean bmffPrefixType(int type) {
        return type == 0x66726565 || type == 0x736b6970 || type == 0x77696465 || type == 0x75756964;
    }

    private static boolean validFtyp(long[] box) {
        return box[0] >= box[2] + 8 && box[0] <= 4096 && ((box[0] - box[2] - 8) & 3) == 0;
    }

    /** Returns size/type/base-header bytes, or null for a truncated/overflowing box. */
    private static long[] bmffBox(RandomAccessFile file, long position) throws IOException {
        long remaining = file.length() - position;
        if (position < 0 || remaining < 8) return null;
        file.seek(position);
        long size = file.readInt() & 0xffffffffL;
        long type = file.readInt() & 0xffffffffL;
        int header = 8;
        if (size == 1) {
            if (remaining < 16) return null;
            size = file.readLong();
            header = 16;
        } else if (size == 0) size = remaining;
        if (size < header || size > remaining) return null;
        return new long[]{size, type, header};
    }

    /** Input/output ownership remains with the caller. Enforces actual, not advertised size. */
    static long copy(InputStream input, OutputStream output, long maximum, Checkpoint check)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        long count = 0;
        int zeroReads = 0;
        for (;;) {
            check.check();
            int n = input.read(buffer);
            check.check();
            if (n < 0) break;
            if (n == 0) {
                if (++zeroReads > 32) throw new IOException("输入流没有继续提供数据");
                continue;
            }
            zeroReads = 0;
            if (n > maximum - count) throw new IOException("文件或解压内容超过安全大小上限");
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

    static byte[] readBounded(InputStream input, int maximum, Checkpoint check) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maximum, 8192));
        copy(input, bytes, maximum, check);
        return bytes.toByteArray();
    }

    static File createExclusive(File directory, String basename) throws IOException {
        requireBasename(basename);
        File target = new File(directory, basename);
        if (!target.createNewFile()) throw new FileAlreadyExistsException(target.getAbsolutePath());
        return target;
    }

    static void writeExclusive(File target, byte[] bytes) throws IOException {
        requireBasename(target.getName());
        if (!target.createNewFile()) throw new FileAlreadyExistsException(target.getAbsolutePath());
        boolean completed = false;
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
            completed = true;
        } finally {
            if (!completed) Files.deleteIfExists(target.toPath());
        }
    }

    /** The destination is private cache or an exclusively-created .part, never a final name. */
    static void copyToOwnedFile(File source, File target, long maximum, Checkpoint check)
            throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            copy(in, out, maximum, check);
            out.flush();
            out.getFD().sync();
        }
    }

    /**
     * Same-directory atomic rename only; no copy fallback and no REPLACE_EXISTING request.
     * UUID names and an exclusive .part exclude cooperative importers. ATOMIC_MOVE cannot
     * express Linux RENAME_NOREPLACE: an adversarial external final-name race is documented.
     */
    static void publishAtomic(File part, File destination) throws IOException {
        if (!part.getParentFile().getCanonicalFile().equals(destination.getParentFile().getCanonicalFile()))
            throw new IOException("提交临时文件不在目标目录");
        requireBasename(destination.getName());
        if (!part.isFile() || part.length() == 0) throw new IOException("提交文件为空或已丢失");
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(destination.getAbsolutePath());
        Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        if (!destination.isFile()) throw new IOException("原子提交后的文件不可读");
    }

    static String generatedItzName() { return "local_" + UUID.randomUUID().toString() + ".itz"; }

    static String wallpaperName(String packageId, String resId) throws IOException {
        // Mirrors w4.f.u(String,String) exactly, with bounded numeric IDs generated by us.
        requireNumericId(packageId);
        requireNumericId(resId);
        return packageId.equals(resId) ? resId + ".jpg" : packageId + "--" + resId + ".jpg";
    }

    static String title(String displayName) {
        String name = displayName == null ? "" : displayName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return cleanTitle(name);
    }

    private static String cleanTitle(String name) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length() && safe.length() < 80; i++) {
            char c = name.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < name.length() && Character.isLowSurrogate(name.charAt(i + 1))
                        && safe.length() < 79) {
                    safe.append(c).append(name.charAt(++i));
                }
            } else if (!Character.isLowSurrogate(c) && c >= 0x20 && c != 0x7f
                    && c != 0xfffe && c != 0xffff && !(c >= 0x202a && c <= 0x202e)
                    && !(c >= 0x2066 && c <= 0x2069)) {
                safe.append(c);
            }
        }
        String result = safe.toString().trim();
        return result.isEmpty() ? "本地资源" : result;
    }

    static String xmlText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String description(int type, String name, String packageId, String resId)
            throws IOException {
        if (type != 4 && type != 2) throw new IOException("仅 TTF 与 MP4 可在本地构造 ITZ");
        requireNumericId(packageId);
        requireNumericId(resId);
        String escaped = xmlText(name);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<theme>\n"
                + "<id>" + packageId + "</id><uid>" + resId + "</uid><id3>" + packageId + "</id3>\n"
                + "<titles><title locale=\"zh_CN\">" + escaped + "</title>"
                + "<title locale=\"en_US\">" + escaped + "</title></titles>\n"
                + "<authors><author locale=\"zh_CN\">本地文件</author></authors>\n"
                + "<designers><designer locale=\"zh_CN\"></designer></designers>\n"
                + "<descriptions><description locale=\"zh_CN\">用户选择的本地文件</description></descriptions>\n"
                + "<edition>1</edition>\n"
                + (type == 2 ? "<extra_packageType>mp4</extra_packageType>\n" : "")
                + "</theme>\n";
    }

    static void buildMediaItz(File destination, File media, int type, String name,
                              String packageId, String resId, Map<String, File> previews,
                              Checkpoint check) throws IOException {
        // ApplyThemeHelper flattens the .ttf entry and looks for ThemeItem.name + ".ttf".
        // These titles already lack the input extension. lg.g skips ".." and "wallpaper_second" entries.
        String label = type == 4 ? cleanTitle(name == null ? "" : name)
                .replaceAll("[\\\\/:*?\"<>|]|\\.{2,}|\\.$", "_").replace("wallpaper_second", "wallpaper-second")
                : title(name);
        String xml = description(type, label, packageId, resId);
        String core = type == 4 ? "fonts/" + label + ".ttf" : "livewallpaper/video_livewallpaper.mp4";
        validateEntryName(core);
        long maximum = type == 4 ? MAX_FONT_BYTES : MAX_VIDEO_BYTES;
        if (media.length() == 0 || media.length() > maximum) throw new IOException("媒体大小不合要求");
        requireBasename(destination.getName());
        if (!destination.createNewFile()) throw new FileAlreadyExistsException(destination.getAbsolutePath());
        boolean done = false;
        try (FileOutputStream file = new FileOutputStream(destination);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(file))) {
            zip.setLevel(6);
            putBytes(zip, "description.xml", xml.getBytes(StandardCharsets.UTF_8));
            putFile(zip, core, media, maximum, check);
            Set<String> names = new HashSet<>();
            names.add("description.xml");
            names.add(core);
            for (Map.Entry<String, File> preview : previews.entrySet()) {
                validateEntryName(preview.getKey());
                if (!names.add(preview.getKey())) throw new IOException("重复构包条目");
                putFile(zip, preview.getKey(), preview.getValue(), MAX_IMAGE_BYTES, check);
            }
            zip.finish();
            zip.flush();
            file.getFD().sync();
            done = true;
        } finally {
            if (!done) Files.deleteIfExists(destination.toPath());
        }
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, File file, long maximum,
                                Checkpoint check) throws IOException {
        if (!file.isFile() || file.length() == 0) throw new IOException("构包输入为空");
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(file)) {
            copy(in, zip, maximum, check);
        }
        zip.closeEntry();
    }

    static final class Manifest {
        final String xml;
        final String packageId;
        final String resId;
        final String oldId;
        final boolean keyless;
        final boolean simpleIds;
        final List<String> entries;
        final Map<String, Long> sizes;

        Manifest(String xml, XmlGuard guard, boolean keyless, Map<String, CentralEntry> entries) {
            this.xml = xml;
            packageId = guard.values.get("id3");
            resId = guard.values.get("uid");
            oldId = guard.values.get("id");
            this.keyless = keyless;
            simpleIds = "theme".equals(guard.root) && guard.canRewrite;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries.keySet()));
            Map<String, Long> lengths = new LinkedHashMap<>();
            for (CentralEntry entry : entries.values()) lengths.put(entry.name, entry.size);
            this.sizes = Collections.unmodifiableMap(lengths);
        }

        boolean hasData(String name) {
            Long length = sizes.get(name);
            return length != null && length > 0 && !name.endsWith("/");
        }

        boolean supportsRewrite(int type) {
            return type != 16 && keyless && simpleIds;
        }

        boolean has(String name) { return entries.contains(name); }

        void requireFontApplyEntry(String label) throws IOException {
            if (label == null || label.isEmpty()) throw new IOException("字体缺少 vivo主题显示名");
            String filename = label + ".ttf";
            requireBasename(filename);
            if (filename.getBytes(StandardCharsets.UTF_8).length > 255)
                throw new IOException("字体显示名过长，无法作为应用时的文件名");
            String match = null;
            for (String entry : entries) {
                if (!filename.equals(entry.substring(entry.lastIndexOf('/') + 1))) continue;
                if (match != null) throw new IOException("字体包有多个同名 .ttf，应用时无法区分");
                match = entry;
            }
            if (match == null || !hasData(match))
                throw new IOException("字体包缺少与 vivo主题显示名一致的非空 .ttf（区分大小写）");
        }
    }

    static Manifest inspect(File archive, Checkpoint check) throws IOException {
        if (!archive.isFile() || archive.length() == 0 || archive.length() > MAX_INPUT_BYTES)
            throw new IOException("ITZ 文件为空或超过安全大小上限");
        List<CentralEntry> central = validateCentralDirectory(archive, check);
        Map<String, CentralEntry> byName = new LinkedHashMap<>();
        Set<String> lowerNames = new HashSet<>();
        for (CentralEntry entry : central) {
            validateEntryName(entry.name);
            String lower = entry.name.toLowerCase(Locale.ROOT);
            if (byName.put(entry.name, entry) != null || !lowerNames.add(lower))
                throw new IOException("ZIP 有重复或大小写歧义条目");
        }
        // A file cannot also be an ancestor directory, even with a differently-cased name.
        Set<String> fileNames = new HashSet<>();
        for (CentralEntry entry : central)
            if (!entry.name.endsWith("/")) fileNames.add(entry.name.toLowerCase(Locale.ROOT));
        for (CentralEntry entry : central) {
            for (int at = entry.name.indexOf('/'); at >= 0; at = entry.name.indexOf('/', at + 1))
                if (fileNames.contains(entry.name.substring(0, at).toLowerCase(Locale.ROOT)))
                    throw new IOException("ZIP 文件与目录路径冲突");
        }
        byte[] manifest = null;
        boolean keyless = true;
        long expanded = 0;
        int seen = 0;
        Set<String> readNames = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                check.check();
                ZipEntry entry = all.nextElement();
                CentralEntry expected = byName.get(entry.getName());
                if (expected == null || !readNames.add(entry.getName()) || ++seen > MAX_ENTRIES
                        || entry.getSize() != expected.size || entry.getCompressedSize() != expected.compressed
                        || entry.getCrc() != expected.crc || entry.getMethod() != expected.method
                        || entry.isDirectory() != expected.name.endsWith("/"))
                    throw new IOException("ZIP 目录信息不一致：" + entry.getName() + "，ZipFile 读取 size="
                            + entry.getSize() + ", compressed=" + entry.getCompressedSize()
                            + ", expected=" + (expected == null ? "missing" : expected.size + "/" + expected.compressed));
                if (entry.getSize() > MAX_ENTRY_BYTES || entry.getSize() > MAX_EXPANDED_BYTES - expanded)
                    throw new IOException("ZIP 解压大小超过上限");
                if (entry.getSize() > 1024 * 1024
                        && entry.getSize() > Math.max(1L, entry.getCompressedSize()) * MAX_RATIO)
                    throw new IOException("ZIP 压缩膨胀比超过上限");
                boolean xml = entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml");
                int xmlMaximum = "description.xml".equals(entry.getName()) ? MAX_MANIFEST_BYTES : MAX_XML_BYTES;
                if (xml && entry.getSize() > xmlMaximum) throw new IOException("XML 内容超过安全大小上限");
                ByteArrayOutputStream text = xml ? new ByteArrayOutputStream((int) entry.getSize()) : null;
                CRC32 crc = new CRC32();
                long size = 0;
                byte[] buffer = new byte[COPY_BUFFER];
                try (InputStream in = zip.getInputStream(entry)) {
                    int zero = 0;
                    for (;;) {
                        check.check();
                        int n = in.read(buffer);
                        if (n < 0) break;
                        if (n == 0) {
                            if (++zero > 32) throw new IOException("ZIP 条目读取停滞");
                            continue;
                        }
                        zero = 0;
                        if (n > expected.size - size || n > MAX_ENTRY_BYTES - size
                                || n > MAX_EXPANDED_BYTES - expanded)
                            throw new IOException("ZIP 实际解压量超过目录声明或安全上限");
                        size += n;
                        expanded += n;
                        crc.update(buffer, 0, n);
                        if (text != null) {
                            if (size > xmlMaximum) throw new IOException("XML 实际内容超过上限");
                            text.write(buffer, 0, n);
                        }
                    }
                }
                if (size != expected.size || crc.getValue() != expected.crc)
                    throw new IOException("ZIP 条目长度或 CRC 校验失败");
                if (entry.isDirectory() && size != 0) throw new IOException("ZIP 目录带有正文");
                if ("key".equals(entry.getName()) && size != 0) keyless = false;
                if (text != null) {
                    byte[] data = text.toByteArray();
                    parseXml(data, "description.xml".equals(entry.getName()), check);
                    if ("description.xml".equals(entry.getName())) manifest = data;
                }
            }
        }
        if (seen != central.size()) throw new IOException("ZIP 条目计数不一致");
        if (manifest == null) throw new IOException("ITZ 缺少根 description.xml");
        XmlGuard guard = parseXml(manifest, true, check);
        return new Manifest(utf8(manifest), guard, keyless, byName);
    }

    static File extractOwned(File archive, String entryName, File destination, long maximum,
                             Checkpoint check) throws IOException {
        validateEntryName(entryName);
        if (!destination.createNewFile()) throw new FileAlreadyExistsException(destination.getAbsolutePath());
        boolean done = false;
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory() || entry.getSize() <= 0 || entry.getSize() > maximum)
                throw new IOException("媒体条目缺失、为空或过大");
            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream out = new FileOutputStream(destination)) {
                long actual = copy(in, out, maximum, check);
                if (actual != entry.getSize()) throw new IOException("媒体条目长度变化");
                out.flush();
                out.getFD().sync();
            }
            done = true;
            return destination;
        } finally {
            if (!done) Files.deleteIfExists(destination.toPath());
        }
    }

    /** Only literal, unambiguous root id/uid/id3 leaves of a physically keyless theme. */
    static Manifest rewriteIds(File source, File destination, Manifest before, int type,
                               String newPackage, String newResource, Checkpoint check) throws IOException {
        if (!before.supportsRewrite(type)) throw new IOException("该包的清单或 key 不支持安全修改 ID");
        requireNumericId(newPackage);
        requireNumericId(newResource);
        String xml = replaceId(before.xml, "id", newPackage);
        xml = replaceId(xml, "uid", newResource);
        xml = replaceId(xml, "id3", newPackage);
        XmlGuard rewritten = parseXml(xml.getBytes(StandardCharsets.UTF_8), true, check);
        if (!newPackage.equals(rewritten.values.get("id3")) || !newResource.equals(rewritten.values.get("uid")))
            throw new IOException("修改 ID 后的清单校验失败");
        if (!destination.createNewFile()) throw new FileAlreadyExistsException(destination.getAbsolutePath());
        boolean done = false;
        try (ZipFile zip = new ZipFile(source);
             FileOutputStream file = new FileOutputStream(destination);
             ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(file))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            long total = 0;
            while (entries.hasMoreElements()) {
                check.check();
                ZipEntry original = entries.nextElement();
                validateEntryName(original.getName());
                ZipEntry entry = new ZipEntry(original.getName());
                // Preserve STORED core entries (several host consumers expect that layout).
                if (original.getMethod() == ZipEntry.STORED && !"description.xml".equals(original.getName())) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(original.getSize());
                    entry.setCompressedSize(original.getSize());
                    entry.setCrc(original.getCrc());
                }
                if (original.getTime() >= 0) entry.setTime(original.getTime());
                out.putNextEntry(entry);
                if ("description.xml".equals(original.getName())) {
                    out.write(xml.getBytes(StandardCharsets.UTF_8));
                } else {
                    try (InputStream in = zip.getInputStream(original)) {
                        total += copy(in, out, Math.min(MAX_ENTRY_BYTES, MAX_EXPANDED_BYTES - total), check);
                    }
                }
                out.closeEntry();
            }
            out.finish();
            out.flush();
            file.getFD().sync();
            done = true;
        } finally {
            if (!done) Files.deleteIfExists(destination.toPath());
        }
        return inspect(destination, check);
    }

    private static String replaceId(String xml, String tag, String replacement) throws IOException {
        Pattern pattern = Pattern.compile("(?s)<" + tag + ">[^<]*</" + tag + ">");
        Matcher matcher = pattern.matcher(xml);
        if (!matcher.find()) throw new IOException("不支持该 ID 标签格式");
        String result = matcher.replaceFirst(Matcher.quoteReplacement("<" + tag + ">" + replacement + "</" + tag + ">"));
        if (matcher.find()) throw new IOException("ID 标签不唯一");
        return result;
    }

    private static XmlGuard parseXml(byte[] bytes, boolean manifest, Checkpoint check) throws IOException {
        String xml = utf8(bytes);
        if (!xml.isEmpty() && xml.charAt(0) == '\ufeff') xml = xml.substring(1);
        if (xml.indexOf('\u0000') >= 0 || xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")
                || xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))
            throw new IOException("不允许 XML DTD、实体声明或非 UTF-8 内容");
        Matcher encoding = ENCODING.matcher(xml);
        if (encoding.find() && !"UTF-8".equalsIgnoreCase(encoding.group(1)))
            throw new IOException("宿主清单只支持明确的 UTF-8 编码");
        XmlGuard guard = new XmlGuard(manifest, xml, check);
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            // Android's Expat and the JDK do not expose exactly the same feature set.
            // The mandatory decoded-text DTD/entity rejection above is independent of it.
            disableIfSupported(reader, "http://xml.org/sax/features/external-general-entities");
            disableIfSupported(reader, "http://xml.org/sax/features/external-parameter-entities");
            disableIfSupported(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd");
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML entity rejected"); });
            reader.setContentHandler(guard);
            reader.setErrorHandler(guard);
            reader.parse(new InputSource(new StringReader(xml)));
            guard.finish();
            return guard;
        } catch (Exception failure) {
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("XML 不完整、过深或清单 ID 有歧义", failure);
        }
    }

    private static void disableIfSupported(XMLReader reader, String feature) {
        try { reader.setFeature(feature, false); } catch (SAXException ignored) { /* lexical deny is mandatory */ }
    }

    private static final class XmlGuard extends DefaultHandler {
        final boolean manifest;
        final String original;
        final Checkpoint check;
        final Map<String, String> values = new HashMap<>();
        final Map<String, Integer> counts = new HashMap<>();
        String root;
        int depth;
        int nodes;
        int characters;
        String leaf;
        StringBuilder text;
        boolean canRewrite = true;

        XmlGuard(boolean manifest, String original, Checkpoint check) {
            this.manifest = manifest;
            this.original = original;
            this.check = check;
        }

        @Override public void startElement(String uri, String local, String qualified, Attributes attributes)
                throws SAXException {
            checkpoint();
            if (++depth > 40 || ++nodes > 20000 || attributes.getLength() > 64)
                throw new SAXException("XML complexity limit");
            String name = qualified.isEmpty() ? local : qualified;
            if (depth == 1) root = name;
            if (manifest && ("id".equals(name) || "uid".equals(name) || "id3".equals(name))) {
                if (depth != 2 || attributes.getLength() != 0 || !uri.isEmpty()) canRewrite = false;
                int count = counts.containsKey(name) ? counts.get(name) + 1 : 1;
                counts.put(name, count);
                if (count > 1) throw new SAXException("Duplicate manifest ID");
                leaf = name;
                text = new StringBuilder();
            } else if (text != null) {
                throw new SAXException("Nested manifest ID");
            }
            if (manifest && ("zip_id".equals(name) || "packageId".equals(name) || "resId".equals(name)))
                canRewrite = false;
        }

        @Override public void characters(char[] chars, int offset, int length) throws SAXException {
            checkpoint();
            characters += length;
            if (characters > MAX_XML_BYTES) throw new SAXException("XML text limit");
            if (text != null) {
                if (text.length() + length > 256) throw new SAXException("Manifest ID too long");
                text.append(chars, offset, length);
            }
        }

        @Override public void endElement(String uri, String local, String qualified) {
            String name = qualified.isEmpty() ? local : qualified;
            if (text != null && name.equals(leaf)) {
                values.put(name, text.toString().trim());
                text = null;
                leaf = null;
            }
            depth--;
        }

        @Override public void error(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
        @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }

        void checkpoint() throws SAXException {
            try { check.check(); } catch (IOException cancelled) { throw new SAXException(cancelled); }
        }

        void finish() throws IOException {
            if (depth != 0 || root == null) throw new IOException("XML 不完整");
            if (!manifest) return;
            if (!values.containsKey("id") && !values.containsKey("id3"))
                throw new IOException("ITZ 清单没有资源 ID");
            for (String name : Arrays.asList("id", "uid", "id3")) {
                String value = values.get(name);
                if (value == null || value.isEmpty()) { canRewrite = false; continue; }
                Matcher literal = Pattern.compile("(?s)<" + name + ">[^<]*</" + name + ">").matcher(original);
                if (!literal.find()) canRewrite = false;
                else if (literal.find()) canRewrite = false;
            }
            // Refuse an ID referred to outside the three leaves; do not repair linked metadata.
            String withoutIds = original.replaceAll("(?s)<(?:id|uid|id3)>[^<]*</(?:id|uid|id3)>", "");
            for (String value : values.values()) {
                if (value != null && value.length() >= 4 && withoutIds.contains(value)) canRewrite = false;
            }
        }
    }

    static void validateEntryName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.length() > MAX_PATH_LENGTH
                || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0)
            throw new IOException("ZIP 条目路径不安全");
        int depth = 0;
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() && i == parts.length - 1 && name.endsWith("/")) continue;
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)
                    || part.endsWith(".") || part.endsWith(" ") || ++depth > 32)
                throw new IOException("ZIP 条目路径包含穿越或歧义组件");
            for (int c = 0; c < part.length(); c++) {
                char value = part.charAt(c);
                if (value < 0x20 || value == 0x7f || value == 0xfffe || value == 0xffff)
                    throw new IOException("ZIP 路径包含控制字符");
            }
        }
    }

    private static void requireBasename(String name) throws IOException {
        validateEntryName(name);
        if (name.indexOf('/') >= 0) throw new IOException("目标文件名不能包含目录");
    }

    static void requireNumericId(String id) throws IOException {
        if (id == null || !id.matches("[0-9]{5,18}")) throw new IOException("本地资源 ID 不符合数字协议");
    }

    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("ZIP 名称或 XML 不是有效 UTF-8", error);
        }
    }

    /** Shared, read-only structural metadata, in CENTRAL order (native g() uses that order). */
    static final class CentralEntry {
        String name;
        byte[] nameBytes;
        byte[] commentBytes;
        int flags;
        int method;
        int versionNeeded;
        long crc;
        long size;
        long compressed;
        long offset;
        long dataEnd; // Includes the validated optional data descriptor, but not alignment padding.
        boolean zip64Sizes;
    }

    private static final long ZIP32_SENTINEL = 0xffffffffL;
    private static final long MAX_CENTRAL_BYTES = 16L * 1024 * 1024;
    private static final long MAX_ZIP_PADDING_BYTES = 1024L * 1024;

    private static final class ZipDirectory {
        long offset;
        long size;
        long end;
        int count;
    }

    private static final class ZipExtra {
        byte[] zip64;
    }

    /**
     * Shared by inspect and the missing-key probe. No extraction, decompression or file writes.
     * ZIP64 is parsed, not ignored: all sentinels need matching bounded 64-bit values. Unicode
     * path extras must agree with the exact UTF-8 name the host's ZipFile reads. Only verified
     * zero alignment padding is allowed between physically sorted local records; central order
     * may differ. CRC and actual decompressed-byte verification remain the caller's obligation.
     */
    static List<CentralEntry> validateCentralDirectory(File archive, Checkpoint check) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive, "r")) {
            long length = file.length();
            if (length <= 0 || length > MAX_INPUT_BYTES) throw new IOException("ZIP 输入大小超过上限");
            ZipDirectory directory = zipDirectory(file, length);
            long cursor = directory.offset;
            long declaredExpanded = 0;
            List<CentralEntry> result = new ArrayList<>();
            Set<String> names = new HashSet<>();
            Set<String> files = new HashSet<>();
            for (int index = 0; index < directory.count; index++) {
                check.check();
                byte[] header = zipReadAt(file, cursor, 46, directory.end);
                if (u32(header, 0) != 0x02014b50L) throw new IOException("ZIP 中央目录无效");
                CentralEntry entry = new CentralEntry();
                entry.versionNeeded = u16(header, 6);
                entry.flags = u16(header, 8);
                entry.method = u16(header, 10);
                entry.crc = u32(header, 16);
                long rawCompressed = u32(header, 20);
                long rawSize = u32(header, 24);
                long rawOffset = u32(header, 42);
                int rawDisk = u16(header, 34);
                int nameLength = u16(header, 28);
                int extraLength = u16(header, 30);
                int commentLength = u16(header, 32);
                // Only normal STORED/DEFLATED, deflate-option, descriptor and UTF-8 flags.
                if ((entry.flags & ~0x080e) != 0 || (entry.method != 0 && entry.method != 8)
                        || (entry.method == 0 && (entry.flags & 6) != 0)
                        || nameLength == 0 || nameLength > MAX_PATH_LENGTH * 4)
                    throw new IOException("不支持加密、补丁、保留标志或非标准 ZIP 条目");
                long variable = cursor + 46;
                long next = zipRangeEnd(variable, (long) nameLength + extraLength + commentLength,
                        directory.end, "ZIP 中央目录越界");
                entry.nameBytes = zipReadAt(file, variable, nameLength, directory.end);
                entry.name = utf8(entry.nameBytes);
                validateEntryName(entry.name);
                String lower = entry.name.toLowerCase(Locale.ROOT);
                if (!names.add(lower)) throw new IOException("ZIP 有重复或大小写歧义条目");
                if (!entry.name.endsWith("/")) files.add(lower);
                byte[] extra = zipReadAt(file, variable + nameLength, extraLength, directory.end);
                entry.commentBytes = zipReadAt(file, variable + nameLength + extraLength, commentLength, directory.end);
                // ZipFile may decode comments while materializing a ZipEntry. Do not hide a
                // name/comment encoding mismatch behind a different parser's legacy charset.
                if (entry.commentBytes.length != 0) utf8(entry.commentBytes);
                ZipExtra parsed = zipExtra(extra, entry.nameBytes, entry.name, entry.commentBytes);
                resolveCentralZip64(entry, parsed.zip64, rawSize, rawCompressed, rawOffset, rawDisk);
                if (entry.size > MAX_ENTRY_BYTES || entry.compressed > MAX_INPUT_BYTES
                        || entry.size > MAX_EXPANDED_BYTES - declaredExpanded
                        || (entry.method == 0 && entry.compressed != entry.size)
                        || (entry.size > 0 && entry.compressed == 0))
                    throw new IOException("ZIP 条目大小或总解压量超过上限，或 STORED 长度不一致");
                if (entry.size > 1024 * 1024 && entry.size > Math.max(1L, entry.compressed) * MAX_RATIO)
                    throw new IOException("ZIP 压缩膨胀比超过上限");
                declaredExpanded += entry.size;
                validateZipEntryType(entry, u16(header, 4) >>> 8, u32(header, 38));
                result.add(entry);
                cursor = next;
            }
            if (cursor != directory.end) throw new IOException("ZIP 中央目录含未支持的多余记录");
            for (CentralEntry entry : result) {
                String lower = entry.name.toLowerCase(Locale.ROOT);
                for (int at = lower.indexOf('/'); at >= 0; at = lower.indexOf('/', at + 1))
                    if (files.contains(lower.substring(0, at))) throw new IOException("ZIP 文件与目录路径冲突");
            }

            List<CentralEntry> sorted = new ArrayList<>(result);
            sorted.sort(Comparator.comparingLong(entry -> entry.offset));
            if (sorted.get(0).offset != 0) throw new IOException("ZIP 含未支持的前置记录");
            long previousEnd = 0;
            long padding = 0;
            for (int index = 0; index < sorted.size(); index++) {
                check.check();
                CentralEntry entry = sorted.get(index);
                long limit = index + 1 < sorted.size() ? sorted.get(index + 1).offset : directory.offset;
                if (entry.offset < previousEnd || limit <= entry.offset || limit > directory.offset)
                    throw new IOException("ZIP 本地条目重叠或共用偏移：" + entry.name);
                byte[] header = zipReadAt(file, entry.offset, 30, limit);
                if (u32(header, 0) != 0x04034b50L || u16(header, 6) != entry.flags
                        || u16(header, 8) != entry.method || u16(header, 26) != entry.nameBytes.length)
                    throw new IOException("ZIP 本地与中央条目协议不一致：" + entry.name);
                int nameLength = u16(header, 26);
                int extraLength = u16(header, 28);
                long dataStart = zipRangeEnd(entry.offset + 30, (long) nameLength + extraLength,
                        limit, "ZIP 本地头越界或与下一条目重叠");
                byte[] localName = zipReadAt(file, entry.offset + 30, nameLength, limit);
                if (!Arrays.equals(localName, entry.nameBytes)) throw new IOException("ZIP 本地名称被伪造");
                ZipExtra localExtra = zipExtra(zipReadAt(file, entry.offset + 30 + nameLength,
                        extraLength, limit), localName, entry.name, entry.commentBytes);
                boolean descriptor = (entry.flags & 8) != 0;
                boolean local64 = validateLocalZip64(entry, header, localExtra.zip64, descriptor);
                if (!zipLocalValue(u32(header, 14), entry.crc, descriptor))
                    throw new IOException("ZIP 本地 CRC 与中央目录不一致");
                entry.dataEnd = zipRangeEnd(dataStart, entry.compressed, limit,
                        "ZIP 条目数据越界或与下一条目重叠：" + entry.name);
                if (descriptor) {
                    boolean wide = local64 || (entry.zip64Sizes && u16(header, 4) >= 45);
                    entry.dataEnd = zipDescriptorEnd(file, entry.dataEnd, limit, entry, wide);
                }
                padding += zipPadding(file, entry.dataEnd, limit, MAX_ZIP_PADDING_BYTES - padding, check);
                previousEnd = entry.dataEnd;
            }
            return Collections.unmodifiableList(result);
        }
    }

    private static ZipDirectory zipDirectory(RandomAccessFile file, long length) throws IOException {
        int tailLength = (int) Math.min(length, 65557);
        if (tailLength < 22) throw new IOException("ZIP 文件被截断");
        byte[] tail = zipReadAt(file, length - tailLength, tailLength, length);
        int end = -1;
        for (int i = tail.length - 22; i >= 0; i--) {
            if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) {
                end = i;
                break;
            }
        }
        if (end < 0 || u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0)
            throw new IOException("ZIP 结束目录缺失或使用多卷格式");
        long eocd = length - tailLength + end;
        long count = u16(tail, end + 10);
        long diskCount = u16(tail, end + 8);
        long size = u32(tail, end + 12);
        long offset = u32(tail, end + 16);
        long centralEnd = eocd;
        boolean sentinel = count == 0xffff || diskCount == 0xffff || size == ZIP32_SENTINEL || offset == ZIP32_SENTINEL;
        byte[] locator = eocd >= 20 ? zipReadAt(file, eocd - 20, 20, eocd) : null;
        boolean hasLocator = locator != null && u32(locator, 0) == 0x07064b50L;
        if (sentinel && !hasLocator) throw new IOException("ZIP64 哨兵缺少真实结束定位记录");
        if (hasLocator) {
            if (u32(locator, 4) != 0 || u32(locator, 16) != 1)
                throw new IOException("ZIP64 不支持多卷格式");
            long recordOffset = zipU64(locator, 8);
            byte[] record = zipReadAt(file, recordOffset, 56, eocd - 20);
            // The standard 4.5 record is fully supported. Unknown extensible/encrypted end
            // sectors are not interpreted or silently trusted as an ordinary ZIP64 footer.
            if (u32(record, 0) != 0x06064b50L || zipU64(record, 4) != 44
                    || recordOffset + 56 != eocd - 20 || u16(record, 14) < 45
                    || u32(record, 16) != 0 || u32(record, 20) != 0)
                throw new IOException("ZIP64 结束记录边界、版本或卷号无效，或使用未支持扩展");
            long actualCount = zipU64(record, 32);
            long actualDiskCount = zipU64(record, 24);
            long actualSize = zipU64(record, 40);
            long actualOffset = zipU64(record, 48);
            if (actualDiskCount != actualCount || !zipLegacyValue(count, 0xffff, actualCount)
                    || !zipLegacyValue(diskCount, 0xffff, actualDiskCount)
                    || !zipLegacyValue(size, ZIP32_SENTINEL, actualSize)
                    || !zipLegacyValue(offset, ZIP32_SENTINEL, actualOffset))
                throw new IOException("ZIP32 与 ZIP64 结束记录相互矛盾");
            count = actualCount; diskCount = actualDiskCount; size = actualSize; offset = actualOffset;
            centralEnd = recordOffset;
        }
        if (count == 0 || count > MAX_ENTRIES || diskCount != count)
            throw new IOException("ZIP 条目数超过上限或目录不一致");
        if (size > MAX_CENTRAL_BYTES || zipRangeEnd(offset, size, centralEnd,
                "ZIP 中央目录偏移或长度越界") != centralEnd)
            throw new IOException("ZIP 中央目录大小超过上限或存在隐藏尾部");
        ZipDirectory result = new ZipDirectory();
        result.offset = offset; result.size = size; result.end = centralEnd; result.count = (int) count;
        return result;
    }

    private static ZipExtra zipExtra(byte[] bytes, byte[] rawName, String name, byte[] rawComment) throws IOException {
        ZipExtra result = new ZipExtra();
        boolean pathSeen = false;
        boolean commentSeen = false;
        int at = 0;
        while (at + 4 <= bytes.length) {
            int tag = u16(bytes, at);
            int size = u16(bytes, at + 2);
            at += 4;
            if (size > bytes.length - at) throw new IOException("ZIP 扩展字段被截断");
            if (tag == 1) {
                if (result.zip64 != null) throw new IOException("ZIP64 扩展字段重复");
                result.zip64 = Arrays.copyOfRange(bytes, at, at + size);
            } else if (tag == 0x7075 || tag == 0x6375) {
                if ((tag == 0x7075 && pathSeen) || (tag == 0x6375 && commentSeen))
                    throw new IOException("Unicode ZIP 扩展字段重复");
                if (tag == 0x7075) pathSeen = true; else commentSeen = true;
                if (size < 5 || bytes[at] != 1) throw new IOException("Unicode ZIP 扩展版本或长度无效");
                CRC32 crc = new CRC32(); crc.update(tag == 0x7075 ? rawName : rawComment);
                if (u32(bytes, at + 1) != crc.getValue()) throw new IOException("Unicode ZIP 扩展与原始名称或注释 CRC 不一致");
                String unicode = utf8(Arrays.copyOfRange(bytes, at + 5, at + size));
                if (tag == 0x7075) {
                    validateEntryName(unicode);
                    if (!name.equals(unicode)) throw new IOException("Unicode ZIP 扩展与宿主读取的名称不一致");
                }
            } else if (tag == 0x9901 || tag == 0x0017) {
                throw new IOException("ZIP 不支持 AES 或强加密扩展");
            }
            at += size;
        }
        if (at != bytes.length) throw new IOException("ZIP 扩展字段被截断");
        return result;
    }

    private static void resolveCentralZip64(CentralEntry entry, byte[] extra, long size,
            long compressed, long offset, int disk) throws IOException {
        boolean size64 = size == ZIP32_SENTINEL;
        boolean compressed64 = compressed == ZIP32_SENTINEL;
        boolean offset64 = offset == ZIP32_SENTINEL;
        boolean disk64 = disk == 0xffff;
        entry.zip64Sizes = size64 || compressed64;
        int required = (size64 ? 8 : 0) + (compressed64 ? 8 : 0) + (offset64 ? 8 : 0) + (disk64 ? 4 : 0);
        if (required != 0) {
            if (entry.versionNeeded < 45 || extra == null || extra.length != required)
                throw new IOException("ZIP64 必需扩展缺失、长度不一致或版本不足");
            int at = 0;
            if (size64) { size = zipU64(extra, at); at += 8; }
            if (compressed64) { compressed = zipU64(extra, at); at += 8; }
            if (offset64) { offset = zipU64(extra, at); at += 8; }
            if (disk64) disk = (int) u32(extra, at);
        } else if (extra != null) {
            // A redundant full-order prefix is safe only if EVERY value agrees with the
            // legacy header. Do not ignore alternate values a different ZIP reader may use.
            if (extra.length != 0 && extra.length != 8 && extra.length != 16 && extra.length != 24 && extra.length != 28)
                throw new IOException("ZIP64 冗余扩展字段长度有歧义");
            if ((extra.length >= 8 && zipU64(extra, 0) != size)
                    || (extra.length >= 16 && zipU64(extra, 8) != compressed)
                    || (extra.length >= 24 && zipU64(extra, 16) != offset)
                    || (extra.length == 28 && u32(extra, 24) != disk))
                throw new IOException("ZIP64 冗余扩展与中央目录相互矛盾");
        }
        if (disk != 0) throw new IOException("ZIP 条目引用其它卷");
        entry.size = size; entry.compressed = compressed; entry.offset = offset;
    }

    private static boolean validateLocalZip64(CentralEntry entry, byte[] header, byte[] extra,
            boolean descriptor) throws IOException {
        long rawCompressed = u32(header, 18);
        long rawSize = u32(header, 22);
        boolean sentinel = rawSize == ZIP32_SENTINEL || rawCompressed == ZIP32_SENTINEL;
        long size = rawSize;
        long compressed = rawCompressed;
        boolean wide = extra != null && extra.length != 0;
        if (sentinel || wide) {
            // A local ZIP64 header must carry BOTH size values, including streaming zeros.
            if (u16(header, 4) < 45 || extra == null || extra.length != 16)
                throw new IOException("ZIP64 本地头缺少完整的双长度扩展");
            long actualSize = zipU64(extra, 0);
            long actualCompressed = zipU64(extra, 8);
            if ((rawSize != ZIP32_SENTINEL && !zipLocalValue(rawSize, actualSize, descriptor))
                    || (rawCompressed != ZIP32_SENTINEL && !zipLocalValue(rawCompressed, actualCompressed, descriptor)))
                throw new IOException("ZIP64 本地头与本地扩展长度相互矛盾");
            size = actualSize; compressed = actualCompressed;
        }
        if (!zipLocalValue(size, entry.size, descriptor) || !zipLocalValue(compressed, entry.compressed, descriptor))
            throw new IOException("ZIP 本地长度不是占位值且与中央目录不一致");
        return sentinel || wide;
    }

    private static void validateZipEntryType(CentralEntry entry, int platform, long attributes) throws IOException {
        boolean directory = entry.name.endsWith("/");
        int mode = (int) ((attributes >>> 16) & 0xf000);
        // A forged made-by platform must not hide a clearly encoded Unix symlink/FIFO/etc.
        // DOS writers normally leave these high type bits zero; retain the probe's strict gate.
        if (mode != 0 && mode != 0x8000 && mode != 0x4000)
            throw new IOException("ZIP 不允许链接或特殊文件");
        if ((platform == 3 || platform == 19) && mode != 0 && (mode == 0x4000) != directory)
            throw new IOException("ZIP Unix 类型与路径目录标记不一致");
        if ((attributes & 0x10) != 0 && !directory) throw new IOException("ZIP DOS 目录属性与名称不一致");
        if (directory && (entry.size != 0 || entry.crc != 0)) throw new IOException("ZIP 目录带有正文或非空 CRC");
    }

    private static long zipDescriptorEnd(RandomAccessFile file, long dataEnd, long limit,
            CentralEntry entry, boolean wide) throws IOException {
        int width = wide ? 8 : 4;
        int unsignedLength = 4 + 2 * width;
        if (limit - dataEnd < unsignedLength) throw new IOException("ZIP 数据描述符越界、被截断或与下一条目重叠");
        int available = (int) Math.min(unsignedLength + 4, limit - dataEnd);
        byte[] bytes = zipReadAt(file, dataEnd, available, limit);
        boolean unsigned = zipDescriptorMatches(bytes, 0, width, entry);
        boolean signed = u32(bytes, 0) == 0x08074b50L && zipDescriptorMatches(bytes, 4, width, entry);
        // A CRC can legitimately equal the optional signature. Evaluate both complete
        // structures, rather than unconditionally consuming four bytes on that coincidence.
        // If both tuples match, an unsigned interpretation may leave the signed tuple's
        // final NONZERO size word as unclaimed padding. That is not a valid second layout.
        if (unsigned && signed && u32(bytes, unsignedLength) != 0) unsigned = false;
        if (unsigned == signed) throw new IOException(unsigned
                ? "ZIP 数据描述符存在两种矛盾的有效边界" : "ZIP 数据描述符与中央目录不一致");
        return dataEnd + unsignedLength + (signed ? 4 : 0);
    }

    private static boolean zipDescriptorMatches(byte[] bytes, int at, int width, CentralEntry entry) {
        if (bytes.length - at < 4 + 2 * width || u32(bytes, at) != entry.crc) return false;
        if (width == 4) return u32(bytes, at + 4) == entry.compressed && u32(bytes, at + 8) == entry.size;
        return u32(bytes, at + 4) == (entry.compressed & ZIP32_SENTINEL)
                && u32(bytes, at + 8) == (entry.compressed >>> 32)
                && u32(bytes, at + 12) == (entry.size & ZIP32_SENTINEL)
                && u32(bytes, at + 16) == (entry.size >>> 32);
    }

    private static long zipPadding(RandomAccessFile file, long start, long end, long remaining,
            Checkpoint check) throws IOException {
        long length = end - start;
        if (length < 0 || length > remaining) throw new IOException("ZIP 对齐填充超过上限或条目重叠");
        byte[] bytes = new byte[(int) Math.min(8192, length)];
        long at = start;
        while (at < end) {
            check.check();
            int count = (int) Math.min(bytes.length, end - at);
            file.seek(at); file.readFully(bytes, 0, count);
            for (int i = 0; i < count; i++) if (bytes[i] != 0)
                throw new IOException("ZIP 条目之间含未认领记录或非零填充");
            at += count;
        }
        return length;
    }

    private static boolean zipLegacyValue(long legacy, long sentinel, long actual) { return legacy == sentinel || legacy == actual; }
    private static boolean zipLocalValue(long local, long actual, boolean descriptor) { return local == actual || (descriptor && local == 0); }

    private static long zipRangeEnd(long offset, long size, long limit, String message) throws IOException {
        if (offset < 0 || size < 0 || offset > limit || size > limit - offset) throw new IOException(message);
        return offset + size;
    }

    private static byte[] zipReadAt(RandomAccessFile file, long offset, int size, long limit) throws IOException {
        zipRangeEnd(offset, size, limit, "ZIP 记录被截断、越界或重叠");
        byte[] result = new byte[size];
        file.seek(offset); file.readFully(result);
        return result;
    }

    private static long zipU64(byte[] bytes, int at) throws IOException {
        if (at < 0 || bytes.length - at < 8 || (bytes[at + 7] & 0x80) != 0)
            throw new IOException("ZIP64 数值超出有界的有符号 long 范围");
        return u32(bytes, at) | (u32(bytes, at + 4) << 32);
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int at) {
        return (bytes[at] & 255L) | ((bytes[at + 1] & 255L) << 8)
                | ((bytes[at + 2] & 255L) << 16) | ((bytes[at + 3] & 255L) << 24);
    }

    /** SFNT/glyf structural checks precede the real Android Typeface builder. OTF/TTC rejected. */
    static void validateTrueType(File font, Checkpoint check) throws IOException {
        if (font.length() < 12 || font.length() > MAX_FONT_BYTES || sniff(font) != Kind.TTF)
            throw new IOException("不是受支持的原始 TrueType TTF（OTF/TTC 不支持）");
        try (RandomAccessFile in = new RandomAccessFile(font, "r")) {
            in.readInt();
            int count = in.readUnsignedShort();
            if (count < 7 || count > 256 || 12L + count * 16L > in.length())
                throw new IOException("TTF 表目录无效");
            in.skipBytes(6);
            Map<String, long[]> tables = new HashMap<>();
            List<long[]> ranges = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                check.check();
                byte[] name = new byte[4];
                in.readFully(name);
                String tag = new String(name, StandardCharsets.US_ASCII);
                in.readInt(); // checksum is not a trust/authentication mechanism
                long offset = in.readInt() & 0xffffffffL;
                long length = in.readInt() & 0xffffffffL;
                if (offset < 12L + count * 16L || offset + length > in.length() || (offset & 3) != 0
                        || tables.put(tag, new long[]{offset, length}) != null)
                    throw new IOException("TTF 表偏移、长度或名称无效");
                if (length > 0) ranges.add(new long[]{offset, offset + length});
            }
            ranges.sort(Comparator.comparingLong(range -> range[0]));
            long end = 0;
            for (long[] range : ranges) {
                if (range[0] < end) throw new IOException("TTF 表相互重叠");
                end = range[1];
            }
            for (String required : Arrays.asList("head", "hhea", "hmtx", "maxp", "cmap", "loca", "glyf"))
                if (!tables.containsKey(required)) throw new IOException("TTF 缺少 " + required + " 表");
            long[] head = tables.get("head");
            long[] maxp = tables.get("maxp");
            if (head[1] < 54 || maxp[1] < 6 || tables.get("cmap")[1] < 4)
                throw new IOException("TTF 核心表被截断");
            in.seek(head[0] + 12);
            if (in.readInt() != 0x5f0f3cf5) throw new IOException("TTF head 魔数无效");
            in.seek(head[0] + 18);
            int units = in.readUnsignedShort();
            if (units < 16 || units > 16384) throw new IOException("TTF 字体单位不合要求");
            in.seek(head[0] + 50);
            int locaFormat = in.readShort();
            if (locaFormat != 0 && locaFormat != 1) throw new IOException("TTF loca 格式无效");
            in.seek(maxp[0] + 4);
            int glyphs = in.readUnsignedShort();
            if (glyphs == 0) throw new IOException("TTF 没有字形");
            long[] loca = tables.get("loca");
            long glyfLength = tables.get("glyf")[1];
            if (loca[1] < (glyphs + 1L) * (locaFormat == 0 ? 2 : 4))
                throw new IOException("TTF loca 字形索引被截断");
            in.seek(loca[0]);
            long previous = 0;
            for (int glyph = 0; glyph <= glyphs; glyph++) {
                check.check();
                long next = locaFormat == 0 ? in.readUnsignedShort() * 2L : in.readInt() & 0xffffffffL;
                if (next < previous || next > glyfLength) throw new IOException("TTF 字形索引越界");
                previous = next;
            }
        }
    }

    /** Bounded ISO BMFF box walk, with the SAME header rule used by sniff. Real codecs are still required. */
    static void validateMp4(File video, Checkpoint check) throws IOException {
        if (video.length() < 24 || video.length() > MAX_VIDEO_BYTES || sniff(video) != Kind.MP4)
            throw new IOException("不是受支持的 MP4 文件");
        boolean ftyp = false;
        boolean moov = false;
        boolean mdat = false;
        long position = 0;
        int boxes = 0;
        try (RandomAccessFile file = new RandomAccessFile(video, "r")) {
            while (position < file.length()) {
                check.check();
                if (++boxes > 10000) throw new IOException("MP4 容器目录超过上限");
                long[] box = bmffBox(file, position);
                if (box == null) throw new IOException("MP4 box 长度被截断或越界");
                long size = box[0];
                int type = (int) box[1];
                int header = (int) box[2];
                if (type == 0x66747970) {
                    if (ftyp || !validFtyp(box)) throw new IOException("MP4 ftyp 无效或重复");
                    ftyp = true;
                } else if (!ftyp && !bmffPrefixType(type)) {
                    throw new IOException("MP4 ftyp 前存在非头部填充的内容");
                }
                if (type == 0x75756964 && size < header + 16) throw new IOException("MP4 uuid box 被截断");
                if (type == 0x6d6f6f76) moov = true;
                if (type == 0x6d646174 && size > header) mdat = true;
                position += size;
            }
        }
        if (!ftyp || !moov || !mdat) throw new IOException("MP4 没有视频类型、元数据或媒体正文");
    }
}
