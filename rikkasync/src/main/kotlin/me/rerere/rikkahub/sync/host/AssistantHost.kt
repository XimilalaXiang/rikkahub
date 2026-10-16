package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.sync.engine.EntitySnapshot
import me.rerere.rikkahub.sync.engine.FirstContactRelation
import me.rerere.rikkahub.sync.engine.LocalRef
import me.rerere.rikkahub.sync.engine.PermanentApplyException
import me.rerere.rikkahub.sync.engine.RemoteEntity
import me.rerere.rikkahub.sync.engine.RemoteSnapshotInfo
import me.rerere.rikkahub.sync.engine.stableJson
import me.rerere.rikkahub.sync.state.SyncStateDb
import java.security.MessageDigest
import java.util.UUID

/**
 * 助手(`settings.item`,id = `assistants/<uuid>`)的线格式契约(docs/06 §2、08-roadmap D7/A7)。
 *
 * 线上的 `data` 是 **Android `Assistant` 的 JSON(含默认值)去掉设备本地字段** 之后的样子;两端对"哪些字段同步"必须
 * 逐字一致,所以这份分类以常量表形式存在(桌面是 `sync/assistant-contract.ts`),并由漂移测试强制:
 * `Assistant` 新增任何字段,在被归类之前测试就会失败(默认不同步)。
 */
object AssistantContract {
    const val TYPE = "settings.item"
    const val COLLECTION = "assistants"
    /** 改动"同步哪些字段"或某字段的线上形态(不兼容地)时递增;不同的 fmt 互相不应用(引擎按 fmt 跳过)。 */
    const val FMT = "android-settings-1"

    fun entityId(assistantId: String) = "$COLLECTION/$assistantId"

    fun assistantIdOf(entityId: String): String? = entityId.removePrefix("$COLLECTION/").takeIf { it != entityId && it.isNotEmpty() && '/' !in it }

    /** 同步的字段(顺序即线上键序)。`avatar`、`chatModelId` 有条件规则,见 [AssistantWire]。 */
    val SYNCED_FIELDS = listOf(
        "id", "name", "avatar", "useAssistantAvatar", "systemPrompt", "chatModelId", "temperature", "topP", "contextMessageLimit",
        "maxTokens", "streamOutput", "enableMemory", "useGlobalMemory", "enableRecentChatsReference", "messageTemplate",
        "presetMessages", "regexes", "reasoningLevel", "customHeaders", "customBodies", "enableWebSearch",
        "enableTimeReminder", "timeReminderIntervalMinutes", "allowConversationSystemPrompt",
    )

    /**
     * 设备本地字段:永不上线,落库时保留本机已有值。分三类:
     * 引用另一个"尚未同步的集合"的 id(tags/quickMessageIds/mcpServers/modeInjectionIds/lorebookIds/enabledSkills——
     * 上游规整会剔除无效引用,同步它们会让接收端把剔除后的版本推回去而丢失发送端的关联);
     * 平台能力不同(localTools);设备外观/路径(workspaceId、background、backgroundOpacity、useGradientBackground)。
     */
    val DEVICE_LOCAL_FIELDS = listOf(
        "tags", "quickMessageIds", "mcpServers", "localTools", "workspaceId", "background", "backgroundOpacity",
        "useGradientBackground", "modeInjectionIds", "lorebookIds", "enabledSkills",
    )

    val ALL_FIELDS: Set<String> = (SYNCED_FIELDS + DEVICE_LOCAL_FIELDS).toSet()

    /** 内置助手(两端 id 相同):不能被同步删除(规整会立刻把它们补回来)。 */
    val BUILT_IN_IDS = setOf("0950e2dc-9bd5-4801-afa3-aa887aa36b4e", "3d47790c-c415-4b90-9388-751128adb0a0")
}

/** 适配器与上游设置存储的窄接口;app 里用 SettingsStore + 真正的 `Assistant` 类实现(编解码即校验)。 */
interface AssistantSink {
    /** 本机全部助手,完整 JSON(含默认值)。 */
    suspend fun list(): List<JsonObject>

    /** 写入(新增或整体替换)一个助手;JSON 无法解码为 Assistant 时抛 [PermanentApplyException]。 */
    suspend fun put(assistantId: String, full: JsonObject)

    suspend fun delete(assistantId: String)

    /** 本机已知的模型 id(全部供应商的 models)。助手引用了本机没有的模型时保留本机的选择。 */
    suspend fun knownModelIds(): Set<String>

    /** 平台出厂的内置助手内容(完整 JSON);不是内置的返回 null。用来判断"本机的内置助手是否从未被改过"。 */
    fun builtIn(assistantId: String): JsonObject?
}

/** 纯函数:同步形态、指纹、合并。 */
object AssistantWire {
    private fun isImageAvatar(avatar: JsonElement?): Boolean {
        val type = ((avatar as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return type.endsWith(".Image") || type.equals("image", ignoreCase = true)
    }

    /** 本机完整 JSON → 上线形态:只留 [AssistantContract.SYNCED_FIELDS];本地文件头像(Image)不上线。 */
    fun synced(full: JsonObject): JsonObject = buildJsonObject {
        for (k in AssistantContract.SYNCED_FIELDS) {
            val v = full[k] ?: continue
            if (k == "avatar" && isImageAvatar(v)) continue
            put(k, v)
        }
    }

    fun fingerprint(syncedForm: JsonObject): String =
        "af1:" + MessageDigest.getInstance("SHA-256").digest(stableJson(syncedForm).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * 远端同步形态 + 本机已有的完整 JSON → 要写入的完整 JSON。
     * - 设备本地字段与未知键:取本机的值(新助手则不设,由 Assistant 的默认值补);
     * - 头像:任何一边是本地文件头像(Image)就保留本机的;
     * - chatModelId:远端引用了本机没有的模型时,保留本机的选择(新助手则置空,即用全局默认模型)。
     */
    fun merge(local: JsonObject?, remote: JsonObject, knownModelIds: Set<String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(local.orEmpty())
        for (k in AssistantContract.SYNCED_FIELDS) {
            val v = remote[k] ?: continue
            when {
                // 本地文件头像不进也不出:远端是图片则不要;本机已经是图片则保留本机的(不被远端的 emoji 覆盖)。
                k == "avatar" && (isImageAvatar(v) || isImageAvatar(local?.get("avatar"))) -> {}
                k == "chatModelId" -> {
                    val id = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (id == null || id in knownModelIds) out[k] = v
                    else if (local == null) out[k] = JsonNull // 新助手引用了本机没有的模型
                    // 否则保留本机已有值
                }
                else -> out[k] = v
            }
        }
        return JsonObject(out)
    }
}

/**
 * 助手实体(docs/06 §2)。不需要触发器:助手很少(几个到几十个),每次 scan 读一遍并算指纹。
 *
 * 删除:远端删了某个助手时,**只要本机还有属于它的会话就先留着**(记入 pending_removal,并且不再出现在 scan 里,
 * 所以不会被当成"本地新建"推回去);等那些会话也被同步删除后,下一次 scan 开头再真正删。这样日常的"删助手"
 * (上游会连带删它的会话)两端收敛,而"另一端还在用它"的情况下不会产生看不见的孤儿会话。内置助手永远不被删除。
 */
class AssistantHost(
    private val db: SqlDb,
    private val syncDb: SyncStateDb,
    private val sink: AssistantSink,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun scan(): List<LocalRef> {
        settlePendingRemovals()
        val pending = syncDb.pendingRemovals()
        return sink.list().mapNotNull { full ->
            val id = idOf(full) ?: return@mapNotNull null
            val entityId = AssistantContract.entityId(id)
            if (entityId in pending) null else LocalRef(AssistantContract.TYPE, entityId, AssistantWire.fingerprint(AssistantWire.synced(full)), null)
        }
    }

    suspend fun read(entityId: String): EntitySnapshot? {
        val full = localOf(entityId) ?: return null
        val synced = AssistantWire.synced(full)
        return EntitySnapshot(AssistantContract.FMT, synced, AssistantWire.fingerprint(synced), null, emptyList())
    }

    suspend fun apply(remote: RemoteEntity): String {
        val id = AssistantContract.assistantIdOf(remote.id) ?: throw PermanentApplyException("settings.item ${remote.id}: 未知集合")
        val data = remote.data as? JsonObject ?: throw PermanentApplyException("assistant $id: data 不是对象")
        if ((data["id"] as? JsonPrimitive)?.content != id) throw PermanentApplyException("assistant $id: data.id 与实体 id 不一致")
        if ((data["name"] as? JsonPrimitive)?.takeIf { it.isString } == null) throw PermanentApplyException("assistant $id: 缺少 name")
        val local = localOf(remote.id)
        sink.put(id, AssistantWire.merge(local, data, sink.knownModelIds()))
        syncDb.clearPendingRemoval(remote.id)
        // 以落库后实际读到的为准(不变量 H3):上游规整可能改写内容。
        val stored = localOf(remote.id) ?: throw IllegalStateException("助手 $id 写入后读不到")
        return AssistantWire.fingerprint(AssistantWire.synced(stored))
    }

    suspend fun remove(entityId: String) {
        if (AssistantContract.assistantIdOf(entityId) == null || localOf(entityId) == null) return
        // 先登记再尝试:无论是否能立刻删掉,在删干净之前它都不能出现在 scan 里。
        syncDb.addPendingRemoval(entityId)
        settlePendingRemovals()
    }

    /** 远端已删但本机还有会话/是内置助手的,等条件满足再删;内置助手永远留在 pending 里(只是不再同步)。 */
    private suspend fun settlePendingRemovals() {
        for (entityId in syncDb.pendingRemovals()) {
            val id = AssistantContract.assistantIdOf(entityId)
            if (id == null || localOf(entityId) == null) {
                syncDb.clearPendingRemoval(entityId)
                continue
            }
            if (id in AssistantContract.BUILT_IN_IDS) continue
            if (hasConversations(id)) continue
            sink.delete(id)
            syncDb.clearPendingRemoval(entityId)
        }
    }

    private fun hasConversations(assistantId: String): Boolean =
        db.query("SELECT 1 FROM ConversationEntity WHERE assistant_id = ? LIMIT 1", listOf(assistantId)) { it.long(0) }.isNotEmpty()

    /**
     * 首次接触(两端都有同 id 的助手,从未同步过):同步形态相同 → 认领;本机是"从未改过的内置助手" → 以远端为准(没有东西可丢);
     * 其它 → diverged(取远端,本机另存为副本,绝不静默覆盖用户改过的助手)。
     */
    suspend fun compareFirstContact(local: LocalRef, remote: RemoteSnapshotInfo): FirstContactRelation {
        val full = localOf(local.id) ?: return FirstContactRelation.REMOTE_NEWER
        val remoteSynced = (remote.data as? JsonObject) ?: return FirstContactRelation.DIVERGED
        val localSynced = AssistantWire.synced(full)
        if (stableJson(localSynced) == stableJson(remoteSynced)) return FirstContactRelation.SAME
        val id = AssistantContract.assistantIdOf(local.id)
        val builtIn = id?.let { sink.builtIn(it) }
        if (builtIn != null && stableJson(AssistantWire.synced(builtIn)) == stableJson(localSynced)) return FirstContactRelation.REMOTE_NEWER
        return FirstContactRelation.DIVERGED
    }

    /** 冲突副本(docs/06 §3):重命名后保留,用户自己处理。整份复制,只换 id 与名字。 */
    suspend fun makeConflictCopy(entityId: String) {
        val full = localOf(entityId) ?: return
        val name = (full["name"] as? JsonPrimitive)?.content.orEmpty()
        val copy = LinkedHashMap(full)
        copy["id"] = JsonPrimitive(newId())
        copy["name"] = JsonPrimitive("$name (冲突副本)")
        val copyId = (copy["id"] as JsonPrimitive).content
        sink.put(copyId, JsonObject(copy))
    }

    private suspend fun localOf(entityId: String): JsonObject? {
        val id = AssistantContract.assistantIdOf(entityId) ?: return null
        return sink.list().firstOrNull { idOf(it) == id }
    }

    private fun idOf(full: JsonObject): String? = (full["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
