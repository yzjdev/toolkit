package app.toolkit.mail.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-256-GCM 密钥加密授权码。
 *
 * 密钥由系统 TEE/StrongBox 托管、不可导出，即使数据库文件被拷走也无法解出授权码。
 * 密文格式为 `IV(12B) || ciphertext || tag`，整体再做 Base64。
 */
object SecretBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "toolkit_mail_authcode"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证：后台同步时需要静默解密
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /**
     * 解密；密钥失效（例如用户清除凭据、恢复备份到新机）时返回 null，
     * 调用方应提示重新输入授权码，而不是崩溃。
     */
    fun decrypt(packed: String): String? = try {
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH),
        )
        String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
