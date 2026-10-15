package me.rerere.rikkahub.sync.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

// 同步服务器(协议 v0)的薄 HTTP 客户端。与 core/src/client.ts 对应。

data class RecordOut(
    val type: String,
    val id: String,
    val version: Long,
    val seq: Long,
    val deleted: Boolean,
    val fmt: String,
    val deviceId: String,
    val ts: Long,
    val size: Long,
    /** base64 的密封负载;墓碑为 null。 */
    val payload: String?,
)

data class PushIn(
    val type: String,
    val id: String,
    val baseVersion: Long,
    val deleted: Boolean = false,
    val fmt: String,
    /** base64 密封负载(删除时省略)。 */
    val payload: String? = null,
)

sealed interface PushResult {
    val type: String
    val id: String

    data class Ok(override val type: String, override val id: String, val version: Long, val seq: Long) : PushResult
    data class Conflict(override val type: String, override val id: String, val current: RecordOut) : PushResult
    data class Error(override val type: String, override val id: String, val error: String) : PushResult
}

class CursorTooOldException(val floor: Long) : Exception("cursor_too_old")

class HttpException(val status: Int, val code: String) : IOException("HTTP $status: $code")

data class ServerInfo(val protocol: Int, val headSeq: Long, val tombstoneFloor: Long, val deviceId: String)

data class ChangesPage(val records: List<RecordOut>, val hasMore: Boolean, val nextSeq: Long, val headSeq: Long)

/** 抽象出来便于测试替换(引擎只依赖它,不依赖 OkHttp)。 */
interface SyncApi {
    suspend fun info(): ServerInfo
    suspend fun changes(since: Long, limit: Int = 200): ChangesPage
    suspend fun push(records: List<PushIn>): List<PushResult>
    suspend fun hasBlob(id: String): Boolean
    suspend fun putBlob(id: String, sealed: ByteArray)
    suspend fun getBlob(id: String): ByteArray

    /** 持续接收服务器的 head 变更通知,直到协程被取消或流结束。 */
    suspend fun events(onHead: (Long) -> Unit)
}

class SyncClient(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = defaultHttp(),
) : SyncApi {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(path: String) = Request.Builder().url(base + path).header("authorization", "Bearer $token")

    private suspend fun <T> call(req: Request, block: (Response) -> T): T = withContext(Dispatchers.IO) {
        cancellable(http.newCall(req)) { it.execute().use(block) }
    }

    /** 协程被取消时中断阻塞中的网络调用(SSE 长连接靠它退出)。只用公开的协程 API。 */
    private suspend fun <T> cancellable(call: okhttp3.Call, block: (okhttp3.Call) -> T): T = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block(call)
        } finally {
            watcher.cancel()
        }
    }

    private fun fail(res: Response): Nothing {
        var code = "http_${res.code}"
        try {
            val text = res.body.string()
            code = json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull ?: code
        } catch (_: Exception) {
            // 不是 JSON
        }
        throw HttpException(res.code, code)
    }

    override suspend fun info(): ServerInfo = call(request("/v1/info").build()) { res ->
        if (!res.isSuccessful) fail(res)
        val o = json.parseToJsonElement(res.body.string()).jsonObject
        ServerInfo(o.int("protocol").toInt(), o.int("head_seq"), o.int("tombstone_floor"), o.str("device_id"))
    }

    override suspend fun changes(since: Long, limit: Int): ChangesPage = call(request("/v1/changes?since=$since&limit=$limit").build()) { res ->
        if (res.code == 410) {
            val floor = runCatching { json.parseToJsonElement(res.body.string()).jsonObject["tombstone_floor"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L
            throw CursorTooOldException(floor)
        }
        if (!res.isSuccessful) fail(res)
        val o = json.parseToJsonElement(res.body.string()).jsonObject
        ChangesPage(
            records = o["records"]!!.jsonArray.map { it.jsonObject.toRecord() },
            hasMore = o["has_more"]!!.jsonPrimitive.booleanOrNull ?: false,
            nextSeq = o.int("next_seq"),
            headSeq = o.int("head_seq"),
        )
    }

    override suspend fun push(records: List<PushIn>): List<PushResult> {
        val body = buildJsonObject {
            put("records", JsonArray(records.map { r ->
                buildJsonObject {
                    put("type", r.type)
                    put("id", r.id)
                    put("base_version", r.baseVersion)
                    if (r.deleted) put("deleted", true)
                    put("fmt", r.fmt)
                    if (r.payload != null) put("payload", r.payload)
                }
            }))
        }.toString().toRequestBody("application/json".toMediaType())
        return call(request("/v1/push").post(body).build()) { res ->
            if (!res.isSuccessful) fail(res)
            val o = json.parseToJsonElement(res.body.string()).jsonObject
            o["results"]!!.jsonArray.map { it.jsonObject.toPushResult() }
        }
    }

    override suspend fun hasBlob(id: String): Boolean = call(request("/v1/blobs/$id").head().build()) { res ->
        if (res.code == 404) return@call false
        if (!res.isSuccessful) fail(res)
        true
    }

    override suspend fun putBlob(id: String, sealed: ByteArray) {
        val body = sealed.toRequestBody("application/octet-stream".toMediaType())
        call(request("/v1/blobs/$id").put(body).build()) { res -> if (!res.isSuccessful) fail(res) }
    }

    override suspend fun getBlob(id: String): ByteArray = call(request("/v1/blobs/$id").build()) { res ->
        if (!res.isSuccessful) fail(res)
        res.body.bytes()
    }

    /**
     * 持续接收服务器的 head 变更通知,直到协程被取消或流结束。每个通知回调一次 onHead(headSeq)。
     * (这是读超时为 0 的长连接,所以用专门的客户端实例。)
     */
    override suspend fun events(onHead: (Long) -> Unit) {
        val streaming = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val req = request("/v1/events").build()
        withContext(Dispatchers.IO) {
            cancellable(streaming.newCall(req)) { call ->
                call.execute().use { res ->
                    if (!res.isSuccessful) fail(res)
                    val source = res.body.source()
                    var event = ""
                    var data = ""
                    while (true) {
                        val line = source.readUtf8Line() ?: return@use
                        when {
                            line.startsWith("event: ") -> event = line.removePrefix("event: ")
                            line.startsWith("data: ") -> data = line.removePrefix("data: ")
                            line.isEmpty() -> {
                                if (event == "head" && data.isNotEmpty()) {
                                    json.parseToJsonElement(data).jsonObject["head_seq"]?.jsonPrimitive?.longOrNull?.let(onHead)
                                }
                                event = ""
                                data = ""
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
private fun JsonObject.int(key: String): Long = this[key]!!.jsonPrimitive.long
private val JsonPrimitive.long: Long get() = longOrNull ?: content.toDouble().toLong()

private fun JsonObject.toRecord(): RecordOut = RecordOut(
    type = str("type"),
    id = str("id"),
    version = int("version"),
    seq = int("seq"),
    deleted = this["deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
    fmt = str("fmt"),
    deviceId = this["device_id"]?.jsonPrimitive?.contentOrNull ?: "",
    ts = this["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
    size = this["size"]?.jsonPrimitive?.longOrNull ?: 0L,
    payload = (this["payload"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
)

private fun JsonObject.toPushResult(): PushResult {
    val type = str("type")
    val id = str("id")
    return when (str("status")) {
        "ok" -> PushResult.Ok(type, id, int("version"), int("seq"))
        "conflict" -> PushResult.Conflict(type, id, this["current"]!!.jsonObject.toRecord())
        else -> PushResult.Error(type, id, this["error"]?.jsonPrimitive?.contentOrNull ?: "unknown")
    }
}

internal fun JsonElement.asObject(): JsonObject = jsonObject
