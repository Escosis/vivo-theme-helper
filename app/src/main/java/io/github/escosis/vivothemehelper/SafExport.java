package io.github.escosis.vivothemehelper;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructStat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owned SAF descriptor, explicit truncation, nonblocking pipe writes and a cooperative time budget. */
final class SafExport {
    interface Control {
        boolean cancelled();
        void signal(CancellationSignal signal);
    }
    private SafExport() {}

    static void write(Context context, Uri destination, File snapshot, Control control) throws Exception {
        final long maxBytes = 1024L * 1024 * 1024;
        final long expected = snapshot.length();
        if (!snapshot.isFile() || expected <= 0 || expected > maxBytes) throw new IOException("导出快照为空或超过上限");
        final long deadline = SystemClock.elapsedRealtime() + 180000;
        final CancellationSignal signal = new CancellationSignal();
        final CountDownLatch finished = new CountDownLatch(1);
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!finished.await(180, TimeUnit.SECONDS)) signal.cancel();
                } catch (InterruptedException ignored) { }
            }
        }, "VivoTheme24-ExportTimeout");
        watchdog.setDaemon(true);
        watchdog.start();
        control.signal(signal);
        ParcelFileDescriptor descriptor = null;
        FileDescriptor fd = null;
        int originalFlags = -1;
        try {
            checkpoint(control, signal, deadline);
            // A cooperative provider receives cancellation during open. An uncooperative Binder cannot be forcibly killed here.
            descriptor = context.getContentResolver().openFileDescriptor(destination, "wt", signal);
            if (descriptor == null) throw new IOException("文件提供器拒绝截断写入模式");
            fd = descriptor.getFileDescriptor();
            originalFlags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0);
            Os.fcntlInt(fd, OsConstants.F_SETFL, originalFlags | OsConstants.O_NONBLOCK);
            byte[] buffer = new byte[65536];
            long written = 0;
            try (FileInputStream input = new FileInputStream(snapshot)) {
                int n;
                while ((n = input.read(buffer)) != -1) {
                    checkpoint(control, signal, deadline);
                    if (n == 0) continue;
                    int offset = 0;
                    while (offset < n) {
                        checkpoint(control, signal, deadline);
                        try {
                            int result = Os.write(fd, buffer, offset, n - offset);
                            if (result > 0) { offset += result; written += result; }
                            else waitWritable(fd, control, signal, deadline);
                        } catch (ErrnoException error) {
                            if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR)
                                waitWritable(fd, control, signal, deadline);
                            else throw error;
                        }
                        if (written > expected || written > maxBytes) throw new IOException("快照长度在写入期间发生变化");
                    }
                }
            }
            checkpoint(control, signal, deadline);
            if (written != expected || snapshot.length() != expected) throw new IOException("导出字节数量不一致");
            StructStat stat = Os.fstat(fd);
            if (OsConstants.S_ISREG(stat.st_mode)) {
                Os.fsync(fd);
                if (Os.fstat(fd).st_size != expected) throw new IOException("提供器未忠实截断/保存完整文件");
            }
            if (descriptor.canDetectErrors()) descriptor.checkError();
            checkpoint(control, signal, deadline);
        } finally {
            if (fd != null && originalFlags != -1) {
                try { Os.fcntlInt(fd, OsConstants.F_SETFL, originalFlags); } catch (Throwable ignored) { }
            }
            try { if (descriptor != null) descriptor.close(); }
            finally {
                finished.countDown();
                watchdog.interrupt();
                control.signal(null);
            }
        }
    }

    private static void waitWritable(FileDescriptor fd, Control control, CancellationSignal signal, long deadline) throws Exception {
        checkpoint(control, signal, deadline);
        StructPollfd poll = new StructPollfd();
        poll.fd = fd;
        poll.events = (short) OsConstants.POLLOUT;
        try { Os.poll(new StructPollfd[]{poll}, 250); }
        catch (ErrnoException error) { if (error.errno != OsConstants.EINTR) throw error; }
        checkpoint(control, signal, deadline);
        if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0)
            throw new IOException("文件提供器写入通道已关闭");
    }

    private static void checkpoint(Control control, CancellationSignal signal, long deadline) throws InterruptedIOException {
        if (control.cancelled() || Thread.currentThread().isInterrupted() || signal.isCanceled())
            throw new InterruptedIOException("导出已取消；所选位置可能留下部分文档");
        if (SystemClock.elapsedRealtime() > deadline) throw new InterruptedIOException("导出超过180秒；所选位置可能留下部分文档");
    }
}
