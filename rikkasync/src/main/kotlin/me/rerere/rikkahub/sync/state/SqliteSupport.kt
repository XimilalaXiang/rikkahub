package me.rerere.rikkahub.sync.state

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

// androidx.sqlite 驱动接口的几个小工具(同步 API,1 起始的绑定下标)。

internal inline fun <T> SQLiteConnection.query(sql: String, bind: (SQLiteStatement) -> Unit = {}, map: (SQLiteStatement) -> T): List<T> {
    val stmt = prepare(sql)
    try {
        bind(stmt)
        val out = ArrayList<T>()
        while (stmt.step()) out.add(map(stmt))
        return out
    } finally {
        stmt.close()
    }
}

internal inline fun <T> SQLiteConnection.queryOne(sql: String, bind: (SQLiteStatement) -> Unit = {}, map: (SQLiteStatement) -> T): T? {
    val stmt = prepare(sql)
    try {
        bind(stmt)
        return if (stmt.step()) map(stmt) else null
    } finally {
        stmt.close()
    }
}

internal inline fun SQLiteConnection.run(sql: String, bind: (SQLiteStatement) -> Unit = {}) {
    val stmt = prepare(sql)
    try {
        bind(stmt)
        while (stmt.step()) { /* 丢弃结果行 */ }
    } finally {
        stmt.close()
    }
}

/** 事务:成功提交,抛错回滚。 */
internal inline fun <T> SQLiteConnection.transaction(block: () -> T): T {
    execSQL("BEGIN IMMEDIATE")
    val result = try {
        block()
    } catch (t: Throwable) {
        runCatching { execSQL("ROLLBACK") }
        throw t
    }
    execSQL("COMMIT")
    return result
}
