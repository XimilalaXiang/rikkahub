package me.rerere.rikkahub.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.sync.host.AssistantContract
import me.rerere.rikkahub.sync.host.AssistantWire
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
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
    fun `the golden record produced by the real desktop adapter decodes with the real Assistant class`() {
        // 桌面适配器产出的线形态(rikkasync 的 CrossPlatformTest 保证它与桌面当前输出一致)。
        val golden = json.parseToJsonElement(java.io.File("../rikkasync/src/test/resources/desktop-assistant-wire.json").readText()).jsonObject
        val a = json.decodeFromJsonElement(Assistant.serializer(), AssistantWire.merge(null, golden, emptySet()))
        assertEquals("桌面助手", a.name)
        assertEquals("桌面的系统提示词", a.systemPrompt)
        assertEquals(me.rerere.ai.core.ReasoningLevel.HIGH, a.reasoningLevel)
        assertEquals(0.5f, a.temperature)
        assertEquals(1, a.regexes.size)
        assertEquals("X-A", a.customHeaders.single().name)
        assertEquals(1, a.presetMessages.size)
        assertTrue(a.avatar is me.rerere.rikkahub.data.model.Avatar.Emoji)
        // 设备本地字段是 Assistant 的默认值
        assertEquals(emptySet<Uuid>(), a.mcpServers)
    }

    @Test
    fun `the real Android encoding of a fully populated assistant equals the checked-in golden record`() {
        val a = Assistant(
            id = Uuid.parse("55555555-5555-4555-8555-555555555555"), name = "手机助手", avatar = me.rerere.rikkahub.data.model.Avatar.Emoji("🦊"),
            useAssistantAvatar = true, systemPrompt = "你是写作助手", temperature = 0.7f, topP = 0.9f, contextMessageLimit = 20, maxTokens = 2048,
            streamOutput = false, enableMemory = true, useGlobalMemory = true, enableRecentChatsReference = true, messageTemplate = "{{ message }}!",
            presetMessages = listOf(
                me.rerere.ai.ui.UIMessage(
                    id = Uuid.parse("6d1e5c8e-2f6a-4c5a-9a3e-0000000000d1"), role = me.rerere.ai.core.MessageRole.USER,
                    parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("hi")),
                    createdAt = kotlinx.datetime.LocalDateTime(2026, 1, 1, 0, 0, 0),
                ),
            ),
            regexes = listOf(me.rerere.rikkahub.data.model.AssistantRegex(id = Uuid.parse("0b8a2f7e-5d1c-4a39-9e47-000000000001"), name = "r", findRegex = "a", replaceString = "b", affectingScope = setOf(me.rerere.rikkahub.data.model.AssistantAffectScope.USER))),
            reasoningLevel = me.rerere.ai.core.ReasoningLevel.HIGH,
            customHeaders = listOf(me.rerere.ai.provider.CustomHeader("X-A", "1")),
            customBodies = listOf(me.rerere.ai.provider.CustomBody("k", JsonPrimitive("v"))),
            enableWebSearch = true, enableTimeReminder = true, timeReminderIntervalMinutes = 15, allowConversationSystemPrompt = true,
        )
        val synced = AssistantWire.synced(encode(a))
        val file = java.io.File("../rikkasync/src/test/resources/android-assistant-wire.json")
        if (System.getenv("UPDATE_GOLDEN") == "1") file.writeText(JsonInstantPretty.encodeToString(JsonObject.serializer(), synced) + "\n")
        assertEquals(json.parseToJsonElement(file.readText()), synced)
        // 并且能原样解码回来
        assertEquals(a, json.decodeFromJsonElement(Assistant.serializer(), AssistantWire.merge(null, synced, emptySet())))
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
