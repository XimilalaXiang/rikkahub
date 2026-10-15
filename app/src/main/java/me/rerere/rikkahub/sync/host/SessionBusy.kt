package me.rerere.rikkahub.sync.host

import me.rerere.rikkahub.service.ConversationSessionManager
import kotlin.uuid.Uuid

/**
 * 会话"正被使用"(docs/04 §4.6):生成中、有排队消息、正在提交消息、处理中(如 OCR)。
 * "仅被查看但空闲"**不算**忙——此时应经 ChatService.saveConversation 应用,让内存里的权威状态与库一致。
 *
 * 只用 `get`/不创建 session:为查询而创建 session 会泄漏内存(session 没人 release 就不会被回收)。
 */
fun ConversationSessionManager.isConversationBusy(conversationId: String): Boolean {
    val id = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return false
    val session = get(id) ?: return false
    return session.isGenerating ||
        session.messageQueue.state.value.messages.isNotEmpty() ||
        session.submittingMessage != null ||
        session.processingStatus.value != null
}
