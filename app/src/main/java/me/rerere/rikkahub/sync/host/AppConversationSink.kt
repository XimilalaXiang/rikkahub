package me.rerere.rikkahub.sync.host

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.ConversationSessionManager
import me.rerere.rikkahub.sync.engine.PermanentApplyException
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * [ConversationSink] 的生产实现 = 适配器与上游业务的**唯一**出口(ADR-007,docs/04 §4.3)。
 *
 * 按 session 状态分流(验证发现 F1):
 *  - 该会话**没有** session → 直接 `ConversationRepository.insert/updateConversation`(它维护全文索引与文件引用)。
 *  - 该会话**有** session 且不忙(用户正在查看、未生成)→ **必须**走 `ChatService.saveConversation`:它先
 *    `session.updateConversation(...)`(界面立刻看到新内容,之后的整对象保存也不会把它覆盖回去),再落库。
 *    副作用(⚠️ V15,需真机验证):`bindConfig(settings).fillModelSnapshots(settings)` 会改写内容;`checkFilesDelete`
 *    会删除新对象不再引用的本地文件;`dispatchNextQueuedMessage` 会检查队列。落库后以重新计算的指纹为准(H3)。
 *  - 该会话**忙** → 抛异常让引擎 HOLD,空闲后再应用,绝不在生成中覆盖。
 *  - 绝不**只**写数据库而让存活的 session 保持旧状态(H13)。
 *
 * **绝不能把可能让会话打不开的数据写进库**:上游解码没有 coerceInputValues,一个畸形字段会让整个会话打不开
 * (见 docs/00 §2.5)。所以落库前先完整解码;失败一律 [PermanentApplyException]。
 */
class AppConversationSink(
    private val repository: ConversationRepository,
    private val chatService: ChatService,
    private val sessions: ConversationSessionManager,
) : ConversationSink {

    override suspend fun save(write: ConversationWrite) {
        val uuid = try {
            Uuid.parse(write.id)
        } catch (e: IllegalArgumentException) {
            throw PermanentApplyException("会话 id 不是合法的 UUID: ${write.id}")
        }
        val session = sessions.get(uuid)
        check(session == null || !sessions.isConversationBusy(write.id)) { "conversation ${write.id} is busy" }

        // 保留本机的收藏标记(favorites 表是设备本地的,线上没有;内存里的 session 状态里有)。
        val favorites = session?.state?.value?.messageNodes?.filter { it.isFavorite }?.map { it.id }?.toSet().orEmpty()
        val conversation = try {
            toConversation(write, favorites)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PermanentApplyException) {
            throw e
        } catch (e: SerializationException) {
            throw PermanentApplyException("会话数据无法解码: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw PermanentApplyException("会话数据不合法: ${e.message}")
        }

        if (session != null) {
            chatService.saveConversation(uuid, conversation)
        } else if (repository.existsConversationById(uuid)) {
            repository.updateConversation(conversation)
        } else {
            repository.insertConversation(conversation)
        }
    }

    override suspend fun delete(id: String) {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return
        check(!sessions.isConversationBusy(id)) { "conversation $id is busy" }
        val existing = repository.getConversationById(uuid) ?: return
        repository.deleteConversation(existing)
    }

    private fun toConversation(write: ConversationWrite, favorites: Set<Uuid>): Conversation {
        fun s(k: String) = write.columns.getValue(k).jsonPrimitive.content
        fun n(k: String) = write.columns.getValue(k).jsonPrimitive.content.toLong()
        val entity = ConversationEntity(
            id = write.id,
            assistantId = s("assistant_id"),
            title = s("title"),
            nodes = "[]", // 遗留列:节点在 message_node 表里
            createAt = n("create_at"),
            updateAt = n("update_at"),
            chatSuggestions = s("suggestions"),
            isPinned = n("is_pinned") != 0L,
            customSystemPrompt = s("custom_system_prompt"),
            modeInjectionIds = s("mode_injection_ids"),
            lorebookIds = s("lorebook_ids"),
            workspaceCwd = write.workspaceCwd,
            folderId = s("folder_id"),
            config = s("config"),
        )
        val nodes = write.nodes.map { node ->
            val messages = JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
            val selectIndex = node.selectIndex.toInt()
            // 越界的 select_index 会让 currentMessages 抛 IndexOutOfBounds:会话打不开。空节点会被上游的转换丢弃,不检查。
            if (messages.isNotEmpty() && selectIndex !in messages.indices) throw PermanentApplyException("节点 ${node.id} 的 select_index $selectIndex 超出范围 0..${messages.lastIndex}")
            val nodeId = Uuid.parse(node.id)
            MessageNode(id = nodeId, messages = messages, selectIndex = selectIndex, isFavorite = nodeId in favorites)
        }
        // 与上游读路径同一个转换函数:能在这里转成功,就能被界面正常打开。
        return repository.conversationEntityToConversation(entity, nodes).also {
            // 落库前的最后检查(与 conversationToConversationEntity 的 require 一致):内联 base64 的消息上游拒绝保存。
            require(it.messageNodes.none { node -> node.messages.any { m -> m.hasBase64Part() } }) { "消息含内联 base64 数据" }
        }
    }
}
