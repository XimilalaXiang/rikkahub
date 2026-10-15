package me.rerere.rikkahub.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.sync.host.AppConversationSink
import me.rerere.rikkahub.sync.host.ConversationWrite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/**
 * V15:AppConversationSink 在真 Room + 真实 ChatService 上的分流与副作用(标题/库/session 一致性、忙时 HOLD)。
 */
@RunWith(AndroidJUnit4::class)
class AppConversationSinkV15Test {
    private lateinit var conversationId: Uuid
    private lateinit var sink: AppConversationSink
    private lateinit var chatService: ChatService
    private lateinit var repository: ConversationRepository
    private lateinit var assistantId: Uuid

    @Before
    fun setUp() {
        conversationId = Uuid.random()
        repository = GlobalContext.get().get()
        chatService = GlobalContext.get().get()
        sink = AppConversationSink(repository, chatService, chatService.sessionManager)
        assistantId = runBlocking {
            GlobalContext.get().get<SettingsStore>().awaitLoaded().getCurrentAssistant().id
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            repository.getConversationById(conversationId)?.let { repository.deleteConversation(it) }
            chatService.sessionManager.get(conversationId)?.let {
                chatService.removeConversationReference(conversationId)
            }
        }
    }

    private suspend fun seedLocal(title: String) {
        repository.insertConversation(Conversation.ofId(conversationId, assistantId).copy(title = title))
    }

    private fun remoteWrite(title: String, updateAt: Long = 5) = ConversationWrite(
        id = conversationId.toString(),
        columns = mapOf(
            "assistant_id" to JsonPrimitive(assistantId.toString()),
            "title" to JsonPrimitive(title),
            "create_at" to JsonPrimitive(1),
            "update_at" to JsonPrimitive(updateAt),
            "suggestions" to JsonPrimitive("[]"),
            "is_pinned" to JsonPrimitive(0),
            "custom_system_prompt" to JsonPrimitive(""),
            "mode_injection_ids" to JsonPrimitive("[]"),
            "lorebook_ids" to JsonPrimitive("[]"),
            "folder_id" to JsonPrimitive(""),
            "config" to JsonPrimitive("{}"),
        ),
        workspaceCwd = "",
        nodes = emptyList(),
        isNew = false,
    )

    @Test
    fun noSession_appliesViaRepository() = runBlocking {
        seedLocal("local")
        sink.save(remoteWrite("remote-no-session"))
        assertEquals("remote-no-session", repository.getConversationById(conversationId)?.title)
    }

    @Test
    fun idleSession_updatesMemoryAndDb_andSurvivesFullSave() = runBlocking {
        seedLocal("local")
        chatService.addConversationReference(conversationId)
        chatService.initializeConversation(conversationId)
        sink.save(remoteWrite("remote-with-session"))
        val sessionTitle = chatService.getConversationFlow(conversationId).value.title
        val dbTitle = repository.getConversationById(conversationId)?.title
        assertEquals("remote-with-session", sessionTitle)
        assertEquals(sessionTitle, dbTitle)
        chatService.saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)
        assertEquals("remote-with-session", repository.getConversationById(conversationId)?.title)
        chatService.removeConversationReference(conversationId)
    }

    @Test
    fun busySession_rejectsApply() = runBlocking {
        seedLocal("local")
        chatService.addConversationReference(conversationId)
        chatService.initializeConversation(conversationId)
        val session = chatService.sessionManager.get(conversationId)!!
        session.processingStatus.value = "instrumented-busy"
        try {
            val err = assertThrows(IllegalStateException::class.java) {
                runBlocking { sink.save(remoteWrite("blocked")) }
            }
            assertTrue(err.message!!.contains("busy"))
        } finally {
            session.processingStatus.value = null
            chatService.removeConversationReference(conversationId)
        }
    }
}
