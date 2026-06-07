const utils = require("./utils.js")

var TAG = "OkHttp-Hook";
var installed = false;

// 主入口 - 使用 onPackageLoaded 监听目标应用
xposed.onPackageLoaded(function (param) {
  console.log("OkHttp Hook 脚本加载成功!", "package=", param.getPackageName(), "process=", env.processName);

  var packageName = String(param.getPackageName());
  
  xposed.i(TAG, "onPackageLoaded: " + packageName);
  xposed.i(TAG, "process=" + env.processName);

  var Application = Java.type("android.app.Application");
  var ContextClass = Java.type("android.content.Context");

  // Hook Application.attach 获取真正的应用 ClassLoader
  var attach = Application.getDeclaredMethod("attach", ContextClass);
  attach.setAccessible(true);

  xposed
    .hook(attach)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      var context = chain.getArg(0);
      xposed.d(TAG, "Application.attach before, context=" + context);

      var result = chain.proceed();

      try {
        var loader = context.getClassLoader();
        xposed.d(TAG, "Application.attach after, appClassLoader=" + loader);
        
        // 使用应用真实的 ClassLoader 安装 Hook
        install(loader);
      } catch (e) {
        xposed.e(TAG, "install OkHttp hooks from Application.attach failed", e);
      }

      return result;
    });
});

function install(loader) {
  if (installed) {
    xposed.d(TAG, "OkHttp hooks already installed, skip");
    return;
  }

  // 动态查找 OkHttp 相关类
  var okHttpClasses = findOkHttpClasses(loader);
  
  if (okHttpClasses.length === 0) {
    xposed.w(TAG, "No OkHttp classes found in application ClassLoader");
    return;
  }

  var count = 0;

  // 尝试 Hook OkHttpClient.newCall(Request)
  if (arrayContains(okHttpClasses, "okhttp3.OkHttpClient") && arrayContains(okHttpClasses, "okhttp3.Request")) {
    try {
      var OkHttpClientClass = loader.loadClass("okhttp3.OkHttpClient");
      var RequestClass = loader.loadClass("okhttp3.Request");
      var newCallMethod = OkHttpClientClass.getDeclaredMethod("newCall", RequestClass);
      newCallMethod.setAccessible(true);

      xposed
        .hook(newCallMethod)
        .setPriority(xposed.PRIORITY_DEFAULT)
        .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
        .intercept(function (chain) {
          var args = chain.getArgs();
          var request = args && args.length > 0 ? args[0] : null;
          logRequest("newCall", request);
          
          var call = chain.proceed();
          xposed.d(TAG, "[OkHttp newCall] call=" + call);
          return call;
        });

      xposed.i(TAG, "Hook installed: okhttp3.OkHttpClient.newCall(Request)");
      count++;
    } catch (e) {
      xposed.w(TAG, "Hook OkHttpClient.newCall failed: " + e);
    }
  }

  // 尝试 Hook RealCall 相关方法
  if (arrayContains(okHttpClasses, "okhttp3.RealCall")) {
    var RealCallClass = loader.loadClass("okhttp3.RealCall");
    
    // Hook execute()
    try {
      var executeMethod = RealCallClass.getDeclaredMethod("execute");
      executeMethod.setAccessible(true);

      xposed
        .hook(executeMethod)
        .setPriority(xposed.PRIORITY_DEFAULT)
        .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
        .intercept(function (chain) {
          var call = chain.getThisObject();
          logRequestFromCall("execute", call);

          var response = chain.proceed();
          logResponse("execute", response);
          return response;
        });

      xposed.i(TAG, "Hook installed: okhttp3.RealCall.execute()");
      count++;
    } catch (e) {
      xposed.w(TAG, "Hook RealCall.execute failed: " + e);
    }

    // Hook enqueue(Callback)
    if (arrayContains(okHttpClasses, "okhttp3.Callback")) {
      try {
        var CallbackClass = loader.loadClass("okhttp3.Callback");
        var enqueueMethod = RealCallClass.getDeclaredMethod("enqueue", CallbackClass);
        enqueueMethod.setAccessible(true);

        xposed
          .hook(enqueueMethod)
          .setPriority(xposed.PRIORITY_DEFAULT)
          .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
          .intercept(function (chain) {
            var call = chain.getThisObject();
            logRequestFromCall("enqueue", call);
            return chain.proceed();
          });

        xposed.i(TAG, "Hook installed: okhttp3.RealCall.enqueue(Callback)");
        count++;
      } catch (e) {
        xposed.w(TAG, "Hook RealCall.enqueue failed: " + e);
      }
    }

    // Hook getResponseWithInterceptorChain()
    try {
      var chainMethod = RealCallClass.getDeclaredMethod("getResponseWithInterceptorChain");
      chainMethod.setAccessible(true);

      xposed
        .hook(chainMethod)
        .setPriority(xposed.PRIORITY_DEFAULT)
        .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
        .intercept(function (chain) {
          var call = chain.getThisObject();
          logRequestFromCall("chain", call);

          var response = chain.proceed();
          logResponse("chain", response);
          return response;
        });

      xposed.i(TAG, "Hook installed: okhttp3.RealCall.getResponseWithInterceptorChain()");
      count++;
    } catch (e) {
      xposed.w(TAG, "Hook RealCall.getResponseWithInterceptorChain failed: " + e);
    }
  }

  installed = count > 0;

  if (installed) {
    xposed.i(TAG, "OkHttp hooks installed, count=" + count);
  } else {
    xposed.w(TAG, "No OkHttp hook installed. Target app may not use okhttp3 or classloader is not ready.");
  }
}

// 动态查找 OkHttp 相关类
function findOkHttpClasses(loader) {
  var okHttpClasses = [];
  
  // 常见的 OkHttp 类名
  var targetClasses = [
    "okhttp3.OkHttpClient",
    "okhttp3.Request",
    "okhttp3.Response",
    "okhttp3.RealCall",
    "okhttp3.Callback",
    "okhttp3.Interceptor",
    "okhttp3.Interceptor$Chain",
    "okhttp3.RequestBody",
    "okhttp3.ResponseBody",
    "okhttp3.Headers",
    "okhttp3.MediaType"
  ];

  // 尝试加载每个类
  for (var i = 0; i < targetClasses.length; i++) {
    var className = targetClasses[i];
    try {
      loader.loadClass(className);
      okHttpClasses.push(className);
      xposed.d(TAG, "Found OkHttp class: " + className);
    } catch (e) {
      // 类不存在，跳过
    }
  }

  // 也尝试模糊查找（如果 OkHttp 被混淆）
  try {
    var OkHttpClientClass = loader.loadClass("okhttp3.OkHttpClient");
    var methods = OkHttpClientClass.getDeclaredMethods();
    for (var j = 0; j < methods.length; j++) {
      var method = methods[j];
      var methodName = method.getName();
      var paramTypes = method.getParameterTypes();
      if (methodName === "newCall" && paramTypes.length === 1) {
        var paramClassName = paramTypes[0].getName();
        okHttpClasses.push(paramClassName);
        xposed.d(TAG, "Found OkHttp Request class via method: " + paramClassName);
      }
    }
  } catch (e) {
    // 忽略
  }

  return okHttpClasses;
}

// 检查数组是否包含某个元素
function arrayContains(arr, element) {
  for (var i = 0; i < arr.length; i++) {
    if (arr[i] === element) {
      return true;
    }
  }
  return false;
}

function logRequestFromCall(stage, call) {
  try {
    if (call && typeof call.request === "function") {
      logRequest(stage, call.request());
    } else {
      xposed.w(TAG, "[OkHttp " + stage + "] call.request unavailable");
    }
  } catch (e) {
    xposed.e(TAG, "logRequestFromCall failed at " + stage, e);
  }
}

function logRequest(stage, request) {
  if (!request) {
    xposed.w(TAG, "[OkHttp " + stage + "] request=null");
    return;
  }

  try {
    var method = request.method();
    var url = request.url();
    xposed.i(TAG, "[OkHttp " + stage + "] --> " + method + " " + url);

    var body = safeCall("request.body", function () {
      return request.body();
    }, null);

    if (body) {
      var contentType = body.contentType();
      var contentLength = getContentLength(body);
      xposed.i(TAG, "[OkHttp " + stage + "] requestBody contentType=" + contentType + ", contentLength=" + contentLength);
    }

    // 打印请求头
    var headers = request.headers();
    if (headers && headers.size() > 0) {
      var size = Math.min(headers.size(), 10);
      for (var k = 0; k < size; k++) {
        var name = headers.name(k);
        var value = redactSensitiveHeader(name, headers.value(k));
        xposed.d(TAG, "[OkHttp header request] " + name + ": " + value);
      }
    }
  } catch (e) {
    xposed.e(TAG, "logRequest failed", e);
  }
}

function logResponse(stage, response) {
  if (!response) {
    xposed.w(TAG, "[OkHttp " + stage + "] response=null");
    return;
  }

  try {
    var request = safeCall("response.request", function () {
      return response.request();
    }, null);

    var url = request ? request.url() : "<unknown-url>";
    xposed.i(TAG, "[OkHttp " + stage + "] <-- code=" + response.code() + " message=" + response.message() + " url=" + url);

    var body = safeCall("response.body", function () {
      return response.body();
    }, null);

    if (body) {
      var contentType = body.contentType();
      var contentLength = getContentLength(body);
      xposed.i(TAG, "[OkHttp " + stage + "] responseBody contentType=" + contentType + ", contentLength=" + contentLength);
    }
  } catch (e) {
    xposed.e(TAG, "logResponse failed", e);
  }
}

function safeCall(name, fn, defaultValue) {
  try {
    var result = fn();
    return result !== undefined && result !== null ? result : defaultValue;
  } catch (e) {
    xposed.w(TAG, "safeCall failed for " + name + ": " + e.message);
    return defaultValue;
  }
}

function getContentLength(body) {
  try {
    var contentLength = body.contentLength();
    return contentLength >= 0 ? contentLength + " bytes" : "unknown";
  } catch (e) {
    return "unknown";
  }
}

function redactSensitiveHeader(name, value) {
  var sensitiveHeaders = ["Authorization", "Cookie", "Set-Cookie", "X-Auth-Token"];
  for (var m = 0; m < sensitiveHeaders.length; m++) {
    if (name.toLowerCase() === sensitiveHeaders[m].toLowerCase()) {
      if (value && value.length > 8) {
        return value.substring(0, 4) + "***" + value.substring(value.length - 4);
      }
    }
  }
  return value;
}