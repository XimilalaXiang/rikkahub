package me.rerere.rikkahub.sync.state

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import me.rerere.rikkahub.sync.engine.EntityState
import me.rerere.rikkahub.sync.engine.StateEntry
import me.rerere.rikkahub.sync.engine.StateStore
import java.io.File

/** 状态库的 schema 比本程序更新(用户降级了应用)。不隔离、不删除,交给调用方提示用户升级。 */
class SyncDbTooNewException(val found: Int) : Exception("sync state db schema $found is newer than supported $SCHEMA_VERSION")

const val SCHEMA_VERSION = 1

/**
 * 云同步的本地状态库(ADR-009:独立文件,不放进上游的 Room 库)。
 *
 * 一个 SQLite 文件里放:引擎簿记(游标、每实体 {version, seq, fingerprint, deleted, skipped}、meta)和
 * 会话内容哈希缓存 fp_cache(触发器标脏 + 分摊式深度校验的缓存)。
 *
 * **可整库重建**:这里没有任何唯一副本——数据都在服务器(加密)和应用主库里。所以损坏时直接隔离并重建
 * (走"首次接触"路径重新对账),而不是让同步整体不可用。见 [openOrRecover]。
 *
 * 每次写入立即提交:进程随时被杀也不丢状态。线程安全:所有访问串行化。
 */
class SyncStateDb(private val conn: SQLiteConnection) : StateStore, AutoCloseable {
    private val lock = Any()

    init {
        // WAL:读写互不阻塞;NORMAL:崩溃时最多丢最后一个事务(状态可重建,可接受)。
        // 用 execSQL(只 step 一次):journal_mode 返回一行结果,Android 的框架驱动对它再 step 一次会抛 CursorIndexOutOfBoundsException(模拟器实测)。
        conn.execSQL("PRAGMA journal_mode = WAL")
        conn.execSQL("PRAGMA synchronous = NORMAL")
        val version = conn.queryOne("PRAGMA user_version") { it.getLong(0).toInt() } ?: 0
        if (version > SCHEMA_VERSION) {
            conn.close()
            throw SyncDbTooNewException(version)
        }
        // 注意:execSQL 一次只执行一条语句(prepare 只编译第一条),所以逐条执行。
        conn.execSQL("CREATE TABLE IF NOT EXISTS sync_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_entity (" +
                "type TEXT NOT NULL, id TEXT NOT NULL, version INTEGER NOT NULL, seq INTEGER NOT NULL, fingerprint TEXT NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0, skipped INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (type, id))",
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS fp_cache (kind TEXT NOT NULL, id TEXT NOT NULL, fingerprint TEXT NOT NULL, " +
                "verified_at INTEGER NOT NULL, PRIMARY KEY (kind, id))",
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS idx_fp_cache_verified ON fp_cache(kind, verified_at)")
        if (version < SCHEMA_VERSION) conn.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
    }

    override fun close() = synchronized(lock) { conn.close() }

    // ── StateStore ──────────────────────────────────────────────────────────

    override fun getCursor(): Long = getMeta("cursor")?.toLongOrNull() ?: 0L
    override fun setCursor(seq: Long) = setMeta("cursor", seq.toString())

    override fun get(type: String, id: String): EntityState? = synchronized(lock) {
        conn.queryOne("SELECT version, seq, fingerprint, deleted, skipped FROM sync_entity WHERE type = ? AND id = ?", {
            it.bindText(1, type)
            it.bindText(2, id)
        }) { EntityState(it.getLong(0), it.getLong(1), it.getText(2), it.getLong(3) != 0L, it.getLong(4) != 0L) }
    }

    override fun set(type: String, id: String, state: EntityState) = synchronized(lock) {
        conn.run(
            "INSERT INTO sync_entity(type, id, version, seq, fingerprint, deleted, skipped) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(type, id) DO UPDATE SET version = excluded.version, seq = excluded.seq, fingerprint = excluded.fingerprint, " +
                "deleted = excluded.deleted, skipped = excluded.skipped",
        ) {
            it.bindText(1, type)
            it.bindText(2, id)
            it.bindLong(3, state.version)
            it.bindLong(4, state.seq)
            it.bindText(5, state.fingerprint)
            it.bindLong(6, if (state.deleted) 1 else 0)
            it.bindLong(7, if (state.skipped) 1 else 0)
        }
    }

    override fun delete(type: String, id: String) = synchronized(lock) {
        conn.run("DELETE FROM sync_entity WHERE type = ? AND id = ?") {
            it.bindText(1, type)
            it.bindText(2, id)
        }
    }

    override fun entries(): List<StateEntry> = synchronized(lock) {
        conn.query("SELECT type, id, version, seq, fingerprint, deleted, skipped FROM sync_entity ORDER BY type, id") {
            StateEntry(it.getText(0), it.getText(1), EntityState(it.getLong(2), it.getLong(3), it.getText(4), it.getLong(5) != 0L, it.getLong(6) != 0L))
        }
    }

    override fun getMeta(key: String): String? = synchronized(lock) {
        conn.queryOne("SELECT value FROM sync_meta WHERE key = ?", { it.bindText(1, key) }) { it.getText(0) }
    }

    override fun setMeta(key: String, value: String) = synchronized(lock) {
        conn.run("INSERT INTO sync_meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value") {
            it.bindText(1, key)
            it.bindText(2, value)
        }
    }

    /** 忘记一切(游标、实体状态、meta、指纹缓存)。恢复备份之后调用(本地数据发生了回滚)。 */
    override fun reset() = synchronized(lock) {
        conn.transaction {
            conn.execSQL("DELETE FROM sync_entity")
            conn.execSQL("DELETE FROM sync_meta")
            conn.execSQL("DELETE FROM fp_cache")
        }
    }

    // ── fp_cache:内容指纹缓存(会话扫描用) ──────────────────────────────────────

    fun getFingerprint(kind: String, id: String): String? = synchronized(lock) {
        conn.queryOne("SELECT fingerprint FROM fp_cache WHERE kind = ? AND id = ?", { it.bindText(1, kind); it.bindText(2, id) }) { it.getText(0) }
    }

    fun setFingerprint(kind: String, id: String, fingerprint: String, verifiedAt: Long) = synchronized(lock) {
        conn.run(
            "INSERT INTO fp_cache(kind, id, fingerprint, verified_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(kind, id) DO UPDATE SET fingerprint = excluded.fingerprint, verified_at = excluded.verified_at",
        ) {
            it.bindText(1, kind)
            it.bindText(2, id)
            it.bindText(3, fingerprint)
            it.bindLong(4, verifiedAt)
        }
    }

    fun deleteFingerprint(kind: String, id: String) = synchronized(lock) {
        conn.run("DELETE FROM fp_cache WHERE kind = ? AND id = ?") { it.bindText(1, kind); it.bindText(2, id) }
    }

    fun listFingerprintIds(kind: String): List<String> = synchronized(lock) {
        conn.query("SELECT id FROM fp_cache WHERE kind = ?", { it.bindText(1, kind) }) { it.getText(0) }
    }

    /** 最久没被深度校验过的 n 个(分摊式深度校验的取样来源)。 */
    fun oldestVerified(kind: String, n: Int): List<String> = synchronized(lock) {
        conn.query("SELECT id FROM fp_cache WHERE kind = ? ORDER BY verified_at ASC, id ASC LIMIT ?", { it.bindText(1, kind); it.bindLong(2, n.toLong()) }) { it.getText(0) }
    }

    fun maxVerifiedAt(kind: String): Long = synchronized(lock) {
        conn.queryOne("SELECT COALESCE(MAX(verified_at), 0) FROM fp_cache WHERE kind = ?", { it.bindText(1, kind) }) { it.getLong(0) } ?: 0L
    }

    fun countFingerprints(kind: String): Int = synchronized(lock) {
        conn.queryOne("SELECT COUNT(*) FROM fp_cache WHERE kind = ?", { it.bindText(1, kind) }) { it.getLong(0).toInt() } ?: 0
    }

    companion object {
        /** 打开状态库;文件损坏时隔离后重建(可整库重建,见类注释)。schema 过新则抛 [SyncDbTooNewException],不动文件。 */
        fun openOrRecover(driver: SQLiteDriver, path: String, onRecovered: (error: Throwable, quarantinedTo: String) -> Unit = { _, _ -> }): SyncStateDb {
            try {
                val db = SyncStateDb(driver.open(path))
                // 打开成功不代表页没坏:做一次快速完整性检查,损坏会在这里暴露而不是在某次同步中途。
                val check = db.synchronizedQuickCheck()
                if (check != "ok") {
                    db.close()
                    throw IllegalStateException("quick_check: $check")
                }
                return db
            } catch (e: SyncDbTooNewException) {
                throw e
            } catch (e: Throwable) {
                val quarantine = "$path.corrupt-${System.currentTimeMillis()}"
                for (suffix in listOf("", "-wal", "-shm")) {
                    val f = File(path + suffix)
                    if (f.exists()) runCatching { f.renameTo(File(quarantine + suffix)) }
                }
                val fresh = SyncStateDb(driver.open(path))
                onRecovered(e, quarantine)
                return fresh
            }
        }
    }

    private fun synchronizedQuickCheck(): String? = synchronized(lock) { conn.queryOne("PRAGMA quick_check") { it.getText(0) } }
}
