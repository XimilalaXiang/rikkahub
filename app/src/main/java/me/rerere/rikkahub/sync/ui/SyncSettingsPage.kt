package me.rerere.rikkahub.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.sync.SyncRuntime
import me.rerere.rikkahub.sync.controller.ConnectionTest
import me.rerere.rikkahub.sync.controller.SyncPhase
import me.rerere.rikkahub.sync.state.PublicSyncConfig
import me.rerere.rikkahub.sync.state.SyncConfigException
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

/** 云同步设置页(docs/04 §8):连接、密钥、状态、立即同步。令牌与密钥由 [SyncRuntime.config] 保管,界面拿不到令牌。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsPage(runtime: SyncRuntime = koinInject()) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val clipboard = LocalClipboardManager.current
    val status by runtime.controller.status.collectAsState()
    var config by remember { mutableStateOf<PublicSyncConfig>(runtime.config.public()) }
    var serverUrl by remember { mutableStateOf(runtime.config.get().serverUrl) }
    var deviceToken by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var shownKey by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val savedToast = stringResource(R.string.sync_saved)
    val testOkToast = stringResource(R.string.sync_test_ok)
    val importedToast = stringResource(R.string.sync_key_imported)
    val clearKeyConfirm = stringResource(R.string.sync_clear_key_confirm)
    val wipeConfirm = stringResource(R.string.sync_wipe_confirm)

    fun refresh() {
        config = runtime.config.public()
    }

    // 轮询公开配置(状态流本身是 StateFlow,配置不是)
    LaunchedEffect(status) { refresh() }

    /** 在 IO 上做一件事,统一处理配置异常(其 message 已是面向用户的中文)。 */
    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: SyncConfigException) {
                toaster.show(e.message ?: "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show(e.message ?: e.javaClass.simpleName)
            } finally {
                refresh()
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 状态 ──
            CardGroup(title = { Text(stringResource(R.string.sync_status)) }) {
                switchItem(
                    checked = config.enabled,
                    onCheckedChange = { on -> act { runtime.config.update(enabled = on); runtime.reload() } },
                    enabled = !busy && config.hasKey && config.hasToken && config.serverUrl.isNotEmpty(),
                    supportingContent = { Text(stringResource(R.string.sync_enable_desc)) },
                    headlineContent = { Text(stringResource(R.string.sync_enable)) },
                )
                formItem(
                    headlineContent = { Text(phaseLabel(status.phase)) },
                    supportingContent = {
                        val last = status.lastSyncAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: stringResource(R.string.sync_never)
                        Text(stringResource(R.string.sync_last_sync) + ": " + last)
                    },
                ) {
                    status.pendingLocalChanges?.takeIf { it > 0 }?.let { Text(stringResource(R.string.sync_pending, it), style = MaterialTheme.typography.bodySmall) }
                    if (status.live) Text(stringResource(R.string.sync_live), style = MaterialTheme.typography.bodySmall)
                    if (status.lastSyncAt != null) {
                        Text(stringResource(R.string.sync_report, status.lastApplied, status.lastPushed, status.lastConflicts, status.lastDeferredBusy), style = MaterialTheme.typography.bodySmall)
                    }
                    status.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (status.lastErrors.isNotEmpty()) {
                        Text(stringResource(R.string.sync_item_errors, status.lastErrors.size, status.lastErrors.take(2).joinToString("; ")), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (config.secretsUnreadable) Text(stringResource(R.string.sync_secrets_unreadable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { act { runCatching { runtime.controller.runNow() }.onFailure { toaster.show(it.message ?: "") } } },
                        enabled = !busy && status.configured && config.enabled,
                    ) { Text(stringResource(if (status.phase == SyncPhase.SYNCING) R.string.sync_running else R.string.sync_now)) }
                }
            }

            // ── 连接 ──
            CardGroup(title = { Text(stringResource(R.string.sync_connection)) }) {
                formItem {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(R.string.sync_server_url)) },
                        placeholder = { Text("https://sync.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = deviceToken,
                        onValueChange = { deviceToken = it },
                        label = { Text(stringResource(R.string.sync_device_token)) },
                        placeholder = { Text(if (config.hasToken) stringResource(R.string.sync_token_saved) else stringResource(R.string.sync_token_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                act {
                                    runtime.config.update(serverUrl = serverUrl.trim(), deviceToken = deviceToken.ifEmpty { null })
                                    deviceToken = ""
                                    runtime.reload()
                                    toaster.show(savedToast)
                                }
                            },
                            enabled = !busy && (serverUrl.trim() != config.serverUrl || deviceToken.isNotEmpty()),
                        ) { Text(stringResource(R.string.sync_save)) }
                        OutlinedButton(
                            onClick = {
                                act {
                                    when (val r = runtime.controller.testConnection(serverUrl.trim().ifEmpty { null }, deviceToken.ifEmpty { null })) {
                                        is ConnectionTest.Ok -> toaster.show(testOkToast)
                                        is ConnectionTest.Failed -> toaster.show(r.error)
                                    }
                                }
                            },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.sync_test)) }
                    }
                }
            }

            // ── 密钥 ──
            CardGroup(title = { Text(stringResource(R.string.sync_key)) }) {
                formItem(
                    supportingContent = { Text(stringResource(R.string.sync_key_desc)) },
                    headlineContent = config.keyFingerprint?.let { fp -> { Text(stringResource(R.string.sync_fingerprint, fp)) } },
                ) {
                    shownKey?.let { (text, fresh) ->
                        Text(
                            stringResource(if (fresh) R.string.sync_key_fresh_notice else R.string.sync_key_shown_notice),
                            color = if (fresh) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(stringResource(R.string.sync_copy_key)) }
                            TextButton(onClick = { shownKey = null }) { Text(stringResource(R.string.sync_hide_key)) }
                        }
                    }
                    if (config.hasKey) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { act { shownKey = runtime.config.exportKey() to false } }, enabled = !busy) { Text(stringResource(R.string.sync_show_key)) }
                            OutlinedButton(
                                onClick = {
                                    confirm = clearKeyConfirm to {
                                        act {
                                            runtime.config.clearKey()
                                            runtime.controller.resetState()
                                            runtime.reload()
                                            shownKey = null
                                        }
                                    }
                                },
                                enabled = !busy,
                            ) { Text(stringResource(R.string.sync_clear_key)) }
                        }
                        Text(stringResource(R.string.sync_clear_key_desc), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.sync_first_device), style = MaterialTheme.typography.titleSmall)
                        Button(onClick = { act { shownKey = runtime.config.generateKey() to true; runtime.reload() } }, enabled = !busy) { Text(stringResource(R.string.sync_generate_key)) }
                        Text(stringResource(R.string.sync_join_existing), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = importText,
                            onValueChange = { importText = it },
                            label = { Text(stringResource(R.string.sync_recovery_code)) },
                            visualTransformation = VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { act { runtime.config.importKey(importText); importText = ""; runtime.reload(); toaster.show(importedToast) } },
                            enabled = !busy && importText.isNotBlank(),
                        ) { Text(stringResource(R.string.sync_import_key)) }
                    }
                }
            }

            // ── 重置 ──
            CardGroup {
                item(
                    onClick = {
                        confirm = wipeConfirm to {
                            act {
                                runtime.config.wipe()
                                runtime.controller.resetState()
                                runtime.reload()
                                shownKey = null
                                serverUrl = ""
                            }
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.sync_wipe), color = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }

    confirm?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text(stringResource(R.string.sync_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.sync_cancel)) } },
        )
    }
}

@Composable
private fun phaseLabel(phase: SyncPhase): String = stringResource(
    when (phase) {
        SyncPhase.DISABLED -> R.string.sync_phase_disabled
        SyncPhase.UNCONFIGURED -> R.string.sync_phase_unconfigured
        SyncPhase.CONNECTING -> R.string.sync_phase_connecting
        SyncPhase.IDLE -> R.string.sync_phase_idle
        SyncPhase.SYNCING -> R.string.sync_phase_syncing
        SyncPhase.ERROR -> R.string.sync_phase_error
    },
)

