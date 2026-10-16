package me.rerere.rikkahub.sync.host

import me.rerere.rikkahub.sync.engine.ApplyIO
import me.rerere.rikkahub.sync.engine.EntitySnapshot
import me.rerere.rikkahub.sync.engine.FirstContactRelation
import me.rerere.rikkahub.sync.engine.HostAdapter
import me.rerere.rikkahub.sync.engine.HostContext
import me.rerere.rikkahub.sync.engine.LocalRef
import me.rerere.rikkahub.sync.engine.RemoteEntity
import me.rerere.rikkahub.sync.engine.RemoteSnapshotInfo
import me.rerere.rikkahub.sync.state.SyncStateDb
import java.util.UUID

/**
 * Android 端的 HostAdapter(阶段 1:只有 conversation 一种实体;附件、文件夹见阶段 2)。
 * 只做组装,各部分逻辑在同包的对应文件里;与上游业务的唯一耦合点是 [ConversationSink] 与 [isBusy]。
 */
class AndroidConversationHost(
    private val db: SqlDb,
    private val syncDb: SyncStateDb,
    sink: ConversationSink,
    /** 会话正被使用(生成中、有排队消息、处理中……)。**不要**为查询而创建 session。 */
    private val isBusy: (conversationId: String) -> Boolean,
    /** 应用版本,进入 supportSignature:升级后曾被跳过的记录会被重新拉取。 */
    private val appVersion: String,
    /** 本机文件管理;提供则同步附件,不提供则只同步文本。 */
    attachments: AttachmentStore? = null,
    now: () -> Long = { System.currentTimeMillis() },
    newId: () -> String = { UUID.randomUUID().toString() },
    auditBatch: Int = 25,
) : HostAdapter {
    private val tracker = DirtyTracker(db)
    private val index = ConversationIndex(db, syncDb, tracker, isBusy, auditBatch, now = now)
    private val bridge = attachments?.let { AttachmentBridge(syncDb, it) }
    private val applier = ConversationApplier(db, syncDb, sink, isBusy, bridge, tracker, now, newId)

    override fun isFmtSupported(type: String, fmt: String) = type == AndroidWireContract.CONVERSATION_TYPE && fmt == AndroidWireContract.FMT

    override fun supportSignature() = "android-$appVersion+${AndroidWireContract.FMT}"

    override suspend fun scan(): List<LocalRef> = index.scan()

    override suspend fun read(type: String, id: String, ctx: HostContext): EntitySnapshot? =
        if (type == AndroidWireContract.CONVERSATION_TYPE) ConversationWire.read(db, id, ctx, bridge) else null

    override suspend fun apply(remote: RemoteEntity, io: ApplyIO): String = applier.apply(remote, io)

    override suspend fun remove(type: String, id: String) {
        if (type == AndroidWireContract.CONVERSATION_TYPE) applier.remove(id)
    }

    override suspend fun makeConflictCopy(type: String, id: String) {
        if (type == AndroidWireContract.CONVERSATION_TYPE) applier.makeConflictCopy(id)
    }

    override fun isBusy(type: String, id: String) = type == AndroidWireContract.CONVERSATION_TYPE && isBusy(id)

    override suspend fun compareFirstContact(local: LocalRef, remote: RemoteSnapshotInfo): FirstContactRelation? =
        Lineage.compareFirstContact(db, local, remote)
}
