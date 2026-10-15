package me.rerere.rikkahub.sync.host

/**
 * 会话库的行级脏标记(ADR-001 修订,docs/04 §4.1)。
 *
 * 为什么不用 update_at / 聚合列:Android 的 update_at 运行时只在"生成结束"时推进(编辑/删除消息、切换分支、翻译、
 * 置顶、移动文件夹都不推进),聚合列对"同字节数的改写"是盲区。触发器在 SQLite 层捕获**任何**写路径,而且不改任何
 * 上游源文件/DAO。表与触发器建在应用主库里,只引用主键列 id / conversation_id(永不改名)。
 *
 * "领取脏标记"是单个事务:领取之后发生的写入会重新标脏,不会丢。
 */
class DirtyTracker(private val db: SqlDb) {
    companion object {
        const val KIND = "conversation"

        /** 触发器名带版本前缀:改触发器体时升级版本号,安装时会删掉旧版本的触发器(IF NOT EXISTS 不会替换同名旧触发器)。 */
        const val TRIGGER_PREFIX = "sync_v2_"

        // ⚠️ 触发器体里**不能**用 INSERT OR REPLACE / ON CONFLICT 来"覆盖已有标记":SQLite 规定触发器体内的冲突策略
        // 会被触发它的外层语句的策略覆盖,而 Room 的 @Insert(onConflict = REPLACE) 是 INSERT OR REPLACE,@Update 是
        // UPDATE OR ABORT。标记已存在时,触发器里的 INSERT OR REPLACE 会以 UNIQUE 冲突失败——也就是用户自己的那次写入
        // 被触发器弄失败(桌面 D3.1 的测试抓到的真实缺陷)。正确写法:先 DELETE 再 INSERT,与外层策略无关。
        private fun mark(idExpr: String) =
            "DELETE FROM sync_dirty WHERE entity_id = $idExpr; INSERT INTO sync_dirty VALUES ($idExpr, '$KIND', strftime('%s','now'));"

        private val DDL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS sync_dirty (entity_id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, ts INTEGER NOT NULL)",
            "CREATE TRIGGER IF NOT EXISTS ${TRIGGER_PREFIX}conv_ins AFTER INSERT ON ConversationEntity BEGIN ${mark("NEW.id")} END",
            "CREATE TRIGGER IF NOT EXISTS ${TRIGGER_PREFIX}conv_upd AFTER UPDATE ON ConversationEntity BEGIN ${mark("NEW.id")} END",
            "CREATE TRIGGER IF NOT EXISTS ${TRIGGER_PREFIX}conv_del AFTER DELETE ON ConversationEntity BEGIN ${mark("OLD.id")} END",
            "CREATE TRIGGER IF NOT EXISTS ${TRIGGER_PREFIX}node_ins AFTER INSERT ON message_node BEGIN ${mark("NEW.conversation_id")} END",
            "CREATE TRIGGER IF NOT EXISTS ${TRIGGER_PREFIX}node_upd AFTER UPDATE ON message_node BEGIN ${mark("NEW.conversation_id")} END",
            "CREATE TRIGGER IF NOT EXISTS ${TRIGGER_PREFIX}node_del AFTER DELETE ON message_node BEGIN ${mark("OLD.conversation_id")} END",
        )
    }

    /**
     * 幂等安装。应用每次启动、每次 scan 前都调用:Room 自动迁移若重建了表会丢掉该表的触发器、备份恢复/库被替换后
     * 触发器也可能不在了,而 IF NOT EXISTS 的成本是微秒级。
     */
    fun install() {
        for (sql in DDL) db.exec(sql)
        // 清掉旧版本的同步触发器(名字以 sync_ 开头但不是当前版本),防止新旧并存导致重复标脏或旧体的缺陷残留。
        val stale = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync\\_%' ESCAPE '\\' AND name NOT LIKE ? ESCAPE '\\'",
            listOf(TRIGGER_PREFIX.replace("_", "\\_") + "%"),
        ) { it.text(0) }
        for (name in stale) db.exec("DROP TRIGGER IF EXISTS \"$name\"")
    }

    /** 原子地取走全部会话脏标记(返回并清除)。之后的写入会重新标脏。 */
    fun claim(): List<String> = db.transaction {
        val ids = db.query("SELECT entity_id FROM sync_dirty WHERE kind = ?", listOf(KIND)) { it.text(0) }
        if (ids.isNotEmpty()) db.exec("DELETE FROM sync_dirty WHERE kind = ?", listOf(KIND))
        ids
    }

    /** 把 id 重新标脏(领取后处理失败、或会话正忙需要稍后再处理时用)。 */
    fun mark(ids: Iterable<String>) {
        db.transaction {
            for (id in ids) {
                db.exec("DELETE FROM sync_dirty WHERE entity_id = ?", listOf(id))
                db.exec("INSERT INTO sync_dirty VALUES (?, ?, strftime('%s','now'))", listOf(id, KIND))
            }
        }
    }

    fun count(): Int = db.queryOne("SELECT COUNT(*) FROM sync_dirty WHERE kind = ?", listOf(KIND)) { it.long(0).toInt() } ?: 0

    /** 清掉某会话的脏标记(同步自己的写入不是用户编辑)。表不存在时先安装,所以不依赖调用顺序。 */
    fun clear(id: String) {
        install()
        db.exec("DELETE FROM sync_dirty WHERE entity_id = ? AND kind = ?", listOf(id, KIND))
    }
}
