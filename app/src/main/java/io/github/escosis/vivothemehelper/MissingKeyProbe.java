package io.github.escosis.vivothemehelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Read-only, bounded eligibility probe. This is NOT a signature or entitlement verifier. */
final class MissingKeyProbe {
    static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;
    static final long MAX_ENTRY_BYTES = 256L * 1024L * 1024L;
    static final long MAX_EXPANDED_BYTES = 1024L * 1024L * 1024L;
    static final int MAX_XML_BYTES = 1024 * 1024;
    static final int MAX_ENTRIES = 8192;
    private static final int MAX_NAME_BYTES = 1024;
    private static final long MAX_RATIO = 200L;
    private static final long PROBE_NANOS = TimeUnit.SECONDS.toNanos(30L);

    private MissingKeyProbe() { }

    static boolean knownType(int type) {
        switch (type) {
            case 1: case 16: case 2: case 9: case 13: case 4:
            case 18: case 5: case 20: case 7: case 14: case 12:
                return true;
            default:
                return false;
        }
    }

    /** Only the authoritative root's lexical/real aliases are trusted; descendants may not redirect. */
    static boolean isFileUnderRoot(File file, File authoritativeRoot) throws IOException {
        Path child = checkedDescendant(file, authoritativeRoot, true);
        if (child == null) return false;
        BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        return attributes.isRegularFile() && !attributes.isSymbolicLink();
    }

    /** Same authority boundary for a future native write, but require its leaf to be provably absent. */
    static boolean isNewFileUnderRoot(File file, File authoritativeRoot) throws IOException {
        Path child = checkedDescendant(file, authoritativeRoot, false);
        // notExists, not !exists: unknown I/O/permission state must not authorize an overwrite.
        return child != null && Files.notExists(child, LinkOption.NOFOLLOW_LINKS);
    }

    private static Path checkedDescendant(File file, File authoritativeRoot, boolean existing) throws IOException {
        if (file == null || authoritativeRoot == null) return null;
        Path original = file.getAbsoluteFile().toPath();
        Path child = original.normalize();
        Path root = authoritativeRoot.getAbsoluteFile().toPath().normalize();
        if (!original.equals(child) || child.equals(root)) return null;
        if (existing && Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return null;
        // Native i2.d creates missing save directories immediately before its copy. Preflight
        // their planned real path from the nearest EXISTING authority ancestor, not toRealPath
        // of a directory that legitimately does not exist yet. Unknown I/O still throws/declines.
        Path realRoot = existing ? root.toRealPath() : plannedDirectoryPath(root);
        if (realRoot == null || (existing && !Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS))) return null;
        Path boundary;
        if (child.startsWith(root)) boundary = root;
        else if (child.startsWith(realRoot)) boundary = realRoot;
        else return null;
        if (child.equals(boundary)) return null;
        // ImportEngine stages at the real cache path, whereas native Context/save roots can
        // use /data/data versus /data/user/<id>. Accept these two authority-root spellings,
        // not arbitrary outside links that happen to target an allowed file.
        Path cursor = boundary;
        Path expected = realRoot;
        Path relative = boundary.relativize(child);
        int index = 0;
        for (Path component : relative) {
            cursor = cursor.resolve(component);
            expected = expected.resolve(component);
            if (!existing && ++index == relative.getNameCount()) break;
            if (Files.notExists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (existing || !expected.equals(plannedDirectoryPath(cursor))) return null;
            } else if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(expected)
                    || (!cursor.equals(child) && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))) return null;
        }
        Path stableRoot = existing ? root.toRealPath() : plannedDirectoryPath(root);
        return realRoot.equals(stableRoot) ? child : null;
    }

    private static Path plannedDirectoryPath(Path directory) throws IOException {
        Path ancestor = directory;
        while (Files.notExists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
            if (ancestor == null) return null;
        }
        if (!Files.isDirectory(ancestor)) return null;
        return ancestor.toRealPath().resolve(ancestor.relativize(directory));
    }

    static final class FileStamp {
        final String canonicalPath;
        final long size;
        final long modified;
        final Object fileKey;

        private FileStamp(String canonicalPath, BasicFileAttributes attributes) {
            this.canonicalPath = canonicalPath;
            size = attributes.size();
            modified = attributes.lastModifiedTime().toMillis();
            fileKey = attributes.fileKey();
        }

        static FileStamp capture(File file) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(file.toPath(),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink())
                throw new IOException("Not a regular, non-symlink ITZ");
            if (attributes.size() <= 0L || attributes.size() > MAX_ARCHIVE_BYTES)
                throw new IOException("ITZ length outside probe limits");
            return new FileStamp(file.toPath().toRealPath().toString(), attributes);
        }

        boolean matches(File file) throws IOException {
            FileStamp current = capture(file);
            return canonicalPath.equals(current.canonicalPath) && size == current.size
                    && modified == current.modified
                    && (fileKey == null ? current.fileKey == null : fileKey.equals(current.fileKey));
        }
    }

    static final class Result {
        final boolean eligible;
        final String reason;
        final FileStamp stamp;
        final boolean wholePayload;
        final boolean requiresCoreHash;
        final boolean sha256Core;
        final String xmlMd5;
        final String xmlSha256;
        final String nativeCoreHash;
        // Present only for download hand-off probes; covers every byte, not only XML/core entries.
        final String archiveSha256;

        private Result(boolean eligible, String reason, FileStamp stamp, boolean wholePayload,
                boolean requiresCoreHash, boolean sha256Core, String xmlMd5, String xmlSha256,
                String nativeCoreHash, String archiveSha256) {
            this.eligible = eligible;
            this.reason = reason;
            this.stamp = stamp;
            this.wholePayload = wholePayload;
            this.requiresCoreHash = requiresCoreHash;
            this.sha256Core = sha256Core;
            this.xmlMd5 = xmlMd5;
            this.xmlSha256 = xmlSha256;
            this.nativeCoreHash = nativeCoreHash;
            this.archiveSha256 = archiveSha256;
        }

        private static Result denied(String reason, FileStamp stamp) {
            return new Result(false, reason, stamp, false, false, false, "", "", "", "");
        }
    }

    static Result inspect(File file, int type) throws IOException {
        return inspect(file, type, false);
    }

    /** Same structural checks as local imports, plus a bounded whole-archive identity digest. */
    static Result inspectForDownload(File file, int type) throws IOException {
        return inspect(file, type, true);
    }

    private static Result inspect(File file, int type, boolean downloadIdentity) throws IOException {
        if (!knownType(type)) return Result.denied("Unknown resource type", null);
        long deadline = System.nanoTime() + PROBE_NANOS;
        FileStamp stamp = FileStamp.capture(file);
        List<EntryMeta> entries = readZipLayout(file, stamp.size, deadline);
        Map<String, EntryMeta> byName = new HashMap<String, EntryMeta>();
        for (EntryMeta entry : entries) byName.put(entry.name, entry);
        EntryMeta key = byName.get("key");
        // A nonempty, malformed, undecodable or encrypted real key is never a keyless archive.
        if (key != null && (key.directory || key.size != 0L))
            return Result.denied("Physical key is not a zero-byte regular entry", stamp);
        EntryMeta description = byName.get("description.xml");
        if (description == null || description.directory || description.size <= 0L
                || description.size > MAX_XML_BYTES)
            return Result.denied("Missing/oversized root description.xml", stamp);
        Payload payload = payloadFor(entries, type);
        if (!payload.valid) return Result.denied("No supported payload for selected type", stamp);
        String coreName = nativeCoreName(entries, type);
        EntryMeta coreEntry = coreName == null ? null : byName.get(coreName);
        if (coreEntry != null && (coreEntry.directory || coreEntry.size <= 0L))
            return Result.denied("Native core entry is empty or not a regular payload", stamp);
        MessageDigest coreDigest = coreEntry == null ? null
                : newDigest(type == 18 || type == 20 ? "SHA-256" : "MD5");

        byte[] xml = null;
        long expanded = 0L;
        int count = 0;
        Set<String> readNames = new HashSet<String>();
        byte[] buffer = new byte[8192];
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                checkTime(deadline);
                ZipEntry entry = enumeration.nextElement();
                EntryMeta meta = byName.get(entry.getName());
                if (meta == null || !readNames.add(entry.getName()) || ++count > MAX_ENTRIES
                        || entry.getMethod() != meta.method || entry.getSize() != meta.size
                        || entry.getCompressedSize() != meta.compressed || entry.getCrc() != meta.crc
                        || entry.isDirectory() != meta.directory)
                    throw new IOException("ZIP reader/central-directory disagreement");
                ByteArrayOutputStream saved = "description.xml".equals(meta.name)
                        ? new ByteArrayOutputStream((int) meta.size) : null;
                CRC32 crc = new CRC32();
                long read = 0L;
                try (InputStream input = zip.getInputStream(entry)) {
                    int zeroReads = 0;
                    for (;;) {
                        checkTime(deadline);
                        int n = input.read(buffer);
                        if (n == -1) break;
                        if (n == 0) {
                            if (++zeroReads > 16) throw new IOException("Stalled ZIP entry stream");
                            continue;
                        }
                        zeroReads = 0;
                        read += n;
                        expanded += n;
                        if (read > meta.size || read > MAX_ENTRY_BYTES || expanded > MAX_EXPANDED_BYTES)
                            throw new IOException("ZIP expanded length exceeds limits/declaration");
                        crc.update(buffer, 0, n);
                        if (coreDigest != null && meta == coreEntry) coreDigest.update(buffer, 0, n);
                        if (saved != null) saved.write(buffer, 0, n);
                    }
                }
                if (read != meta.size || crc.getValue() != meta.crc)
                    throw new IOException("ZIP entry length/CRC mismatch");
                if ("key".equals(meta.name) && read != 0L)
                    throw new IOException("Key bytes disagree with zero-size declaration");
                if (saved != null) xml = saved.toByteArray();
            }
        }
        if (count != entries.size() || xml == null || !stamp.matches(file))
            throw new IOException("ZIP changed during eligibility probe");
        validateXml(xml, type, deadline);
        String archiveHash = downloadIdentity ? hashArchive(file, stamp, deadline) : "";
        if (!stamp.matches(file)) throw new IOException("ITZ changed while validating XML/archive identity");
        return new Result(true, key == null ? "absent key" : "zero-byte key", stamp,
                payload.whole, payload.coreHash, type == 18 || type == 20,
                hash(xml, "MD5"), hash(xml, "SHA-256"), coreDigest == null ? "" : hex(coreDigest.digest()),
                archiveHash);
    }

    private static String hashArchive(File file, FileStamp stamp, long deadline) throws IOException {
        MessageDigest digest = newDigest("SHA-256");
        byte[] buffer = new byte[8192];
        long read = 0L;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            int zeroReads = 0;
            for (;;) {
                checkTime(deadline);
                int n = input.read(buffer);
                if (n == -1) break;
                if (n == 0) {
                    if (++zeroReads > 16) throw new IOException("Stalled archive identity stream");
                    continue;
                }
                zeroReads = 0;
                read += n;
                if (read > stamp.size || read > MAX_ARCHIVE_BYTES)
                    throw new IOException("Archive changed during identity read");
                digest.update(buffer, 0, n);
            }
        }
        if (read != stamp.size || !stamp.matches(file))
            throw new IOException("Archive identity length/stamp mismatch");
        return hex(digest.digest());
    }

    private static final class Payload {
        final boolean valid;
        final boolean whole;
        final boolean coreHash;
        Payload(boolean valid, boolean whole, boolean coreHash) {
            this.valid = valid;
            this.whole = whole;
            this.coreHash = coreHash;
        }
    }

    /** Exact fixed native g() entries, or conservative recognized component prefixes. */
    private static Payload payloadFor(List<EntryMeta> entries, int type) {
        boolean valid = false;
        boolean vivo = false;
        boolean genericNativeCore = false;
        for (EntryMeta entry : entries) {
            if (entry.directory || entry.size == 0L) continue;
            String name = entry.name;
            if ("vivo".equals(name)) vivo = true;
            if (!name.contains("wallpaper_second")
                    && (name.endsWith(".ttf") || name.endsWith(".ux") || name.endsWith(".zip")))
                genericNativeCore = true;
            switch (type) {
                case 1:
                    valid |= "vivo".equals(name) || name.startsWith("icons/")
                            || name.startsWith("lockscreen/") || name.startsWith("desktop/")
                            || name.startsWith("wallpaper/") || name.startsWith("fonts/");
                    break;
                case 16: valid |= name.startsWith("widget/"); break;
                case 2: valid |= "livewallpaper/video_livewallpaper.mp4".equals(name); break;
                case 9: valid |= name.startsWith("wallpaper/"); break;
                case 13: valid |= "behavior/video.mp4".equals(name) || "behavior".equals(name); break;
                case 4: valid |= name.startsWith("fonts/") && name.endsWith(".ttf"); break;
                case 18: valid |= "themeicon/icons.zip".equals(name); break;
                case 5: valid |= name.startsWith("lockscreen/"); break;
                case 20: valid |= "com.vivo.systemuiplugin".equals(name); break;
                case 7: valid |= "screenclock/screenclock.zip".equals(name); break;
                case 14: valid |= name.startsWith("videoringtone/"); break;
                case 12: valid |= "inputmethod/theme_info.json".equals(name); break;
                default: break;
            }
        }
        boolean core;
        switch (type) {
            case 1: core = vivo; break; // Partial no-vivo themes legitimately produce an empty g().
            case 2: case 13: case 18: case 20: case 7: case 12: core = valid; break;
            default: core = genericNativeCore; break;
        }
        return new Payload(valid, type == 1 && vivo, core);
    }

    /** Match the actual domestic kg.g() selection; never invent a successful missing core hash. */
    private static String nativeCoreName(List<EntryMeta> entries, int type) {
        String fixed = null;
        switch (type) {
            case 1: fixed = "vivo"; break;
            case 2: fixed = "livewallpaper/video_livewallpaper.mp4"; break;
            case 7: fixed = "screenclock/screenclock.zip"; break;
            case 18: fixed = "themeicon/icons.zip"; break;
            case 20: fixed = "com.vivo.systemuiplugin"; break;
            case 12: fixed = "inputmethod/theme_info.json"; break;
            case 13:
                for (EntryMeta entry : entries) {
                    if ("behavior/video.mp4".equals(entry.name) || "behavior/video.mp4/".equals(entry.name))
                        return entry.name;
                }
                fixed = "behavior";
                break;
            default: break;
        }
        for (EntryMeta entry : entries) {
            if (fixed != null) {
                // java.util.zip.ZipFile.getEntry also recognizes a same-name directory suffix.
                if (fixed.equals(entry.name) || (fixed + "/").equals(entry.name)) return entry.name;
            } else if (!entry.name.contains("wallpaper_second") && (entry.name.endsWith(".ttf")
                    || entry.name.endsWith(".ux") || entry.name.endsWith(".zip"))) {
                return entry.name;
            }
        }
        return null;
    }

    private static final class EntryMeta {
        final String name;
        final byte[] nameBytes;
        final boolean directory;
        final int method;
        final int flags;
        final long crc;
        final long compressed;
        final long size;
        final long localOffset;
        long localEnd;
        EntryMeta(String name, byte[] bytes, int method, int flags, long crc,
                long compressed, long size, long offset) {
            this.name = name;
            nameBytes = bytes;
            directory = name.endsWith("/");
            this.method = method;
            this.flags = flags;
            this.crc = crc;
            this.compressed = compressed;
            this.size = size;
            localOffset = offset;
        }
    }

    /** One shared structural parser for local import AND download eligibility; policy/hash checks remain here. */
    private static List<EntryMeta> readZipLayout(File file, long length, final long deadline) throws IOException {
        List<ArchiveIO.CentralEntry> layout = ArchiveIO.validateCentralDirectory(file,
                new ArchiveIO.Checkpoint() { @Override public void check() throws IOException { checkTime(deadline); } });
        if (layout.size() > MAX_ENTRIES || file.length() != length)
            throw new IOException("ZIP count/length changed during shared layout validation");
        List<EntryMeta> entries = new ArrayList<EntryMeta>(layout.size());
        long expanded = 0L;
        for (ArchiveIO.CentralEntry central : layout) {
            checkTime(deadline);
            validateName(central.name); // Extra key/description alias rules are specific to the keyless policy.
            if (central.nameBytes.length > MAX_NAME_BYTES || central.size > MAX_ENTRY_BYTES
                    || central.compressed > MAX_ARCHIVE_BYTES || central.size > MAX_EXPANDED_BYTES - expanded
                    || (central.size > 1024L * 1024L && central.size > Math.max(1L, central.compressed) * MAX_RATIO))
                throw new IOException("ZIP length/name/ratio outside keyless probe limits");
            expanded += central.size;
            EntryMeta entry = new EntryMeta(central.name, central.nameBytes, central.method, central.flags,
                    central.crc, central.compressed, central.size, central.offset);
            entry.localEnd = central.dataEnd;
            entries.add(entry);
        }
        return entries; // Keep central order: native kg.g() chooses its first matching payload in that order.
    }

    private static void validateName(String name) throws IOException {
        if (name.length() == 0 || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0)
            throw new IOException("Absolute/nonportable ZIP path");
        String body = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : body.split("/", -1)) {
            if (part.length() == 0 || ".".equals(part) || "..".equals(part)
                    || part.endsWith(".") || part.endsWith(" "))
                throw new IOException("Noncanonical ZIP path");
            for (int i = 0; i < part.length(); i++) {
                if (Character.isISOControl(part.charAt(i)))
                    throw new IOException("Control character in ZIP path");
            }
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if ((lower.equals("key") && (!name.equals("key") || name.endsWith("/")))
                || lower.startsWith("key/")
                || (lower.equals("description.xml") && !name.equals("description.xml")))
            throw new IOException("Ambiguous key/description ZIP entry");
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("ZIP/XML is not well-formed UTF-8", error);
        }
    }

    private static MessageDigest newDigest(String algorithm) throws IOException {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException error) { throw new IOException("Required hash algorithm unavailable", error); }
    }

    private static String hash(byte[] bytes, String algorithm) throws IOException {
        return hex(newDigest(algorithm).digest(bytes));
    }

    private static String hex(byte[] digest) {
        StringBuilder text = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            text.append(Character.forDigit((value >>> 4) & 15, 16));
            text.append(Character.forDigit(value & 15, 16));
        }
        return text.toString();
    }

    private static void checkTime(long deadline) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Keyless probe interrupted");
        if (System.nanoTime() - deadline > 0L) throw new InterruptedIOException("Keyless probe time limit exceeded");
    }

    private static void validateXml(byte[] bytes, int type, final long deadline) throws IOException {
        String xml = decodeUtf8(bytes);
        if (xml.startsWith("\ufeff")) xml = xml.substring(1);
        String upper = xml.toUpperCase(Locale.ROOT);
        // This check is deliberately conservative (also rejects DTD text inside comments/CDATA).
        // InputSource uses a Reader, so an encoding declaration cannot reinterpret the checked bytes.
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY") || xml.indexOf('\u0000') >= 0)
            throw new IOException("DTD/entities/NUL forbidden in keyless description");
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            // DTDs were rejected before parsing; the throwing resolver is a second independent guard.
            try { reader.setFeature("http://xml.org/sax/features/external-general-entities", false); }
            catch (SAXException unsupported) { /* Never relied upon as the only protection. */ }
            try { reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
            catch (SAXException unsupported) { /* DTD check + resolver remain mandatory. */ }
            ManifestHandler handler = new ManifestHandler(type, deadline);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver(handler);
            reader.parse(new InputSource(new StringReader(xml)));
            if (!handler.validId || handler.depth != 0) throw new IOException("Incomplete description ID/root");
        } catch (ParserConfigurationException error) {
            throw new IOException("Secure description parser unavailable", error);
        } catch (SAXException error) {
            throw new IOException("Invalid/bounded description XML", error);
        }
    }

    private static final class ManifestHandler extends DefaultHandler {
        final int type;
        final long deadline;
        final Set<String> singletons = new HashSet<String>();
        int depth;
        int nodes;
        boolean validId;
        String capture;
        StringBuilder value;

        ManifestHandler(int type, long deadline) { this.type = type; this.deadline = deadline; }

        @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
            throw new SAXException("External XML entity forbidden");
        }

        @Override public void startElement(String uri, String localName, String name, Attributes attributes)
                throws SAXException {
            try { checkTime(deadline); } catch (IOException error) { throw new SAXException(error); }
            if (++depth > 32 || ++nodes > 4096 || name.length() > 128 || attributes.getLength() > 32)
                throw new SAXException("Description XML structural limit");
            if (depth == 1 && !"theme".equals(name) && !(type == 16 && "widget".equals(name)))
                throw new SAXException("Unsupported description root");
            for (int i = 0; i < attributes.getLength(); i++) {
                if (attributes.getQName(i).length() > 128 || attributes.getValue(i).length() > 16384)
                    throw new SAXException("Description attribute limit");
            }
            if (capture != null) throw new SAXException("Nested identity/style element");
            if (depth == 2 && ("id".equals(name) || "id3".equals(name) || "uid".equals(name)
                    || "style".equals(name))) {
                if (!singletons.add(name)) throw new SAXException("Duplicate identity/style element");
                capture = name;
                value = new StringBuilder();
            }
        }

        @Override public void characters(char[] characters, int start, int length) throws SAXException {
            if (value != null) {
                if (value.length() + length > 4096) throw new SAXException("Identity/style text limit");
                value.append(characters, start, length);
            }
        }

        @Override public void endElement(String uri, String localName, String name) throws SAXException {
            if (depth == 2 && capture != null) {
                if (!capture.equals(name)) throw new SAXException("Identity element mismatch");
                if (("id".equals(capture) || (type != 16 && "id3".equals(capture)))
                        && value.toString().trim().length() > 0) validId = true;
                capture = null;
                value = null;
            }
            if (--depth < 0) throw new SAXException("Description depth underflow");
        }

        @Override public void error(SAXParseException error) throws SAXException { throw error; }
        @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
    }
}
