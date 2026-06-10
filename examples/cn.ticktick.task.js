// ==LSPosedScript==
// @name         滴答清单永久会员脚本
// @id           cn.ticktick.task
// @version      1.0.0
// @author       XiaoHeiHook
// @description  WebIDE 创建的单脚本
// @target       cn.ticktick.task
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

let TAG = "XHH-DDQD";
let install = false;

xposed.onPackageLoaded(function (param) {
  if (install) return;

    let classLoader = param.getClassLoader();
    let UserClass = classLoader.loadClass("com.ticktick.task.data.User");

    // ==================== 1. 固定 proType = 1 ====================
    try {
        let getProType = UserClass.getDeclaredMethod("getProType");
        getProType.setAccessible(true);

        xposed.hook(getProType)
            .setPriority(xposed.PRIORITY_DEFAULT)
            .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
            .intercept(function (chain) {
                try {
                    let oldResult = chain.proceed();
                    xposed.d(TAG, "getProType original=" + oldResult);
                } catch (e) {
                    xposed.e(TAG, "getProType failed", e);
                }
                return Java.to("java.lang.Integer", 1); // Fixed VIP type
            });
        xposed.d(TAG, "✅ getProType hooked");
    } catch (e) {
        xposed.e(TAG, "Hook getProType failed: " + e);
    }

    // ==================== 2. 延长 VIP 到期时间至 2999 年 ====================
    try {
        let getProEndTime = UserClass.getDeclaredMethod("getProEndTime");
        getProEndTime.setAccessible(true);

        xposed.hook(getProEndTime)
            .setPriority(xposed.PRIORITY_DEFAULT)
            .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
            .intercept(function (chain) {
                try {
                    let oldTime = chain.proceed();
                    let oldDate = new Date(oldTime);
                    xposed.d(TAG, "getProEndTime original=" + oldTime + " (" + oldDate.toISOString() + ")");
                } catch (e) {
                    xposed.e(TAG, "getProEndTime failed", e);
                }
                // 2999-12-31 23:59:59 UTC
                // Java Date 使用的是毫秒时间戳
                return Java.to("java.lang.Long", "32503679999000");
            });
        xposed.d(TAG, "✅ getProEndTime hooked → 2999-12-31");
    } catch (e) {
        xposed.e(TAG, "Hook getProEndTime failed: " + e);
    }

    // ==================== 3. 双重保障：固定 isPro = true ====================
    try {
        let UserClass = Java.type("com.ticktick.task.data.User");
        let proTypeField = UserClass.class.getDeclaredField("proType");
        proTypeField.setAccessible(true);

        let isPro = UserClass.class.getDeclaredMethod("isPro");
        isPro.setAccessible(true);

        xposed.hook(isPro)
            .setPriority(xposed.PRIORITY_DEFAULT)
            .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
            .intercept(function (chain) {
                let user = chain.thisObject;

                try {
                    // int 字段用 setInt
                    proTypeField.setInt(user, 1);

                    xposed.d(TAG, "set this.proType = " + proTypeField.getInt(user));
                } catch (e) {
                    xposed.e(TAG, "set proType failed", e);
                }

                // 让原始 isPro() 自己执行
                return chain.proceed();
            });

        xposed.d(TAG, "✅ isPro field patch hooked");
    } catch (e) {
        xposed.e(TAG, "hook isPro/proType failed", e);
    }

    // ==================== 4. 设置 proStartTime（可选） ====================
    try {
        let getProStartTime = UserClass.getDeclaredMethod("getProStartTime");
        getProStartTime.setAccessible(true);

        xposed.hook(getProStartTime)
            .setPriority(xposed.PRIORITY_DEFAULT)
            .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
            .intercept(function (chain) {
                try {
                    let oldTime = chain.proceed();
                    xposed.d(TAG, "getProStartTime original=" + oldTime);
                } catch (e) {
                    xposed.e(TAG, "getProStartTime failed", e);
                }
                return Java.to("java.lang.Long", "1715558400000"); // 2024-01-01（随便一个过去时间）
            });
        xposed.d(TAG, "✅ getProStartTime hooked");
    } catch (e) {
        xposed.e(TAG, "Hook getProStartTime failed: " + e);
    }

    install = true;
});