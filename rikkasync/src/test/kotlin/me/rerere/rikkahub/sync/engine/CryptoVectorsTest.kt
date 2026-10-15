package me.rerere.rikkahub.sync.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 黄金向量(vectors/crypto-v0.json):由 TypeScript 端生成,并经独立的 Python 实现校验;Kotlin 必须复现。 */
class CryptoVectorsTest {
    private val text = javaClass.getResourceAsStream("/crypto-v0.json")!!.readBytes().toString(Charsets.UTF_8)
    private val vectors = Json.parseToJsonElement(text).jsonObject
    private val master = vectors.str("master_key_hex").hexToBytes()
    private val keys = Crypto.deriveKeys(master)

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun `key derivation`() {
        val derived = vectors.getValue("derived").jsonObject
        assertEquals(derived.str("enc_key_hex"), keys.enc.toHex())
        assertEquals(derived.str("blob_key_hex"), keys.blob.toHex())
    }

    @Test
    fun `records decrypt to the exact plaintext`() {
        for (r in vectors.getValue("records").jsonArray.map { it.jsonObject }) {
            val plain = Crypto.decryptRecord(keys, r.str("type"), r.str("id"), r.str("version").toLong(), r.str("sealed_hex").hexToBytes())
            assertEquals(r.str("name"), r.str("plaintext_utf8"), String(plain, Charsets.UTF_8))
        }
    }

    @Test
    fun `small record is reproduced byte for byte`() {
        val r = vectors.getValue("records").jsonArray[0].jsonObject
        val sealed = Crypto.encryptRecord(
            keys, r.str("type"), r.str("id"), r.str("version").toLong(),
            r.str("plaintext_utf8").toByteArray(Charsets.UTF_8), nonce = r.str("nonce_hex").hexToBytes(),
        )
        assertEquals(r.str("sealed_hex"), sealed.toHex())
    }

    @Test
    fun `a large record round trips through gzip and uses the gzip flag`() {
        val plain = ("hello world ".repeat(200)).toByteArray()
        val sealed = Crypto.encryptRecord(keys, "conversation", "x", 3, plain)
        assertTrue("compressed ciphertext must be smaller than the plaintext", sealed.size < plain.size)
        assertArrayEquals(plain, Crypto.decryptRecord(keys, "conversation", "x", 3, sealed))
    }

    @Test
    fun `blob ids and blob decryption`() {
        for (b in vectors.getValue("blobs").jsonArray.map { it.jsonObject }) {
            val plain = b.str("plaintext_hex").hexToBytes()
            assertEquals(b.str("blob_id"), Crypto.blobId(keys, plain))
            assertArrayEquals(plain, Crypto.decryptBlob(keys, b.str("blob_id"), b.str("sealed_hex").hexToBytes()))
            assertEquals(b.str("sealed_hex"), Crypto.encryptBlob(keys, b.str("blob_id"), plain, b.str("nonce_hex").hexToBytes()).toHex())
        }
    }

    @Test
    fun `recovery key encodes and decodes`() {
        assertEquals(vectors.str("recovery_key"), Crypto.encodeRecoveryKey(master))
        assertArrayEquals(master, Crypto.decodeRecoveryKey(vectors.str("recovery_key")))
        // 大小写、空白、分隔符都不影响
        assertArrayEquals(master, Crypto.decodeRecoveryKey("  " + vectors.str("recovery_key").lowercase().replace("-", " ") + " "))
    }

    @Test
    fun `random master keys round trip through the recovery code`() {
        repeat(50) {
            val m = Crypto.generateMasterKey()
            assertArrayEquals(m, Crypto.decodeRecoveryKey(Crypto.encodeRecoveryKey(m)))
        }
    }

    @Test
    fun `tampering is detected`() {
        val r = vectors.getValue("records").jsonArray[0].jsonObject
        val sealed = r.str("sealed_hex").hexToBytes()
        // 错误的 AAD(版本不同)
        assertThrows(Exception::class.java) { Crypto.decryptRecord(keys, r.str("type"), r.str("id"), 99, sealed) }
        // 错误的 id(服务器把记录换到别的 id)
        assertThrows(Exception::class.java) { Crypto.decryptRecord(keys, r.str("type"), "other", r.str("version").toLong(), sealed) }
        // 翻转一个密文位
        val flipped = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { Crypto.decryptRecord(keys, r.str("type"), r.str("id"), r.str("version").toLong(), flipped) }
        // 错误的密钥
        val other = Crypto.deriveKeys(Crypto.generateMasterKey())
        assertThrows(Exception::class.java) { Crypto.decryptRecord(other, r.str("type"), r.str("id"), r.str("version").toLong(), sealed) }
    }

    @Test
    fun `recovery key rejects bad input`() {
        assertThrows(IllegalArgumentException::class.java) { Crypto.decodeRecoveryKey("hello") }
        val good = vectors.str("recovery_key")
        val broken = good.dropLast(2) + (if (good.endsWith("AA")) "BB" else "AA")
        assertThrows(IllegalArgumentException::class.java) { Crypto.decodeRecoveryKey(broken) }
        assertThrows(IllegalArgumentException::class.java) { Crypto.decodeRecoveryKey("rks1-AEBAG") }
    }

    @Test
    fun `vector file is identical to the rikkasync repository copy`() {
        val repo = System.getenv("RIKKASYNC_REPO").orEmpty()
        assumeTrue("RIKKASYNC_REPO not set", repo.isNotEmpty())
        val original = File(repo, "vectors/crypto-v0.json").readText()
        assertEquals("测试资源里的向量必须与 rikkasync 仓库的 vectors/crypto-v0.json 逐字节一致", original, text)
    }
}
