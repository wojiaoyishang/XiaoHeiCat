// ==LSPosedScript==
// @name         DumpDex 之后通过 Smali 搜索找到目标类
// @id           generic.dumpdex.smali.search
// @version      1.1.0
// @description  Application.attach 后 dump dex，然后遍历 ret.paths 按 smali 特征搜索；找到第一个目标后立即停止。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// @grant        dex.dump
// @grant        dex.find
// ==/LSPosedScript==

const TAG = 'DumpDexSmaliSearch';
let executed = false;

const SMALI_FEATURES = [
    'const-string "am7_dev_vip_override"',
    'Lcom/blankj/utilcode/util/r;->a(Ljava/lang/String;)Lcom/blankj/utilcode/util/r;',
    'Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',
    'const-string "vip"',
    'const-string "nonvip"',
    'Ljava/lang/System;->currentTimeMillis()J'
];

function dumpDexAndSearch(loader, appPackage, processName) {
    const outputDir = '/data/user/0/' + appPackage + '/code_cache/xhh_dumpdex';
    const dumpRet = dex.dumpDexCookies({
        loader: loader,
        outputDir: outputDir,
        clearOutputDir: true,
        clearCookieDir: true,
        maxDexBytes: 512 * 1024 * 1024
    });

    if (!dumpRet.ok) {
        xposed.e(TAG, 'dumpDexCookies failed ret=' + JSON.stringify(dumpRet));
        return;
    }

    let found = null;
    const paths = dumpRet.paths || [];

    xhh.each(paths, function (path, i) {
        xposed.i(TAG, 'search smali in dex[' + i + ']=' + path);

        const results = dex.findMethods({
            path: path,
            smaliContains: SMALI_FEATURES,
            limit: 1,
            includeSmali: true
        });

        if (results.length === 0) return true;

        const m = results[0];
        found = {
            dexIndex: i,
            dexPath: path,
            className: m.className,
            methodName: m.methodName,
            proto: m.proto,
            descriptor: m.descriptor
        };
        return false;
    });

    if (found) {
        xposed.i(TAG, 'foundDexIndex=' + found.dexIndex);
        xposed.i(TAG, 'foundDexPath=' + found.dexPath);
        xposed.i(TAG, 'foundMethod=' + found.className + '.' + found.methodName + found.proto);
    } else {
        xposed.w(TAG, 'no dex matched the given smali features, searched=' + paths.length);
    }
}

xposed.onPackageLoaded(function () {
    const processName = String(env.processName || '');
    const Application = Java.type('android.app.Application');
    const ContextClass = Java.type('android.content.Context');
    const attach = Application.getDeclaredMethod('attach', ContextClass);
    attach.setAccessible(true);

    xposed.hook(attach)
        .setPriority(xposed.PRIORITY_DEFAULT)
        .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
        .intercept(function (chain) {
            const context = chain.getArg(0);
            const result = chain.proceed();
            try {
                if (executed) return result;
                executed = true;
                dumpDexAndSearch(context.getClassLoader(), String(context.getPackageName()), processName);
            } catch (e) {
                xposed.e(TAG, 'dump and search failed', e);
            }
            return result;
        });
});
