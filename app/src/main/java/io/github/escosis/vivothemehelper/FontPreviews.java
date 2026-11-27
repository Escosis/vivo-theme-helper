package io.github.escosis.vivothemehelper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Three previews with the host's font package entry names. No bundled font. */
final class FontPreviews {
    static final String LARGE = "preview/preview_fonts_0.jpg";
    static final String SMALL = "preview/preview_fonts_small_0.png";
    static final String THUMB = "thumb/preview_fonts_small_1.jpg";
    private FontPreviews() {}

    /** Host-supported encrypted TTF must be decoded on a NEW owned cache copy before previewing. */
    static File prepareOwnedFont(HostApi api, File font, File directory, ArchiveIO.Checkpoint check) throws Exception {
        check.check();
        if (!ArchiveIO.hasVivoFontTrailer(font)) return font;
        long before = font.length();
        // Native u7.a decodes two 512-byte blocks and removes a twelve-byte trailer.
        if (before < 1036 || before > ArchiveIO.MAX_FONT_BYTES)
            throw new IOException("宿主加密字体长度不符合有界的双512字节块格式");
        if (api == null || api.context.getCacheDir() == null
                || !MissingKeyProbe.isFileUnderRoot(font, api.context.getCacheDir())
                || !directory.getCanonicalFile().equals(font.getParentFile().getCanonicalFile()))
            throw new IOException("仅可在本次宿主缓存副本上解密字体");
        File plain = ArchiveIO.createExclusive(directory, "decoded-font-" + UUID.randomUUID() + ".ttf");
        ArchiveIO.copyToOwnedFile(font, plain, ArchiveIO.MAX_FONT_BYTES, check);
        if (plain.length() != before) throw new IOException("加密字体缓存副本长度发生变化");
        Class<?> owner = api.clazz("u7.a");
        Method decode = HostApi.exact(owner, "decrypt", String.class, String.class);
        if (Modifier.isStatic(decode.getModifiers()) || decode.getReturnType() != int.class)
            throw new NoSuchMethodException("Native font decrypt(String,String) must be instance int");
        Object decoder = owner.getConstructor().newInstance();
        Object status = HostApi.invoke(decode, decoder, plain.getAbsolutePath(), plain.getAbsolutePath());
        check.check();
        if (!Integer.valueOf(0).equals(status) || !plain.isFile() || plain.length() != before - 12)
            throw new IOException("宿主加密字体解密失败（返回值=" + status + "），未修改用户原文件");
        ArchiveIO.validateTrueType(plain, check);
        return plain; // Caller must still use the real Android Typeface engine; no DEFAULT success.
    }

    static Typeface verifiedTypeface(File font, ArchiveIO.Checkpoint check) throws IOException {
        ArchiveIO.validateTrueType(font, check);
        check.check();
        try {
            Typeface result = new Typeface.Builder(font).build();
            if (result == null || result == Typeface.DEFAULT)
                throw new IOException("Android 字体引擎无法加载此 TTF（拒绝系统默认字体兜底）");
            // Exercise shaping without allocating a large bitmap. Some fonts only cover Latin;
            // platform fallback for absent glyphs is not represented as complete font coverage.
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(result);
            paint.setTextSize(32);
            float width = paint.measureText("Aa 0123");
            if (!(width > 0) || Float.isInfinite(width) || Float.isNaN(width))
                throw new IOException("TTF 字形度量无效");
            return result;
        } catch (RuntimeException error) {
            throw new IOException("Android 字体引擎拒绝了此 TTF", error);
        }
    }

    static Map<String, File> generate(File font, File directory, String name,
                                      ArchiveIO.Checkpoint check) throws IOException {
        Typeface face = verifiedTypeface(font, check);
        Map<String, File> previews = new LinkedHashMap<>();
        boolean done = false;
        try {
            File large = new File(directory, "font-large.jpg");
            render(large, 720, 1000, face, name, 0, Bitmap.CompressFormat.JPEG, check);
            previews.put(LARGE, large);
            File small = new File(directory, "font-small.png");
            render(small, 720, 144, face, name, 1, Bitmap.CompressFormat.PNG, check);
            previews.put(SMALL, small);
            File thumb = new File(directory, "font-thumb.jpg");
            render(thumb, 720, 360, face, name, 2, Bitmap.CompressFormat.JPEG, check);
            previews.put(THUMB, thumb);
            done = true;
            return previews;
        } finally {
            if (!done) {
                for (File preview : previews.values()) {
                    try { Files.deleteIfExists(preview.toPath()); }
                    catch (IOException cleanup) { ModuleLog.error("Font preview cleanup failed", cleanup); }
                }
            }
        }
    }

    private static void render(File output, int width, int height, Typeface face, String name,
                               int mode, Bitmap.CompressFormat format,
                               ArchiveIO.Checkpoint check) throws IOException {
        check.check();
        if (!output.createNewFile()) throw new IOException("字体预览缓存文件已存在");
        Bitmap bitmap = null;
        boolean complete = false;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            paint.setTypeface(face);
            paint.setColor(Color.rgb(35, 39, 47));
            if (mode == 1) {
                paint.setTextSize(46);
                drawFitted(canvas, paint, "字体预览  AaBb  012345", 28, 93, width - 56);
            } else if (mode == 2) {
                paint.setTextSize(45);
                drawFitted(canvas, paint, ArchiveIO.title(name), 30, 72, width - 60);
                paint.setTextSize(47);
                drawFitted(canvas, paint, "美好的一天  AaBb", 30, 164, width - 60);
                paint.setTextSize(43);
                drawFitted(canvas, paint, "0123456789  !?", 30, 256, width - 60);
            } else {
                paint.setTextSize(49);
                drawFitted(canvas, paint, ArchiveIO.title(name), 38, 90, width - 76);
                paint.setTextSize(46);
                String[] samples = {
                        "春风与明月，字里行间。", "记录生活，发现美好。",
                        "天地玄黄 宇宙洪荒", "Aa Bb Cc Dd Ee Ff",
                        "Gg Hh Ii Jj Kk Ll Mm", "Nn Oo Pp Qq Rr Ss",
                        "Tt Uu Vv Ww Xx Yy Zz", "0123456789  !? @#"
                };
                float y = 205;
                for (String sample : samples) {
                    check.check();
                    drawFitted(canvas, paint, sample, 38, y, width - 76);
                    y += 90;
                }
            }
            check.check();
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!bitmap.compress(format, 90, stream)) throw new IOException("字体预览压缩失败");
                stream.flush();
                stream.getFD().sync();
            }
            if (output.length() == 0) throw new IOException("字体预览输出为空");
            check.check();
            complete = true;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (!complete) Files.deleteIfExists(output.toPath());
        }
    }

    private static void drawFitted(Canvas canvas, Paint paint, String text, float x, float y, float width) {
        float original = paint.getTextSize();
        float actual = paint.measureText(text);
        if (actual > width) paint.setTextSize(Math.max(18, original * width / actual));
        int count = text.length();
        while (count > 0 && paint.measureText(text, 0, count) > width) {
            count--;
            if (count > 0 && Character.isHighSurrogate(text.charAt(count - 1))) count--;
        }
        canvas.drawText(text, 0, count, x, y, paint);
        paint.setTextSize(original);
    }
}
