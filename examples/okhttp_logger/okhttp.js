var utils = require("./utils.js")
var reflect = require("./reflect.js")

var installedLoaderKeys = {}
var hookedCallClassNames = {}

function install(param, config, logger) {
  logger.info("OkHttp hook install start")
  logger.info("package=" + param.getPackageName() + ", process=" + env.processName)

  var Application = Java.use("android.app.Application")

  // XiaoHeiHook 1.32 (109) 推荐语法：getDeclaredMethod 可以直接传字符串签名。
  var attach = Application.getDeclaredMethod("attach", "android.content.Context")
  attach.setAccessible(true)

  xposed
    .hook(attach)
    .setPriority(xposed.PRIORITY_DEFAULT)
    .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
    .intercept(function (chain) {
      var context = chain.getArg(0)
      logger.debug("Application.attach before, context=" + context)

      var result = chain.proceed()

      try {
        var loader = context.getClassLoader()
        logger.debug("Application.attach after, classLoader=" + loader)
        installWithClassLoader(loader, config, logger)
      } catch (e) {
        logger.error("install OkHttp hooks from Application.attach failed", e)
      }

      return result
    })
}

function installWithClassLoader(loader, config, logger) {
  var loaderKey = String(loader)

  if (installedLoaderKeys[loaderKey]) {
    logger.debug("OkHttp hooks already installed for classLoader, skip")
    return
  }

  var classes = findOkHttpClasses(loader, logger)
  var count = 0

  if (config.hookNewCall !== false && classes.OkHttpClient && classes.Request) {
    count += hookDeclaredMethodsByNameAndParamCount(
      classes.OkHttpClient,
      "newCall",
      1,
      "okhttp3.OkHttpClient.newCall(Request)",
      function (method) {
        return function (chain) {
          var request = chain.getArg(0)

          safeLogRequest("newCall", request, config, logger)

          var call = chain.proceed()
          logger.debug("[OkHttp newCall] call=" + call)

          try {
            installCallEnhancementHooksFromObject(call, classes, config, logger, "OkHttpClient.newCall")
          } catch (e) {
            logger.warn("install Call enhancement from newCall failed but ignored: " + e)
          }

          return call
        }
      },
      logger
    )
  } else {
    logger.warn("Skip OkHttpClient.newCall hook: OkHttpClient or Request class not found")
  }

  if (config.hookRequestBuilder !== false && classes.RequestBuilder) {
    count += hookRequestBuilder(classes, config, logger)
  } else {
    logger.warn("Skip Request.Builder hooks: okhttp3.Request$Builder not found or disabled")
  }

  if (config.hookFormBodyBuilder !== false && classes.FormBodyBuilder) {
    count += hookFormBodyBuilder(classes, config, logger)
  } else {
    logger.warn("Skip FormBody.Builder hooks: okhttp3.FormBody$Builder not found or disabled")
  }

  if (config.hookCallInterface !== false && classes.Call) {
    count += hookCallLikeClass(classes.Call, classes, config, logger, "okhttp3.Call")
  }

  if (count > 0) {
    installedLoaderKeys[loaderKey] = true
    logger.info("OkHttp hooks installed for classLoader, count=" + count)
  } else {
    logger.warn("No OkHttp hook installed for this classLoader")
  }
}

function findOkHttpClasses(loader, logger) {
  var classes = {}

  classes.OkHttpClient = tryLoadClass(loader, "okhttp3.OkHttpClient", logger)
  classes.Call = tryLoadClass(loader, "okhttp3.Call", logger)
  classes.Request = tryLoadClass(loader, "okhttp3.Request", logger)
  classes.Response = tryLoadClass(loader, "okhttp3.Response", logger)
  classes.Callback = tryLoadClass(loader, "okhttp3.Callback", logger)
  classes.Headers = tryLoadClass(loader, "okhttp3.Headers", logger)
  classes.RequestBody = tryLoadClass(loader, "okhttp3.RequestBody", logger)
  classes.ResponseBody = tryLoadClass(loader, "okhttp3.ResponseBody", logger)
  classes.RequestBuilder = tryLoadClass(loader, "okhttp3.Request$Builder", logger)
  classes.FormBodyBuilder = tryLoadClass(loader, "okhttp3.FormBody$Builder", logger)
  classes.HttpUrl = tryLoadClass(loader, "okhttp3.HttpUrl", logger)

  // 注意：不要主动 loader.loadClass("okhttp3.RealCall")。
  // 很多 App 的 RealCall 可能被移动、混淆、裁剪，主动加载容易导致 Hook 回调失败。
  // 本示例只在 OkHttpClient.newCall(Request) 返回真实 Call 对象后，从对象本身获取 Class 再增强 Hook。
  classes.RealCall = null

  return classes
}

function tryLoadClass(loader, name, logger) {
  try {
    var cls = loader.loadClass(name)
    logger.debug("Found OkHttp class: " + name)
    return cls
  } catch (e) {
    logger.debug("Class not found: " + name)
    return null
  }
}

function hookRequestBuilder(classes, config, logger) {
  var count = 0
  var BuilderClass = classes.RequestBuilder

  count += hookDeclaredMethodsByNameAndParamCount(
    BuilderClass,
    "get",
    0,
    "Request.Builder.get()",
    function (method) {
      return function (chain) {
        logger.info("[OkHttp builder] GET")
        return chain.proceed()
      }
    },
    logger
  )

  count += hookDeclaredMethodsByNameAndParamCount(
    BuilderClass,
    "post",
    1,
    "Request.Builder.post(RequestBody)",
    function (method) {
      return function (chain) {
        var body = chain.getArg(0)
        logger.info("[OkHttp builder] POST body=" + describeRequestBody(body, logger))
        return chain.proceed()
      }
    },
    logger
  )

  count += hookDeclaredMethodsByNameAndParamCount(
    BuilderClass,
    "method",
    2,
    "Request.Builder.method(String, RequestBody)",
    function (method) {
      return function (chain) {
        var methodName = chain.getArg(0)
        var body = chain.getArg(1)
        logger.info("[OkHttp builder] method=" + methodName + ", body=" + describeRequestBody(body, logger))
        return chain.proceed()
      }
    },
    logger
  )

  count += hookDeclaredMethodsByNameAndParamCount(
    BuilderClass,
    "url",
    1,
    "Request.Builder.url(...)",
    function (method) {
      return function (chain) {
        logger.info("[OkHttp builder] url=" + chain.getArg(0))
        return chain.proceed()
      }
    },
    logger
  )

  count += hookDeclaredMethodsByNameAndParamCount(
    BuilderClass,
    "build",
    0,
    "Request.Builder.build()",
    function (method) {
      return function (chain) {
        var request = chain.proceed()
        safeLogRequest("build", request, config, logger)
        return request
      }
    },
    logger
  )

  return count
}

function hookFormBodyBuilder(classes, config, logger) {
  var count = 0
  var BuilderClass = classes.FormBodyBuilder

  count += hookDeclaredMethodsByNameAndParamCount(
    BuilderClass,
    "add",
    2,
    "FormBody.Builder.add(String, String)",
    function (method) {
      return function (chain) {
        logger.info("[OkHttp form] add " + chain.getArg(0) + "=" + chain.getArg(1))
        return chain.proceed()
      }
    },
    logger
  )

  count += hookDeclaredMethodsByNameAndParamCount(
    BuilderClass,
    "addEncoded",
    2,
    "FormBody.Builder.addEncoded(String, String)",
    function (method) {
      return function (chain) {
        logger.info("[OkHttp form] addEncoded " + chain.getArg(0) + "=" + chain.getArg(1))
        return chain.proceed()
      }
    },
    logger
  )

  count += hookDeclaredMethodsByNameAndParamCount(
    BuilderClass,
    "build",
    0,
    "FormBody.Builder.build()",
    function (method) {
      return function (chain) {
        var body = chain.proceed()
        logger.info("[OkHttp form] build body=" + describeRequestBody(body, logger))
        return body
      }
    },
    logger
  )

  return count
}

function hookDeclaredMethodsByNameAndParamCount(clazz, methodName, paramCount, label, handlerFactory, logger) {
  var count = 0
  var methods = reflect.findDeclaredMethods(clazz, methodName, paramCount, logger)
  var i

  if (methods.length === 0) {
    logger.debug("No method matched: " + label)
    return 0
  }

  for (i = 0; i < methods.length; i++) {
    var method = methods[i]

    try {
      reflect.setAccessible(method)

      xposed
        .hook(method)
        .setPriority(xposed.PRIORITY_DEFAULT)
        .setExceptionMode(xposed.ExceptionMode.PROTECTIVE)
        .intercept(handlerFactory(method))

      logger.info("Hook installed: " + label)
      count++
    } catch (e) {
      logger.warn("Hook " + label + " failed but ignored: " + e)
    }
  }

  return count
}

function describeRequestBody(body, logger) {
  if (!body) return "null"

  var contentType = reflect.invokeNoArg(body, "contentType", null)
  var contentLength = reflect.invokeNoArg(body, "contentLength", null)

  if (contentLength === null || contentLength === undefined) {
    contentLength = -1
  }

  return "contentType=" + contentType + ", contentLength=" + contentLength
}

function installCallEnhancementHooksFromObject(obj, classes, config, logger, source) {
  if (obj === null || obj === undefined) return

  var cls = reflect.getJavaObjectClass(obj)
  var className = reflect.getClassNameFromClass(cls)

  if (hookedCallClassNames[className]) {
    return
  }

  logger.debug("Try install Call enhancement hooks from " + source + ": " + className)

  var count = hookCallLikeClass(cls, classes, config, logger, className)

  if (count > 0) {
    hookedCallClassNames[className] = true
    logger.info("Call enhancement hooks installed from " + source + ": " + className)
  } else {
    logger.debug("No Call enhancement hook installed for class: " + className)
  }
}

function hookCallLikeClass(CallClass, classes, config, logger, labelPrefix) {
  var count = 0

  if (config.hookExecute !== false) {
    count += hookDeclaredMethodsByNameAndParamCount(
      CallClass,
      "execute",
      0,
      labelPrefix + ".execute()",
      function (method) {
        return function (chain) {
          var call = chain.getThisObject()
          safeLogRequestFromCall("execute", call, config, logger)

          var response = chain.proceed()
          safeLogResponse("execute", response, config, logger)
          return response
        }
      },
      logger
    )
  }

  if (config.hookEnqueue !== false && classes.Callback) {
    count += hookDeclaredMethodsByNameAndParamCount(
      CallClass,
      "enqueue",
      1,
      labelPrefix + ".enqueue(Callback)",
      function (method) {
        return function (chain) {
          var call = chain.getThisObject()
          safeLogRequestFromCall("enqueue", call, config, logger)
          return chain.proceed()
        }
      },
      logger
    )
  }

  // 不 hook getResponseWithInterceptorChain。
  // 该方法在不同 OkHttp 版本、Kotlin 版本、混淆版本中经常不存在；
  // 示例优先使用更稳定的 newCall / execute / enqueue / Builder 入口。

  return count
}

function safeLogRequestFromCall(stage, call, config, logger) {
  try {
    logRequestFromCall(stage, call, config, logger)
  } catch (e) {
    logger.warn("logRequestFromCall failed but ignored at " + stage + ": " + e)
  }
}

function logRequestFromCall(stage, call, config, logger) {
  var request = reflect.invokeNoArg(call, "request", null)

  if (request) {
    logRequest(stage, request, config, logger)
  } else {
    logger.debug("[OkHttp " + stage + "] call.request unavailable")
  }
}

function safeLogRequest(stage, request, config, logger) {
  try {
    logRequest(stage, request, config, logger)
  } catch (e) {
    logger.warn("logRequest failed but ignored at " + stage + ": " + e)
  }
}

function logRequest(stage, request, config, logger) {
  if (!request) {
    logger.debug("[OkHttp " + stage + "] request=null")
    return
  }

  var method = reflect.invokeNoArg(request, "method", null)
  var url = reflect.invokeNoArg(request, "url", null)

  if (method === null || method === undefined) {
    try {
      method = request.method
    } catch (e1) {
      method = "<unknown-method>"
    }
  }

  if (url === null || url === undefined) {
    try {
      url = request.url
    } catch (e2) {
      url = "<unknown-url>"
    }
  }

  logger.info("[OkHttp " + stage + "] --> " + method + " " + url)

  var body = reflect.invokeNoArg(request, "body", null)

  if (body) {
    logger.info("[OkHttp " + stage + "] requestBody " + describeRequestBody(body, logger))
  }

  if (config.logHeaders !== false) {
    var headers = reflect.invokeNoArg(request, "headers", null)
    safeLogHeaders("request", headers, config, logger)
  }
}

function safeLogResponse(stage, response, config, logger) {
  try {
    logResponse(stage, response, config, logger)
  } catch (e) {
    logger.warn("logResponse failed but ignored at " + stage + ": " + e)
  }
}

function logResponse(stage, response, config, logger) {
  if (!response) {
    logger.debug("[OkHttp " + stage + "] response=null")
    return
  }

  var request = reflect.invokeNoArg(response, "request", null)
  var url = request ? reflect.invokeNoArg(request, "url", null) : "<unknown-url>"
  var code = reflect.invokeNoArg(response, "code", null)
  var message = reflect.invokeNoArg(response, "message", null)

  logger.info("[OkHttp " + stage + "] <-- code=" + code + " message=" + message + " url=" + url)

  var body = reflect.invokeNoArg(response, "body", null)

  if (body) {
    logger.info("[OkHttp " + stage + "] responseBody " + describeRequestBody(body, logger))
  }

  if (config.logHeaders !== false) {
    var headers = reflect.invokeNoArg(response, "headers", null)
    safeLogHeaders("response", headers, config, logger)
  }
}

function safeLogHeaders(kind, headers, config, logger) {
  try {
    logHeaders(kind, headers, config, logger)
  } catch (e) {
    logger.debug("logHeaders failed but ignored: " + e)
  }
}

function logHeaders(kind, headers, config, logger) {
  if (!headers) return

  if (reflect.isRhinoNativeArray(headers) || reflect.isJsArrayLike(headers)) {
    logArrayLikeHeaders(kind, headers, config, logger)
    return
  }

  logJavaHeaders(kind, headers, config, logger)
}

function logJavaHeaders(kind, headers, config, logger) {
  var headerSize = reflect.invokeNoArg(headers, "size", null)

  if (headerSize === null || headerSize === undefined) {
    logger.debug("logHeaders skipped: headers.size unavailable")
    return
  }

  var size = Math.min(utils.asNumber(headerSize, 0), config.maxHeaderCount)
  var i

  for (i = 0; i < size; i++) {
    var name = reflect.invokeIntArg(headers, "name", i, null)
    var rawValue = reflect.invokeIntArg(headers, "value", i, null)

    if (name === null || name === undefined) {
      continue
    }

    logger.debug("[OkHttp header " + kind + "] " + name + ": " + utils.redactHeaderValue(config, name, rawValue))
  }
}

function logArrayLikeHeaders(kind, headers, config, logger) {
  var length = Math.min(reflect.toArrayLength(headers), config.maxHeaderCount)
  var i

  for (i = 0; i < length; i++) {
    var item = null

    try {
      item = headers[i]
    } catch (e) {
      item = null
    }

    logOneArrayHeader(kind, item, i, config, logger)
  }
}

function logOneArrayHeader(kind, item, index, config, logger) {
  if (item === null || item === undefined) return

  try {
    if (typeof item === "string") {
      logger.debug("[OkHttp header " + kind + "] " + item)
      return
    }

    var name = null
    var value = null

    try {
      name = item.name
    } catch (e1) {
    }

    try {
      value = item.value
    } catch (e2) {
    }

    if (name === null || name === undefined) {
      try {
        name = item[0]
      } catch (e3) {
      }
    }

    if (value === null || value === undefined) {
      try {
        value = item[1]
      } catch (e4) {
      }
    }

    if (name === null || name === undefined) {
      logger.debug("[OkHttp header " + kind + "] #" + index + " " + item)
      return
    }

    logger.debug("[OkHttp header " + kind + "] " + name + ": " + utils.redactHeaderValue(config, name, value))
  } catch (e) {
    logger.debug("logOneArrayHeader failed but ignored: " + e)
  }
}

module.exports = {
  install: install
}
