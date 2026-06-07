module.exports = {
  TAG: "XHH-OkHttp",

  // 改成你的授权调试目标包名。
  // index.js metadata 中的 @target / @process 也建议同步修改。
  targetPackages: [
    "com.example.app"
  ],

  // 是否打印请求/响应头。
  logHeaders: true,

  // 是否 Hook RealCall.getResponseWithInterceptorChain。
  // 这个通常能覆盖同步 execute 和异步 enqueue 的真实响应。
  hookResponseChain: true,

  // 是否 Hook execute() 入口。
  hookExecute: true,

  // 是否 Hook enqueue() 入口。
  hookEnqueue: true,

  // 是否 Hook OkHttpClient.newCall(Request)。
  hookNewCall: true,

  // 默认不读取 body，避免破坏请求或消耗响应体。
  logBody: false,

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
