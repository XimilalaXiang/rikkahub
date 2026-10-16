package me.rerere.rikkahub.sync.host

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.sync.engine.Crypto
import me.rerere.rikkahub.sync.engine.HttpException
import me.rerere.rikkahub.sync.engine.ServerHarness
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** 附件(A5):两台带真实文件的"手机" × 真实引擎 × 真实服务器。 */
class AttachmentSyncTest {
    private lateinit var server: ServerHarness
    private val keys = Crypto.deriveKeys(Crypto.generateMasterKey())
    private val closers = ArrayList<AutoCloseable>()
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4)

    @Before
    fun setUp() {
        server = ServerHarness.startOrSkip()
    }

    @After
    fun tearDown() {
        closers.reversed().forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.close()
    }

    private fun phone(name: String) = TestPhone(server, keys, name, withAttachments = true).also { closers.add(it) }
    private fun msg(url: String, text: String = "see", id: String = "6d1e5c8e-2f6a-4c5a-9a3e-0000000000c1") =
        """[{"id":"$id","role":"user","parts":[{"type":"text","text":"$text"},{"type":"image","url":"$url"}],"createdAt":"2026-01-01T00:00:00"}]"""

    private fun TestPhone.convWithAttachment(id: String, bytes: ByteArray, name: String, text: String = "see") {
        val url = store!!.addLocal(bytes, name)
        TestAndroidDb.insertConversation(db, id)
        TestAndroidDb.insertNode(db, "$id-n0", id, 0, msg(url, text))
    }

    private fun localUrlOf(p: TestPhone, id: String): String = Regex("file://[^\"\\\\]+").find(p.nodes(id).single().messages)!!.value
    private fun run(block: suspend () -> Unit) = runBlocking { block() }
    private suspend fun settle(vararg phones: TestPhone) = repeat(4) { for (p in phones) p.engine.syncOnce() }

    @Test
    fun `an attachment travels from one phone to the other as an encrypted blob`() = run {
        val a = phone("a"); val b = phone("b")
        a.convWithAttachment("c1", png, "photo.png")
        settle(a, b)
        val onB = b.nodes("c1").single().messages
        assertFalse("令牌已被换回本机 URL", onB.contains("rks-blob://"))
        val url = localUrlOf(b, "c1")
        val att = b.store!!.describe(url)
        assertNotNull(att)
        assertEquals("photo.png", att!!.displayName)
        assertArrayEquals(png, att.read())
        // 回声:不推回、不重复应用
        val idle = b.engine.syncOnce()
        assertEquals(0, idle.pushed + idle.applied)
        // 服务器上的块是密文
        val blobId = Crypto.blobId(keys, png)
        val conn = URL("${server.url}/v1/blobs/$blobId").openConnection() as HttpURLConnection
        conn.setRequestProperty("authorization", "Bearer ${server.createDevice("probe").first}")
        assertEquals(200, conn.responseCode)
        assertFalse(String(conn.inputStream.readBytes(), Charsets.ISO_8859_1).contains(String(png, Charsets.ISO_8859_1)))
    }

    @Test
    fun `a round trip leaves the original wire bytes untouched`() = run {
        val a = phone("a"); val b = phone("b")
        a.convWithAttachment("c1", png, "photo.png")
        settle(a, b)
        // B 只改标题再推回:A 收到的节点 messages 与最初 A 自己推送时的令牌形态逐字节一致
        val tokenForm = ConversationWire.read(a.db, "c1", object : me.rerere.rikkahub.sync.engine.HostContext { override fun blobId(plaintext: ByteArray) = Crypto.blobId(keys, plaintext) }, AttachmentBridge(a.syncDb, a.store!!))!!.data.toString()
        b.db.exec("UPDATE ConversationEntity SET title = 'renamed on b' WHERE id = 'c1'")
        settle(a, b)
        assertEquals("renamed on b", a.row("c1").title)
        val after = ConversationWire.read(a.db, "c1", object : me.rerere.rikkahub.sync.engine.HostContext { override fun blobId(plaintext: ByteArray) = Crypto.blobId(keys, plaintext) }, AttachmentBridge(a.syncDb, a.store!!))!!.data.toString()
        assertEquals(tokenForm.substringAfter("\"nodes\""), after.substringAfter("\"nodes\""))
    }

    @Test
    fun `the same attachment in two conversations is saved once`() = run {
        val a = phone("a"); val b = phone("b")
        a.convWithAttachment("c1", png, "x.png")
        a.convWithAttachment("c2", png, "x.png", text = "again")
        settle(a, b)
        assertEquals(1, b.store!!.saves)
    }

    @Test
    fun `a block the server no longer has does not block the conversation`() = run {
        val a = phone("a"); val b = phone("b")
        val ghost = AttachmentBridge.token("f".repeat(64), "ghost.png")
        TestAndroidDb.insertConversation(a.db, "c1")
        TestAndroidDb.insertNode(a.db, "c1-n0", "c1", 0, msg(ghost))
        settle(a, b)
        assertTrue(b.nodes("c1").single().messages.contains(ghost))
        assertEquals(0, b.store!!.saves)
        b.db.exec("UPDATE ConversationEntity SET title = 'renamed' WHERE id = 'c1'")
        val r = b.engine.syncOnce()
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals(1, r.pushed)
    }

    @Test
    fun `a failure while saving the attachment holds the record and the next pass applies it`() = run {
        val a = phone("a"); val b = phone("b")
        a.convWithAttachment("c1", png, "photo.png")
        a.engine.syncOnce()
        b.store!!.failSave = java.io.IOException("disk full")
        val r1 = b.engine.syncOnce()
        assertTrue(r1.errors.joinToString(), r1.errors.any { it.contains("disk full") })
        assertTrue(b.ids().isEmpty())
        b.store.failSave = null
        assertTrue(b.engine.syncOnce().errors.isEmpty())
        assertEquals(setOf("c1"), b.ids())
    }

    @Test
    fun `a local file that vanished does not fail the push`() = run {
        val a = phone("a"); val b = phone("b")
        a.convWithAttachment("c1", png, "gone.png")
        a.store!!.files().forEach { it.delete() }
        settle(a, b)
        assertEquals(setOf("c1"), b.ids())
        assertTrue(b.nodes("c1").single().messages.contains("gone") || b.nodes("c1").single().messages.contains("file://"))
    }

    @Test
    fun `a local file the user deleted is fetched again when the message is next applied`() = run {
        val a = phone("a"); val b = phone("b")
        a.convWithAttachment("c1", png, "photo.png")
        settle(a, b)
        b.store!!.files().forEach { it.delete() }
        a.db.exec("UPDATE message_node SET messages = ? WHERE id = 'c1-n0'", listOf(msg(localUrlOf(a, "c1"), text = "edited")))
        settle(a, b)
        val onB = b.nodes("c1").single().messages
        assertTrue(onB.contains("edited"))
        val att = b.store!!.describe(localUrlOf(b, "c1"))
        assertNotNull("被删掉的本机文件在下次应用时重新取回", att)
        assertArrayEquals(png, att!!.read())
    }

    @Test
    fun `an attachment edited on one side after sync keeps working in both directions`() = run {
        val a = phone("a"); val b = phone("b")
        a.convWithAttachment("c1", png, "photo.png")
        settle(a, b)
        val second = byteArrayOf(9, 8, 7, 6, 5)
        val url2 = b.store!!.addLocal(second, "second.bin")
        val oldUrl = localUrlOf(b, "c1")
        b.db.exec("UPDATE message_node SET messages = ? WHERE id = 'c1-n0'", listOf(msg(oldUrl).dropLast(1) + "," + """{"id":"6d1e5c8e-2f6a-4c5a-9a3e-0000000000c2","role":"user","parts":[{"type":"document","url":"$url2","fileName":"second.bin","mime":"application/octet-stream"}],"createdAt":"2026-01-01T00:00:01"}]"""))
        settle(a, b)
        val docUrl = Regex("file://[^\"\\\\]+second[^\"\\\\]*|file://[^\"\\\\]+").findAll(a.nodes("c1").single().messages).map { it.value }.toList().last()
        assertArrayEquals(second, a.store!!.describe(docUrl)!!.read())
    }

    @Test
    fun `encodeUriComponent matches JavaScript for awkward names and decodes back`() {
        val cases = listOf(
            "a b.png" to "a%20b.png",
            "截图 2026-10-16.png" to "%E6%88%AA%E5%9B%BE%202026-10-16.png",
            "we\"ird\\name.txt" to "we%22ird%5Cname.txt",
            "ünï.PDF" to "%C3%BCn%C3%AF.PDF",
            "no-ext" to "no-ext",
            "a%b.png" to "a%25b.png",
            "(x)!*'~.bin" to "(x)!*'~.bin",
            "😀 emoji.jpg" to "%F0%9F%98%80%20emoji.jpg",
            "a+b=c&d;e,f:g@h\$i.txt" to "a%2Bb%3Dc%26d%3Be%2Cf%3Ag%40h%24i.txt",
            "x/y?z#w.png" to "x%2Fy%3Fz%23w.png",
        )
        for ((plain, encoded) in cases) {
            assertEquals(encoded, AttachmentBridge.encodeUriComponent(plain))
            assertEquals(plain, AttachmentBridge.decodeUriComponent(encoded))
            val t = AttachmentBridge.token("a".repeat(64), plain)
            assertEquals(mapOf("a".repeat(64) to plain), AttachmentBridge.tokensIn("\"$t\""))
        }
        assertEquals("100%", AttachmentBridge.decodeUriComponent("100%")) // 残缺的百分号不抛错
        assertEquals("100%4", AttachmentBridge.decodeUriComponent("100%4"))
        assertEquals("a%zzb", AttachmentBridge.decodeUriComponent("a%zzb"))
    }

    @Suppress("unused")
    private val keep = HttpException::class
}
