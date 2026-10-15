package me.rerere.rikkahub.sync

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SyncRestoreHookTest {
    @Test
    fun onBackupRestoredDeletesStateDbSidecars() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val state = SyncRuntime.stateDbPath(app)
        state.parentFile?.mkdirs()
        state.writeBytes(byteArrayOf(1, 2, 3))
        File(state.path + "-wal").writeBytes(byteArrayOf(4))
        assertTrue(state.isFile)
        SyncRuntime.onBackupRestored(app)
        assertFalse(state.exists())
        assertFalse(File(state.path + "-wal").exists())
    }
}
