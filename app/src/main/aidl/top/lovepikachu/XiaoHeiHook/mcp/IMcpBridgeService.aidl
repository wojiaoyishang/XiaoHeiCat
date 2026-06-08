package top.lovepikachu.XiaoHeiHook.mcp;

import android.os.Bundle;
import top.lovepikachu.XiaoHeiHook.mcp.IMcpRuntimeCallback;

interface IMcpBridgeService {
    Bundle openSession(in Bundle hello, IMcpRuntimeCallback callback);
    Bundle registerMethod(String sessionId, in Bundle method);
    void unregisterMethod(String sessionId, String methodName);
    void unregisterAllMethods(String sessionId);
    void heartbeat(String sessionId);
    void sendResult(String sessionId, String requestId, in Bundle result);
}
