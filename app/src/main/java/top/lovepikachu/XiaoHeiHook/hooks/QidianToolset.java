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
        String className = "pc.a";
        String methodName = "d";

        try {
            Class<?> appClass = cl.loadClass("android.app.Application");
            var onCreate = appClass.getDeclaredMethod("onCreate");
            xp.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();

                try {
                    Class<?> jfA = cl.loadClass(className);
                    xp.log(Log.INFO, TAG, String.format("Hook 七点工具箱类 %s 方法 %s 。", className, methodName));

                    var cMethod = jfA.getDeclaredMethod(methodName);
                    xp.hook(cMethod).intercept(inner -> {
                        inner.proceed();
                        return true;
                    });
                    xp.log(Log.INFO, TAG, String.format("已 hook %s.%s()", className, methodName));

                } catch (ClassNotFoundException e) {
                    xp.log(Log.WARN, TAG, String.format("找不到 %s.%s() 方法，Hook 失败。", className, methodName));
                } catch (Throwable t) {
                    xp.log(Log.ERROR, TAG, String.format("内部错误，Hook %s.%s 方法 失败", className, methodName), t);
                }


                return result;
            });
        } catch (Throwable t) {
            xp.log(Log.ERROR, TAG, "Hook Application.onCreate 失败", t);
        }
    }
}
