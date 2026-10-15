package me.rerere.rikkahub.sync.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SyncConfigStoreTest {
    private val dirs = ArrayList<File>()

    @After
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    /** 内存密钥的 SecretBox(JVM 测试用;Android 上是 Keystore)。 */
    private class TestBox(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : SecretBox {
        override fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12))) }
            return c.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    private fun fresh(box: SecretBox = TestBox()): Triple<File, SyncConfigStore, SecretBox> {
        val d = File(Files.createTempDirectory("rks-cfg-").toFile().also { dirs.add(it) }, "sync")
        return Triple(d, SyncConfigStore(d, box), box)
    }

    private fun code(block: () -> Unit): SyncConfigError? = try {
        block(); null
    } catch (e: SyncConfigException) {
        e.code
    }

    @Test
    fun `server url normalisation`() {
        assertEquals("https://sync.example.com", SyncConfigStore.normalizeServerUrl("https://sync.example.com/"))
        assertEquals("http://10.0.0.5:8787", SyncConfigStore.normalizeServerUrl(" http://10.0.0.5:8787 "))
        assertEquals("https://example.com/rikka", SyncConfigStore.normalizeServerUrl("https://example.com/rikka/?x=1#y"))
        assertEquals(SyncConfigError.INVALID_URL, code { SyncConfigStore.normalizeServerUrl("ftp://x") })
        assertEquals(SyncConfigError.INVALID_URL, code { SyncConfigStore.normalizeServerUrl("not a url") })
        assertEquals(SyncConfigError.INVALID_URL, code { SyncConfigStore.normalizeServerUrl("https://user:pw@example.com") })
        assertEquals(SyncConfigError.INVALID_URL, code { SyncConfigStore.normalizeServerUrl("https://") })
    }

    @Test
    fun `starts unconfigured and the public view carries no secrets`() {
        val (_, s) = fresh()
        assertEquals(PublicSyncConfig(false, "", false, false, null, false), s.public())
        s.update(serverUrl = "https://s.example.com/", deviceToken = "tok-secret")
        val key = s.generateKey()
        val view = s.public().toString()
        assertFalse(view.contains("tok-secret"))
        assertFalse(view.contains(key))
        assertTrue(s.public().hasToken && s.public().hasKey)
        assertTrue(Regex("[0-9A-F]{4}-[0-9A-F]{4}").matches(s.public().keyFingerprint!!))
    }

    @Test
    fun `persists across instances and the secrets are not stored in clear text`() {
        val (dir, s, box) = fresh()
        s.update(enabled = true, serverUrl = "https://s.example.com", deviceToken = "tok-secret")
        val key = s.generateKey()
        val again = SyncConfigStore(dir, box).get()
        assertEquals(SyncConfig(true, "https://s.example.com", "tok-secret", key), again)
        val disk = dir.listFiles()!!.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse("令牌不能以明文落盘", disk.contains("tok-secret"))
        assertFalse("恢复码不能以明文落盘", disk.contains(key))
        assertEquals(setOf("config.json", "secrets.bin"), dir.list()!!.toSet()) // 没有遗留的临时文件
    }

    @Test
    fun `omitted token keeps the stored one`() {
        val (_, s) = fresh()
        s.update(deviceToken = "t1")
        s.update(serverUrl = "https://s.example.com")
        assertEquals("t1", s.get().deviceToken)
    }

    @Test
    fun `generate refuses to overwrite, import normalises and checks`() {
        val (_, a) = fresh()
        val k = a.generateKey()
        assertEquals(SyncConfigError.KEY_EXISTS, code { a.generateKey() })

        val (_, b) = fresh()
        b.importKey("  " + k.lowercase().replace("-", " ") + "  ")
        assertEquals(k, b.exportKey())
        assertEquals(a.public().keyFingerprint, b.public().keyFingerprint)

        val (_, c) = fresh()
        assertEquals(SyncConfigError.INVALID_RECOVERY_KEY, code { c.importKey("hello") })
        assertEquals(SyncConfigError.INVALID_RECOVERY_KEY, code { c.importKey(k.dropLast(2) + (if (k.endsWith("AA")) "BB" else "AA")) })
        c.importKey(k)
        c.importKey(k) // 同一把密钥重复导入是幂等的
        val other = fresh().second.generateKey()
        assertEquals(SyncConfigError.KEY_EXISTS, code { c.importKey(other) })
    }

    @Test
    fun `clearKey disables sync and export then fails`() {
        val (_, s) = fresh()
        s.update(enabled = true)
        s.generateKey()
        s.clearKey()
        assertEquals(false, s.get().enabled)
        assertEquals("", s.get().recoveryKey)
        assertEquals(SyncConfigError.NO_KEY, code { s.exportKey() })
    }

    @Test
    fun `secrets that cannot be decrypted are quarantined and reported, not crashed on`() {
        val (dir, s) = fresh()
        s.update(serverUrl = "https://s.example.com", deviceToken = "t")
        s.generateKey()
        // 换了一个密钥(模拟 Keystore 被清/换设备):密文打不开
        val reopened = SyncConfigStore(dir, TestBox())
        assertEquals("", reopened.get().recoveryKey)
        assertEquals("https://s.example.com", reopened.get().serverUrl) // 非秘密部分仍在
        assertTrue(reopened.public().secretsUnreadable)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("secrets.bin.corrupt-") })
        assertNotNull(dir.listFiles()!!.firstOrNull { it.name.startsWith("secrets.bin.corrupt-") })
    }

    @Test
    fun `wipe deletes everything`() {
        val (dir, s) = fresh()
        s.update(deviceToken = "t")
        s.generateKey()
        s.wipe()
        assertFalse(File(dir, "config.json").exists())
        assertFalse(File(dir, "secrets.bin").exists())
        assertFalse(s.public().hasKey)
    }
}
