package me.rerere.rikkahub.sync.host

/**
 * 云同步线格式里 Android Room 实体的列分类(协议 §5.4)。线格式 = Android 实体行(ADR-002),fmt = `android-room-<Room 版本>`。
 * 与桌面的 sync/android-wire-contract.ts 是同一份分类;AndroidSchemaDriftTest 强制"Room 新增/改名任何列都必须先在此分类"。
 */
object AndroidWireContract {
    const val SCHEMA_VERSION = 30
    const val FMT = "android-room-$SCHEMA_VERSION"
    const val CONVERSATION_TYPE = "conversation"

    const val CONVERSATION_TABLE = "ConversationEntity"
    const val NODE_TABLE = "message_node"

    /** 随线格式同步的会话列(顺序即线上对象的键顺序)。 */
    val SYNCED_CONVERSATION_COLUMNS = listOf(
        "id", "assistant_id", "title", "create_at", "update_at", "suggestions", "is_pinned",
        "custom_system_prompt", "mode_injection_ids", "lorebook_ids", "folder_id", "config",
    )

    /** 设备本地(指向本机路径),永不同步;落库时保留本地已有值。 */
    val DEVICE_LOCAL_COLUMNS = listOf("workspace_cwd")

    /** 遗留列,恒为 "[]"(节点已迁到 message_node);不同步,落库时写 "[]"。 */
    val LEGACY_COLUMNS = listOf("nodes")

    /** 数值列(JSON 数字);其余为字符串。`is_pinned` 是整数 0/1,不是布尔。 */
    val NUMERIC_COLUMNS = setOf("create_at", "update_at", "is_pinned")

    val NODE_COLUMNS = listOf("id", "conversation_id", "node_index", "messages", "select_index")

    /** `config` 内的设备本地字段(JSON key)。推送前剔除,落库时保留本地原值。 */
    const val CONFIG_DEVICE_LOCAL_KEY = "workspaceId"
}
