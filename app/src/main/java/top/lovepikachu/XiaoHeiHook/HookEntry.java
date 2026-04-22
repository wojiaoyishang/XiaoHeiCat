package top.lovepikachu.XiaoHeiHook;

import static java.lang.Boolean.getBoolean;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import top.lovepikachu.XiaoHeiHook.hooks.QidianToolset;

public class HookEntry extends XposedModule {
    private static final String TAG = "XiaoHeiCat-HookEntry";
    private SharedPreferences prefs = null;

    private boolean hasProp(long prop) {
        return (getFrameworkProperties() & prop) != 0;
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
//        log(Log.INFO, TAG, "onModuleLoaded: " + param.getProcessName());
//        log(Log.INFO, TAG, String.format(Locale.getDefault(), "framework: %s (%s) API %d", getFrameworkName(), getFrameworkVersionCode(), getApiVersion()));
//        log(Log.INFO, TAG, "system supported: " + hasProp(PROP_CAP_SYSTEM));
//        log(Log.INFO, TAG, "remote supported: " + hasProp(PROP_CAP_REMOTE));
//        log(Log.INFO, TAG, "api protection: " + hasProp(PROP_RT_API_PROTECTION));

        prefs = getRemotePreferences("XiaoHeiHookSetting");
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
//        log(Log.INFO, TAG, "onPackageLoaded: " + param.getPackageName());
//        log(Log.INFO, TAG, "default classloader is " + param.getDefaultClassLoader());

        if (prefs.getBoolean("七点工具箱 VIP", false)) (new QidianToolset()).invoke(param, this);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {

    }


}