package me.rerere.rikkahub.sync.host

import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.AppDatabase

/**
 * [SqlDb] 在应用主库(Room)上的实现:直接走 `openHelper.writableDatabase`,**不经过任何 DAO**,也不改任何 DAO
 * (上游的 `AppDatabaseFactory.onOpen` 创建 FTS 虚拟表、`MessageNodeDAO.getTokenStatsRaw(SupportSQLiteQuery)` 是同样的做法)。
 * SupportSQLiteDatabase 自带线程安全与连接管理。
 */
class RoomSqlDb(private val database: AppDatabase) : SqlDb {
    private val db: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

    private fun bindable(args: List<Any?>): Array<Any?> = Array(args.size) {
        when (val a = args[it]) {
            is Boolean -> if (a) 1L else 0L
            is Int -> a.toLong()
            else -> a
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, bindable(args))
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> {
        val cursor = db.query(sql, bindable(args))
        cursor.use { c ->
            val row = object : SqlRow {
                override fun text(i: Int): String = c.getString(i) ?: ""
                override fun long(i: Int): Long = c.getLong(i)
                override fun isNull(i: Int): Boolean = c.isNull(i)
            }
            val out = ArrayList<T>()
            while (c.moveToNext()) out.add(map(row))
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val result = block()
            d.setTransactionSuccessful()
            return result
        } finally {
            d.endTransaction()
        }
    }
}
