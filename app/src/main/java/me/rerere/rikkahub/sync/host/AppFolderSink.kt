package me.rerere.rikkahub.sync.host

import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.entity.FolderEntity

/**
 * [FolderSink] 在上游 FolderDAO 之上的实现(接入点 A6)。经 DAO 写,所以界面里订阅文件夹列表的 Flow 会自动刷新。
 * 删除只删文件夹行,**不**清会话的 folder_id(理由见 [FolderHost]):删除方设备已把那些会话改好并会同步过来。
 */
class AppFolderSink(private val dao: FolderDAO) : FolderSink {
    override suspend fun upsert(row: FolderRow) {
        dao.insert(FolderEntity(id = row.id, assistantId = row.assistantId, name = row.name, sortIndex = row.sortIndex.toInt(), createAt = row.createAt))
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}
