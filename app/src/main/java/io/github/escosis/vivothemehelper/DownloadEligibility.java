package io.github.escosis.vivothemehelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Small read-only download checks. No I/O retries, transfer hooks, HTTP calls or protocol state machine. */
final class DownloadEligibility {
    private DownloadEligibility() {}

    static boolean versionAllowed(String version, long code) {
        return ModuleInfo.TARGET_VERSION.equals(version) && code == ModuleInfo.TARGET_CODE;
    }

    static boolean completed(int type, long id, boolean success, int status, int deleted, String path) {
        return MissingKeyProbe.knownType(type) && id > 0 && success && status >= 200 && status < 300
                && deleted == 0 && path != null && path.endsWith(".itz") && new File(path).isAbsolute();
    }

    static boolean localFlags(Object[] args) {
        return commonFlags(args) && Boolean.TRUE.equals(args[3]) && Boolean.FALSE.equals(args[6]);
    }

    static boolean downloadFlags(Object[] args) {
        return commonFlags(args) && Boolean.FALSE.equals(args[3]) && Boolean.TRUE.equals(args[6]);
    }

    private static boolean commonFlags(Object[] args) {
        return args != null && args.length == 7 && args[0] instanceof String && args[1] instanceof Integer
                && MissingKeyProbe.knownType(((Integer) args[1]).intValue())
                && Boolean.FALSE.equals(args[2]) && Boolean.FALSE.equals(args[4]) && Boolean.FALSE.equals(args[5]);
    }

    /** Complete keyless bytes at a verified native root; the proof survives native rename/copy, not content changes. */
    static final class Source {
        final File file;
        final int type;
        final MissingKeyProbe.Result proof;

        private Source(File file, int type, MissingKeyProbe.Result proof) {
            this.file = file;
            this.type = type;
            this.proof = proof;
        }

        static Source capture(File file, int type, File root) throws IOException {
            if (file == null || !file.getName().endsWith(".itz") || !MissingKeyProbe.knownType(type)
                    || !MissingKeyProbe.isFileUnderRoot(file, root)) return null;
            MissingKeyProbe.Result proof = MissingKeyProbe.inspectForDownload(file, type);
            if (!proof.eligible || proof.archiveSha256.length() != 64 || !proof.stamp.matches(file)) return null;
            return new Source(file, type, proof);
        }

        boolean matches(MissingKeyProbe.Result current) {
            return current != null && current.eligible && current.stamp.size == proof.stamp.size
                    && proof.archiveSha256.equals(current.archiveSha256);
        }
    }

    static boolean safeDestination(File target, File root) throws IOException {
        return MissingKeyProbe.isNewFileUnderRoot(target, root);
    }

    /** Native updates may replace their own regular targets; never extend that to links or outside paths. */
    static boolean targetsWithinRoots(Source source, File sourceRoot, File saveRoot, String name) throws IOException {
        File future = source.file;
        if (future.getName().endsWith("_temp.itz")) {
            if (name == null || name.length() == 0 || name.length() > 256) return false;
            ArchiveIO.validateEntryName(name + ".itz");
            if (name.indexOf('/') >= 0) return false;
            future = new File(source.file.getParentFile(), name + ".itz");
            if (future.getAbsolutePath().equals(source.file.getAbsolutePath()) || !targetWithinRoot(future, sourceRoot)) return false;
        }
        File destination = new File(saveRoot, future.getName());
        if (destination.getAbsolutePath().equals(future.getAbsolutePath())) return true; // Exact native no-copy branch.
        if (!targetWithinRoot(destination, saveRoot)) return false;
        // Native d compares STRING paths. Distinct aliases of one inode are not a safe copy:
        // FileOutputStream(destination) would truncate the source before it is read.
        return !destination.exists() || (!Files.isSameFile(source.file.toPath(), destination.toPath())
                && (!future.exists() || !Files.isSameFile(future.toPath(), destination.toPath())));
    }

    private static boolean targetWithinRoot(File target, File root) throws IOException {
        return safeDestination(target, root) || MissingKeyProbe.isFileUnderRoot(target, root);
    }
}
