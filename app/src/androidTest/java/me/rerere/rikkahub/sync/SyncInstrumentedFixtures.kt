package me.rerere.rikkahub.sync

import me.rerere.rikkahub.sync.engine.Crypto

/** 共用恢复码:instrumented 套件共用一台服务器,每测例各自 generateKey 会导致 BAD_DECRYPT。 */
object SyncInstrumentedFixtures {
    val recoveryKey: String = Crypto.encodeRecoveryKey(Crypto.generateMasterKey())
}
