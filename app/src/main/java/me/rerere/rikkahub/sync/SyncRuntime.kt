package me.rerere.rikkahub.sync

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.sync.controller.SyncController
import me.rerere.rikkahub.sync.host.AndroidConversationHost
import me.rerere.rikkahub.sync.host.AppConversationSink
import me.rerere.rikkahub.sync.host.DirtyTracker
import me.rerere.rikkahub.sync.host.RoomSqlDb
import me.rerere.rikkahub.sync.host.isConversationBusy
import me.rerere.rikkahub.sync.state.SyncConfigStore
import me.rerere.rikkahub.sync.state.SyncStateDb
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "RikkaSync"
private const val PERIODIC_WORK = "rikkasync-periodic"

/**
 * 云同步在 Android 进程里的装配(Koin single):配置存储 + 控制器 + 前后台生命周期 + 周期任务。
 * 所有对上游的依赖都在这里与 sync/host 里(ADR-007);引擎模块 :rikkasync 不依赖 Android。
 */
class SyncRuntime(
    private val app: Application,
    database: AppDatabase,
    conversationRepository: ConversationRepository,
    chatService: ChatService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sqlDb = RoomSqlDb(database)

    val config = SyncConfigStore(syncDir(app), KeystoreSecretBox())

    val controller = SyncController(
        scope = scope,
        config = config,
        openStateDb = { SyncStateDb.openOrRecover(AndroidSQLiteDriver(), stateDbPath(app).absolutePath) { e, q -> Log.w(TAG, "状态库损坏已隔离到 $q", e) } },
        createHost = { stateDb ->
            AndroidConversationHost(
                db = sqlDb,
                syncDb = stateDb,
                sink = AppConversationSink(conversationRepository, chatService, chatService.sessionManager),
                isBusy = { chatService.sessionManager.isConversationBusy(it) },
                appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "unknown",
            )
        },
        pendingLocalChanges = { runCatching { DirtyTracker(sqlDb).count() }.getOrNull() },
        log = { Log.i(TAG, it) },
    )

    /** 应用启动后调用一次:按前后台启停控制器,并按开关维护 15 分钟的后台周期任务。 */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch { runCatching { controller.start() }.onFailure { Log.e(TAG, "start failed", it) } }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch { runCatching { controller.stop() }.onFailure { Log.e(TAG, "stop failed", it) } }
            }
        })
        refreshSchedule()
    }

    /** 设置变化(开关、地址、令牌、密钥)后调用:重载前台控制器并刷新后台任务。 */
    suspend fun reload() {
        controller.start()
        refreshSchedule()
    }

    fun refreshSchedule() {
        val wm = WorkManager.getInstance(app)
        val c = config.get()
        if (c.enabled && c.serverUrl.isNotEmpty() && c.deviceToken.isNotEmpty() && c.recoveryKey.isNotEmpty()) {
            wm.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        } else {
            wm.cancelUniqueWork(PERIODIC_WORK)
        }
    }

    companion object {
        fun syncDir(app: Application) = File(app.noBackupFilesDir, "sync")
        fun stateDbPath(app: Application) = File(syncDir(app), "state.db")

        /**
         * 恢复备份钩子(docs/04 §6):恢复 = 整库回滚,同步状态描述的是"回滚之前"的世界,必须丢弃——否则备份里没有的会话
         * 会被引擎当成"本地删除"推墓碑。恢复发生在 Koin 启动之前,所以这里只删文件(状态库可整库重建;配置与密钥保留)。
         */
        fun onBackupRestored(app: Application) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(stateDbPath(app).path + suffix).delete()
            Log.i(TAG, "备份已恢复,同步状态已重置")
        }
    }
}

/** 后台周期任务:Doze 下不保证实时,这是预期。前台控制器在跑时它会等同于"立即同步"。 */
class SyncWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result = try {
        val runtime = get<SyncRuntime>()
        if (!runtime.config.get().enabled) Result.success() else {
            val report = runtime.controller.syncOnceNow()
            Log.i(TAG, "后台同步完成: $report")
            Result.success()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "后台同步失败,稍后重试", e)
        Result.retry()
    }
}
