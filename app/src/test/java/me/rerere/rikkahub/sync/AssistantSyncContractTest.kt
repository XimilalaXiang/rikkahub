package me.rerere.rikkahub.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.sync.host.AssistantContract
import me.rerere.rikkahub.sync.host.AssistantWire
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 助手同步契约与上游 `Assistant` 的漂移检测:上游给 Assistant 加/删/改名任何字段,这里先失败——
 * 逼着你决定它是"同步"还是"设备本地"(默认不同步),而不是悄悄漏掉或悄悄带上。
 */
class AssistantSyncContractTest {
    private val json = JsonInstant

    private fun encode(a: Assistant): JsonObject = json.encodeToJsonElement(Assistant.serializer(), a).jsonObject

    @Test
    fun `every Assistant field is classified as synced or device-local`() {
        val fields = Assistant.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) }.toSet() }
        assertEquals(
            "Assistant 的字段变了:请在 AssistantContract(SYNCED_FIELDS / DEVICE_LOCAL_FIELDS)里归类," +
                "并在 rikkasync 桌面端 sync/assistant-contract.ts 做同样的改动;若同步形态不兼容地变了,递增 FMT。",
            fields, AssistantContract.ALL_FIELDS,
        )
    }

    @Test
    fun `encoding includes defaults so a reset-to-default value reaches the other side`() {
        val a = Assistant(id = Uuid.random(), name = "x", temperature = 0.7f)
        val full = encode(a)
        assertEquals(AssistantContract.ALL_FIELDS, full.keys)
        val reset = encode(a.copy(temperature = null))
        assertTrue("temperature" in reset.keys)
    }

    @Test
    fun `synced form of a real assistant decodes back into the same synced values`() {
        val a = Assistant(id = Uuid.random(), name = "写作", systemPrompt = "你好", temperature = 0.3f, mcpServers = setOf(Uuid.random()), workspaceId = Uuid.random(), background = "file:///bg.png")
        val synced = AssistantWire.synced(encode(a))
        val merged = AssistantWire.merge(encode(a.copy(systemPrompt = "", name = "")), synced, emptySet())
        val back = json.decodeFromJsonElement(Assistant.serializer(), merged)
        assertEquals(a.copy(mcpServers = emptySet(), workspaceId = null, background = null), back.copy(mcpServers = emptySet(), workspaceId = null, background = null))
        assertEquals(synced.keys.toList(), AssistantContract.SYNCED_FIELDS.filter { it in synced.keys })
    }

    @Test
    fun `a desktop-shaped synced record decodes with upstream strictness`() {
        // 桌面推送的形态(Android 字段名/枚举值,桌面独有字段已剥离)。
        val id = Uuid.random().toString()
        val record = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id), "name" to JsonPrimitive("桌面助手"), "systemPrompt" to JsonPrimitive("p"),
                "reasoningLevel" to JsonPrimitive("high"), "regexes" to JsonArray(emptyList()), "presetMessages" to JsonArray(emptyList()),
                "contextMessageLimit" to JsonPrimitive(10), "temperature" to JsonPrimitive(0.5),
            ),
        )
        val merged = AssistantWire.merge(null, record, emptySet())
        val a = json.decodeFromJsonElement(Assistant.serializer(), merged)
        assertEquals(id, a.id.toString())
        assertEquals(10, a.contextMessageLimit)
    }
}
