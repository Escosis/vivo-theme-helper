package io.github.escosis.vivothemehelper;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Bounded URI staging on the caller worker. No document name is used as a filesystem path. */
final class UriStaging {
    private UriStaging() {}
    interface Progress { void message(String value); }

    static final class Result {
        final String displayName;
        final long copied;
        Result(String displayName, long copied) { this.displayName = displayName; this.copied = copied; }
    }

    static Result copy(Context context, Uri uri, File output, Progress progress,
                       ArchiveIO.Checkpoint operation) throws IOException {
        String scheme = uri.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme))
            throw new IOException("只支持用户选择的 content/file URI");
        ArchiveIO.Deadline deadline = new ArchiveIO.Deadline(180, TimeUnit.SECONDS);
        CancellationSignal cancellation = new CancellationSignal();
        ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "v24-import-uri-deadline");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledFuture<?> deadlineTask = watch.scheduleWithFixedDelay(() -> {
            if (deadline.cancelled() || deadline.expired()) cancellation.cancel();
        }, 100, 100, TimeUnit.MILLISECONDS);
        ArchiveIO.Checkpoint check = () -> { operation.check(); deadline.check(); };
        try {
            check.check();
            String name = uri.getLastPathSegment();
            long advertised = -1;
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null, cancellation)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0 && !cursor.isNull(column)) name = cursor.getString(column);
                    column = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (column >= 0 && !cursor.isNull(column)) advertised = cursor.getLong(column);
                }
            } catch (RuntimeException metadataFailure) {
                check.check();
                // Optional URI metadata is not a DB reference query; actual bytes remain bounded.
                ModuleLog.error("Document metadata not available", metadataFailure);
            }
            if (advertised > ArchiveIO.MAX_INPUT_BYTES) throw new IOException("所选文件超过 512 MiB 上限");
            if (name != null && name.length() > 256) name = name.substring(0, 256);
            check.check();
            // CancellationSignal covers provider open/query. A malicious provider may ignore its
            // Binder cancellation; no claim is made to forcibly terminate an uncooperative Binder.
            try (AssetFileDescriptor source = context.getContentResolver().openAssetFileDescriptor(uri, "r", cancellation)) {
                if (source == null) throw new IOException("文件提供器没有返回可读文件描述符");
                long declared = source.getDeclaredLength();
                if (declared > ArchiveIO.MAX_INPUT_BYTES) throw new IOException("输入文件描述符超过大小上限");
                check.check();
                if (!output.createNewFile()) throw new IOException("URI 缓存目标已存在");
                try (FileOutputStream out = new FileOutputStream(output)) {
                    long copied = readDescriptor(source, out, declared, check, progress);
                    if (copied == 0) throw new IOException("所选文件为空");
                    out.flush();
                    out.getFD().sync();
                    check.check();
                    return new Result(name == null ? "本地资源" : name, copied);
                }
            } catch (ErrnoException failure) {
                check.check();
                throw new IOException("无法安全读取文件描述符", failure);
            } catch (RuntimeException failure) {
                check.check();
                throw new IOException("文件提供器无法打开所选 URI", failure);
            }
        } finally {
            deadlineTask.cancel(true);
            watch.shutdownNow();
        }
    }

    private static long readDescriptor(AssetFileDescriptor source, FileOutputStream output,
                                       long declared, ArchiveIO.Checkpoint check, Progress progress)
            throws IOException, ErrnoException {
        java.io.FileDescriptor descriptor = source.getFileDescriptor();
        int flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0);
        Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags | OsConstants.O_NONBLOCK);
        try {
            long offset = source.getStartOffset();
            try { Os.lseek(descriptor, offset, OsConstants.SEEK_SET); }
            catch (ErrnoException notSeekable) {
                if (offset != 0 || notSeekable.errno != OsConstants.ESPIPE) throw notSeekable;
            }
            StructPollfd poll = new StructPollfd();
            poll.fd = descriptor;
            poll.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            long nextProgress = 16L * 1024 * 1024;
            while (declared < 0 || total < declared) {
                check.check();
                poll.revents = 0;
                int ready;
                try { ready = Os.poll(new StructPollfd[]{poll}, 250); }
                catch (ErrnoException interrupted) {
                    if (interrupted.errno == OsConstants.EINTR) { check.check(); continue; }
                    throw interrupted;
                }
                check.check();
                if (ready == 0) continue;
                if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0)
                    throw new IOException("输入文件描述符已关闭或出现错误");
                int request = declared < 0 ? buffer.length : (int) Math.min(buffer.length, declared - total);
                int n;
                try { n = Os.read(descriptor, buffer, 0, request); }
                catch (ErrnoException unavailable) {
                    if (unavailable.errno == OsConstants.EAGAIN || unavailable.errno == OsConstants.EINTR) {
                        check.check();
                        continue;
                    }
                    throw unavailable;
                }
                if (n == 0) break;
                if (n > ArchiveIO.MAX_INPUT_BYTES - total) throw new IOException("输入实际字节超过 512 MiB 上限");
                output.write(buffer, 0, n);
                total += n;
                if (total >= nextProgress) {
                    progress.message("已复制 " + total / (1024 * 1024) + " MiB，正在读取本地文件…");
                    nextProgress += 16L * 1024 * 1024;
                }
            }
            if (declared >= 0 && total != declared) throw new IOException("文件提供器提前结束了输入");
            return total;
        } catch (InterruptedIOException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            try { Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags); }
            catch (ErrnoException cleanup) { ModuleLog.error("Restore descriptor flags failed", cleanup); }
        }
    }
}
