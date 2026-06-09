// ==LSPosedScript==
// @name         Java Bridge Smoke Test
// @id           demo.java.bridge.smoke.test
// @version      1.0.0
// @description  验证 Java.type wrapper、构造函数、实例方法、SAM proxy、Java.proxy 和反射 fallback。
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

  test("Java.type static field", function () {
    const Version = Java.type("android.os.Build$VERSION");
    const sdk = Version.SDK_INT;

    assertTrue(Number(sdk) > 0, "invalid SDK_INT: " + sdk);

    return "SDK_INT=" + sdk;
  });

  test("Java.type static method", function () {
    const Looper = Java.type("android.os.Looper");
    const mainLooper = Looper.getMainLooper();

    assertTrue(mainLooper != null, "main looper is null");

    return "mainLooper=" + mainLooper;
  });

  test("JavaClassWrapper raw Class entry", function () {
    const Application = Java.type("android.app.Application");

    const raw = Application.classObject || Application.getRawClass();
    const name = String(raw.getName());

    assertTrue(name === "android.app.Application", "unexpected raw class name: " + name);

    return name;
  });

  test("JavaClassWrapper Class method forwarding + varargs", function () {
    const Application = Java.type("android.app.Application");
    const ContextClass = Java.type("android.content.Context");

    const attach = Application.getDeclaredMethod("attach", ContextClass);

    assertTrue(attach != null, "attach method is null");
    assertTrue(String(attach.getName()) === "attach", "unexpected method: " + attach);

    return String(attach);
  });

  test("Java constructor + instance method chaining", function () {
    const StringBuilder = Java.type("java.lang.StringBuilder");

    const sb = new StringBuilder();
    sb.append("bridge").append("-").append(106);

    const value = String(sb.toString());

    assertTrue(value === "bridge-106", "unexpected StringBuilder value: " + value);

    return value;
  });

  test("Java constructor with primitive conversion", function () {
    const AtomicInteger = Java.type("java.util.concurrent.atomic.AtomicInteger");

    const counter = new AtomicInteger(40);
    const value = counter.addAndGet(2);

    assertTrue(Number(value) === 42, "unexpected AtomicInteger value: " + value);

    return "value=" + value;
  });

  test("Static method overload resolution", function () {
    const Integer = Java.type("java.lang.Integer");

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
    const Looper = Java.type("android.os.Looper");
    const Handler = Java.type("android.os.Handler");

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
    const Looper = Java.type("android.os.Looper");
    const Handler = Java.type("android.os.Handler");

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
    const Looper = Java.type("android.os.Looper");
    const Handler = Java.type("android.os.Handler");

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

  const Application = Java.type("android.app.Application");
  const ContextClass = Java.type("android.content.Context");

  // 这里专门验证 JavaClassWrapper 是否能透传 java.lang.Class 方法，
  // 以及 getDeclaredMethod(String, Class<?>...) 这种 varargs 是否能正常转换。
  const attach = Application.getDeclaredMethod("attach", ContextClass);
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