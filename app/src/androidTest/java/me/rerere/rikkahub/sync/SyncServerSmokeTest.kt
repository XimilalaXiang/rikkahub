package me.rerere.rikkahub.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.sync.controller.ConnectionTest
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.TimeUnit

/** 可选:传入 serverUrl + adminToken 时在真 Room/Keystore/WorkManager 栈上跑一轮同步。 */
@RunWith(AndroidJUnit4::class)
class SyncServerSmokeTest {
    private val args get() = InstrumentationRegistry.getArguments()

    @Test
    fun syncOnceAgainstLocalServer() = runBlocking {
        val serverUrl = args.getString("serverUrl") ?: return@runBlocking
        val adminToken = args.getString("adminToken") ?: return@runBlocking
        assumeTrue(serverUrl.isNotBlank() && adminToken.isNotBlank())

        val deviceToken = createDeviceToken(serverUrl.trimEnd('/'), adminToken)
        val runtime = GlobalContext.get().get<SyncRuntime>()
        runtime.config.clearKey()
        runtime.config.update(enabled = false, serverUrl = serverUrl.trimEnd('/'), deviceToken = deviceToken)
        runtime.config.generateKey()
        runtime.config.update(enabled = true)
        runtime.reload()

        val test = runtime.controller.testConnection()
        assertTrue(test.toString(), test is ConnectionTest.Ok)
        val report = runtime.controller.syncOnceNow()
        assertTrue("sync errors: ${report.errors}", report.errors.isEmpty())

        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val syncDir = File(app.noBackupFilesDir, "sync")
        assertTrue("secrets.bin missing", File(syncDir, "secrets.bin").isFile)
        assertTrue("config.json missing", File(syncDir, "config.json").isFile)
        val secretsAscii = File(syncDir, "secrets.bin").readBytes().decodeToString(ignore = true)
        assertTrue("secrets must not be plaintext token", !secretsAscii.contains(deviceToken))

        val jobs = WorkManager.getInstance(app).getWorkInfosForUniqueWork("rikkasync-periodic").get(30, TimeUnit.SECONDS)
        assertTrue("periodic work not scheduled: $jobs", jobs.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }

    @Test
    fun invalidDeviceTokenShowsRevokedMessage() = runBlocking {
        val serverUrl = args.getString("serverUrl") ?: return@runBlocking
        assumeTrue(serverUrl.isNotBlank())
        val runtime = GlobalContext.get().get<SyncRuntime>()
        runtime.config.update(serverUrl = serverUrl.trimEnd('/'), deviceToken = "invalid-token-for-smoke-test")
        if (runtime.config.get().recoveryKey.isEmpty()) runtime.config.generateKey()
        val result = runtime.controller.testConnection()
        assertTrue(result is ConnectionTest.Failed)
        assertEquals("设备令牌无效或已被撤销", (result as ConnectionTest.Failed).error)
    }

    private fun createDeviceToken(baseUrl: String, adminToken: String): String {
        val body = buildJsonObject { put("name", "android-smoke") }.toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/v1/devices")
            .header("authorization", "Bearer $adminToken")
            .post(body)
            .build()
        OkHttpClient().newCall(request).execute().use { response ->
            check(response.isSuccessful) { "create device failed: ${response.code} ${response.body?.string()}" }
            val json = JsonInstant.parseToJsonElement(response.body!!.string()).jsonObject
            return json["token"]!!.jsonPrimitive.content
        }
    }
}
