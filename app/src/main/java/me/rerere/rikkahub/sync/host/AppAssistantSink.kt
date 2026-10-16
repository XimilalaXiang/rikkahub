package me.rerere.rikkahub.sync.host

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.sync.engine.PermanentApplyException
import me.rerere.rikkahub.utils.JsonInstant

/**
 * [AssistantSink] 在上游 SettingsStore 之上的实现(接入点 A7)。编码/解码用上游真实的 `Assistant` 序列化器(含默认值),
 * 所以"能解码"就是最好的校验:远端数据解码失败 → [PermanentApplyException](引擎跳过该记录,不挡后面的)。
 * 写入走 `SettingsStore.update`(一次一个助手),上游的规整照常执行(补回内置助手、剔除无效引用)——
 * 设备本地的引用字段不上线,所以规整剔除它们不会反过来影响发送端。
 */
class AppAssistantSink(private val settings: SettingsStore) : AssistantSink {
    override suspend fun list(): List<JsonObject> = settings.awaitLoaded().assistants.map(::encode)

    override suspend fun put(assistantId: String, full: JsonObject) {
        val assistant = try {
            JsonInstant.decodeFromJsonElement(Assistant.serializer(), full)
        } catch (e: SerializationException) {
            throw PermanentApplyException("assistant $assistantId 无法解码: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw PermanentApplyException("assistant $assistantId 无法解码: ${e.message}")
        }
        if (assistant.id.toString() != assistantId) throw PermanentApplyException("assistant $assistantId: id 不一致")
        settings.update { s ->
            val index = s.assistants.indexOfFirst { it.id == assistant.id }
            s.copy(assistants = if (index >= 0) s.assistants.toMutableList().also { it[index] = assistant } else s.assistants + assistant)
        }
    }

    override suspend fun delete(assistantId: String) {
        settings.update { s -> s.copy(assistants = s.assistants.filter { it.id.toString() != assistantId }) }
    }

    override suspend fun knownModelIds(): Set<String> =
        settings.awaitLoaded().providers.flatMapTo(HashSet()) { p -> p.models.map { it.id.toString() } }

    override fun builtIn(assistantId: String): JsonObject? = DEFAULT_ASSISTANTS.firstOrNull { it.id.toString() == assistantId }?.let(::encode)

    private fun encode(a: Assistant): JsonObject = JsonInstant.encodeToJsonElement(Assistant.serializer(), a).jsonObject
}
