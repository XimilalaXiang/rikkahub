package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.sync.engine.ApplyIO
import me.rerere.rikkahub.sync.engine.RemoteEntity
import me.rerere.rikkahub.sync.state.SyncStateDb
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * HostAdapter.apply() / remove() / makeConflictCopy()(docs/04 §4.3–4.5)。
 * 写库本身交给 [ConversationSink](上游耦合点);这里负责校验、合并本机专属值、善后(清脏标记、刷新指纹缓存、返回指纹)。
 */
class ConversationApplier(
    private val db: SqlDb,
    private val syncDb: SyncStateDb,
    private val sink: ConversationSink,
    private val isBusy: (conversationId: String) -> Boolean,
    private val bridge: AttachmentBridge? = null,
    private val tracker: DirtyTracker = DirtyTracker(db),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val kind = DirtyTracker.KIND
    private val json = Json

    /** 返回落库后的指纹(= 此后 scan() 对该会话报告的值,docs/03 H3)。永久性数据错误抛 PermanentApplyException。 */
    suspend fun apply(remote: RemoteEntity, io: ApplyIO? = null): String {
        val wire = ConversationWire.parse(remote.data, remote.id)
        // 绝不在生成中覆盖:引擎本来就会因 isBusy 把远端变更 HOLD,这里是最后一道防线。
        check(!isBusy(remote.id)) { "conversation ${remote.id} is busy" }
        // 写入可能发生在第一次 scan 之前:先确保触发器与表存在,这样这次写入也被正确追踪/清理(幂等,微秒级)。
        tracker.install()

        val existing = ConversationRows.readConversation(db, remote.id)
        val workspaceCwd = if (existing != null) readWorkspaceCwd(remote.id) else ""
        val columns = LinkedHashMap(wire.columns)
        // 设备本地数据:config 里的 workspaceId 取本机已有值(推送前已被剔除,所以远端的 config 里没有它)。
        columns["config"] = JsonPrimitive(mergeConfig(wire.columns.getValue("config").stringValue(), existing?.config.orEmpty()))
        // updateAt 用远端的值:wire.columns 里已经是它。

        // 附件:先把令牌引用的块取回并保存(失败 = 暂时性错误,引擎稍后重试),再把令牌换成本机 URL。
        var nodes = wire.nodes
        if (bridge != null && io != null) {
            val tokens = LinkedHashMap<String, String>()
            for (n in nodes) tokens.putAll(AttachmentBridge.tokensIn(n.messages))
            if (tokens.isNotEmpty()) {
                val mat = bridge.materialize(tokens, io)
                nodes = nodes.map { it.copy(messages = bridge.toLocalUrls(it.messages, mat.files)) }
            }
        }
        sink.save(ConversationWrite(remote.id, columns, workspaceCwd, nodes, isNew = existing == null))
        return settle(remote.id)
    }

    suspend fun remove(id: String) {
        sink.delete(id)
        syncDb.deleteFingerprint(kind, id)
        tracker.clear(id)
    }

    /**
     * 把会话**当前的本地状态**存成一个新会话(新 id、新节点 id、新消息 id),让随后的远端覆盖不丢任何东西。
     * 副本是新实体:不清脏标记、不写指纹缓存,让 scan 把它当作"从未同步过"的会话发现并推送。
     */
    suspend fun makeConflictCopy(id: String) {
        val row = ConversationRows.readConversation(db, id) ?: return
        val nodes = ConversationRows.readNodes(db, id)
        val copyId = newId()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(now()))
        val columns = linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(copyId),
            "assistant_id" to JsonPrimitive(row.assistantId),
            "title" to JsonPrimitive("${row.title} (冲突副本 $stamp)"),
            "create_at" to JsonPrimitive(row.createAt),
            "update_at" to JsonPrimitive(now()),
            "suggestions" to JsonPrimitive(row.suggestions),
            "is_pinned" to JsonPrimitive(0L), // 副本不置顶
            "custom_system_prompt" to JsonPrimitive(row.customSystemPrompt),
            "mode_injection_ids" to JsonPrimitive(row.modeInjectionIds),
            "lorebook_ids" to JsonPrimitive(row.lorebookIds),
            "folder_id" to JsonPrimitive(row.folderId),
            "config" to JsonPrimitive(ConversationRows.stripDeviceLocalConfig(row.config)), // 副本不绑定原来的工作区
        )
        val copies = nodes.mapIndexed { i, n -> WireNode(newId(), i.toLong(), n.selectIndex, withFreshMessageIds(n.messages)) }
        sink.save(ConversationWrite(copyId, columns, workspaceCwd = "", nodes = copies, isNew = true))
    }

    /** apply 之后的收尾:清脏标记并刷新指纹缓存,返回指纹。 */
    private fun settle(id: String): String {
        tracker.clear(id)
        val (fp, _) = ConversationFingerprint.compute(db, id) ?: error("conversation $id vanished right after being saved")
        syncDb.setFingerprint(kind, id, fp, now())
        return fp
    }

    private fun readWorkspaceCwd(id: String): String =
        db.queryOne("SELECT workspace_cwd FROM ConversationEntity WHERE id = ?", listOf(id)) { it.text(0) } ?: ""

    /** 给每条消息换新 id(消息 JSON 是不透明的,但副本必须避免与原会话的消息 id 冲突)。无法解析时原样保留。 */
    private fun withFreshMessageIds(messages: String): String = try {
        val arr = json.parseToJsonElement(messages) as? JsonArray ?: return messages
        JsonArray(arr.map { m -> if (m is JsonObject && "id" in m) JsonObject(m + ("id" to JsonPrimitive(newId()))) else m }).toString()
    } catch (_: Exception) {
        messages
    }

    private fun JsonElement.stringValue(): String = (this as JsonPrimitive).content

    companion object {
        /** 远端的 config(已不含 workspaceId)与本机现有 config 合并:本机的 workspaceId 保留下来。 */
        fun mergeConfig(remoteConfig: String, localConfig: String): String {
            val localWorkspace = try {
                if (localConfig.isEmpty()) null else Json.parseToJsonElement(localConfig).jsonObject[AndroidWireContract.CONFIG_DEVICE_LOCAL_KEY]
            } catch (_: Exception) {
                null
            } ?: return remoteConfig
            if (localWorkspace is kotlinx.serialization.json.JsonNull) return remoteConfig
            val remote = try {
                if (remoteConfig.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(remoteConfig).jsonObject
            } catch (_: Exception) {
                return remoteConfig // 远端 config 不是对象:不去碰它
            }
            return JsonObject(remote + (AndroidWireContract.CONFIG_DEVICE_LOCAL_KEY to localWorkspace)).toString()
        }
    }
}
