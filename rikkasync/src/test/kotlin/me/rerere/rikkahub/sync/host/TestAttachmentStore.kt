package me.rerere.rikkahub.sync.host

import java.io.File
import java.util.UUID

/** 测试用的本机文件管理:`<root>/files/upload/<uuid><扩展名>`,URL 形如 Android 的 `file:///…/files/upload/<name>`。 */
class TestAttachmentStore(val root: File) : AttachmentStore {
    private val dir = File(root, "files/upload").also { it.mkdirs() }
    private val names = HashMap<String, String>() // localRef → displayName
    var saves = 0
    var failSave: Exception? = null

    override val urlPattern = Regex("file://[^\"\\\\\\s]*/upload/[^\"\\\\\\s/?#]+")

    override fun urlOf(localRef: String) = File(File(root, "files"), localRef).toURI().toString().replaceFirst("file:/", "file:///")

    private fun refOfUrl(url: String): String? = Regex("/upload/([^/?#]+)$").find(url)?.let { "upload/${it.groupValues[1]}" }

    override fun describe(url: String): LocalAttachment? = refOfUrl(url)?.let { describeRef(it) }

    override fun describeRef(localRef: String): LocalAttachment? {
        val f = File(File(root, "files"), localRef)
        if (!f.isFile) return null
        return LocalAttachment(localRef, names[localRef] ?: f.name, f.length(), f.lastModified()) { f.readBytes() }
    }

    override suspend fun save(bytes: ByteArray, displayName: String, mimeType: String): Pair<String, String> {
        failSave?.let { throw it }
        saves++
        val ext = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val file = File(dir, UUID.randomUUID().toString() + ext)
        file.writeBytes(bytes)
        val ref = "upload/${file.name}"
        names[ref] = displayName
        return ref to urlOf(ref)
    }

    /** 模拟用户从相册选了一张图:直接落一个本机文件并返回 URL(消息里用)。 */
    fun addLocal(bytes: ByteArray, displayName: String): String = kotlinx.coroutines.runBlocking { save(bytes, displayName, "x/y") }.second.also { saves-- }

    fun files(): List<File> = dir.listFiles()!!.toList()
}
