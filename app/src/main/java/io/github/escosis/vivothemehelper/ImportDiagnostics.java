package io.github.escosis.vivothemehelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Small, non-secret diagnostics for owned staged files; never reads an entitlement key. */
final class ImportDiagnostics {
    private ImportDiagnostics() {}

    static String failureMessage(String summary, Throwable error) {
        String result = summary == null ? "" : summary.trim();
        String detail = error == null ? "" : error.getMessage();
        if (detail == null || detail.trim().length() == 0)
            detail = error == null ? "" : error.getClass().getSimpleName();
        else detail = detail.trim();
        boolean alreadyPresent = detail.length() != 0 && result.contains(detail);
        if (detail.length() > 260) {
            int end = Character.isHighSurrogate(detail.charAt(259)) ? 259 : 260;
            detail = detail.substring(0, end) + "…";
        }
        // ImportEngine already includes its exception message plus compensation warnings.
        // Appending getMessage() again made the actual failure appear twice in the host dialog.
        if (result.length() == 0) result = detail;
        else if (detail.length() != 0 && !alreadyPresent && !result.contains(detail)) result += "\n" + detail;
        return result.length() == 0 ? "操作失败，请查看 VivoTheme24 日志" : result;
    }

    static String inputMismatch(File file, int type, ArchiveIO.Kind kind) throws IOException {
        String name = String.valueOf(type);
        for (int i = 0; i < ModuleInfo.TYPE_VALUES.length; i++)
            if (ModuleInfo.TYPE_VALUES[i] == type) { name = ModuleInfo.TYPE_NAMES[i] + "（" + type + "）"; break; }
        return "类型与真实内容不匹配：所选类别=" + name + "，检测格式=" + kind
                + "\n暂存文件大小=" + file.length() + " 字节，文件头=" + headerHex(file)
                + "\n全部类别接受 ITZ；原始 TTF 仅字体、MP4 仅动态壁纸、图片仅静态壁纸。"
                + "不会仅因扩展名正确就把不匹配的内容提交入库。";
    }

    static String headerHex(File file) throws IOException {
        byte[] bytes = new byte[16];
        int count = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (count < bytes.length) {
                int n = input.read(bytes, count, bytes.length - count);
                if (n < 0) break;
                if (n == 0) continue;
                count += n;
            }
        }
        StringBuilder text = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            text.append(Character.forDigit((bytes[i] >>> 4) & 15, 16));
            text.append(Character.forDigit(bytes[i] & 15, 16));
        }
        return count == 0 ? "（空）" : text.toString();
    }
}
