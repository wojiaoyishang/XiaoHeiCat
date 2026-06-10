// Rhino / XiaoHeiHook Java Bridge 反射辅助。
// 目标：尽量避免直接调用 obj.method() 时被字段同名属性覆盖，
// 同时避免 getDeclaredMethod 探测不存在方法时打断 Hook 回调。

var objectGetClassMethod = null

function getJavaObjectClass(obj) {
  if (obj === null || obj === undefined) return null

  if (objectGetClassMethod === null) {
    var ObjectClass = Java.use("java.lang.Object")
    objectGetClassMethod = ObjectClass.getDeclaredMethod("getClass")
    objectGetClassMethod.setAccessible(true)
  }

  return objectGetClassMethod.invoke(obj)
}

function getClassNameFromClass(cls) {
  if (cls === null || cls === undefined) return ""

  try {
    return String(cls.getName())
  } catch (e1) {
    try {
      return String(cls)
    } catch (e2) {
      return ""
    }
  }
}

function getJavaClassName(obj) {
  try {
    return getClassNameFromClass(getJavaObjectClass(obj))
  } catch (e) {
    return ""
  }
}

function isJsArrayLike(value) {
  if (value === null || value === undefined) return false

  try {
    return typeof value.length === "number"
  } catch (e) {
    return false
  }
}

function isRhinoNativeArray(value) {
  return getJavaClassName(value) === "org.mozilla.javascript.NativeArray"
}

function toArrayLength(value) {
  if (value === null || value === undefined) return 0

  try {
    return Number(value.length)
  } catch (e) {
    return 0
  }
}

function getDeclaredMethods(clazz, logger) {
  try {
    return clazz.getDeclaredMethods()
  } catch (e) {
    if (logger) logger.debug("getDeclaredMethods failed: " + e)
    return []
  }
}

function methodName(method) {
  try {
    return String(method.getName())
  } catch (e) {
    return ""
  }
}

function methodParamCount(method) {
  try {
    var types = method.getParameterTypes()

    if (types === null || types === undefined) {
      return 0
    }

    if (typeof types.length === "number") {
      return Number(types.length)
    }
  } catch (e) {
  }

  return -1
}

function findDeclaredMethods(clazz, name, paramCount, logger) {
  var result = []
  var methods = getDeclaredMethods(clazz, logger)
  var length = toArrayLength(methods)
  var i

  for (i = 0; i < length; i++) {
    var method = null

    try {
      method = methods[i]
    } catch (e1) {
      method = null
    }

    if (method === null || method === undefined) {
      continue
    }

    if (methodName(method) !== name) {
      continue
    }

    if (paramCount >= 0 && methodParamCount(method) !== paramCount) {
      continue
    }

    result.push(method)
  }

  return result
}

function setAccessible(method) {
  try {
    method.setAccessible(true)
  } catch (e) {
  }
}

function invokeNoArg(obj, methodNameText, logger) {
  if (obj === null || obj === undefined) return null

  try {
    var cls = getJavaObjectClass(obj)
    var methods = findDeclaredMethods(cls, methodNameText, 0, null)
    var method = methods.length > 0 ? methods[0] : null

    if (method === null) {
      try {
        method = cls.getMethod(methodNameText)
      } catch (e1) {
        method = null
      }
    }

    if (method === null) {
      return null
    }

    setAccessible(method)
    return method.invoke(obj)
  } catch (e) {
    if (logger) logger.debug("invokeNoArg failed: " + methodNameText + ", error=" + e)
    return null
  }
}

function invokeIntArg(obj, methodNameText, argValue, logger) {
  if (obj === null || obj === undefined) return null

  try {
    var cls = getJavaObjectClass(obj)
    var methods = findDeclaredMethods(cls, methodNameText, 1, null)
    var method = methods.length > 0 ? methods[0] : null

    if (method === null) {
      return null
    }

    setAccessible(method)
    return method.invoke(obj, Java.to("int", argValue))
  } catch (e) {
    if (logger) logger.debug("invokeIntArg failed: " + methodNameText + ", index=" + argValue + ", error=" + e)
    return null
  }
}

module.exports = {
  getJavaObjectClass: getJavaObjectClass,
  getClassNameFromClass: getClassNameFromClass,
  getJavaClassName: getJavaClassName,
  isJsArrayLike: isJsArrayLike,
  isRhinoNativeArray: isRhinoNativeArray,
  toArrayLength: toArrayLength,
  findDeclaredMethods: findDeclaredMethods,
  setAccessible: setAccessible,
  invokeNoArg: invokeNoArg,
  invokeIntArg: invokeIntArg
}
