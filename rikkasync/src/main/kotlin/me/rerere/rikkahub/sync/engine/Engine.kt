package me.rerere.rikkahub.sync.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * 平台无关的同步引擎:**不含任何 RikkaHub 数据的知识**,全部在 HostAdapter 之后。
 * 与 core/src/engine.ts 逐段对应(docs/03 §2);TypeScript 的 engine.test.ts 是它的规格,
 * Kotlin 的测试复现同一批场景。
 */
class SyncEngine(
    private val api: SyncApi,
    private val keys: Keys,
    private val host: HostAdapter,
    private val state: StateStore,
    @Suppress("unused") private val deviceId: String,
    private val log: (String) -> Unit = {},
    /** 待推送的删除超过已知存活实体的这个比例时,中止这一轮的删除推送…… */
    private val massDeleteFraction: Double = 0.5,
    /** ……但只有待删数量至少达到这个值才触发。 */
    private val massDeleteMin: Int = 20,
) {
    private val ctx = object : HostContext {
        override fun blobId(plaintext: ByteArray) = Crypto.blobId(keys, plaintext)
    }
    private val uploaded = HashSet<String>()
    private val lock = Mutex()
    private val json = Json

    /** 跑一整轮收敛。调用被串行化,同一时刻只有一轮。 */
    suspend fun syncOnce(): SyncReport = lock.withLock { pass() }

    private suspend fun pass(): SyncReport {
        val report = SyncReport()

        // 支持签名变化(应用升级)意味着之前被跳过的记录现在也许能读了。
        val sig = host.supportSignature()
        if (state.getMeta("support_sig") != sig) {
            state.setCursor(0)
            state.setMeta("support_sig", sig)
        }

        for (round in 0 until 5) {
            pull(report)
            val conflictsBefore = report.conflicts
            val retry = push(report)
            // 推送冲突会在下一轮 pull 时解决。
            if (report.conflicts == conflictsBefore && !retry) break
        }
        return report
    }

    // ── pull ──────────────────────────────────────────────────────────────────

    private suspend fun pull(report: SyncReport) {
        var rebuild = false
        val seen = HashSet<Pair<String, String>>()
        // 每次 pull 只 scan 一次;应用/移除时同步维护,让查找保持 O(1)。
        val index = HashMap<Pair<String, String>, LocalRef>()
        for (r in host.scan()) index[r.type to r.id] = r

        while (true) {
            val cursor = state.getCursor()
            val page = try {
                api.changes(cursor, 200)
            } catch (e: CursorTooOldException) {
                if (rebuild) throw e
                log("cursor too old (floor ${e.floor}); rebuilding")
                rebuild = true
                state.setCursor(0) // 完整快照;没见过的本地实体会被重新创建,绝不删除
                continue
            }
            var holdBelow = Long.MAX_VALUE // 还没能处理的最早 seq
            for (rec in page.records) {
                report.pulled++
                seen.add(rec.type to rec.id)
                if (handleRemote(rec, report, index) == Outcome.HOLD) holdBelow = minOf(holdBelow, rec.seq)
            }
            val next = if (holdBelow == Long.MAX_VALUE) page.nextSeq else maxOf(cursor, holdBelow - 1)
            state.setCursor(next)
            if (holdBelow != Long.MAX_VALUE) break // 留到下一轮重试,不空转
            if (!page.hasMore) break
        }

        if (rebuild) {
            // 墓碑可能已被清理:凡是我们知道、服务器已不再列出的,都重新创建而不是删除。
            for (e in state.entries()) {
                if ((e.type to e.id) in seen) continue
                if (e.state.deleted) state.delete(e.type, e.id)
                else if (!e.state.skipped) state.set(e.type, e.id, EntityState(version = 0, seq = e.state.seq, fingerprint = ""))
            }
        }
    }

    private enum class Outcome { DONE, HOLD }

    private suspend fun handleRemote(rec: RecordOut, report: SyncReport, index: MutableMap<Pair<String, String>, LocalRef>): Outcome {
        val s = state.get(rec.type, rec.id)
        // "我见过它吗?"由 seq 决定(全库单调)。version 在墓碑被清理后会重新开始,所以只用于乐观并发与 AAD。
        if (s != null && s.seq >= rec.seq && !s.skipped) return Outcome.DONE // 已知 / 自己的回声

        if (host.isBusy(rec.type, rec.id)) {
            report.deferredBusy++
            return Outcome.HOLD
        }

        if (rec.deleted) return handleRemoteDelete(rec, s, report, index)

        if (!host.isFmtSupported(rec.type, rec.fmt)) {
            state.set(rec.type, rec.id, EntityState(rec.version, rec.seq, s?.fingerprint ?: "", skipped = true))
            report.skippedUnsupported++
            return Outcome.DONE
        }

        val env: Envelope
        try {
            val plain = Crypto.decryptRecord(keys, rec.type, rec.id, rec.version, Base64.getDecoder().decode(rec.payload!!))
            env = Envelope.parse(json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject)
            if (env.type != rec.type || env.id != rec.id) throw IllegalStateException("envelope does not match record")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report.errors.add("decrypt ${rec.type}/${rec.id}: ${e.message}")
            return Outcome.DONE // 不让一条被投毒的记录卡住游标
        }

        val local = index[rec.type to rec.id]
        if (local != null) {
            when (resolveClash(local, s, env)) {
                Resolution.KEEP_LOCAL -> {
                    // 本地胜:把远端版本记为我们的基线,下次推送会覆盖它。
                    state.set(rec.type, rec.id, EntityState(rec.version, rec.seq, ""))
                    return Outcome.DONE
                }
                Resolution.ADOPT -> {
                    state.set(rec.type, rec.id, EntityState(rec.version, rec.seq, local.fingerprint))
                    return Outcome.DONE
                }
                Resolution.COPY_THEN_APPLY -> {
                    host.makeConflictCopy(rec.type, rec.id)
                    report.conflicts++
                }
                Resolution.APPLY -> Unit
            }
        }

        try {
            val fp = host.apply(
                RemoteEntity(rec.type, rec.id, env.fmt, env.data, rec.version, env.updatedAt, env.blobs),
                object : ApplyIO {
                    override suspend fun fetchBlob(id: String) = Crypto.decryptBlob(keys, id, api.getBlob(id))
                },
            )
            state.set(rec.type, rec.id, EntityState(rec.version, rec.seq, fp))
            index[rec.type to rec.id] = LocalRef(rec.type, rec.id, fp, env.updatedAt)
            report.applied++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report.errors.add("apply ${rec.type}/${rec.id}: ${e.message}")
            if (e is PermanentApplyException) {
                // 本构建永远应用不了它:跳过(错误可见),而不是卡住它后面的所有记录。
                state.set(rec.type, rec.id, EntityState(rec.version, rec.seq, s?.fingerprint ?: "", skipped = true))
                return Outcome.DONE
            }
            return Outcome.HOLD
        }
        return Outcome.DONE
    }

    private suspend fun handleRemoteDelete(
        rec: RecordOut,
        s: EntityState?,
        report: SyncReport,
        index: MutableMap<Pair<String, String>, LocalRef>,
    ): Outcome {
        val local = index[rec.type to rec.id]
        if (local == null) {
            state.set(rec.type, rec.id, EntityState(rec.version, rec.seq, "", deleted = true))
            return Outcome.DONE
        }
        val dirty = s == null || s.skipped || s.fingerprint != local.fingerprint
        if (dirty) {
            // 编辑胜过删除:保留本地副本,下次推送时让它复活。
            state.set(rec.type, rec.id, EntityState(rec.version, rec.seq, ""))
            return Outcome.DONE
        }
        try {
            host.remove(rec.type, rec.id)
            index.remove(rec.type to rec.id)
            state.set(rec.type, rec.id, EntityState(rec.version, rec.seq, "", deleted = true))
            report.deletedLocal++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report.errors.add("remove ${rec.type}/${rec.id}: ${e.message}")
            return Outcome.HOLD
        }
        return Outcome.DONE
    }

    private enum class Resolution { APPLY, ADOPT, KEEP_LOCAL, COPY_THEN_APPLY }

    /**
     * 本地有副本,且来了一个更新的远端版本:谁赢?
     *  - 自上次同步后本地没改过        → 应用远端
     *  - 从未同步过(首次接触)         → 交给宿主比较谱系
     *  - 本地改过                      → 远端胜,本地留作冲突副本
     */
    private suspend fun resolveClash(local: LocalRef, s: EntityState?, env: Envelope): Resolution {
        if (s != null && !s.skipped && s.fingerprint == local.fingerprint) return Resolution.APPLY
        if (s != null && s.skipped) return Resolution.APPLY // 我们从没拥有过它的已同步版本

        if (s == null) {
            val remote = RemoteSnapshotInfo(env.fmt, env.data, env.updatedAt)
            val rel = host.compareFirstContact(local, remote) ?: defaultFirstContact(local, remote)
            return when (rel) {
                FirstContactRelation.SAME -> Resolution.ADOPT
                FirstContactRelation.REMOTE_NEWER -> Resolution.APPLY
                FirstContactRelation.LOCAL_NEWER -> Resolution.KEEP_LOCAL
                FirstContactRelation.DIVERGED -> Resolution.COPY_THEN_APPLY
            }
        }
        return Resolution.COPY_THEN_APPLY
    }

    /** 宿主不能比较谱系时的兜底:深度相等,再比时间戳。 */
    private suspend fun defaultFirstContact(local: LocalRef, remote: RemoteSnapshotInfo): FirstContactRelation {
        val snap = host.read(local.type, local.id, ctx)
        if (snap != null && snap.fmt == remote.fmt && stableJson(snap.data) == stableJson(remote.data)) return FirstContactRelation.SAME
        val lu = local.updatedAt
        val ru = remote.updatedAt
        if (lu != null && ru != null) {
            if (ru > lu) return FirstContactRelation.REMOTE_NEWER
            if (lu > ru) return FirstContactRelation.LOCAL_NEWER
        }
        return FirstContactRelation.DIVERGED
    }

    // ── push ──────────────────────────────────────────────────────────────────

    /** 需要再来一轮(有记录必须从 base 0 重新推送)时返回 true。 */
    private suspend fun push(report: SyncReport): Boolean {
        val scan = host.scan()
        val present = scan.mapTo(HashSet()) { it.type to it.id }

        // 删除:已知存活、本地却消失了的实体。
        var gone = state.entries().filter { !it.state.deleted && !it.state.skipped && it.state.version > 0 && (it.type to it.id) !in present }
        val live = state.entries().count { !it.state.deleted && !it.state.skipped }
        if (gone.size >= massDeleteMin && gone.size > live * massDeleteFraction) {
            report.errors.add("mass_delete_guard: refusing to delete ${gone.size} of $live entities")
            gone = emptyList()
        }

        class Meta(val type: String, val id: String, val fingerprint: String, val deleted: Boolean)

        val batch = ArrayList<PushIn>()
        val meta = ArrayList<Meta>()

        for (e in gone) {
            batch.add(PushIn(e.type, e.id, baseVersion = e.state.version, deleted = true, fmt = "tombstone"))
            meta.add(Meta(e.type, e.id, "", true))
        }

        for (ref in scan) {
            val s = state.get(ref.type, ref.id)
            if (s?.skipped == true) continue
            val dirty = s == null || s.deleted || s.fingerprint != ref.fingerprint
            if (!dirty) continue
            if (host.isBusy(ref.type, ref.id)) {
                report.deferredBusy++
                continue
            }
            // 一个坏实体(数据损坏、附件消失、上传失败)不能挡住其它所有实体:记录错误,保持它的状态不变
            // (它仍是脏的,下一轮重试),继续往下。
            try {
                val snap = host.read(ref.type, ref.id, ctx) ?: continue
                for (b in snap.blobs) ensureBlob(b.id, b.read)
                val base = s?.version ?: 0L
                val env = buildJsonObject {
                    put("v", 0)
                    put("type", ref.type)
                    put("id", ref.id)
                    put("fmt", snap.fmt)
                    if (snap.updatedAt != null) put("updated_at", snap.updatedAt)
                    put("blobs", JsonArray(snap.blobs.map { JsonPrimitive(it.id) }))
                    put("data", snap.data)
                }
                val sealed = Crypto.encryptRecord(keys, ref.type, ref.id, base + 1, env.toString().toByteArray(Charsets.UTF_8))
                batch.add(PushIn(ref.type, ref.id, baseVersion = base, fmt = snap.fmt, payload = Base64.getEncoder().encodeToString(sealed)))
                meta.add(Meta(ref.type, ref.id, snap.fingerprint, false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report.errors.add("read ${ref.type}/${ref.id}: ${e.message}")
            }
        }

        var retry = false
        var i = 0
        while (i < batch.size) {
            val chunk = batch.subList(i, minOf(i + 50, batch.size))
            val results = api.push(chunk)
            for ((j, r) in results.withIndex()) {
                val m = meta[i + j]
                when (r) {
                    is PushResult.Ok -> {
                        state.set(m.type, m.id, EntityState(r.version, r.seq, m.fingerprint, deleted = m.deleted))
                        if (m.deleted) report.deletedRemote++ else report.pushed++
                    }
                    is PushResult.Conflict -> {
                        report.conflicts++ // 下一轮 pull 解决(服务器记录胜,本地被复制)
                        log("push conflict ${r.type}/${r.id} -> server v${r.current.version}")
                    }
                    is PushResult.Error -> {
                        if (r.error == "unknown_record") {
                            // 服务器没有这条记录(墓碑已被清理):忘掉基线,下一轮重新创建。
                            state.set(m.type, m.id, EntityState(0, state.get(m.type, m.id)?.seq ?: 0, ""))
                            retry = true
                        } else {
                            report.errors.add("push ${r.type}/${r.id}: ${r.error}")
                        }
                    }
                }
            }
            i += 50
        }
        return retry
    }

    private suspend fun ensureBlob(id: String, read: suspend () -> ByteArray) {
        if (id in uploaded) return
        if (!api.hasBlob(id)) {
            val plain = read()
            if (Crypto.blobId(keys, plain) != id) throw IllegalStateException("blob $id content does not match its id")
            api.putBlob(id, Crypto.encryptBlob(keys, id, plain))
        }
        uploaded.add(id)
    }

    // ── 信封 ──────────────────────────────────────────────────────────────────

    private class Envelope(val type: String, val id: String, val fmt: String, val updatedAt: Long?, val blobs: List<String>, val data: JsonElement) {
        companion object {
            fun parse(o: JsonObject) = Envelope(
                type = o["type"]!!.jsonPrimitive.content,
                id = o["id"]!!.jsonPrimitive.content,
                fmt = o["fmt"]!!.jsonPrimitive.content,
                updatedAt = (o["updated_at"] as? JsonPrimitive)?.longOrNull,
                blobs = (o["blobs"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                data = o["data"] ?: JsonNull,
            )
        }
    }
}

/** 键排序的 JSON 文本,只用于相等比较(与 TypeScript 的 stableJson 行为一致:对象按键排序,数组保序)。 */
fun stableJson(v: JsonElement): String = StringBuilder().also { writeStable(v, it) }.toString()

private fun writeStable(v: JsonElement, out: StringBuilder) {
    when (v) {
        is JsonObject -> {
            out.append('{')
            var first = true
            for (k in v.keys.sorted()) {
                if (!first) out.append(',')
                first = false
                out.append(JsonPrimitive(k).toString()).append(':')
                writeStable(v.getValue(k), out)
            }
            out.append('}')
        }
        is JsonArray -> {
            out.append('[')
            v.forEachIndexed { i, e ->
                if (i > 0) out.append(',')
                writeStable(e, out)
            }
            out.append(']')
        }
        else -> out.append(v.toString())
    }
}
