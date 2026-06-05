# XiaoHeiHook JS 脚本语法说明

XiaoHeiHook 支持使用 JavaScript 编写 LSPosed Hook 脚本。脚本运行在目标应用进程中，JS 引擎为 Rhino，脚本可以通过内置的 `env`、`console`、`Java`、`xposed` 对象访问目标进程环境、调用 Java/Android 类，并注册 Hook。

> 仅建议用于你自己开发的应用、测试环境，或已获得明确授权的应用。脚本运行在目标进程内，拥有很高权限，写错可能导致目标应用崩溃。

---

## 1. 脚本放在哪里

本地脚本目录：

```text
/sdcard/Documents/XiaoHeiHook
```

支持递归扫描子目录：

```text
/sdcard/Documents/XiaoHeiHook/qidian_toolbox_log.js
/sdcard/Documents/XiaoHeiHook/scripts/demo.js
/sdcard/Documents/XiaoHeiHook/urls/qidian.url
```

文件类型：

| 类型 | 后缀 | 说明 |
|---|---|---|
| 本地 JS 脚本 | `.js` | 直接扫描、解析、同步 |
| URL 脚本指针 | `.url` | 保存远程 JS 地址，同步时拉取远程内容 |

---

## 2. 基础脚本结构

每个 `.js` 脚本建议以类似油猴脚本的头部注释开头：

```js
// ==LSPosedScript==
// @name         Application.onCreate 日志模板
// @id           sample.application.oncreate.log
// @version      1.0.0
// @author       XiaoHeiHook
// @description  记录目标应用 Application.onCreate，不修改应用行为。
// @target       com.example.demo
// @process      com.example.demo
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.hook
// ==/LSPosedScript==

console.log("script loaded: " + env.packageName + " / " + env.processName);

xposed.hookMethod({
    className: "android.app.Application",
    methodName: "onCreate",
    parameterTypes: [],
    after: function (param) {
        console.log("Application.onCreate finished: " + env.packageName);
    }
});
```

---

## 3. 脚本头字段

| 字段 | 是否建议填写 | 说明 |
|---|---:|---|
| `@name` | 是 | 页面展示名称 |
| `@id` | 是 | 脚本唯一 ID。不要和其他脚本重复 |
| `@version` | 可选 | 脚本版本 |
| `@author` | 可选 | 作者 |
| `@description` | 可选 | 描述 |
| `@target` | 是 | 目标包名，可写多个，也可写 `*` |
| `@process` | 建议 | 目标进程名，可写多个，也可写 `*` |
| `@run-at` | 是 | 执行时机，支持 `package-loaded`、`package-ready` |
| `@grant` | 可选 | 权限声明，仅用于展示/提醒 |
| `@url` | URL 指针可用 | 远程脚本地址 |
| `@mode` / `@source` | 可选 | 可写 `local` 或 `url` |

多个值可以写多行，也可以用空格或英文逗号分隔：

```js
// @target com.example.a com.example.b
// @process com.example.a, com.example.a:remote
// @grant java.full
// @grant xposed.hook
```

匹配规则：

```text
@target 为空：匹配所有包
@target *：匹配所有包
@target com.xxx：只匹配指定包

@process 为空：匹配所有进程
@process *：匹配所有进程
@process com.xxx:remote：只匹配指定进程
```

---

## 4. URL 脚本模式

URL 脚本适合在电脑上写脚本，然后通过 HTTP 服务给手机同步。

### 4.1 推荐 `.url` 文件格式

例如保存为：

```text
/sdcard/Documents/XiaoHeiHook/urls/qidian.url
```

内容：

```js
// ==LSPosedScript==
// @name         URL Script qidian.js
// @id           url.qidian
// @target       cn.am7code.tools
// @process      cn.am7code.tools
// @run-at       package-loaded
// @url          http://192.168.1.23:8000/qidian.js
// @grant        java.full
// @grant        xposed.hook
// ==/LSPosedScript==
```

也支持极简格式：

```text
http://192.168.1.23:8000/qidian.js
```

或者：

```text
URL Script qidian.js <http://192.168.1.23:8000/qidian.js>
```

### 4.2 电脑本地开发

在电脑脚本目录启动 HTTP 服务：

```bash
python3 -m http.server 8000
```

手机 URL 使用电脑局域网 IP：

```text
http://192.168.1.23:8000/qidian.js
```

如果想在手机里使用 `127.0.0.1:8000` 访问电脑，需要先执行：

```bash
adb reverse tcp:8000 tcp:8000
```

否则 `127.0.0.1` 指向的是手机自己，不是电脑。

---

## 5. 内置对象

脚本运行时提供 4 个全局对象：

```js
env
console
Java
xposed
```

---

## 6. env

`env` 表示当前目标进程环境。

```js
console.log(env.packageName);
console.log(env.processName);
console.log(env.classLoader);
```

字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `env.packageName` | Java String | 当前包名 |
| `env.processName` | Java String | 当前进程名 |
| `env.classLoader` | ClassLoader | 目标应用默认 ClassLoader |

注意 Rhino 中 Java String 和 JS String 做严格比较时可能出问题。建议这样比较：

```js
if (String(env.packageName).trim() !== "cn.am7code.tools") {
    console.log("package mismatch: " + env.packageName);
    return;
}
```

不要直接依赖：

```js
// 不推荐
if (env.packageName !== "cn.am7code.tools") {
    return;
}
```

---

## 7. console

用于输出日志到 LSPosed / Logcat。

```js
console.log("info message");
console.warn("warn message");
console.error("error message");
```

Logcat 可以过滤：

```text
XiaoHeiHook-JS
XiaoHeiHook-Entry
XiaoHeiHook-Scripts
```

也可以直接使用 Android Log：

```js
var Log = Java.type("android.util.Log");
Log.d("MyScript", "hello");
```

---

## 8. Java API

`Java` 对象用于加载类、获取方法、字段、构造器。

### 8.1 加载类

```js
var Log = Java.type("android.util.Log");
var Activity = Java.use("android.app.Activity");
var Cls = Java.classForName("com.example.demo.SomeClass");
```

这三个方法等价，都会优先使用目标应用 ClassLoader：

```js
Java.type(className)
Java.use(className)
Java.classForName(className)
```

### 8.2 基本类型

Hook 参数类型支持 Java 基本类型名称：

```text
void
boolean
byte
char
short
int
long
float
double
```

示例：

```js
parameterTypes: ["int", "java.lang.String", "boolean"]
```

### 8.3 获取方法

```js
var method = Java.method(
    "com.example.demo.UserService",
    "login",
    ["java.lang.String", "java.lang.String"]
);
```

也可以先加载类：

```js
var UserService = Java.type("com.example.demo.UserService");
var method = Java.method(UserService, "login", ["java.lang.String", "java.lang.String"]);
```

返回的是 Java `Method`，已经调用过 `setAccessible(true)`。

### 8.4 获取构造器

```js
var ctor = Java.constructor(
    "com.example.demo.User",
    ["java.lang.String", "int"]
);
```

### 8.5 获取字段

```js
var field = Java.field("com.example.demo.User", "name");
```

读取实例字段：

```js
var name = field.get(userObj);
```

修改实例字段：

```js
field.set(userObj, "new name");
```

读取静态字段：

```js
var value = field.get(null);
```

修改静态字段：

```js
field.set(null, value);
```

---

## 9. xposed API

`xposed` 用于注册 Hook。

目前支持：

```js
xposed.hookMethod(config)
xposed.hookConstructor(config)
xposed.log(message)
```

---

## 10. Hook 普通方法

```js
xposed.hookMethod({
    className: "com.example.demo.UserService",
    methodName: "login",
    parameterTypes: ["java.lang.String", "java.lang.String"],

    before: function (param) {
        console.log("before login, user=" + param.getArg(0));
    },

    after: function (param) {
        console.log("after login, result=" + param.result);
    }
});
```

`parameterTypes` 必须和目标方法参数完全匹配。无参方法写空数组：

```js
parameterTypes: []
```

---

## 11. Hook 构造器

```js
xposed.hookConstructor({
    className: "com.example.demo.User",
    parameterTypes: ["java.lang.String", "int"],

    before: function (param) {
        console.log("new User name=" + param.getArg(0));
    },

    after: function (param) {
        console.log("User constructed: " + param.thisObject);
    }
});
```

---

## 12. before / after / replace

### 12.1 before

`before` 在原方法执行前调用。

可以：

```text
读取参数
修改参数
提前返回
提前抛异常
```

示例：修改参数：

```js
xposed.hookMethod({
    className: "com.example.demo.UserService",
    methodName: "login",
    parameterTypes: ["java.lang.String", "java.lang.String"],

    before: function (param) {
        param.setArg(0, "new_user");
        param.setArg(1, "new_password");
    }
});
```

示例：跳过原函数，直接返回：

```js
xposed.hookMethod({
    className: "com.example.demo.Feature",
    methodName: "isEnabled",
    parameterTypes: [],

    before: function (param) {
        param.setResult(false);
    }
});
```

### 12.2 after

`after` 在原方法执行后调用。

可以：

```text
读取原返回值
修改最终返回值
读取异常
替换异常
清除异常
```

示例：修改返回值：

```js
xposed.hookMethod({
    className: "com.example.demo.Config",
    methodName: "getText",
    parameterTypes: [],

    after: function (param) {
        console.log("old result=" + param.result);
        param.setResult("new value");
    }
});
```

### 12.3 replace

`replace` 会完全替换原方法逻辑。

```js
xposed.hookMethod({
    className: "com.example.demo.Calculator",
    methodName: "sum",
    parameterTypes: ["int", "int"],

    replace: function (param) {
        var a = param.getArg(0);
        var b = param.getArg(1);
        console.log("sum replaced, old args=" + a + ", " + b);
        return 114514;
    }
});
```

执行优先级：

```text
如果配置了 replace：只执行 replace，不执行 before / 原方法 / after
否则：before → 原方法 → after
```

---

## 13. HookParam 参数对象

Hook 回调里的 `param` 是 `HookParam` 对象。

### 13.1 字段

| 字段 | 说明 |
|---|---|
| `param.executable` | 当前被 Hook 的 Method 或 Constructor |
| `param.thisObject` | 当前实例对象；静态方法可能为 `null` |
| `param.args` | 参数数组 |
| `param.result` | 返回值，主要在 `after` 中读取 |
| `param.throwable` | 原方法抛出的异常，主要在 `after` 中读取 |

### 13.2 方法

| 方法 | 说明 |
|---|---|
| `param.getArg(index)` | 获取指定参数 |
| `param.setArg(index, value)` | 修改指定参数。通常在 `before` 中使用 |
| `param.argCount()` | 参数数量 |
| `param.setResult(value)` | 设置返回值。`before` 中会跳过原方法，`after` 中会覆盖返回值 |
| `param.returnResult(value)` | `setResult` 的别名 |
| `param.replaceResult(value)` | `setResult` 的别名 |
| `param.setThrowable(throwable)` | 设置最终抛出的异常 |
| `param.clearThrowable()` | 清除异常 |
| `param.hasThrowable()` | 是否存在异常 |

### 13.3 修改参数

```js
before: function (param) {
    console.log("arg count=" + param.argCount());
    console.log("old arg0=" + param.getArg(0));
    param.setArg(0, "modified");
}
```

### 13.4 修改返回值

```js
after: function (param) {
    console.log("old result=" + param.result);
    param.setResult("modified result");
}
```

### 13.5 抛出异常

```js
before: function (param) {
    var IllegalStateException = Java.type("java.lang.IllegalStateException");
    param.setThrowable(new IllegalStateException("blocked by script"));
}
```

### 13.6 清除异常并返回默认值

```js
after: function (param) {
    if (param.hasThrowable()) {
        console.log("origin throwable=" + param.throwable);
        param.clearThrowable();
        param.setResult(null);
    }
}
```

---

## 14. 常用模板

### 14.1 只打印方法调用

```js
// ==LSPosedScript==
// @name         Method Log Demo
// @id           demo.method.log
// @target       com.example.demo
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.hook
// ==/LSPosedScript==

xposed.hookMethod({
    className: "com.example.demo.MainActivity",
    methodName: "onResume",
    parameterTypes: [],
    before: function (param) {
        console.log("MainActivity.onResume before");
    },
    after: function (param) {
        console.log("MainActivity.onResume after");
    }
});
```

### 14.2 Hook Application.onCreate 后再 Hook 目标类

有些类在应用启动初期还没加载，可以先 Hook `Application.onCreate`，再注册其他 Hook。

```js
// ==LSPosedScript==
// @name         Lazy Hook Demo
// @id           demo.lazy.hook
// @target       com.example.demo
// @process      com.example.demo
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.hook
// ==/LSPosedScript==

xposed.hookMethod({
    className: "android.app.Application",
    methodName: "onCreate",
    parameterTypes: [],
    after: function (param) {
        console.log("Application.onCreate done, register app hook");

        xposed.hookMethod({
            className: "com.example.demo.SomeClass",
            methodName: "someMethod",
            parameterTypes: [],
            after: function (inner) {
                console.log("SomeClass.someMethod result=" + inner.result);
            }
        });
    }
});
```

### 14.3 Android Log.d

```js
var Log = Java.type("android.util.Log");
Log.d("XiaoHeiHook-Demo", "hello from js");
```

### 14.4 读取对象字段

```js
xposed.hookMethod({
    className: "com.example.demo.User",
    methodName: "print",
    parameterTypes: [],
    before: function (param) {
        var nameField = Java.field("com.example.demo.User", "name");
        var name = nameField.get(param.thisObject);
        console.log("user.name=" + name);
    }
});
```

### 14.5 修改对象字段

```js
xposed.hookMethod({
    className: "com.example.demo.User",
    methodName: "print",
    parameterTypes: [],
    before: function (param) {
        var nameField = Java.field("com.example.demo.User", "name");
        nameField.set(param.thisObject, "new name");
    }
});
```

---

## 15. 同步与启用流程

典型流程：

```text
1. 把 .js 或 .url 放入 Documents/XiaoHeiHook
2. 打开 XiaoHeiHook
3. 进入应用列表
4. 打开目标应用总开关
5. 进入目标应用详情页
6. 启用需要的脚本
7. 点击“同步”或“同步并重启”
8. 重启目标应用后生效
```

说明：

```text
公共目录里的脚本不会被目标进程直接读取。
应用会先把启用脚本同步到 LSPosed Remote Files。
目标应用启动后，HookEntry 从 Remote Files 读取脚本并执行。
```

---

## 16. 热重载模式

应用详情页可以打开热重载模式。

热重载会监控当前应用匹配的脚本指纹：

```text
本地 .js 内容变化
URL 指针内容变化
URL 远程脚本内容变化
```

变化后自动执行：

```text
同步脚本 → 强制停止目标应用 → 启动目标应用
```

适合 adb/电脑开发环境。

---

## 17. 常见问题

### 17.1 文件存在但扫描不到

检查：

```text
1. 文件是否在 /sdcard/Documents/XiaoHeiHook 下
2. 后缀是否为 .js 或 .url
3. 是否授予“管理所有文件”权限
4. Logcat 是否有 XiaoHeiHook-Scripts 日志
```

如果普通权限不能读取，应用会尝试 root fallback。

### 17.2 URL 脚本无法下载

检查：

```text
1. 手机是否能访问该 URL
2. http://127.0.0.1 是否误用了。它在手机上指向手机本机
3. 如果服务在电脑上，请用电脑局域网 IP
4. 或执行 adb reverse tcp:8000 tcp:8000
5. AndroidManifest 是否允许明文 HTTP：usesCleartextTraffic=true
```

### 17.3 脚本显示但 Hook 不执行

检查：

```text
1. 目标应用总开关是否开启
2. 单个脚本开关是否开启
3. 是否点击同步
4. @target 是否匹配当前包名
5. @process 是否匹配当前进程
6. @run-at 是否正确
7. 目标应用是否已重启
```

### 17.4 String 比较明明一样却判断失败

Rhino 中 `env.packageName` 是 Java String，建议转换后比较：

```js
if (String(env.packageName).trim() === "cn.am7code.tools") {
    console.log("matched");
}
```

### 17.5 找不到目标类或方法

检查：

```text
1. className 是否是完整类名
2. methodName 是否正确
3. parameterTypes 是否和目标方法完全一致
4. 类是否在当前进程中加载
5. 是否应该放到 Application.onCreate 后再注册 Hook
```

---

## 18. 调试建议

建议每个脚本开头都写：

```js
console.log("script loaded: " + env.packageName + " / " + env.processName);
```

同时建议在关键 Hook 注册前后打日志：

```js
console.log("register hook begin");

xposed.hookMethod({
    className: "android.app.Application",
    methodName: "onCreate",
    parameterTypes: [],
    after: function (param) {
        console.log("Application.onCreate after");
    }
});

console.log("register hook done");
```

Logcat 常用过滤：

```text
XiaoHeiHook-JS
XiaoHeiHook-Entry
XiaoHeiHook-Scripts
XiaoHeiHook-Apps
```

---

## 19. 最小可用脚本

```js
// ==LSPosedScript==
// @name         Minimal Demo
// @id           demo.minimal
// @target       com.example.demo
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.hook
// ==/LSPosedScript==

console.log("hello: " + env.packageName + " / " + env.processName);

xposed.hookMethod({
    className: "android.app.Application",
    methodName: "onCreate",
    parameterTypes: [],
    after: function (param) {
        var Log = Java.type("android.util.Log");
        Log.d("XiaoHeiHook-Minimal", "Application.onCreate: " + env.packageName);
    }
});
```
