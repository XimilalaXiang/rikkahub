package me.rerere.rikkahub.sync.host

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import java.io.File

/**
 * [AttachmentStore] 在上游 FilesManager 之上的实现(接入点 A5)。
 * 聊天附件由 FilesManager 管理在 `filesDir/upload/<uuid>.<ext>`,消息里存的是 `file:///…/files/upload/<uuid>.<ext>`;
 * 引用 = 相对 filesDir 的路径(与 ManagedFileEntity.relativePath 一致)。
 *
 * describe/describeRef 是同步接口(要在 JSON 文本的正则替换里调用),查展示名需要 Room 的 suspend 查询——
 * 调用方永远在 IO 线程上(同步引擎跑在 Dispatchers.IO),所以这里用 runBlocking 桥接,不会卡住主线程。
 */
class AppAttachmentStore(private val files: FilesManager, private val filesDir: File) : AttachmentStore {
    override val urlPattern = Regex("file://[^\"\\\\\\s]*/files/upload/[^\"\\\\\\s/?#]+")

    override fun urlOf(localRef: String): String = File(filesDir, localRef).toUri().toString()

    override fun describe(url: String): LocalAttachment? {
        // 只取最后一段文件名(UUID),不信任 URL 里的目录部分:换过安装路径的备份里 URL 的前缀可能不是当前 filesDir。
        val name = Uri.parse(url).lastPathSegment ?: return null
        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") return null
        return describeRef("${FileFolders.UPLOAD}/$name")
    }

    override fun describeRef(localRef: String): LocalAttachment? {
        val file = File(filesDir, localRef)
        if (!file.isFile) return null
        val displayName = runBlocking { files.getByRelativePath(localRef)?.displayName } ?: file.name
        return LocalAttachment(localRef, displayName, file.length(), file.lastModified()) { file.readBytes() }
    }

    override suspend fun save(bytes: ByteArray, displayName: String, mimeType: String): Pair<String, String> {
        val entity = files.saveManagedFromBytes(FileFolders.UPLOAD, bytes, displayName, mimeType)
        return entity.relativePath to urlOf(entity.relativePath)
    }
}
