package top.lovepikachu.XiaoHeiHook.script;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Small file helpers used by xhh.fs. */
public final class FileCopyUtils {
    public static final int DEFAULT_MAX_READ_BYTES = 16 * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;

    private FileCopyUtils() {}

    @NonNull
    public static byte[] readAll(@NonNull File file, int maxBytes) throws IOException {
        if (!file.isFile()) {
            throw new IOException("不是普通文件: " + file.getAbsolutePath());
        }
        if (maxBytes > 0 && file.length() > maxBytes) {
            throw new IOException("文件过大: " + file.getAbsolutePath() + ", size=" + file.length() + ", max=" + maxBytes);
        }
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in, maxBytes);
        }
    }

    @NonNull
    public static byte[] readAll(@NonNull InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n == 0) continue;
            total += n;
            if (maxBytes > 0 && total > maxBytes) {
                throw new IOException("读取内容超过限制: max=" + maxBytes);
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    public static void writeAll(@NonNull File file, @NonNull byte[] bytes, boolean append) throws IOException {
        ensureParent(file);
        try (OutputStream out = new FileOutputStream(file, append)) {
            out.write(bytes);
            out.flush();
        }
    }

    public static long copy(@NonNull File src, @NonNull File dst, boolean overwrite) throws IOException {
        if (!src.isFile()) {
            throw new IOException("源路径不是普通文件: " + src.getAbsolutePath());
        }
        if (dst.exists() && !overwrite) return 0L;
        ensureParent(dst);
        long bytes = 0L;
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst, false)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                out.write(buffer, 0, n);
                bytes += n;
            }
            out.flush();
        }
        return bytes;
    }

    public static long copy(@NonNull byte[] bytes, @NonNull File dst, boolean overwrite) throws IOException {
        if (dst.exists() && !overwrite) return 0L;
        writeAll(dst, bytes, false);
        return bytes.length;
    }

    public static boolean mkdirs(@NonNull File dir) throws IOException {
        if (dir.exists()) {
            if (dir.isDirectory()) return true;
            throw new IOException("路径已存在但不是目录: " + dir.getAbsolutePath());
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("创建目录失败: " + dir.getAbsolutePath());
        }
        return true;
    }

    public static void ensureParent(@NonNull File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) mkdirs(parent);
    }

    public static boolean deleteRecursive(@NonNull File file) throws IOException {
        if (!file.exists()) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("删除失败: " + file.getAbsolutePath());
        }
        return true;
    }

    public static boolean isChanged(@NonNull File dst, long sourceLength, @Nullable String sourceSha256) {
        if (!dst.isFile()) return true;
        if (dst.length() != sourceLength) return true;
        if (sourceSha256 == null || sourceSha256.trim().isEmpty()) return false;
        try {
            return !sourceSha256.equalsIgnoreCase(sha256(dst));
        } catch (Throwable ignored) {
            return true;
        }
    }

    @NonNull
    public static String sha256(@NonNull File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return sha256(in);
        }
    }

    @NonNull
    public static String sha256(@NonNull byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(bytes));
        } catch (Throwable t) {
            throw new IllegalStateException("SHA-256 计算失败", t);
        }
    }

    @NonNull
    public static String sha256(@NonNull InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n > 0) digest.update(buffer, 0, n);
            }
            return hex(digest.digest());
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("SHA-256 计算失败", t);
        }
    }

    @NonNull
    private static String hex(@NonNull byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
}
