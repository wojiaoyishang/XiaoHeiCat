// ==LSPosedScript==
// @name         Java Proxy Smoke Test
// @id           demo.java.proxy.smoke.test
// @version      1.32.109
// @author       XiaoHeiHook
// @description  验证 Java.proxy、自动 SAM 代理、代理返回值转换和代理对象传回 Java 的可用性。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

const TAG = "JavaProxySmoke";

let executed = false;
let passed = 0;
let failed = 0;

function pass(name, detail) {
  passed++;
  xposed.i(TAG, "[PASS] " + name + (detail ? " => " + detail : ""));
}

function fail(name, error) {
  failed++;
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

function runJavaProxySmokeTests() {
  xposed.i(TAG, "========== Java proxy smoke test start ==========");

  test("explicit Java.proxy Runnable", function () {
    let ran = false;

    const runnable = Java.proxy("java.lang.Runnable", {
      run: function () {
        ran = true;
        xposed.i(TAG, "Runnable.run callback executed");
      }
    });

    runnable.run();

    assertTrue(ran, "Runnable.run was not called");
    return "ran=" + ran;
  });

  test("automatic SAM proxy from JS function", function () {
    let ran = false;
    const Thread = Java.use("java.lang.Thread");

    // Thread(Runnable) 的 Runnable 参数由 Bridge 自动把 JS function 转成 Java 代理。
    const thread = new Thread(function () {
      ran = true;
      xposed.i(TAG, "automatic SAM Runnable callback executed");
    });

    // 直接调用 run()，避免真实开启线程带来的时序不确定性。
    thread.run();

    assertTrue(ran, "automatic SAM Runnable was not called");
    return "ran=" + ran;
  });

  test("Java.proxy Callable return value", function () {
    const FutureTask = Java.use("java.util.concurrent.FutureTask");

    const callable = Java.proxy("java.util.concurrent.Callable", {
      call: function () {
        return Java.to("java.lang.String", "callable-ok");
      }
    });

    const task = new FutureTask(callable);
    task.run();

    const value = task.get();

    assertTrue(String(value) === "callable-ok", "unexpected Callable result: " + value);
    return String(value);
  });

  test("Java.proxy Comparator passed to Java reflection", function () {
    const ArrayList = Java.use("java.util.ArrayList");
    const Collections = Java.use("java.util.Collections");

    const list = new ArrayList();
    list.add("bbb");
    list.add("a");
    list.add("cc");

    const comparator = Java.proxy("java.util.Comparator", {
      compare: function (a, b) {
        return Java.to("int", String(a).length - String(b).length);
      }
    });

    // 1.32 (109) 起，getDeclaredMethod 支持字符串签名。
    const sort = Collections.getDeclaredMethod(
      "sort",
      "java.util.List",
      "java.util.Comparator"
    );

    sort.invoke(null, list, comparator);

    const first = String(list.get(0));
    const second = String(list.get(1));
    const third = String(list.get(2));

    assertTrue(first === "a" && second === "cc" && third === "bbb", "unexpected order: " + list);
    return first + "," + second + "," + third;
  });

  xposed.i(
    TAG,
    "========== Java proxy smoke test summary: passed=" + passed + ", failed=" + failed + " =========="
  );
}

xposed.onPackageLoaded(function (param) {
  if (executed) return;
  executed = true;

  xposed.i(
    TAG,
    "loaded package=" + param.getPackageName() + " process=" + String(env.processName || "")
  );

  runJavaProxySmokeTests();
});
