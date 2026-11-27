package io.github.escosis.vivothemehelper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/** Android media decoding is additional validation, not an extension/MIME trust decision. */
final class MediaChecks {
    private static final long MAX_SOURCE_PIXELS = 64L * 1024 * 1024;
    private static final long MAX_DECODE_PIXELS = 8L * 1024 * 1024;
    private static final int MAX_EDGE = 16384;
    private MediaChecks() {}

    static final class ImageResult {
        final int width;
        final int height;
        final String notice;
        ImageResult(int width, int height, String notice) {
            this.width = width;
            this.height = height;
            this.notice = notice;
        }
    }

    static void verifyImage(File input, ArchiveIO.Checkpoint check) throws IOException {
        Bitmap decoded = null;
        try {
            decoded = decodeBounded(input, check);
            check.check();
        } catch (RuntimeException error) {
            throw new IOException("图片解码失败", error);
        } finally {
            recycle(decoded);
        }
    }

    static ImageResult toJpeg(File input, File output, ArchiveIO.Checkpoint check) throws IOException {
        Bitmap decoded = null;
        Bitmap rotated = null;
        Bitmap opaque = null;
        try {
            BitmapFactory.Options bounds = bounds(input);
            decoded = decodeBounded(input, check);
            Matrix matrix = new Matrix();
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            String exifNotice = "";
            try {
                orientation = new ExifInterface(input.getAbsolutePath())
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (IOException unsupported) {
                // No library is installed to handle a format the platform Exif reader rejects.
                exifNotice = "平台未能读取方向元数据，图片按解码方向保存";
                ModuleLog.error("Image EXIF was not readable", unsupported);
            }
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
                default: break;
            }
            check.check();
            rotated = matrix.isIdentity() ? decoded
                    : Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            Bitmap image = rotated;
            if (rotated.hasAlpha()) {
                opaque = Bitmap.createBitmap(rotated.getWidth(), rotated.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(opaque);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(rotated, 0, 0, null);
                image = opaque;
            }
            writeJpeg(image, output, check);
            String notice = "";
            if (!"image/jpeg".equals(bounds.outMimeType))
                notice = "图片已受控转换为 JPEG；透明或动画内容保存为白底静态图";
            if (decoded.getWidth() < bounds.outWidth || decoded.getHeight() < bounds.outHeight)
                notice = append(notice, "大图已按内存上限缩小");
            notice = append(notice, exifNotice);
            return new ImageResult(image.getWidth(), image.getHeight(), notice);
        } catch (RuntimeException error) {
            throw new IOException("图片无法安全解码或转换成 JPEG", error);
        } finally {
            recycle(opaque);
            if (rotated != decoded) recycle(rotated);
            recycle(decoded);
        }
    }

    private static BitmapFactory.Options bounds(File input) throws IOException {
        if (!input.isFile() || input.length() == 0 || input.length() > ArchiveIO.MAX_IMAGE_BYTES)
            throw new IOException("图片为空或超过 64 MiB 上限");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(input.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType == null
                || !options.outMimeType.startsWith("image/") || options.outWidth > MAX_EDGE
                || options.outHeight > MAX_EDGE || (long) options.outWidth * options.outHeight > MAX_SOURCE_PIXELS)
            throw new IOException("文件不是可解码图片，或图片尺寸超过安全上限");
        return options;
    }

    private static Bitmap decodeBounded(File input, ArchiveIO.Checkpoint check) throws IOException {
        check.check();
        BitmapFactory.Options dimensions = bounds(input);
        int sample = 1;
        while ((long) ((dimensions.outWidth + sample - 1) / sample)
                * ((dimensions.outHeight + sample - 1) / sample) > MAX_DECODE_PIXELS
                || dimensions.outWidth / sample > 4096 || dimensions.outHeight / sample > 4096)
            sample *= 2;
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        decode.inScaled = false;
        Bitmap result = BitmapFactory.decodeFile(input.getAbsolutePath(), decode);
        if (result == null) throw new IOException("图片正文解码失败（仅尺寸信息不足以通过验证）");
        if ((long) result.getWidth() * result.getHeight() > MAX_DECODE_PIXELS) {
            recycle(result);
            throw new IOException("图片解码结果超过内存上限");
        }
        return result;
    }

    /** Validates a video track, a real sample and a decoded frame; returns a genuine preview. */
    static File videoPreview(File video, File output, ArchiveIO.Checkpoint check) throws IOException {
        ArchiveIO.validateMp4(video, check);
        MediaExtractor extractor = null;
        MediaMetadataRetriever retriever = null;
        Bitmap frame = null;
        try (FileInputStream source = new FileInputStream(video)) {
            try {
                check.check();
                extractor = new MediaExtractor();
                retriever = new MediaMetadataRetriever();
                extractor.setDataSource(source.getFD(), 0, video.length());
                int videoTrack = -1;
                for (int track = 0; track < extractor.getTrackCount(); track++) {
                    MediaFormat format = extractor.getTrackFormat(track);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        int width = format.getInteger(MediaFormat.KEY_WIDTH);
                        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                        if (width <= 0 || height <= 0 || width > 8192 || height > 8192)
                            throw new IOException("MP4 视频尺寸超过安全上限");
                        videoTrack = track;
                        break;
                    }
                }
                if (videoTrack < 0) throw new IOException("MP4 没有真实视频轨道");
                extractor.selectTrack(videoTrack);
                if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                    throw new IOException("不支持受保护或加密的视频样本");
                long sampleSize = extractor.getSampleSize();
                if (sampleSize <= 0 || sampleSize > 16L * 1024 * 1024)
                    throw new IOException("MP4 视频样本无效或过大");
                ByteBuffer sample = ByteBuffer.allocate((int) sampleSize);
                if (extractor.readSampleData(sample, 0) <= 0)
                    throw new IOException("MP4 视频样本读取失败");
                check.check();
                retriever.setDataSource(source.getFD(), 0, video.length());
                String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long milliseconds;
                try { milliseconds = Long.parseLong(duration == null ? "" : duration); }
                catch (NumberFormatException error) { throw new IOException("MP4 视频时长无效", error); }
                if (milliseconds <= 0 || milliseconds > 24L * 60 * 60 * 1000)
                    throw new IOException("MP4 视频时长不合要求");
                frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640);
                if (frame == null) throw new IOException("平台无法实际解码 MP4 视频帧");
                writeJpeg(frame, output, check);
                check.check();
                return output;
            } catch (RuntimeException failure) {
                throw new IOException("平台视频引擎无法读取或解码此 MP4", failure);
            } finally {
                recycle(frame);
                if (extractor != null) {
                    try { extractor.release(); }
                    catch (RuntimeException cleanup) { ModuleLog.error("MediaExtractor release failed", cleanup); }
                }
                if (retriever != null) {
                    try { retriever.release(); }
                    catch (Exception cleanup) { ModuleLog.error("Media retriever release failed", cleanup); }
                }
            }
        }
    }

    private static void writeJpeg(Bitmap image, File output, ArchiveIO.Checkpoint check) throws IOException {
        check.check();
        if (!output.createNewFile()) throw new IOException("图片输出缓存文件已存在");
        boolean done = false;
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!image.compress(Bitmap.CompressFormat.JPEG, 92, stream)) throw new IOException("JPEG 压缩失败");
            stream.flush();
            stream.getFD().sync();
            if (output.length() <= 0 || output.length() > ArchiveIO.MAX_IMAGE_BYTES)
                throw new IOException("JPEG 输出大小无效");
            done = true;
        } finally {
            if (!done) Files.deleteIfExists(output.toPath());
        }
        try (FileInputStream in = new FileInputStream(output)) {
            if (in.read() != 0xff || in.read() != 0xd8) throw new IOException("JPEG 输出不是实际 JPEG 字节");
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static String append(String first, String second) {
        if (second.isEmpty()) return first;
        return first.isEmpty() ? second : first + "；" + second;
    }
}
