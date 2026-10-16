package me.rerere.rikkahub.sync.host

import me.rerere.rikkahub.sync.engine.ApplyIO
import me.rerere.rikkahub.sync.engine.HostContext
import me.rerere.rikkahub.sync.engine.HttpException
import me.rerere.rikkahub.sync.state.SyncStateDb

/** 一个本机已管理的附件文件。 */
class LocalAttachment(
    /** 稳定的本机引用(相对 filesDir 的路径,如 `upload/<uuid>.png`)。 */
    val localRef: String,
    /** 展示用文件名(ManagedFileEntity.displayName;没有就用磁盘文件名)。令牌里带的就是它。 */
    val displayName: String,
    val size: Long,
    val mtimeMs: Long,
    val read: () -> ByteArray,
)

/**
 * 适配器与"本机文件管理"的窄接口(与 [ConversationSink] 同样的思路):逻辑在纯 Kotlin 里,
 * 只有真正碰上游 FilesManager 的地方在 app 模块里实现。
 */
interface AttachmentStore {
    /** 本机 URL 指向的、已管理且还在磁盘上的附件;不是附件(或文件已丢失)返回 null。 */
    fun describe(url: String): LocalAttachment?

    /** 本机引用 → 描述(文件丢了返回 null)。块映射里存的是引用,读字节时用它。 */
    fun describeRef(localRef: String): LocalAttachment?

    /** 保存取回的字节,返回 (localRef, 本机 URL)。 */
    suspend fun save(bytes: ByteArray, displayName: String, mimeType: String): Pair<String, String>

    /** localRef → 本机 URL(消息里要写的形态)。 */
    fun urlOf(localRef: String): String

    /** 本机 URL 的匹配规则(在 JSON 文本上扫描用);组 0 是整个 URL。 */
    val urlPattern: Regex
}

/**
 * 附件:本机文件 ↔ 线上块令牌(协议 §5.4、docs/04 §4.2–4.3)。与桌面的 sync/blob-bridge.ts 同一份约定:
 * 令牌 `rks-blob://<blobId>/<百分号编码的文件名>`,编码规则与 JavaScript 的 encodeURIComponent 完全一致(跨语言测试钉住)。
 * 令牌不含引号/反斜杠/空白,所以可以直接在 JSON 文本上做替换,不必解析消息。
 */
class AttachmentBridge(private val syncDb: SyncStateDb, private val store: AttachmentStore) {
    class Materialized(val files: Map<String, String>, val missing: List<String>, val created: Int)

    /** 本机附件 URL → 块令牌。指向已丢失文件的 URL 原样保留(不让一个坏链接挡住整个会话)。 */
    fun tokenize(text: String, ctx: HostContext): String =
        store.urlPattern.replace(text) { m ->
            val att = store.describe(m.value) ?: return@replace m.value
            token(blobIdOf(att, ctx), att.displayName)
        }

    private fun blobIdOf(att: LocalAttachment, ctx: HostContext): String {
        val blobId = syncDb.getFileBlob(att.localRef, att.size, att.mtimeMs) ?: ctx.blobId(att.read()).also { syncDb.setFileBlob(att.localRef, att.size, att.mtimeMs, it) }
        syncDb.setBlob(blobId, att.localRef)
        return blobId
    }

    fun hasLocalBlob(blobId: String): Boolean = syncDb.localRefOf(blobId)?.let { store.describeRef(it) } != null

    fun readBlob(blobId: String): ByteArray {
        val att = syncDb.localRefOf(blobId)?.let { store.describeRef(it) } ?: throw IllegalStateException("块 $blobId 对应的本地文件已不存在")
        return att.read()
    }

    /** 取回缺失的块并保存。404(服务器上已没有)记为 missing 不阻塞;其它错误抛出(引擎保持游标稍后重试)。 */
    suspend fun materialize(tokens: Map<String, String>, io: ApplyIO): Materialized {
        val files = LinkedHashMap<String, String>()
        val missing = ArrayList<String>()
        var created = 0
        for ((blobId, fileName) in tokens) {
            val known = syncDb.localRefOf(blobId)
            if (known != null && store.describeRef(known) != null) {
                files[blobId] = known
                continue
            }
            val bytes = try {
                io.fetchBlob(blobId)
            } catch (e: HttpException) {
                if (e.status == 404) {
                    missing.add(blobId)
                    continue
                }
                throw e
            }
            val (ref, _) = store.save(bytes, fileName, guessMime(fileName))
            syncDb.setBlob(blobId, ref)
            files[blobId] = ref
            created++
        }
        return Materialized(files, missing, created)
    }

    /** 令牌 → 本机 URL;映射里没有的块(missing)原样保留。 */
    fun toLocalUrls(text: String, files: Map<String, String>): String =
        TOKEN.replace(text) { m ->
            val ref = files[m.groupValues[1]]
            if (ref == null) m.value else store.urlOf(ref)
        }

    companion object {
        val TOKEN = Regex("rks-blob://([0-9a-f]{64})/([A-Za-z0-9\\-._~%!$&'()*+,;=:@]*)")

        fun token(blobId: String, fileName: String) = "rks-blob://$blobId/${encodeUriComponent(fileName)}"

        /** 文本里出现的全部块令牌:blobId → 文件名(按出现顺序去重)。 */
        fun tokensIn(text: String): Map<String, String> {
            val found = LinkedHashMap<String, String>()
            for (m in TOKEN.findAll(text)) found.putIfAbsent(m.groupValues[1], decodeUriComponent(m.groupValues[2]))
            return found
        }

        /** 与 JavaScript 的 encodeURIComponent 一致:不编码 `A-Za-z0-9 - _ . ! ~ * ' ( )`,其余按 UTF-8 字节 %XX。 */
        fun encodeUriComponent(s: String): String {
            val out = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") out.append(ch) else out.append('%').append("%02X".format(c))
            }
            return out.toString()
        }

        fun decodeUriComponent(s: String): String {
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 3 <= s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        bytes.write(v)
                        i += 3
                        continue
                    }
                }
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }

        fun guessMime(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "png", "gif", "webp", "bmp" -> "image/$ext"
                "jpg", "jpeg" -> "image/jpeg"
                "pdf" -> "application/pdf"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "txt", "md" -> "text/plain"
                else -> "application/octet-stream"
            }
        }
    }
}
