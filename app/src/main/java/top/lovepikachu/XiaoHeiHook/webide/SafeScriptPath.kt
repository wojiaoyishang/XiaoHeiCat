package top.lovepikachu.XiaoHeiHook.webide

import java.io.File

object SafeScriptPath {
    private val segmentRegex = Regex("^[A-Za-z0-9._-]{1,96}$")
    private val allowedFileExtensions = setOf("js", "json", "md", "txt")

    fun resolve(
        root: File,
        relativePath: String?,
        mustExist: Boolean = false,
        expectDirectory: Boolean? = null
    ): File {
        val canonicalRoot = root.canonicalFile
        val raw = relativePath.orEmpty().replace('\\', '/').trim()
        require(!raw.contains('\u0000')) { "路径包含非法字符" }
        require(!raw.startsWith("/")) { "不允许绝对路径" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(raw)) { "不允许 Windows 绝对路径" }

        val segments = raw.split('/').filter { it.isNotBlank() }
        if (raw.isNotBlank()) {
            require(segments.isNotEmpty()) { "路径为空" }
        }

        for (segment in segments) {
            require(segment != "." && segment != "..") { "不允许 . 或 .." }
            require(!segment.startsWith(".")) { "不允许隐藏文件或目录" }
            require(segmentRegex.matches(segment)) { "非法路径片段：$segment" }
        }

        val target = if (segments.isEmpty()) canonicalRoot else File(canonicalRoot, segments.joinToString(File.separator)).canonicalFile
        require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
            "路径越界"
        }

        val isLikelyFile = expectDirectory == false || (segments.lastOrNull()?.contains('.') == true && expectDirectory != true)
        if (isLikelyFile && segments.isNotEmpty()) {
            val ext = segments.last().substringAfterLast('.', "").lowercase()
            require(ext in allowedFileExtensions) { "不允许的文件类型：.$ext" }
        }

        if (mustExist) {
            require(target.exists()) { "路径不存在：$raw" }
            if (expectDirectory == true) require(target.isDirectory) { "不是目录：$raw" }
            if (expectDirectory == false) require(target.isFile) { "不是文件：$raw" }
        }

        return target
    }
}
