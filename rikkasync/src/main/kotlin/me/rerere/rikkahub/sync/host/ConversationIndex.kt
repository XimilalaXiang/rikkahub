package me.rerere.rikkahub.sync.host

import kotlinx.coroutines.delay
import me.rerere.rikkahub.sync.engine.LocalRef
import me.rerere.rikkahub.sync.state.SyncStateDb

/**
 * HostAdapter.scan() 的会话部分(docs/04 §4.1)。
 *
 * 做法:触发器标脏(DirtyTracker)+ 指纹缓存(fp_cache)+ 分摊式深度校验:
 *  - 只有被标脏的会话才重新读全文算哈希;其余直接用缓存值,所以 scan 的稳态成本只是一次轻量查询。
 *  - 每轮额外对"最久没校验过"的 N 个会话重算哈希:触发器万一失效(Room 迁移重建了表、库被替换),最坏也会在
 *    总数/N 轮内最终一致。
 *  - 首次(缓存为空)会对全部会话算一遍,期间每处理一批就让出一次,不阻塞调用方。
 *
 * 顺序很重要:**先领取(清除)脏标记,再读取并哈希**;若先读后清,读取之后、清除之前发生的写入会被漏掉。
 */
class ConversationIndex(
    private val db: SqlDb,
    private val syncDb: SyncStateDb,
    private val tracker: DirtyTracker = DirtyTracker(db),
    /** 会话正被使用(生成中等)时为 true:保持旧指纹、脏标记留到下次。 */
    private val isBusy: (conversationId: String) -> Boolean = { false },
    private val auditBatch: Int = 25,
    private val yieldEvery: Int = 50,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 测试/诊断钩子:每次真正重算指纹时调用。 */
    private val onCompute: ((conversationId: String, reason: String) -> Unit)? = null,
) {
    private val kind = DirtyTracker.KIND

    // 深度校验按 verified_at 升序轮转。若直接用墙钟毫秒,同一毫秒内的并列会按 id 排序,永远选中同样的几个(饥饿);
    // 系统时间被往回调也会打乱顺序。所以用单调递增的序号:取墙钟与"已发出的最大值 + 1"的较大者,
    // 并以缓存里已有的最大 verified_at 为种子(跨进程重启、时钟回拨都不会让新戳比旧戳小)。
    private var lastStamp = -1L
    private fun stamp(): Long {
        if (lastStamp < 0) lastStamp = syncDb.maxVerifiedAt(kind)
        lastStamp = maxOf(now(), lastStamp + 1)
        return lastStamp
    }

    suspend fun scan(): List<LocalRef> {
        tracker.install()

        // 候选:有节点的会话,或曾经同步过的会话(从未同步过的空会话——新建后还没有任何消息——不推送;
        // 一旦曾经同步过,变空也仍是同一个会话,不能被当成删除)。
        val rows = db.query("SELECT c.id, EXISTS (SELECT 1 FROM message_node n WHERE n.conversation_id = c.id) FROM ConversationEntity c") { it.text(0) to (it.long(1) == 1L) }
        val claimed = tracker.claim().toSet()
        var released = false
        fun rollbackClaim() {
            if (!released) {
                released = true
                tracker.mark(claimed)
            }
        }

        try {
            val eligible = LinkedHashSet<String>()
            for ((id, hasNodes) in rows) if (hasNodes || syncDb.getFingerprint(kind, id) != null) eligible.add(id)

            // 不再合格的(已删除)清掉缓存。
            for (id in syncDb.listFingerprintIds(kind)) if (id !in eligible) syncDb.deleteFingerprint(kind, id)

            // 需要重算的集合:脏 > 没缓存 > 抽检。
            val reasons = LinkedHashMap<String, String>()
            for (id in eligible) if (id in claimed) reasons[id] = "dirty"
            for (id in eligible) if (id !in reasons && syncDb.getFingerprint(kind, id) == null) reasons[id] = "uncached"
            for (id in syncDb.oldestVerified(kind, auditBatch)) if (id in eligible && id !in reasons) reasons[id] = "audit"

            val deferred = ArrayList<String>()
            val fresh = HashMap<String, LocalRef>()
            var computed = 0
            for ((id, reason) in reasons) {
                if (isBusy(id)) {
                    deferred.add(id)
                    continue
                }
                val fp = ConversationFingerprint.compute(db, id)
                onCompute?.invoke(id, reason)
                if (fp == null) {
                    syncDb.deleteFingerprint(kind, id)
                    continue
                }
                syncDb.setFingerprint(kind, id, fp.first, stamp())
                fresh[id] = LocalRef(AndroidWireContract.CONVERSATION_TYPE, id, fp.first, fp.second)
                if (++computed % yieldEvery == 0) delay(1)
            }

            // 忙的会话:保持缓存里的旧指纹(引擎会因 isBusy 跳过它),脏标记留到它空闲之后。
            if (deferred.isNotEmpty()) tracker.mark(deferred.filter { it in claimed })
            released = true

            // 组装结果:重算过的用新值,其余用缓存。没有缓存的忙会话暂不报告(还不知道它的指纹)。
            val refs = ArrayList<LocalRef>()
            for (id in eligible) {
                fresh[id]?.let { refs.add(it); continue }
                val cached = syncDb.getFingerprint(kind, id) ?: continue
                val updatedAt = db.queryOne("SELECT update_at FROM ConversationEntity WHERE id = ?", listOf(id)) { it.long(0) }
                refs.add(LocalRef(AndroidWireContract.CONVERSATION_TYPE, id, cached, updatedAt))
            }
            return refs
        } catch (t: Throwable) {
            rollbackClaim() // 处理中途失败:把领取的标记还回去,绝不丢
            throw t
        }
    }
}
