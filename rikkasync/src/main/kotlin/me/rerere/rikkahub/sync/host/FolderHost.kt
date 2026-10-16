package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.sync.engine.EntitySnapshot
import me.rerere.rikkahub.sync.engine.FirstContactRelation
import me.rerere.rikkahub.sync.engine.LocalRef
import me.rerere.rikkahub.sync.engine.PermanentApplyException
import me.rerere.rikkahub.sync.engine.RemoteEntity
import me.rerere.rikkahub.sync.engine.RemoteSnapshotInfo
import java.security.MessageDigest

/** `conversation_folder` 的一行。线上就是这五列(协议 §5.4 `folder`)。 */
data class FolderRow(val id: String, val assistantId: String, val name: String, val sortIndex: Long, val createAt: Long)

/** 适配器与上游文件夹存储的窄接口;app 里用 FolderDAO 实现。 */
interface FolderSink {
    suspend fun upsert(row: FolderRow)

    suspend fun delete(id: String)
}

/**
 * `folder` 实体(docs/04 §5)。表很小(几十行),所以不需要触发器:每次 scan 全表读一遍并算指纹。
 *
 * 远端删除文件夹时**只删文件夹行,不碰会话的 folder_id**:删除方设备已经把这些会话的 folder_id 清空并会把它们同步过来;
 * 本地再清一遍会让会话指纹先变,和随后到达的远端版本撞成一次假冲突(产生多余的冲突副本)。
 * 上游没有给 folder_id 加外键,所以短暂悬空无害,且在同一轮拉取里就会被远端版本修正。
 */
class FolderHost(private val db: SqlDb, private val sink: FolderSink) {
    fun scan(): List<LocalRef> = readAll().map { LocalRef(AndroidWireContract.FOLDER_TYPE, it.id, fingerprint(it), null) }

    fun read(id: String): EntitySnapshot? {
        val row = readOne(id) ?: return null
        return EntitySnapshot(AndroidWireContract.FMT, toJson(row), fingerprint(row), null, emptyList())
    }

    suspend fun apply(remote: RemoteEntity): String {
        val row = parse(remote.id, remote.data)
        sink.upsert(row)
        // 以落库后实际读到的为准(不变量 H3);读不到说明 sink 没写成功,按暂时性错误让引擎重试。
        val stored = readOne(remote.id) ?: throw IllegalStateException("文件夹 ${remote.id} 写入后读不到")
        return fingerprint(stored)
    }

    suspend fun remove(id: String) = sink.delete(id)

    /** 文件夹只有一个名字,没有"最后修改时间"可比,也没有值得保留的副本:内容相同则认领,否则以服务器为准。 */
    fun compareFirstContact(local: LocalRef, remote: RemoteSnapshotInfo): FirstContactRelation {
        val row = readOne(local.id) ?: return FirstContactRelation.REMOTE_NEWER
        val remoteRow = runCatching { parse(local.id, remote.data) }.getOrNull() ?: return FirstContactRelation.REMOTE_NEWER
        return if (row == remoteRow) FirstContactRelation.SAME else FirstContactRelation.REMOTE_NEWER
    }

    private fun readAll(): List<FolderRow> = db.query("SELECT id, assistant_id, name, sort_index, create_at FROM conversation_folder ORDER BY id", emptyList(), ::mapRow)

    private fun readOne(id: String): FolderRow? = db.queryOne("SELECT id, assistant_id, name, sort_index, create_at FROM conversation_folder WHERE id = ?", listOf(id), ::mapRow)

    private fun mapRow(r: SqlRow) = FolderRow(r.text(0), r.text(1), r.text(2), r.long(3), r.long(4))

    companion object {
        fun toJson(row: FolderRow): JsonObject = buildJsonObject {
            put("id", row.id)
            put("assistant_id", row.assistantId)
            put("name", row.name)
            put("sort_index", row.sortIndex)
            put("create_at", row.createAt)
        }

        /** 长度前缀编码每个字段,避免拼接碰撞;带版本前缀,改算法 = 全部文件夹重推一次。 */
        fun fingerprint(row: FolderRow): String {
            val md = MessageDigest.getInstance("SHA-256")
            for (f in listOf(row.id, row.assistantId, row.name, row.sortIndex.toString(), row.createAt.toString())) {
                val b = f.toByteArray(Charsets.UTF_8)
                md.update("${b.size}:".toByteArray())
                md.update(b)
            }
            return "ff1:" + md.digest().joinToString("") { "%02x".format(it) }
        }

        fun parse(id: String, data: JsonElement): FolderRow {
            val o = (data as? JsonObject) ?: throw PermanentApplyException("folder $id: data 不是对象")
            fun str(k: String): String = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw PermanentApplyException("folder $id: 缺少字符串字段 $k")
            fun num(k: String, default: Long): Long = when (val v = o[k]) {
                null, JsonNull -> default
                is JsonPrimitive -> v.longOrNull ?: throw PermanentApplyException("folder $id: $k 不是整数")
                else -> throw PermanentApplyException("folder $id: $k 不是整数")
            }
            if (str("id") != id) throw PermanentApplyException("folder $id: data.id 与实体 id 不一致")
            return FolderRow(id, str("assistant_id"), str("name"), num("sort_index", 0), num("create_at", 0))
        }
    }
}
