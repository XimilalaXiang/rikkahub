package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.JsonElement

/**
 * 一次"把会话写进应用"的请求。字段已经把设备本地数据合并好了(workspace_cwd、config.workspaceId 取本机已有值)。
 * `updatedAt` 使用远端的 update_at(**不要**用当前时间:否则时间线跳动并破坏回声抑制)。
 */
class ConversationWrite(
    val id: String,
    /** 同步列(Room 存储类型:数值列是整数,其余是字符串),已含合并后的 config。 */
    val columns: Map<String, JsonElement>,
    val workspaceCwd: String,
    /** 按 node_index 升序、已重新编号为 0..n-1。messages 是不透明字符串。 */
    val nodes: List<WireNode>,
    /** 本机之前没有这个会话。 */
    val isNew: Boolean,
)

/**
 * 适配器里**唯一**依赖上游业务的出口(ADR-007):生产实现在 app 模块,按会话的 session 状态分流
 * (docs/04 §4.3:无 session → ConversationRepository;有 session → ChatService.saveConversation,保证内存里的权威状态
 * 与库一致)。测试里用直接写表的实现。
 *
 * 约定:数据永远无法落库(例如上游解码失败)抛 [me.rerere.rikkahub.sync.engine.PermanentApplyException];
 * 其它异常按暂时性处理(引擎 HOLD 并重试)。**绝不能把可能让会话打不开的数据写进库。**
 */
interface ConversationSink {
    suspend fun save(write: ConversationWrite)

    suspend fun delete(id: String)
}
