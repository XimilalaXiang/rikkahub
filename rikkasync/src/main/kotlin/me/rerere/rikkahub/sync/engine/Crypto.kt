package me.rerere.rikkahub.sync.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 协议 v0 的客户端密码学(docs/02-protocol-v0.md §4)。与 TypeScript 的 core/src/crypto.ts 逐字节一致,
 * vectors/crypto-v0.json 同时钉住两端。
 */
class Keys(val enc: ByteArray, val blob: ByteArray) {
    init {
        require(enc.size == 32 && blob.size == 32) { "keys must be 32 bytes" }
    }
}

object Crypto {
    private val SALT = "rikkasync/v0".toByteArray(Charsets.UTF_8)
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val TAG_LEN = 16
    private const val FLAG_RAW: Byte = 0x00
    private const val FLAG_GZIP: Byte = 0x01
    private const val COMPRESS_MIN_BYTES = 256
    private val random = SecureRandom()

    fun generateMasterKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun deriveKeys(master: ByteArray): Keys {
        require(master.size == 32) { "master key must be 32 bytes" }
        return Keys(
            enc = hkdfSha256(master, SALT, "enc".toByteArray(), 32),
            blob = hkdfSha256(master, SALT, "blob".toByteArray(), 32),
        )
    }

    /** RFC 5869 HKDF-SHA256(extract + expand)。只支持 ≤ 255 块,这里最多用到 1 块。 */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun recordAad(type: String, id: String, version: Long): ByteArray =
        "rikkasync/v0|rec|$type|$id|$version".toByteArray(Charsets.UTF_8)

    fun blobAad(blobId: String): ByteArray = "rikkasync/v0|blob|$blobId".toByteArray(Charsets.UTF_8)

    /** 输出布局:nonce(12) || 密文 || tag(16)。JCE 的 AES/GCM 的 doFinal 本身就是 密文||tag。 */
    private fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray, nonce: ByteArray?): ByteArray {
        val iv = nonce ?: ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        require(iv.size == NONCE_LEN) { "nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    private fun open(key: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= NONCE_LEN + TAG_LEN) { "ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, NONCE_LEN)))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, NONCE_LEN, sealed.size - NONCE_LEN)
    }

    /** 加 1 字节标志(原文/gzip),可选 gzip(≥256 字节且确实更小),再加密。 */
    fun encryptRecord(keys: Keys, type: String, id: String, version: Long, plaintext: ByteArray, nonce: ByteArray? = null, compress: Boolean = true): ByteArray {
        var flag = FLAG_RAW
        var body = plaintext
        if (compress && plaintext.size >= COMPRESS_MIN_BYTES) {
            val gz = gzip(plaintext)
            if (gz.size < plaintext.size) {
                flag = FLAG_GZIP
                body = gz
            }
        }
        return seal(keys.enc, recordAad(type, id, version), byteArrayOf(flag) + body, nonce)
    }

    fun decryptRecord(keys: Keys, type: String, id: String, version: Long, sealed: ByteArray): ByteArray {
        val framed = open(keys.enc, recordAad(type, id, version), sealed)
        require(framed.isNotEmpty()) { "empty record" }
        val body = framed.copyOfRange(1, framed.size)
        return when (framed[0]) {
            FLAG_RAW -> body
            FLAG_GZIP -> gunzip(body)
            else -> throw IllegalArgumentException("unknown record flag ${framed[0]}")
        }
    }

    /** 带密钥的内容寻址:明文的 HMAC-SHA256,小写十六进制。 */
    fun blobId(keys: Keys, plaintext: ByteArray): String = hmacSha256(keys.blob, plaintext).toHex()

    fun encryptBlob(keys: Keys, id: String, plaintext: ByteArray, nonce: ByteArray? = null): ByteArray =
        seal(keys.enc, blobAad(id), plaintext, nonce)

    fun decryptBlob(keys: Keys, id: String, sealed: ByteArray): ByteArray {
        val plain = open(keys.enc, blobAad(id), sealed)
        require(blobId(keys, plain) == id) { "blob id mismatch" }
        return plain
    }

    // ── 恢复码:"rks1-" + base32(master || sha256(master)[0..4]),每 5 个字符一组用 - 连接 ──

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun b32encode(bytes: ByteArray): String {
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(B32[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
            value = value and ((1 shl bits) - 1) // 只保留未消费的位,避免溢出
        }
        if (bits > 0) out.append(B32[(value shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun b32decode(s: String): ByteArray {
        var bits = 0
        var value = 0
        val out = ArrayList<Byte>()
        for (ch in s) {
            val idx = B32.indexOf(ch)
            require(idx >= 0) { "invalid key character" }
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out.add(((value ushr (bits - 8)) and 0xff).toByte())
                bits -= 8
                value = value and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }

    private fun checksum(master: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(master).copyOf(4)

    fun encodeRecoveryKey(master: ByteArray): String {
        require(master.size == 32) { "master key must be 32 bytes" }
        val raw = b32encode(master + checksum(master))
        return "rks1-" + raw.chunked(5).joinToString("-")
    }

    fun decodeRecoveryKey(text: String): ByteArray {
        val t = text.trim().uppercase().replace(Regex("[\\s-]"), "")
        require(t.startsWith("RKS1")) { "not a rikkasync recovery key" }
        val bytes = b32decode(t.substring(4))
        require(bytes.size >= 36) { "recovery key too short" }
        val master = bytes.copyOfRange(0, 32)
        val check = checksum(master)
        for (i in 0 until 4) require(bytes[32 + i] == check[i]) { "recovery key checksum mismatch" }
        return master
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
