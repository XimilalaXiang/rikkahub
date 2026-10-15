package me.rerere.rikkahub.sync.host

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * 适配器逻辑对"应用主库"的最小访问面。生产环境由 app 模块用 Room 的 SupportSQLiteDatabase 实现(**不经过任何
 * DAO,不改任何 DAO**);测试里用 [ConnectionSqlDb] 在纯 JVM 的 SQLite 上跑,表结构取自 Room 导出的真实 schema。
 * 绑定参数是 String / Long / Int / Boolean / null。
 */
interface SqlDb {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>
    fun <T> transaction(block: () -> T): T
}

interface SqlRow {
    fun text(i: Int): String
    fun long(i: Int): Long
    fun isNull(i: Int): Boolean
}

internal fun <T> SqlDb.queryOne(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): T? = query(sql, args, map).firstOrNull()

/** [SqlDb] 在 androidx.sqlite 连接上的实现(JVM 测试与任何用驱动接口的场景)。 */
class ConnectionSqlDb(private val conn: SQLiteConnection) : SqlDb {
    private fun bind(stmt: SQLiteStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> stmt.bindNull(idx)
                is String -> stmt.bindText(idx, a)
                is Long -> stmt.bindLong(idx, a)
                is Int -> stmt.bindLong(idx, a.toLong())
                is Boolean -> stmt.bindLong(idx, if (a) 1 else 0)
                else -> throw IllegalArgumentException("unsupported bind type ${a::class}")
            }
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        val stmt = conn.prepare(sql)
        try {
            bind(stmt, args)
            while (stmt.step()) { /* 丢弃结果行 */ }
        } finally {
            stmt.close()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> {
        val stmt = conn.prepare(sql)
        try {
            bind(stmt, args)
            val row = object : SqlRow {
                override fun text(i: Int) = stmt.getText(i)
                override fun long(i: Int) = stmt.getLong(i)
                override fun isNull(i: Int) = stmt.isNull(i)
            }
            val out = ArrayList<T>()
            while (stmt.step()) out.add(map(row))
            return out
        } finally {
            stmt.close()
        }
    }

    override fun <T> transaction(block: () -> T): T {
        conn.execSQL("BEGIN IMMEDIATE")
        val result = try {
            block()
        } catch (t: Throwable) {
            runCatching { conn.execSQL("ROLLBACK") }
            throw t
        }
        conn.execSQL("COMMIT")
        return result
    }
}
