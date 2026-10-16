package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.sync.engine.PermanentApplyException

/** 测试用的助手存储:内存里的 JSON 列表,字段和默认值与 Android 的 `Assistant` 一致。 */
class TestAssistantSink : AssistantSink {
    val items = LinkedHashMap<String, JsonObject>()
    val models = HashSet<String>()
    var failPut: Exception? = null
    var puts = 0

    /** 模拟上游规整改写内容(例如修剪名字)。 */
    var normalize: (JsonObject) -> JsonObject = { it }

    /** 模拟上游规整:剔除不存在的引用(这里只模拟 lorebookIds,足以证明设备本地字段不被同步所以不会被剔除后推回)。 */
    val knownLorebooks = HashSet<String>()

    override suspend fun list(): List<JsonObject> = items.values.toList()

    override suspend fun put(assistantId: String, full: JsonObject) {
        failPut?.let { throw it }
        if ((full["name"] as? JsonPrimitive)?.isString != true) throw PermanentApplyException("decode failed: name")
        puts++
        // 与真实的解码一致:缺失的键取 Assistant 的默认值
        val complete = LinkedHashMap(assistant(assistantId, "")).also { it.putAll(full) }
        val lorebooks = (complete["lorebookIds"] as? JsonArray).orEmpty().filter { (it as JsonPrimitive).content in knownLorebooks }
        items[assistantId] = normalize(JsonObject(complete + ("lorebookIds" to JsonArray(lorebooks))))
    }

    override suspend fun delete(assistantId: String) {
        items.remove(assistantId)
    }

    override suspend fun knownModelIds(): Set<String> = models

    override fun builtIn(assistantId: String): JsonObject? = if (assistantId in AssistantContract.BUILT_IN_IDS) assistant(assistantId, "") else null

    companion object {
        const val DUMMY_AVATAR = "me.rerere.rikkahub.data.model.Avatar.Dummy"

        /** 与 Android `Assistant` 默认值逐字段一致的完整 JSON。 */
        fun assistant(id: String, name: String, vararg overrides: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject {
            val base = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                "id" to JsonPrimitive(id), "chatModelId" to JsonNull, "name" to JsonPrimitive(name),
                "avatar" to buildJsonObject { put("type", DUMMY_AVATAR) }, "useAssistantAvatar" to JsonPrimitive(false), "tags" to JsonArray(emptyList()),
                "systemPrompt" to JsonPrimitive(""), "temperature" to JsonNull, "topP" to JsonNull, "contextMessageLimit" to JsonPrimitive(0),
                "streamOutput" to JsonPrimitive(true), "enableMemory" to JsonPrimitive(false), "useGlobalMemory" to JsonPrimitive(false),
                "enableRecentChatsReference" to JsonPrimitive(false), "messageTemplate" to JsonPrimitive("{{ message }}"),
                "presetMessages" to JsonArray(emptyList()), "quickMessageIds" to JsonArray(emptyList()), "regexes" to JsonArray(emptyList()),
                "reasoningLevel" to JsonPrimitive("auto"), "maxTokens" to JsonNull, "customHeaders" to JsonArray(emptyList()),
                "customBodies" to JsonArray(emptyList()), "mcpServers" to JsonArray(emptyList()),
                "localTools" to JsonArray(listOf(buildJsonObject { put("type", "time_info") })),
                "enableWebSearch" to JsonPrimitive(false), "workspaceId" to JsonNull, "background" to JsonNull, "backgroundOpacity" to JsonPrimitive(1.0),
                "useGradientBackground" to JsonPrimitive(false), "modeInjectionIds" to JsonArray(emptyList()), "lorebookIds" to JsonArray(emptyList()),
                "enabledSkills" to JsonArray(emptyList()), "enableTimeReminder" to JsonPrimitive(false), "timeReminderIntervalMinutes" to JsonPrimitive(60),
                "allowConversationSystemPrompt" to JsonPrimitive(false),
            )
            for ((k, v) in overrides) base[k] = v
            return JsonObject(base)
        }
    }
}
