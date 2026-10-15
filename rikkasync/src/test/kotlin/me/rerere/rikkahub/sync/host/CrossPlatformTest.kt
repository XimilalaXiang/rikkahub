package me.rerere.rikkahub.sync.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.sync.engine.Crypto
import me.rerere.rikkahub.sync.engine.ServerHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * **Kotlin 的 Android 适配器 ↔ TypeScript 的桌面适配器**,经真实服务器同步真实会话数据。
 * 桌面端是 rikkahub-desktop 仓库里真实的 `createDesktopHostAdapter`(raw-shadow、触发器、谱系比较都是真的),
 * 由 `pc-server/sync/interop-peer.ts` 驱动。证明的是"线格式真的跨平台一致",并且 Android 独有的数据
 * (modelSnapshot、未知字段、folder_id、config)经桌面往返后**逐字节**不变。
 *
 * 需要环境变量 RIKKAHUB_DESKTOP_REPO(桌面仓库检出目录)、RIKKASYNC_REPO 和 bun;缺任何一个都跳过。
 */
class CrossPlatformTest {
    private lateinit var server: ServerHarness
    private lateinit var desktopRepo: File
    private lateinit var desktopData: File
    private val master = Crypto.generateMasterKey()
    private val keys = Crypto.deriveKeys(master)
    private val recovery = Crypto.encodeRecoveryKey(master)
    private val closers = ArrayList<AutoCloseable>()
    private lateinit var desktopToken: String

    @Before
    fun setUp() {
        val repo = System.getenv("RIKKAHUB_DESKTOP_REPO").orEmpty()
        assumeTrue("RIKKAHUB_DESKTOP_REPO not set", repo.isNotEmpty())
        desktopRepo = File(repo, "pc-server")
        assumeTrue("$desktopRepo/sync/interop-peer.ts not found", File(desktopRepo, "sync/interop-peer.ts").exists())
        server = ServerHarness.startOrSkip()
        desktopData = Files.createTempDirectory("rks-desktop-").toFile()
        desktopToken = server.createDevice("desktop").first
    }

    @After
    fun tearDown() {
        closers.reversed().forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.close()
        if (::desktopData.isInitialized) desktopData.deleteRecursively()
    }

    private fun desktop(vararg actions: String): JsonObject {
        // 动作经 UTF-8 的 JSON 文件传递:JVM 子进程的命令行参数编码取决于系统 locale,中文会变成 "?"。
        val file = File(desktopData, "actions.json")
        file.writeText(JsonArray(actions.map { JsonPrimitive(it) }).toString(), Charsets.UTF_8)
        val cmd = listOf("bun", "sync/interop-peer.ts", desktopData.absolutePath, server.url, desktopToken, recovery, "--actions-file", file.absolutePath)
        val p = ProcessBuilder(cmd).directory(desktopRepo).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val err = p.errorStream.bufferedReader(Charsets.UTF_8).readText()
        val code = p.waitFor()
        assertEquals("desktop peer failed:\n$err", 0, code)
        val state = Json.parseToJsonElement(out.trim().lines().last()).jsonObject
        assertEquals("desktop sync errors", emptyList<String>(), state.getValue("errors").jsonArray.map { it.jsonPrimitive.content })
        return state
    }

    private fun JsonObject.conv(id: String) = getValue("conversations").jsonArray.map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == id }
    private fun JsonObject.has(id: String) = getValue("conversations").jsonArray.any { it.jsonObject.getValue("id").jsonPrimitive.content == id }
    private fun JsonObject.nodeText(i: Int) = getValue("nodes").jsonArray[i].jsonObject.getValue("messages").jsonPrimitive.content

    private val androidMsg0 = """[{"id":"6d1e5c8e-2f6a-4c5a-9a3e-0000000000a1","role":"user","parts":[{"type":"text","text":"你好,桌面"}],"annotations":[],"createdAt":"2026-01-01T10:00:00.123456","modelId":null}]"""
    private val androidMsg1 = """[{"id":"6d1e5c8e-2f6a-4c5a-9a3e-0000000000a2","role":"assistant","parts":[{"type":"text","text":"收到"},{"type":"reasoning","reasoning":"想一想","createdAt":"2026-01-01T10:00:01.5"}],"annotations":[],"createdAt":"2026-01-01T10:00:01.123456","finishedAt":"2026-01-01T10:00:05.654321","modelId":"3f1d5c8e-2f6a-4c5a-9a3e-0000000000b1","usage":{"promptTokens":10,"completionTokens":20,"totalTokens":30},"modelSnapshot":{"id":"3f1d5c8e-2f6a-4c5a-9a3e-0000000000b1","modelId":"gpt-x","futureField":[1,{"deep":true}]},"isContextCheckpoint":false}]"""
    private val folderId = "7a1d5c8e-2f6a-4c5a-9a3e-0000000000f1"
    private val config = """{"chatModelId":"3f1d5c8e-2f6a-4c5a-9a3e-0000000000b1","reasoningLevel":"HIGH","unknownFutureConfigKey":{"a":[1,2]}}"""

    @Test
    fun `an android conversation round-trips through the desktop adapter with android-only data byte for byte`() = runBlocking {
        val phone = TestPhone(server, keys, "phone").also { closers.add(it) }
        TestAndroidDb.insertConversation(phone.db, "c1", title = "手机上的会话", updateAt = 5000, pinned = 1, config = config)
        phone.db.exec("UPDATE ConversationEntity SET folder_id = ? WHERE id = 'c1'", listOf(folderId))
        TestAndroidDb.insertNode(phone.db, "n0", "c1", 0, androidMsg0)
        TestAndroidDb.insertNode(phone.db, "n1", "c1", 1, androidMsg1, select = 0)
        repeat(2) { phone.engine.syncOnce() }
        val before = phone.nodes("c1")
        val rowBefore = phone.row("c1")

        // 桌面拉取:能看到会话、标题、置顶
        val d1 = desktop()
        assertEquals("手机上的会话", d1.conv("c1").getValue("title").jsonPrimitive.content)
        assertEquals(1, d1.conv("c1").getValue("is_pinned").jsonPrimitive.content.toInt())

        // 桌面改标题 + 改第一条消息,推送
        desktop("--edit-title", "c1", "桌面改的标题", "--append-text", "n0", " (desktop edit)")
        repeat(3) { phone.engine.syncOnce() }

        // 手机收到:标题与被改的那个节点变了……
        val after = phone.nodes("c1")
        assertEquals("桌面改的标题", phone.row("c1").title)
        assertTrue(after[0].messages.contains("(desktop edit)"))
        assertTrue("被改节点里的 Android 字段(id/role/createdAt)仍在", after[0].messages.contains("6d1e5c8e-2f6a-4c5a-9a3e-0000000000a1") && after[0].messages.contains("\"role\":\"user\""))
        // ……没改的节点逐字节不变(modelSnapshot、未知字段、微秒时间戳、reasoning part 全在)
        assertEquals(before[1].messages, after[1].messages)
        // 会话级的 Android 独有列逐字节不变
        assertEquals(rowBefore.folderId, phone.row("c1").folderId)
        assertEquals(rowBefore.config, phone.row("c1").config)
        assertEquals(1L, phone.row("c1").isPinned)
        assertEquals(rowBefore.createAt, phone.row("c1").createAt)
        // 收敛后双方安静
        assertEquals(0, phone.engine.syncOnce().let { it.pushed + it.applied })
        val d2 = desktop()
        assertEquals("桌面改的标题", d2.conv("c1").getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `a conversation created on the desktop opens on the phone, and deletes propagate both ways`() = runBlocking {
        val phone = TestPhone(server, keys, "phone").also { closers.add(it) }
        desktop("--create", "00000000-0000-4000-8000-0000000000d1", "桌面新建", "第一句", "第二句")
        repeat(3) { phone.engine.syncOnce() }
        val id = "00000000-0000-4000-8000-0000000000d1"
        assertEquals("桌面新建", phone.row(id).title)
        val nodes = phone.nodes(id)
        assertEquals(2, nodes.size)
        // 桌面写出的消息在手机端必须是合法的 JSON 数组,且带 Android 解码器要求的字段
        for (n in nodes) {
            val msgs = Json.parseToJsonElement(n.messages).jsonArray
            assertTrue(msgs.isNotEmpty())
            for (m in msgs.map { it.jsonObject }) {
                for (k in listOf("id", "role", "parts", "createdAt")) assertTrue("缺 $k: ${n.messages}", k in m)
                assertEquals(36, m.getValue("id").jsonPrimitive.content.length)
            }
        }
        // 手机删除 → 桌面消失
        phone.db.exec("DELETE FROM ConversationEntity WHERE id = ?", listOf(id))
        repeat(3) { phone.engine.syncOnce() }
        assertFalse(desktop().has(id))
        // 桌面删除 → 手机消失
        TestAndroidDb.insertConversation(phone.db, "c2", title = "x")
        TestAndroidDb.insertNode(phone.db, "c2n", "c2", 0, androidMsg0)
        repeat(3) { phone.engine.syncOnce() }
        assertTrue(desktop().has("c2"))
        desktop("--delete", "c2")
        repeat(3) { phone.engine.syncOnce() }
        assertFalse("c2" in phone.ids())
    }

    @Test
    fun `an already aligned pair (same conversation on both sides before the first sync) is adopted without duplicates`() = runBlocking {
        val phone = TestPhone(server, keys, "phone").also { closers.add(it) }
        // 先让桌面把同一个会话"导入"(用 apply 的等价物:通过同步拿到),再让两端从零状态开始——模拟手动导入对齐后的首次同步
        TestAndroidDb.insertConversation(phone.db, "c1", title = "T", updateAt = 5000, config = config)
        TestAndroidDb.insertNode(phone.db, "n0", "c1", 0, androidMsg0)
        TestAndroidDb.insertNode(phone.db, "n1", "c1", 1, androidMsg1)
        repeat(2) { phone.engine.syncOnce() }
        desktop() // 桌面拿到它
        // 两端都丢掉同步状态(各自"首次接触"),数据仍然相同
        phone.syncDb.reset()
        File(desktopData, "sync-state.db").let { f -> listOf("", "-wal", "-shm").forEach { File(f.path + it).delete() } }
        repeat(3) { phone.engine.syncOnce() }
        val d = desktop()
        assertEquals(setOf("c1"), phone.ids())
        assertEquals(1, d.getValue("conversations").jsonArray.size)
    }
}
