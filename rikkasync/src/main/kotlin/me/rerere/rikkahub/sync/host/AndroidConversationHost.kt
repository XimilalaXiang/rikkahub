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
 * Android 端的 HostAdapter:conversation(含附件),可选 folder([FolderSink])与 assistants([AssistantSink])。
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
    /** 文件夹的落库接口;提供则同步 `folder` 实体(阶段 2),不提供则只同步会话。 */
    folderSink: FolderSink? = null,
    /** 助手的落库接口;提供则同步助手(`settings.item`),不提供则不同步。 */
    assistantSink: AssistantSink? = null,
) : HostAdapter {
    private val tracker = DirtyTracker(db)
    private val index = ConversationIndex(db, syncDb, tracker, isBusy, auditBatch, now = now)
    private val bridge = attachments?.let { AttachmentBridge(syncDb, it) }
    private val applier = ConversationApplier(db, syncDb, sink, isBusy, bridge, tracker, now, newId)
    private val folders = folderSink?.let { FolderHost(db, it) }
    private val assistants = assistantSink?.let { AssistantHost(db, syncDb, it, newId) }

    override fun isFmtSupported(type: String, fmt: String) = when (type) {
        AndroidWireContract.CONVERSATION_TYPE -> fmt == AndroidWireContract.FMT
        AndroidWireContract.FOLDER_TYPE -> folders != null && fmt == AndroidWireContract.FMT
        AssistantContract.TYPE -> assistants != null && fmt == AssistantContract.FMT
        else -> false
    }

    // 带 +folder/+assistants:加入某种实体支持的那一版签名会变,之前因"不支持"被跳过的记录会被重新拉取。
    override fun supportSignature() = "android-$appVersion+${AndroidWireContract.FMT}" +
        (if (folders != null) "+folder" else "") + (if (assistants != null) "+${AssistantContract.FMT}" else "")

    override suspend fun scan(): List<LocalRef> = index.scan() + folders?.scan().orEmpty() + assistants?.scan().orEmpty()

    override suspend fun read(type: String, id: String, ctx: HostContext): EntitySnapshot? = when (type) {
        AndroidWireContract.CONVERSATION_TYPE -> ConversationWire.read(db, id, ctx, bridge)
        AndroidWireContract.FOLDER_TYPE -> folders?.read(id)
        AssistantContract.TYPE -> assistants?.read(id)
        else -> null
    }

    override suspend fun apply(remote: RemoteEntity, io: ApplyIO): String = when {
        remote.type == AndroidWireContract.FOLDER_TYPE && folders != null -> folders.apply(remote)
        remote.type == AssistantContract.TYPE && assistants != null -> assistants.apply(remote)
        else -> applier.apply(remote, io)
    }

    override suspend fun remove(type: String, id: String) {
        when (type) {
            AndroidWireContract.CONVERSATION_TYPE -> applier.remove(id)
            AndroidWireContract.FOLDER_TYPE -> folders?.remove(id)
            AssistantContract.TYPE -> assistants?.remove(id)
        }
    }

    override suspend fun makeConflictCopy(type: String, id: String) {
        when (type) {
            AndroidWireContract.CONVERSATION_TYPE -> applier.makeConflictCopy(id)
            AssistantContract.TYPE -> assistants?.makeConflictCopy(id)
        }
    }

    override fun isBusy(type: String, id: String) = type == AndroidWireContract.CONVERSATION_TYPE && isBusy(id)

    override suspend fun compareFirstContact(local: LocalRef, remote: RemoteSnapshotInfo): FirstContactRelation? =
        when {
            local.type == AndroidWireContract.FOLDER_TYPE && folders != null -> folders.compareFirstContact(local, remote)
            local.type == AssistantContract.TYPE && assistants != null -> assistants.compareFirstContact(local, remote)
            else -> Lineage.compareFirstContact(db, local, remote)
        }
}
