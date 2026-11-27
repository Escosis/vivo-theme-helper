package io.github.escosis.vivothemehelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exact version and bound DEX hashes, fail closed. Cached only for the same APK stat/central-directory identity. */
final class Compatibility {
    private Compatibility() {}

    static String verify(Context context, boolean allowCache) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(ModuleInfo.TARGET_PACKAGE, 0);
        if (!ModuleInfo.TARGET_VERSION.equals(info.versionName) || info.getLongVersionCode() != ModuleInfo.TARGET_CODE)
            return "仅适配 " + ModuleInfo.TARGET_VERSION + " / " + ModuleInfo.TARGET_CODE
                    + "，当前为 " + info.versionName + " / " + info.getLongVersionCode();
        if (info.applicationInfo == null || info.applicationInfo.sourceDir == null)
            return "无法定位 vivo主题安装包";
        File apk = new File(info.applicationInfo.sourceDir);
        String directory = directoryIdentity(apk);
        SharedPreferences cache = context.getSharedPreferences("dsh_v24_compat", Context.MODE_PRIVATE);
        String key = ModuleInfo.RELEASE + ":" + directory;
        if (allowCache && key.equals(cache.getString("verified", ""))) return "";
        String failure = verifyArchive(apk);
        if (failure.length() == 0 && allowCache) cache.edit().putString("verified", key).apply();
        return failure;
    }

    static String directoryIdentity(File apk) throws Exception {
        StringBuilder result = new StringBuilder(apk.getCanonicalPath())
                .append(':').append(apk.length()).append(':').append(apk.lastModified());
        try (ZipFile zip = new ZipFile(apk)) {
            int count = 0;
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (isDex(entry.getName())) count++;
            }
            if (count != ModuleInfo.DEX_NAMES.length) throw new IllegalStateException("目标 dex 数量不匹配");
            for (int i = 0; i < ModuleInfo.DEX_NAMES.length; i++) {
                ZipEntry entry = zip.getEntry(ModuleInfo.DEX_NAMES[i]);
                if (entry == null || entry.getSize() != ModuleInfo.DEX_SIZES[i]
                        || entry.getCrc() != ModuleInfo.DEX_CRCS[i])
                    throw new IllegalStateException("目标 dex 结构/CRC 不匹配：" + ModuleInfo.DEX_NAMES[i]);
                result.append(':').append(entry.getName()).append(':').append(entry.getSize()).append(':').append(entry.getCrc());
            }
        }
        return hex(MessageDigest.getInstance("SHA-256").digest(result.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static String verifyArchive(File apk) throws Exception {
        try (ZipFile zip = new ZipFile(apk)) {
            int count = 0;
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) if (isDex(all.nextElement().getName())) count++;
            if (count != ModuleInfo.DEX_NAMES.length) return "dex 数量不同，已拒绝安装 Hook";
            byte[] buffer = new byte[65536];
            for (int i = 0; i < ModuleInfo.DEX_NAMES.length; i++) {
                ZipEntry entry = zip.getEntry(ModuleInfo.DEX_NAMES[i]);
                if (entry == null || entry.getSize() != ModuleInfo.DEX_SIZES[i])
                    return "dex 文件/长度不同：" + ModuleInfo.DEX_NAMES[i];
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long read = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (n == 0) continue;
                        read += n;
                        if (read > ModuleInfo.DEX_SIZES[i]) return "dex 长度超出绑定值";
                        digest.update(buffer, 0, n);
                    }
                }
                if (read != ModuleInfo.DEX_SIZES[i] || !ModuleInfo.DEX_SHA256[i].equals(hex(digest.digest())))
                    return "dex SHA-256 不同：" + ModuleInfo.DEX_NAMES[i] + "，已拒绝安装 Hook";
            }
        }
        return "";
    }

    private static boolean isDex(String name) { return name.matches("classes(?:[0-9]+)?\\.dex"); }
    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
}
