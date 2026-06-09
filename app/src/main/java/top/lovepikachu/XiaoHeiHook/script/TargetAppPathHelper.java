package top.lovepikachu.XiaoHeiHook.script;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.javascript.Wrapper;

import java.io.File;
import java.util.LinkedHashMap;

/** Resolves target-app private paths from the target Context, never from hard-coded /data/user/0 paths. */
public final class TargetAppPathHelper {
    private TargetAppPathHelper() {}

    @NonNull
    public static Context requireContext(@Nullable Object value) {
        Object raw = unwrap(value);
        if (raw instanceof Context) return (Context) raw;
        throw new IllegalArgumentException("xhh.fs 需要 android.content.Context，实际为: " + (raw == null ? "null" : raw.getClass().getName()));
    }

    @NonNull
    public static Object appDirs(@Nullable Object contextValue) {
        Context context = requireContext(contextValue);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("packageName", context.getPackageName());
        out.put("dataDir", path(dataDir(context)));
        out.put("filesDir", path(context.getFilesDir()));
        out.put("cacheDir", path(context.getCacheDir()));
        out.put("codeCacheDir", path(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? context.getCodeCacheDir() : null));
        out.put("noBackupFilesDir", path(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? context.getNoBackupFilesDir() : null));
        out.put("externalFilesDir", path(context.getExternalFilesDir(null)));
        out.put("externalCacheDir", path(context.getExternalCacheDir()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context dp = context.createDeviceProtectedStorageContext();
                out.put("deviceProtectedDataDir", path(dp == null ? null : dataDir(dp)));
                out.put("deviceProtectedFilesDir", path(dp == null ? null : dp.getFilesDir()));
            } catch (Throwable ignored) {
                out.put("deviceProtectedDataDir", null);
                out.put("deviceProtectedFilesDir", null);
            }
        } else {
            out.put("deviceProtectedDataDir", null);
            out.put("deviceProtectedFilesDir", null);
        }
        return JsApiValueNormalizer.toJs(out);
    }

    @Nullable
    public static File dataDir(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { return context.getDataDir(); } catch (Throwable ignored) {}
        }
        File filesDir = context.getFilesDir();
        return filesDir == null ? null : filesDir.getParentFile();
    }

    @Nullable
    private static String path(@Nullable File file) {
        return file == null ? null : file.getAbsolutePath();
    }

    @Nullable
    public static Object unwrap(@Nullable Object value) {
        Object current = value;
        for (int i = 0; i < 4; i++) {
            if (current instanceof Wrapper) {
                current = ((Wrapper) current).unwrap();
                continue;
            }
            return current;
        }
        return current;
    }
}
