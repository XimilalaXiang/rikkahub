package me.rerere.rikkahub.sync.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

class Item(
    var text: String,
    var updatedAt: Long,
    var files: Map<String, ByteArray> = emptyMap(),
    /** 宿主本地保存、从不同步的数据。 */
    var localOnly: String? = null,
)

/** 内存宿主:一种实体类型 "note",负载是 {text, files}。与 TS 的 FakeHost 行为一致。 */
class FakeHost(val name: String) : HostAdapter {
    val items = LinkedHashMap<String, Item>()
    val busy = HashSet<String>()
    val supported = hashSetOf("fake-1")
    var signature = "sig-1"
    var fmt = "fake-1"

    /** 模拟另一个平台的序列化里带有本端产生不了的额外字段。 */
    var extraField = false

    /** false = 宿主没有实现 compareFirstContact,引擎走兜底。 */
    var hasComparator = true

    // 故障注入
    var failReadFor: String? = null
    var failBlobReadFor: String? = null
    var permanentApplyFailFor: String? = null
    var transientApplyFailFor: String? = null

    val applied = ArrayList<String>()
    private var copyCounter = 0

    override fun isFmtSupported(type: String, fmt: String) = fmt in supported
    override fun supportSignature() = signature

    private fun fp(it: Item): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(it.text.toByteArray())
        for ((n, b) in it.files.toSortedMap()) {
            md.update(n.toByteArray())
            md.update(MessageDigest.getInstance("SHA-256").digest(b))
        }
        return md.digest().toHex()
    }

    override suspend fun scan(): List<LocalRef> = items.map { (id, it) -> LocalRef("note", id, fp(it), it.updatedAt) }

    override suspend fun read(type: String, id: String, ctx: HostContext): EntitySnapshot? {
        if (id == failReadFor) throw IllegalStateException("corrupt data")
        val it = items[id] ?: return null
        val blobs = it.files.map { (n, bytes) ->
            BlobUpload(ctx.blobId(bytes)) {
                if (id == failBlobReadFor) throw IllegalStateException("file vanished")
                bytes
            }
        }.zip(it.files.keys)
        return EntitySnapshot(
            fmt = fmt,
            fingerprint = fp(it),
            updatedAt = it.updatedAt,
            blobs = blobs.map { it.first },
            data = buildJsonObject {
                put("text", it.text)
                put("files", JsonArray(blobs.map { (b, n) -> buildJsonObject { put("name", n); put("blob", b.id) } }))
                if (extraField) put("androidOnly", 1)
            },
        )
    }

    override suspend fun apply(remote: RemoteEntity, io: ApplyIO): String {
        if (remote.id == permanentApplyFailFor) throw PermanentApplyException("this record can never be applied")
        if (remote.id == transientApplyFailFor) throw IllegalStateException("database is locked")
        val d = remote.data.jsonObject
        val files = LinkedHashMap<String, ByteArray>()
        for (f in d.getValue("files").jsonArray.map { it.jsonObject }) {
            files[f.getValue("name").jsonPrimitive.content] = io.fetchBlob(f.getValue("blob").jsonPrimitive.content)
        }
        val it = Item(d.getValue("text").jsonPrimitive.content, remote.updatedAt ?: System.currentTimeMillis(), files, items[remote.id]?.localOnly)
        items[remote.id] = it
        applied.add(remote.id)
        return fp(it)
    }

    override suspend fun remove(type: String, id: String) {
        items.remove(id)
    }

    override suspend fun makeConflictCopy(type: String, id: String) {
        val it = items[id] ?: return
        items["$id-conflict-$name-${++copyCounter}"] = Item("${it.text} (conflict copy)", it.updatedAt, it.files, it.localOnly)
    }

    override fun isBusy(type: String, id: String) = id in busy

    /** 比较两个平台都能看到的东西(文本),忽略表示上的差异。 */
    override suspend fun compareFirstContact(local: LocalRef, remote: RemoteSnapshotInfo): FirstContactRelation? {
        if (!hasComparator) return null
        val it = items.getValue(local.id)
        if (it.text == (remote.data as JsonObject).getValue("text").jsonPrimitive.content) return FirstContactRelation.SAME
        val lu = local.updatedAt
        val ru = remote.updatedAt
        if (lu != null && ru != null) {
            if (ru > lu) return FirstContactRelation.REMOTE_NEWER
            if (lu > ru) return FirstContactRelation.LOCAL_NEWER
        }
        return FirstContactRelation.DIVERGED
    }

    fun put(id: String, text: String, updatedAt: Long = System.currentTimeMillis(), files: Map<String, ByteArray> = emptyMap()) {
        items[id] = Item(text, updatedAt, files, items[id]?.localOnly)
    }

    fun texts(): Map<String, String> = items.mapValues { it.value.text }
}

@Suppress("unused")
private val unused = JsonPrimitive("")
