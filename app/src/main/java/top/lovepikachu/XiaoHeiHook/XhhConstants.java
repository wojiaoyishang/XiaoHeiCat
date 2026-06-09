package top.lovepikachu.XiaoHeiHook;

/**
 * Centralized XiaoHeiHook runtime constants exposed to both Android code and the JS xhh object.
 * Keep these values in sync with app/build.gradle.kts when publishing a new app version.
 */
public final class XhhConstants {
    private XhhConstants() {}

    public static final String APP_NAME = "XiaoHeiHook";
    public static final String VERSION_NAME = "1.31";
    public static final int VERSION_CODE = 108;
    public static final String VERSION_LABEL = VERSION_NAME + " (" + VERSION_CODE + ")";

    public static final int JS_API_VERSION = 2;
    public static final int MCP_BRIDGE_VERSION = 1;
    public static final int DEX_API_VERSION = 1;
}
