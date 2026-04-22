package top.lovepikachu.XiaoHeiHook.hooks;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import io.github.libxposed.api.XposedModuleInterface;
import top.lovepikachu.XiaoHeiHook.HookEntry;

@RequiresApi(Build.VERSION_CODES.Q)
public class QidianToolset {

    private static final String TAG = "XiaoHeiCat-QidianToolset";

    /**
    * Hook 七点工具箱 把这个代码放在 onPackageLoaded 中
    * */
    public void invoke(@NonNull XposedModuleInterface.PackageLoadedParam param, @NonNull HookEntry xp) {
        if (!"cn.am7code.tools".equals(param.getPackageName())) return;

        ClassLoader cl = param.getDefaultClassLoader();

        try {
            Class<?> appClass = cl.loadClass("android.app.Application");
            var onCreate = appClass.getDeclaredMethod("onCreate");
            xp.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();

                try {
                    Class<?> jfA = cl.loadClass("jf.a");
                    xp.log(Log.INFO, TAG, "Hook 七点工具箱类 jf.a 方法 c 。");

                    // 现在 hook jf.a.c
                    var cMethod = jfA.getDeclaredMethod("c");
                    xp.hook(cMethod).intercept(inner -> {
                        inner.proceed();
                        return true;
                    });
                    xp.log(Log.INFO, TAG, "已 hook jf.a.c()");

                } catch (ClassNotFoundException e) {
                    xp.log(Log.WARN, TAG, "找不到 jf.a.c() 方法，Hook 失败。");
                } catch (Throwable t) {
                    xp.log(Log.ERROR, TAG, "内部错误，Hook jf.a.c 失败", t);
                }


                return result;
            });
        } catch (Throwable t) {
            xp.log(Log.ERROR, TAG, "Hook Application.onCreate 失败", t);
        }
    }
}
