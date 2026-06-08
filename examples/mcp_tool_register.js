// ==LSPosedScript==
// @name         MCP Echo Test
// @namespace    XiaoHeiHook
// @version      1.0
// @description  注册一个 MCP 远程调用 echo 方法，用于测试 invoke_method
// @target       *
// @grant        rpc.register
// @grant        xhh.debug
// @grant        mcp.debug
// ==/LSPosedScript==

(function () {
  xhh.rpc.register_method("echo", {
    description: "Echo MCP input params back to caller",
    conflict: "overwrite",
    timeoutMs: 5000,
    concurrency: "parallel",
    paramsSchema: {
      type: "object",
      additionalProperties: true,
      description: "Any JSON object to echo back"
    }
  }, function (params, ctx) {
    return {
      ok: true,
      method: "echo",
      requestId: ctx && ctx.requestId ? ctx.requestId : null,
      received: params,
      timestamp: Date.now()
    }
  })
})()