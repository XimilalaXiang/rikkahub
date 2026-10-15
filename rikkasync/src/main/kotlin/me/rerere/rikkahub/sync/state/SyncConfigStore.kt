package me.rerere.rikkahub.sync.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.sync.engine.Crypto
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** 把秘密包装成只有本机能解开的密文。Android 上由 Keystore 的 AES-GCM 密钥实现;JVM 测试用内存密钥。 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** 解不开(密钥已失效、数据损坏)时抛出任意异常,调用方按"没有秘密"处理。 */
    fun open(sealed: ByteArray): ByteArray
}

enum class SyncConfigError { INVALID_URL, INVALID_RECOVERY_KEY, KEY_EXISTS, NO_KEY }

class SyncConfigException(message: String, val code: SyncConfigError) : Exception(message)

data class SyncConfig(
    val enabled: Boolean = false,
    /** 规范化的服务器基地址(无末尾斜杠)。 */
    val serverUrl: String = "",
    /** 管理员为本设备创建的设备令牌。 */
    val deviceToken: String = "",
    /** 主密钥的恢复码文本;空串 = 尚未设置。 */
    val recoveryKey: String = "",
)

/** 可以安全交给界面的视图:没有任何秘密。 */
data class PublicSyncConfig(
    val enabled: Boolean,
    val serverUrl: String,
    val hasToken: Boolean,
    val hasKey: Boolean,
    /** 密钥短指纹,供用户在两台设备上核对是不是同一把密钥;从主密钥单向派生,不可逆。 */
    val keyFingerprint: String?,
    /** secrets.bin 存在但解不开(例如换了设备/Keystore 被清):需要重新输入令牌与恢复码。 */
    val secretsUnreadable: Boolean,
)

/**
 * 云同步的配置与密钥存储(docs/04 §3)。
 *
 * 放在 `noBackupFilesDir/sync/`:`config.json`(开关、地址)与用 [SecretBox] 包装过的 `secrets.bin`(设备令牌 + 恢复码)。
 * **不进 Settings(DataStore)**——BackupManager 会把整个 settings.json 写进备份 zip 并上传 WebDAV/S3,密钥进去就等于
 * 把端到端加密的主密钥交给了备份文件。noBackupFilesDir 同时被系统 Auto Backup 排除。
 */
class SyncConfigStore(private val dir: File, private val box: SecretBox) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val configFile get() = File(dir, "config.json")
    private val secretsFile get() = File(dir, "secrets.bin")
    private var cache: SyncConfig? = null
    private var unreadable = false

    fun get(): SyncConfig = synchronized(lock) { cache ?: load().also { cache = it } }

    fun public(): PublicSyncConfig = synchronized(lock) {
        val c = get()
        PublicSyncConfig(c.enabled, c.serverUrl, c.deviceToken.isNotEmpty(), c.recoveryKey.isNotEmpty(), keyFingerprintOf(c.recoveryKey), unreadable)
    }

    /** 部分更新连接设置。`deviceToken` 为 null 表示不改(界面不回显令牌,所以"保持原值"是常态)。 */
    fun update(enabled: Boolean? = null, serverUrl: String? = null, deviceToken: String? = null): SyncConfig = synchronized(lock) {
        var next = get()
        if (serverUrl != null) next = next.copy(serverUrl = if (serverUrl.isEmpty()) "" else normalizeServerUrl(serverUrl))
        if (deviceToken != null) next = next.copy(deviceToken = deviceToken.trim())
        if (enabled != null) next = next.copy(enabled = enabled)
        write(next)
        next
    }

    /** 生成新的主密钥(只在尚无密钥时允许——覆盖现有密钥会让云端已有数据永远无法解密)。返回恢复码。 */
    fun generateKey(): String = synchronized(lock) {
        val cur = get()
        if (cur.recoveryKey.isNotEmpty()) throw SyncConfigException("已经存在密钥;覆盖它会使云端现有数据无法解密。如确需更换请先清除", SyncConfigError.KEY_EXISTS)
        val key = Crypto.encodeRecoveryKey(Crypto.generateMasterKey())
        write(cur.copy(recoveryKey = key))
        key
    }

    /** 导入恢复码(加入已有的同步库)。已有不同密钥时拒绝覆盖,须先 [clearKey]。 */
    fun importKey(text: String) = synchronized(lock) {
        val cur = get()
        val key = try {
            Crypto.encodeRecoveryKey(Crypto.decodeRecoveryKey(text)) // 解码再编码 = 规范化并校验校验和
        } catch (e: IllegalArgumentException) {
            throw SyncConfigException("恢复码无效:${e.message}", SyncConfigError.INVALID_RECOVERY_KEY)
        }
        if (cur.recoveryKey.isNotEmpty() && cur.recoveryKey != key) throw SyncConfigException("已经存在另一把密钥;请先清除再导入", SyncConfigError.KEY_EXISTS)
        write(cur.copy(recoveryKey = key))
    }

    fun exportKey(): String = synchronized(lock) {
        get().recoveryKey.ifEmpty { throw SyncConfigException("尚未设置密钥", SyncConfigError.NO_KEY) }
    }

    /** 清除密钥并停用同步。云端数据不受影响(它们用旧密钥加密,用旧恢复码仍可找回)。 */
    fun clearKey() = synchronized(lock) { write(get().copy(recoveryKey = "", enabled = false)) }

    /** 清掉全部配置(含令牌与密钥)。 */
    fun wipe() = synchronized(lock) {
        configFile.delete()
        secretsFile.delete()
        cache = SyncConfig()
        unreadable = false
    }

    // ── 文件 ──────────────────────────────────────────────────────────────────

    private fun load(): SyncConfig {
        unreadable = false
        var enabled = false
        var serverUrl = ""
        if (configFile.exists()) {
            try {
                val o = json.parseToJsonElement(configFile.readText()).jsonObject
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                serverUrl = o["serverUrl"]?.jsonPrimitive?.contentOrNull ?: ""
            } catch (_: Exception) {
                quarantine(configFile)
            }
        }
        var token = ""
        var key = ""
        if (secretsFile.exists()) {
            try {
                val o = json.parseToJsonElement(String(box.open(secretsFile.readBytes()), Charsets.UTF_8)).jsonObject
                token = o["deviceToken"]?.jsonPrimitive?.contentOrNull ?: ""
                key = o["recoveryKey"]?.jsonPrimitive?.contentOrNull ?: ""
            } catch (_: Exception) {
                // 密文解不开:可能是用户唯一的密钥副本,不删除,改名留存;界面据此提示重新输入。
                unreadable = true
                quarantine(secretsFile)
            }
        }
        return SyncConfig(enabled, serverUrl, token, key)
    }

    private fun write(next: SyncConfig) {
        dir.mkdirs()
        atomicWrite(configFile, buildJsonObject { put("v", 1); put("enabled", next.enabled); put("serverUrl", next.serverUrl) }.toString().toByteArray())
        if (next.deviceToken.isEmpty() && next.recoveryKey.isEmpty()) {
            secretsFile.delete()
        } else {
            val secrets = buildJsonObject { put("deviceToken", next.deviceToken); put("recoveryKey", next.recoveryKey) }
            atomicWrite(secretsFile, box.seal(secrets.toString().toByteArray(Charsets.UTF_8)))
        }
        unreadable = false
        cache = next
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "cannot replace ${target.name}" }
        }
    }

    private fun quarantine(f: File) {
        runCatching { f.renameTo(File(f.path + ".corrupt-${System.currentTimeMillis()}")) }
    }

    companion object {
        /** 只接受 http(s),不允许内嵌凭据;去掉末尾斜杠、查询串与片段。 */
        fun normalizeServerUrl(input: String): String {
            val u = try {
                URI(input.trim())
            } catch (_: Exception) {
                throw SyncConfigException("服务器地址不是合法的 URL", SyncConfigError.INVALID_URL)
            }
            val scheme = u.scheme?.lowercase()
            if (scheme != "http" && scheme != "https" || u.host.isNullOrEmpty()) throw SyncConfigException("服务器地址必须以 http:// 或 https:// 开头", SyncConfigError.INVALID_URL)
            if (u.userInfo != null) throw SyncConfigException("服务器地址不能内嵌用户名或密码(请使用设备令牌)", SyncConfigError.INVALID_URL)
            val port = if (u.port >= 0) ":${u.port}" else ""
            return "$scheme://${u.host}$port${u.rawPath.orEmpty().trimEnd('/')}"
        }

        fun keyFingerprintOf(recoveryKey: String): String? {
            if (recoveryKey.isEmpty()) return null
            return try {
                val master = Crypto.decodeRecoveryKey(recoveryKey)
                val h = MessageDigest.getInstance("SHA-256").apply { update("rikkasync/keyid/v0".toByteArray()); update(master) }.digest()
                val hex = h.joinToString("") { "%02x".format(it) }
                "${hex.substring(0, 4)}-${hex.substring(4, 8)}".uppercase()
            } catch (_: Exception) {
                null
            }
        }
    }
}

