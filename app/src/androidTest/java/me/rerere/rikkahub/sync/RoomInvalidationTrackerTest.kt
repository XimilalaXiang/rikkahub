package me.rerere.rikkahub.sync

import androidx.room.InvalidationTracker
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** V1:Room invalidationTracker 能否在 ConversationEntity / message_node 写入时发通知。 */
@RunWith(AndroidJUnit4::class)
class RoomInvalidationTrackerTest {
    @Test
    fun insertOnBothTables_emitsInvalidation() = runBlocking {
        val db = GlobalContext.get().get<AppDatabase>()
        val tracker: InvalidationTracker = db.invalidationTracker
        val id = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val fired = CompletableDeferred<Boolean>()
        val job = launch {
            tracker.createFlow("ConversationEntity", "message_node").collect {
                fired.complete(true)
            }
        }
        delay(200)
        db.conversationDao().insert(
            ConversationEntity(id, "0950e2dc-9bd5-4801-afa3-aa887aa36b4e", "inv", "[]", 1, 1, "[]", false),
        )
        db.messageNodeDao().insertAll(
            listOf(MessageNodeEntity("$id-n", id, 0, "[]", 0)),
        )
        withTimeout(5_000) { assertTrue(fired.await()) }
        job.cancel()
    }
}
