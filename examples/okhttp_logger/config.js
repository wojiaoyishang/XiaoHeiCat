// OkHttp Hook 配置。
// 默认 hook 所有包名和所有进程。
// 如果只想调试指定 App，请把 includePackages 改成 ["目标包名"]。

var config = {
  TAG: "XHH-OkHttp",

  // 默认空数组表示不限制包名。
  includePackages: [],

  // 命中 excludePackages 的包会跳过，避免调试自身或系统进程时日志过多。
  // 如果确实需要 hook 这些包，可删掉对应项。
  excludePackages: [
    "android",
    "com.android.systemui",
    "com.google.android.gms",
    "com.google.android.googlequicksearchbox"
  ],

  // 默认空数组表示不限制进程名。
  includeProcesses: [],

  // 公开稳定发送入口：OkHttpClient.newCall(Request)。
  hookNewCall: true,

  // 请求构造入口：Request.Builder.get/post/method/url/build。
  hookRequestBuilder: true,

  // 表单 POST 构造入口：FormBody.Builder.add/addEncoded/build。
  hookFormBodyBuilder: true,

  // 是否尝试 hook okhttp3.Call 接口本身。
  // 某些 Hook 框架对接口方法 hook 的覆盖不稳定，默认关闭。
  // 实际 Call 实现类会在 OkHttpClient.newCall 返回对象后再自动增强 hook。
  hookCallInterface: false,

  // 实际 Call 对象增强入口。
  hookExecute: true,
  hookEnqueue: true,

  // 是否打印请求/响应头。
  logHeaders: true,

  // 默认不读取 body 内容，避免消费请求/响应流。
  logBody: false,

  maxHeaderCount: 20,
  maxHeaderValueLength: 240,

  redactHeaders: [
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "x-auth-token",
    "token",
    "access-token",
    "refresh-token"
  ]
}

function arrayContains(array, value) {
  var i

  for (i = 0; i < array.length; i++) {
    if (String(array[i]) === String(value)) {
      return true
    }
  }

  return false
}

config.shouldHookPackage = function (packageName, processName) {
  if (arrayContains(config.excludePackages, packageName)) {
    return false
  }

  if (config.includePackages.length > 0 && !arrayContains(config.includePackages, packageName)) {
    return false
  }

  if (config.includeProcesses.length > 0 && !arrayContains(config.includeProcesses, processName)) {
    return false
  }

  return true
}

module.exports = config
