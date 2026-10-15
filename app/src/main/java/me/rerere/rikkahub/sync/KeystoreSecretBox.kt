package me.rerere.rikkahub.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import me.rerere.rikkahub.sync.state.SecretBox
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-GCM 密钥包装同步的令牌与主密钥(docs/04 §3)。密钥本身不可导出,
 * 且随应用卸载/清数据一起消失——所以配置文件即使被复制到别处也解不开。
 * 布局:iv(12) || 密文 || tag(16)。
 */
class KeystoreSecretBox(private val alias: String = "rikkasync_secret_v1") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()) // IV 由 Keystore 生成(不允许调用方指定,防止复用)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}
