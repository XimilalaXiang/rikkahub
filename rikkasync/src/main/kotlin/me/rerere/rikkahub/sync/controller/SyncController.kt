package me.rerere.rikkahub.sync.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.sync.engine.Crypto
import me.rerere.rikkahub.sync.engine.HostAdapter
import me.rerere.rikkahub.sync.engine.HttpException
import me.rerere.rikkahub.sync.engine.SyncApi
import me.rerere.rikkahub.sync.engine.SyncClient
import me.rerere.rikkahub.sync.engine.SyncEngine
import me.rerere.rikkahub.sync.engine.SyncReport
import me.rerere.rikkahub.sync.state.SyncConfigStore
import me.rerere.rikkahub.sync.state.SyncDbTooNewException
import me.rerere.rikkahub.sync.state.SyncStateDb
import java.security.MessageDigest

const val SUPPORTED_PROTOCOL = 0

enum class SyncPhase { DISABLED, UNCONFIGURED, CONNECTING, IDLE, SYNCING, ERROR }

data class SyncStatus(
    val phase: SyncPhase = SyncPhase.DISABLED,
    /** 地址、令牌、密钥都已填写。 */
    val configured: Boolean = false,
    val enabled: Boolean = false,
    /** 服务器推送通道在线(别的设备的改动会立刻到达)。 */
    val live: Boolean = false,
    val deviceId: String? = null,
    val lastSyncAt: Long? = null,
    val lastErrors: List<String> = emptyList(),
    val lastApplied: Int = 0,
    val lastPushed: Int = 0,
    val lastConflicts: Int = 0,
    val lastDeferredBusy: Int = 0,
    val lastError: String? = null,
    /** 本地尚未同步的脏会话数(粗略;未运行时为 null)。 */
    val pendingLocalChanges: Int? = null,
    /** 状态库曾损坏并被重建(下一轮会重新对账)。 */
    val stateRecovered: Boolean = false,
)

sealed interface ConnectionTest {
    data class Ok(val protocol: Int, val deviceId: String, val headSeq: Long) : ConnectionTest
    data class Failed(val error: String) : ConnectionTest
}

class SyncControllerOptions(
    val pollMs: Long = 5_000,
    val heartbeatMs: Long = 60_000,
    val debounceMs: Long = 300,
    val baseBackoffMs: Long = 5_000,
    val maxBackoffMs: Long = 300_000,
)

/**
 * 同步控制器:连接 → 一轮 syncOnce → 等待唤醒 → 再来。与桌面的 sync/controller.ts 同一设计,协程版(docs/04 §7)。
 *
 * 唤醒来源:服务器 SSE 的 head 事件;本地变更(SSE 在线时每 pollMs 只查一次脏标记,有才跑;离线时每 pollMs 都跑);
 * 心跳(SSE 在线也至少每 heartbeatMs 跑一轮,兜底丢失的事件与失效的触发器);用户点击"立即同步"。
 * 失败(网络/鉴权)指数退避,配置变更/手动同步打断退避。
 *
 * 平台无关:Android 的前后台生命周期、WorkManager 在 app 模块里调 [start]/[stop]/[syncOnceNow]。
 */
class SyncController(
    private val scope: CoroutineScope,
    private val config: SyncConfigStore,
    private val openStateDb: () -> SyncStateDb,
    /** 用打开的状态库造适配器(适配器需要状态库的指纹缓存)。 */
    private val createHost: (SyncStateDb) -> HostAdapter,
    private val createApi: (serverUrl: String, token: String) -> SyncApi = { url, token -> SyncClient(url, token) },
    private val pendingLocalChanges: () -> Int? = { null },
    private val options: SyncControllerOptions = SyncControllerOptions(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val lifecycle = Mutex()

    /** 同一时刻只允许一轮同步(前台循环与后台 Worker 共用),否则两个引擎会同时改同一份状态。 */
    private val passMutex = Mutex()
    private var loopJob: Job? = null
    private var gen = 0
    private var stateDb: SyncStateDb? = null
    private var engine: SyncEngine? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var immediate = false

    @Volatile private var passing = false

    @Volatile private var passesStarted = 0

    @Volatile private var passesFinished = 0
    private val waiters = ArrayList<Waiter>()
    private val waiterLock = Any()

    @Volatile private var lastPassAt = 0L

    private class Waiter(val needPass: Int, val deferred: CompletableDeferred<SyncReport>)

    init {
        refreshFlags()
    }

    // ── 公共接口 ──────────────────────────────────────────────────────────────

    /** 按当前配置启动(已在运行则先停止再启动,即"重载")。配置不完整或未启用时只更新状态。 */
    suspend fun start() = lifecycle.withLock {
        stopLocked()
        refreshFlags()
        val cfg = config.get()
        if (!cfg.enabled) return@withLock setPhase(SyncPhase.DISABLED)
        if (!_status.value.configured) return@withLock setPhase(SyncPhase.UNCONFIGURED)
        _status.update { it.copy(lastError = null) }
        setPhase(SyncPhase.CONNECTING)
        val myGen = ++gen
        loopJob = scope.launch { loop(myGen) }
    }

    suspend fun stop() = lifecycle.withLock {
        stopLocked()
        refreshFlags()
        setPhase(if (_status.value.enabled && !_status.value.configured) SyncPhase.UNCONFIGURED else SyncPhase.DISABLED)
    }

    /** 立即同步一轮;返回本轮报告。未运行时抛错。会等待进行中的一轮结束后再跑一整轮(保证包含调用前的改动)。 */
    suspend fun runNow(): SyncReport {
        if (loopJob?.isActive != true) throw IllegalStateException("同步未启用或配置不完整")
        val waiter = synchronized(waiterLock) {
            // 下一个才开始的那一轮(无论当前是否有一轮在进行):调用之后的改动一定包含在它里面。
            Waiter(passesStarted + 1, CompletableDeferred()).also { waiters.add(it) }
        }
        immediate = true
        wake.trySend(Unit)
        return waiter.deferred.await()
    }

    /** 控制器没在跑(例如后台 Worker)时执行一轮完整同步;在跑则等同 [runNow]。 */
    /** 宿主在"没有脏标记可查"的本地数据(设置、文件夹)变化时调用:唤醒循环,防抖后跑一轮。 */
    fun notifyLocalChange() {
        wake.trySend(Unit)
    }

    suspend fun syncOnceNow(): SyncReport {
        if (loopJob?.isActive == true) return runNow()
        refreshFlags()
        val cfg = config.get()
        check(_status.value.configured) { "同步配置不完整" }
        return passMutex.withLock { oneShot(cfg) }
    }

    private suspend fun oneShot(cfg: me.rerere.rikkahub.sync.state.SyncConfig): SyncReport {
        val db = openDb()
        try {
            val api = createApi(cfg.serverUrl, cfg.deviceToken)
            val info = api.info()
            check(info.protocol == SUPPORTED_PROTOCOL) { "服务器协议版本 ${info.protocol} 与本程序支持的 $SUPPORTED_PROTOCOL 不一致" }
            applyBinding(db, cfg.serverUrl, cfg.recoveryKey)
            val e = SyncEngine(api, Crypto.deriveKeys(Crypto.decodeRecoveryKey(cfg.recoveryKey)), createHost(db), db, info.deviceId, log)
            return e.syncOnce()
        } finally {
            if (stateDb == null) db.close()
        }
    }

    /** 连接测试:不改变任何状态,只验证"地址 + 令牌 + 协议版本"。 */
    suspend fun testConnection(serverUrl: String? = null, deviceToken: String? = null): ConnectionTest {
        val cfg = config.get()
        val url = serverUrl?.takeIf { it.isNotEmpty() }?.let { SyncConfigStore.normalizeServerUrl(it) } ?: cfg.serverUrl
        val token = deviceToken?.takeIf { it.isNotEmpty() } ?: cfg.deviceToken
        if (url.isEmpty() || token.isEmpty()) return ConnectionTest.Failed("请先填写服务器地址和设备令牌")
        return try {
            val info = createApi(url, token).info()
            if (info.protocol != SUPPORTED_PROTOCOL) ConnectionTest.Failed("服务器协议版本 ${info.protocol} 与本程序支持的 $SUPPORTED_PROTOCOL 不一致")
            else ConnectionTest.Ok(info.protocol, info.deviceId, info.headSeq)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionTest.Failed(describe(e))
        }
    }

    /** 丢弃本地同步状态(游标、指纹、状态)。换服务器/换密钥/恢复备份之后调用;下一轮从"首次接触"重新对账。 */
    suspend fun resetState() = lifecycle.withLock {
        val wasRunning = loopJob?.isActive == true
        stopLocked()
        val db = openDb()
        try {
            db.reset()
        } finally {
            if (stateDb == null) db.close()
        }
        if (wasRunning) {
            refreshFlags()
            setPhase(SyncPhase.CONNECTING)
            val myGen = ++gen
            loopJob = scope.launch { loop(myGen) }
        }
    }

    // ── 主循环 ────────────────────────────────────────────────────────────────

    private suspend fun loop(myGen: Int) {
        var failures = 0
        try {
            while (scope.isActive && gen == myGen) {
                try {
                    if (engine == null) connect(myGen)
                    if (gen != myGen) break
                    val report = pass()
                    failures = 0
                    _status.update {
                        it.copy(
                            lastError = null, lastErrors = report.errors.toList(), lastApplied = report.applied, lastPushed = report.pushed,
                            lastConflicts = report.conflicts, lastDeferredBusy = report.deferredBusy, lastSyncAt = now(),
                            pendingLocalChanges = pendingLocalChanges(),
                        )
                    }
                    setPhase(SyncPhase.IDLE)
                    settleWaiters(report)
                    sleepUntilWake(if (report.errors.isNotEmpty()) maxOf(options.pollMs, 30_000) else options.pollMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SyncDbTooNewException) {
                    fail(e.message ?: "同步状态库版本过新")
                    rejectWaiters(e)
                    return // 重试没有意义:需要用户升级应用
                } catch (e: Exception) {
                    if (gen != myGen) break
                    failures++
                    val wait = minOf(options.maxBackoffMs, options.baseBackoffMs * (1L shl minOf(failures - 1, 10)))
                    fail(describe(e))
                    rejectWaiters(e)
                    sleepUntilWake(wait)
                }
            }
        } finally {
            _status.update { it.copy(live = false) }
        }
    }

    private suspend fun connect(myGen: Int) {
        val cfg = config.get()
        val api = createApi(cfg.serverUrl, cfg.deviceToken)
        val info = api.info()
        if (info.protocol != SUPPORTED_PROTOCOL) throw IllegalStateException("服务器协议版本 ${info.protocol} 与本程序支持的 $SUPPORTED_PROTOCOL 不一致,请升级")
        if (gen != myGen) return
        val db = stateDb ?: openDb().also { stateDb = it }
        applyBinding(db, cfg.serverUrl, cfg.recoveryKey)
        val keys = Crypto.deriveKeys(Crypto.decodeRecoveryKey(cfg.recoveryKey))
        engine = SyncEngine(api, keys, createHost(db), db, info.deviceId, log)
        _status.update { it.copy(deviceId = info.deviceId) }
        scope.launch { eventsLoop(myGen, api) }
    }

    /** 状态与"服务器 + 密钥"绑定:换了服务器或换了密钥,游标和每实体版本都不再有意义,必须清掉重新对账。 */
    private fun applyBinding(db: SyncStateDb, serverUrl: String, recoveryKey: String) {
        val binding = MessageDigest.getInstance("SHA-256").digest("$serverUrl\n$recoveryKey".toByteArray()).joinToString("") { "%02x".format(it) }
        val previous = db.getMeta("binding")
        if (previous != null && previous != binding) {
            log("服务器或密钥已更换,重置同步状态")
            db.reset()
        }
        db.setMeta("binding", binding)
    }

    private suspend fun pass(): SyncReport {
        synchronized(waiterLock) { passing = true; passesStarted++ }
        setPhase(SyncPhase.SYNCING)
        try {
            val report = passMutex.withLock { engine!!.syncOnce() }
            lastPassAt = now()
            return report
        } finally {
            synchronized(waiterLock) { passing = false; passesFinished++ }
        }
    }

    /** SSE:别的设备推送时立刻唤醒;断线后自动重连。 */
    private suspend fun eventsLoop(myGen: Int, api: SyncApi) {
        while (scope.isActive && gen == myGen) {
            try {
                _status.update { it.copy(live = true) }
                api.events { wake.trySend(Unit) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 断线/鉴权失败:退避后重连;鉴权问题会由主循环的请求报告
            }
            _status.update { it.copy(live = false) }
            if (gen != myGen) return
            delay(options.pollMs)
        }
    }

    /** 等到被唤醒或超时。SSE 在线时本地变更靠脏标记检测:无脏标记且未到心跳就继续睡。 */
    private suspend fun sleepUntilWake(ms: Long) {
        val startedAt = now()
        while (scope.isActive) {
            val woken = withTimeoutOrNull(minOf(ms, options.pollMs)) { wake.receive() } != null
            if (woken) {
                // 防抖:合并紧接着到来的一串事件。立即同步(runNow)不防抖。
                if (!immediate) delay(options.debounceMs)
                immediate = false
                return
            }
            if (now() - startedAt >= ms) return
            val hasLocalChanges = (pendingLocalChanges() ?: 0) > 0
            if (!_status.value.live || hasLocalChanges || now() - lastPassAt >= options.heartbeatMs) return
        }
    }

    // ── 收尾 ──────────────────────────────────────────────────────────────────

    private suspend fun stopLocked() {
        gen++
        val job = loopJob
        loopJob = null
        job?.cancel()
        wake.trySend(Unit)
        job?.join()
        rejectWaiters(IllegalStateException("同步已停止"))
        engine = null
        stateDb?.close()
        stateDb = null
        synchronized(waiterLock) { passing = false }
        _status.update { it.copy(live = false) }
    }

    private fun openDb(): SyncStateDb = stateDb ?: openStateDb()

    private fun settleWaiters(report: SyncReport) {
        synchronized(waiterLock) {
            val done = waiters.filter { passesFinished >= it.needPass }
            waiters.removeAll(done.toSet())
            done.forEach { it.deferred.complete(report) }
        }
    }

    private fun rejectWaiters(error: Exception) {
        synchronized(waiterLock) {
            waiters.forEach { it.deferred.completeExceptionally(error) }
            waiters.clear()
        }
    }

    private fun refreshFlags() {
        val c = config.get()
        _status.update { it.copy(enabled = c.enabled, configured = c.serverUrl.isNotEmpty() && c.deviceToken.isNotEmpty() && c.recoveryKey.isNotEmpty()) }
    }

    private fun setPhase(p: SyncPhase) = _status.update { it.copy(phase = p) }

    private fun fail(message: String) = _status.update { it.copy(lastError = message, phase = SyncPhase.ERROR) }

    private fun describe(e: Exception): String = when {
        e is HttpException && (e.status == 401 || e.status == 403) -> "设备令牌无效或已被撤销"
        e is HttpException -> "服务器返回错误 ${e.status}(${e.code})"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
    }
}
