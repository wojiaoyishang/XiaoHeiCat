package top.lovepikachu.XiaoHeiHook.mcp

import org.json.JSONObject

/**
 * Server-side handle for a live target-app runtime session.
 *
 * The MCP registry only depends on this small abstraction so the transport can
 * use TCP now and can still be replaced later without changing tool logic.
 */
interface McpRuntimeConnection {
    /**
     * Sends an invoke frame to the target runtime.
     *
     * @return true if the frame was accepted by the transport; false means the
     * runtime should be treated as offline and the session should be detached.
     */
    fun send(message: JSONObject): Boolean

    /**
     * Closes this runtime connection and clears any associated registry state.
     */
    fun close(reason: String)
}
