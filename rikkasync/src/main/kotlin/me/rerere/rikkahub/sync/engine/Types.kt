package me.rerere.rikkahub.sync.engine

import kotlinx.serialization.json.JsonElement

// 平台无关的引擎与宿主之间的契约。与 TypeScript 的 core/src/types.ts 一一对应(docs/03-engine-and-host.md)。

/** 一个本地可同步实体的轻量句柄。 */
data class LocalRef(
    val type: String,
    val id: String,
    /**
     * 该实体所有被同步字段的稳定摘要:任何被同步字段变化都必须改变它(包括不推进修改时间的字段),
     * 数据没变时必须完全相同。每次 scan 都会为每个实体计算,所以必须便宜。
     */
    val fingerprint: String,
    /** 毫秒修改时间(若宿主有)。只用于首次接触时的兜底比较。 */
    val updatedAt: Long? = null,
)

class BlobUpload(
    /** `ctx.blobId(明文)`。 */
    val id: String,
    val read: suspend () -> ByteArray,
)

class EntitySnapshot(
    /** `data` 的格式标签,例如 "android-room-30"。 */
    val fmt: String,
    /** 可 JSON 序列化的负载,对引擎不透明。 */
    val data: JsonElement,
    /** 恰好描述 `data` 内容的指纹(可能与 scan 时不同,若其间发生了变化)。 */
    val fingerprint: String,
    val updatedAt: Long?,
    /** `data` 里引用的附件(已改写成块令牌)。 */
    val blobs: List<BlobUpload> = emptyList(),
)

class RemoteEntity(
    val type: String,
    val id: String,
    val fmt: String,
    val data: JsonElement,
    val version: Long,
    val updatedAt: Long?,
    val blobs: List<String>,
)

interface HostContext {
    fun blobId(plaintext: ByteArray): String
}

interface ApplyIO {
    /** 下载并解密一个块;缺失则抛出。 */
    suspend fun fetchBlob(id: String): ByteArray
}

enum class FirstContactRelation {
    /** 内容相同(表示可能不同)→ 采纳,不推送也不复制。 */
    SAME,

    /** 本地谱系更新 → 保留本地并覆盖服务器。 */
    LOCAL_NEWER,

    /** 远端谱系更新 → 取远端,不留副本。 */
    REMOTE_NEWER,

    /** 无法判断/两边都改过 → 取远端,本地保留为冲突副本。 */
    DIVERGED,
}

interface HostAdapter {
    /** 本构建能否读取该 type 的 fmt。不支持的记录会被跳过,升级后重新处理。 */
    fun isFmtSupported(type: String, fmt: String): Boolean

    /** 当 isFmtSupported 的答案集合变化时必须变化(例如应用版本)。变化会触发一次完整重拉。 */
    fun supportSignature(): String

    /** 本地全部可同步实体。 */
    suspend fun scan(): List<LocalRef>

    /** 序列化一个实体;若自 scan 之后已消失则返回 null。 */
    suspend fun read(type: String, id: String, ctx: HostContext): EntitySnapshot?

    /**
     * 把远端实体 upsert 进本地,保留宿主为它保存的任何仅本地数据。
     * 返回落库结果按 scan() 口径的指纹(引擎据此不把它回声推送出去)。
     */
    suspend fun apply(remote: RemoteEntity, io: ApplyIO): String

    /** 因远端已删除而删除本地实体。 */
    suspend fun remove(type: String, id: String)

    /** 把实体当前的本地状态存成一个新实体,让随后的远端覆盖不丢任何东西。 */
    suspend fun makeConflictCopy(type: String, id: String)

    /** 实体正被使用时(如会话正在生成)为 true:此时既不能推送,也不能被远端覆盖。 */
    fun isBusy(type: String, id: String): Boolean = false

    /**
     * 首次接触:本地有一个从未同步过的实体,服务器也有。两端序列化通常不同(各平台有损方式不同),
     * 所以**引擎不得自己比较 data**,由宿主比较它们真正能比较的东西(id、结构、可见文本)。
     * 返回 null = 宿主没有实现,引擎回退到深度相等 + updatedAt。
     */
    suspend fun compareFirstContact(local: LocalRef, remote: RemoteSnapshotInfo): FirstContactRelation? = null
}

class RemoteSnapshotInfo(val fmt: String, val data: JsonElement, val updatedAt: Long?)

/**
 * apply() 抛出它表示该远端记录**永远**无法被本构建应用(数据畸形、宿主拒绝的形状)。引擎记录错误、
 * 标记跳过并让游标前进,而不是永远卡在它上面。其它异常按暂时性处理:游标保持,下一轮重试。
 */
class PermanentApplyException(message: String) : Exception(message)

data class EntityState(
    /** 最后已知远端记录的版本;推送时作为 base_version。 */
    val version: Long,
    /** 最后见过/应用/推送过的远端记录的全库序号;据此判断"已见过"。 */
    val seq: Long,
    val fingerprint: String,
    /** 最后已知的远端状态是墓碑。 */
    val deleted: Boolean = false,
    /** 远端记录存在但本构建无法应用。永不推送。 */
    val skipped: Boolean = false,
)

/** 引擎簿记的持久化。同步接口(SQLite/SharedPreferences 风格)。 */
interface StateStore {
    fun getCursor(): Long
    fun setCursor(seq: Long)
    fun get(type: String, id: String): EntityState?
    fun set(type: String, id: String, state: EntityState)
    fun delete(type: String, id: String)
    fun entries(): List<StateEntry>
    fun getMeta(key: String): String?
    fun setMeta(key: String, value: String)

    /** 忘记一切(游标、实体、meta)。宿主在本地回滚(如恢复备份)之后调用。 */
    fun reset()
}

data class StateEntry(val type: String, val id: String, val state: EntityState)

class MemoryStateStore : StateStore {
    private var cursor = 0L
    private val map = LinkedHashMap<Pair<String, String>, EntityState>()
    private val meta = HashMap<String, String>()

    override fun getCursor() = cursor
    override fun setCursor(seq: Long) {
        cursor = seq
    }

    override fun get(type: String, id: String) = map[type to id]
    override fun set(type: String, id: String, state: EntityState) {
        map[type to id] = state
    }

    override fun delete(type: String, id: String) {
        map.remove(type to id)
    }

    override fun entries() = map.map { (k, v) -> StateEntry(k.first, k.second, v) }
    override fun getMeta(key: String) = meta[key]
    override fun setMeta(key: String, value: String) {
        meta[key] = value
    }

    override fun reset() {
        cursor = 0
        map.clear()
        meta.clear()
    }
}

class SyncReport {
    var pulled = 0
    var applied = 0
    var pushed = 0
    var deletedLocal = 0
    var deletedRemote = 0
    var conflicts = 0
    var skippedUnsupported = 0
    var deferredBusy = 0
    val errors = mutableListOf<String>()

    override fun toString() =
        "SyncReport(pulled=$pulled, applied=$applied, pushed=$pushed, deletedLocal=$deletedLocal, deletedRemote=$deletedRemote, " +
            "conflicts=$conflicts, skippedUnsupported=$skippedUnsupported, deferredBusy=$deferredBusy, errors=$errors)"
}
