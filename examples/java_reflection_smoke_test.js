// ==LSPosedScript==
// @name         Java Reflection Smoke Test
// @id           demo.java.reflection.smoke.test
// @version      1.32.109
// @author       XiaoHeiHook
// @description  验证 getDeclaredMethod/getMethod/getDeclaredConstructor 字符串签名、Method.invoke 参数转换和 Java 对象直传。
// @target       *
// @process      *
// @run-at       package-loaded
// @grant        java.full
// @grant        xposed.full
// ==/LSPosedScript==

const TAG = "JavaReflectSmoke";

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

function runJavaReflectionSmokeTests() {
  xposed.i(TAG, "========== Java reflection smoke test start ==========");

  test("getDeclaredMethod varargs string signature", function () {
    const StringClass = Java.use("java.lang.String");
    const substring = StringClass.getDeclaredMethod("substring", "int", "int");
    const text = Java.to("java.lang.String", "abcdef");

    // Method.invoke 会按 substring(int, int) 的真实目标签名转换后续参数。
    const value = substring.invoke(text, 1, 4);

    assertTrue(String(value) === "bcd", "unexpected substring result: " + value);
    return String(value);
  });

  test("getDeclaredMethod array string signature", function () {
    const StringClass = Java.use("java.lang.String");
    const indexOf = StringClass.getDeclaredMethod("indexOf", ["java.lang.String"]);
    const text = Java.to("java.lang.String", "abcdef");
    const value = indexOf.invoke(text, "cd");

    assertTrue(Number(value) === 2, "unexpected indexOf result: " + value);
    return "index=" + value;
  });

  test("getDeclaredMethod Java.use wrapper signature", function () {
    const StringClass = Java.use("java.lang.String");
    const IntegerClass = Java.use("java.lang.Integer");
    const valueOf = IntegerClass.getDeclaredMethod("valueOf", StringClass);
    const value = valueOf.invoke(null, "456");

    assertTrue(Number(value) === 456, "unexpected Integer.valueOf result: " + value);
    return "value=" + value;
  });

  test("getMethod public method", function () {
    const ObjectClass = Java.use("java.lang.Object");
    const ArrayList = Java.use("java.util.ArrayList");
    const list = new ArrayList();

    const getClassMethod = ObjectClass.getMethod("getClass");
    const clazz = getClassMethod.invoke(list);
    const name = String(clazz.getName());

    assertTrue(name === "java.util.ArrayList", "unexpected class name: " + name);
    return name;
  });

  test("getDeclaredConstructor string signature", function () {
    const StringBuilder = Java.use("java.lang.StringBuilder");
    const constructor = StringBuilder.getDeclaredConstructor("java.lang.String");
    const sb = constructor.newInstance(Java.to("java.lang.String", "abc"));
    const value = String(sb.toString());

    assertTrue(value === "abc", "unexpected StringBuilder value: " + value);
    return value;
  });

  test("Java object passed as Object is not auto-converted", function () {
    const LongClass = Java.use("java.lang.Long");
    const ArrayList = Java.use("java.util.ArrayList");

    const valueOf = LongClass.getDeclaredMethod("valueOf", "java.lang.String");
    const longObject = valueOf.invoke(null, "1234567890123");

    const list = new ArrayList();
    const add = ArrayList.getMethod("add", "java.lang.Object");
    add.invoke(list, longObject);

    const item = list.get(0);

    // 某些 Java wrapper 不会把 Object.getClass() 暴露为直接可点调用的方法。
    // 这里用 Java 原生反射读取 class，避免把 JS wrapper 的方法可见性误判为类型转换失败。
    const ObjectClass = Java.use("java.lang.Object");
    const getClass = ObjectClass.getMethod("getClass");
    const itemClass = getClass.invoke(item);
    const itemClassName = String(itemClass.getName());

    assertTrue(itemClassName === "java.lang.Long", "unexpected item class: " + itemClassName);
    return itemClassName;
  });

  test("Java.callStatic explicit primitive signature", function () {
    const value = Java.callStatic(
      "java.lang.Math",
      "max",
      [Java.to("int", 3), Java.to("int", 7)],
      ["int", "int"]
    );

    assertTrue(Number(value) === 7, "unexpected Math.max result: " + value);
    return "max=" + value;
  });

  xposed.i(
    TAG,
    "========== Java reflection smoke test summary: passed=" + passed + ", failed=" + failed + " =========="
  );
}

xposed.onPackageLoaded(function (param) {
  if (executed) return;
  executed = true;

  xposed.i(
    TAG,
    "loaded package=" + param.getPackageName() + " process=" + String(env.processName || "")
  );

  runJavaReflectionSmokeTests();
});
