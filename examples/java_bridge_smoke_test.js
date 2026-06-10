// ==LSPosedScript==
// @name         Java Bridge Smoke Test
// @id           demo.java.bridge.smoke.test
// @version      1.32.109
// @description  验证 1.32 (109) Java Bridge 推荐写法、Java.to、Java.proxy、自动 SAM 和 Java 反射。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

const TAG = "JavaBridgeSmoke";

let executed = false;
let syncPassed = 0;
let syncFailed = 0;

function pass(name, detail) {
  syncPassed++;
  xposed.i(TAG, "[PASS] " + name + (detail ? " => " + detail : ""));
}

function fail(name, error) {
  syncFailed++;
  xposed.e(TAG, "[FAIL] " + name, error);
}

function assertTrue(value, message) {
  if (!value) {
    throw new Error(message || "assert failed");
  }
}

function test(name, fn) {
  try {
    const detail = fn();
    pass(name, detail);
  } catch (e) {
    fail(name, e);
  }
}

function runJavaBridgeSmokeTests(context) {
  xposed.i(TAG, "========== Java Bridge smoke test start ==========");

  test("Java.use static field", function () {
    const Version = Java.use("android.os.Build$VERSION");
    const sdk = Version.SDK_INT;

    assertTrue(Number(sdk) > 0, "invalid SDK_INT: " + sdk);

    return "SDK_INT=" + sdk;
  });

  test("Java.use static method", function () {
    const Looper = Java.use("android.os.Looper");
    const mainLooper = Looper.getMainLooper();

    assertTrue(mainLooper != null, "main looper is null");

    return "mainLooper=" + mainLooper;
  });

  test("JavaClassWrapper raw Class entry", function () {
    const Application = Java.use("android.app.Application");

    const raw = Application.classObject || Application.getRawClass();
    const name = String(raw.getName());

    assertTrue(name === "android.app.Application", "unexpected raw class name: " + name);

    return name;
  });

  test("getDeclaredMethod string signature", function () {
    const Application = Java.use("android.app.Application");

    const attach = Application.getDeclaredMethod("attach", "android.content.Context");

    assertTrue(attach != null, "attach method is null");
    assertTrue(String(attach.getName()) === "attach", "unexpected method: " + attach);

    return String(attach);
  });

  test("getDeclaredMethod array signature", function () {
    const StringClass = Java.use("java.lang.String");
    const substring = StringClass.getDeclaredMethod("substring", ["int", "int"]);
    const text = Java.to("java.lang.String", "abcdef");
    const value = substring.invoke(text, 1, 4);

    assertTrue(String(value) === "bcd", "unexpected substring value: " + value);

    return String(value);
  });

  test("Java.use wrapper signature", function () {
    const StringClass = Java.use("java.lang.String");
    const IntegerClass = Java.use("java.lang.Integer");
    const valueOf = IntegerClass.getDeclaredMethod("valueOf", StringClass);
    const value = valueOf.invoke(null, "123");

    assertTrue(Number(value) === 123, "unexpected Integer.valueOf value: " + value);

    return "value=" + value;
  });

  test("Java.to explicit primitive value", function () {
    const AtomicInteger = Java.use("java.util.concurrent.atomic.AtomicInteger");
    const counter = new AtomicInteger(Java.to("int", "40"));
    const value = counter.addAndGet(Java.to("int", 2));

    assertTrue(Number(value) === 42, "unexpected AtomicInteger value: " + value);

    return "value=" + value;
  });

  test("Java constructor + instance method chaining", function () {
    const StringBuilder = Java.use("java.lang.StringBuilder");

    const sb = new StringBuilder();
    sb.append("bridge").append("-").append(109);

    const value = String(sb.toString());

    assertTrue(value === "bridge-109", "unexpected StringBuilder value: " + value);

    return value;
  });

  test("Static method overload resolution", function () {
    const Integer = Java.use("java.lang.Integer");

    const value = Integer.parseInt("123");

    assertTrue(Number(value) === 123, "unexpected parseInt value: " + value);

    return "parseInt=" + value;
  });

  test("Context object instance method", function () {
    const packageName = String(context.getPackageName());

    assertTrue(packageName.length > 0, "package name is empty");

    return packageName;
  });

  test("Handler constructor", function () {
    const Looper = Java.use("android.os.Looper");
    const Handler = Java.use("android.os.Handler");

    const handler = new Handler(Looper.getMainLooper());

    assertTrue(handler != null, "handler is null");

    return String(handler);
  });

  test("Reflection fallback callStatic/newInstance/call", function () {
    const mainLooper = Java.callStatic("android.os.Looper", "getMainLooper");
    const handler = Java.newInstance("android.os.Handler", [mainLooper]);

    const runnable = Java.proxy("java.lang.Runnable", {
      run: function () {
        xposed.i(TAG, "[ASYNC PASS] reflection fallback Java.call(...post...) runnable executed");
      }
    });

    const posted = Java.call(
      handler,
      "post",
      [runnable],
      ["java.lang.Runnable"]
    );

    assertTrue(posted === true || String(posted) === "true", "Handler.post returned: " + posted);

    return "post=" + posted;
  });

  test("Explicit Java.proxy Runnable", function () {
    const Looper = Java.use("android.os.Looper");
    const Handler = Java.use("android.os.Handler");

    const handler = new Handler(Looper.getMainLooper());

    const runnable = Java.proxy("java.lang.Runnable", {
      run: function () {
        xposed.i(TAG, "[ASYNC PASS] explicit Java.proxy Runnable executed");
      }
    });

    const posted = handler.post(runnable);

    assertTrue(posted === true || String(posted) === "true", "Handler.post returned: " + posted);

    return "post=" + posted;
  });

  test("Automatic SAM proxy from JS function", function () {
    const Looper = Java.use("android.os.Looper");
    const Handler = Java.use("android.os.Handler");

    const handler = new Handler(Looper.getMainLooper());

    const posted = handler.post(function () {
      xposed.i(TAG, "[ASYNC PASS] automatic SAM function Runnable executed");
    });

    assertTrue(posted === true || String(posted) === "true", "Handler.post returned: " + posted);

    return "post=" + posted;
  });

  xposed.i(
    TAG,
    "========== Java Bridge smoke test sync summary: passed=" +
      syncPassed +
      ", failed=" +
      syncFailed +
      " =========="
  );
}

xposed.onPackageLoaded(function (param) {
  const packageName = String(param.getPackageName());
  const processName = String(env.processName || "");

  xposed.i(TAG, "loaded package=" + packageName + " process=" + processName);

  const Application = Java.use("android.app.Application");

  // 这里专门验证 JavaClassWrapper 是否能透传 java.lang.Class 方法，
  // 以及 getDeclaredMethod(String, Class<?>...) 字符串签名是否能正常转换。
  const attach = Application.getDeclaredMethod("attach", "android.content.Context");
  attach.setAccessible(true);

  xposed
    .hook(attach)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      const context = chain.getArg(0);
      const result = chain.proceed();

      if (executed) {
        return result;
      }

      executed = true;

      try {
        runJavaBridgeSmokeTests(context);
      } catch (e) {
        xposed.e(TAG, "Java Bridge smoke test crashed", e);
      }

      return result;
    });
});