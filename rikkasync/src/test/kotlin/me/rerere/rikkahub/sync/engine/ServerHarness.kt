package me.rerere.rikkahub.sync.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files

/**
 * 起一个**真实的**同步服务器进程(rikkasync 仓库的 server/test-harness.ts,需要 bun)。
 * 没设置 RIKKASYNC_REPO 或没有 bun 时,依赖它的测试被跳过(假设不成立)——密码学向量测试不受影响。
 */
class ServerHarness private constructor(
    private val process: Process,
    val url: String,
    private val ctl: String,
    private val admin: String,
    private val dataDir: File,
) : AutoCloseable {
    private val http = OkHttpClient()

    fun createDevice(name: String): Pair<String, String> {
        val req = Request.Builder().url("$url/v1/devices")
            .header("authorization", "Bearer $admin")
            .post("""{"name":"$name"}""".toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "create device failed: ${res.code}" }
            val o = Json.parseToJsonElement(res.body.string()).jsonObject
            return o.getValue("token").jsonPrimitive.content to o.getValue("device").jsonObject.getValue("id").jsonPrimitive.content
        }
    }

    /** 清理全部墓碑(模拟服务器忘掉旧的删除记录)。 */
    fun purgeTombstones() = post("/purge")

    /** 把一条记录的密封负载复制到另一个 id 上(恶意服务器)。 */
    fun swapPayload(from: String, to: String) = post("/swap?from=$from&to=$to")

    private fun post(path: String) {
        http.newCall(Request.Builder().url(ctl + path).post("".toRequestBody()).build()).execute().use { check(it.isSuccessful) { "ctl $path: ${it.code}" } }
    }

    override fun close() {
        process.destroy()
        process.waitFor()
        dataDir.deleteRecursively()
    }

    companion object {
        fun startOrSkip(): ServerHarness {
            val repo = System.getenv("RIKKASYNC_REPO").orEmpty()
            assumeTrue("RIKKASYNC_REPO not set: skipping tests that need a real server", repo.isNotEmpty())
            val harness = File(repo, "server/test-harness.ts")
            assumeTrue("$harness not found", harness.exists())
            val dataDir = Files.createTempDirectory("rikkasync-kt-").toFile()
            val pb = ProcessBuilder("bun", harness.absolutePath, dataDir.absolutePath).redirectErrorStream(false)
            val process = try {
                pb.start()
            } catch (e: Exception) {
                assumeTrue("bun is not available: ${e.message}", false)
                throw e
            }
            val line = BufferedReader(InputStreamReader(process.inputStream)).readLine() ?: error("harness printed nothing")
            val o = Json.parseToJsonElement(line).jsonObject
            return ServerHarness(
                process,
                o.getValue("url").jsonPrimitive.content,
                o.getValue("ctl").jsonPrimitive.content,
                o.getValue("admin").jsonPrimitive.content,
                dataDir,
            )
        }
    }
}
