package com.aichat.core.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aichat.domain.secret.SecretStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 AndroidKeyStore 里的 AES-256-GCM 主密钥加密后落盘。
 *
 * ## 存放形态
 *
 * ```
 *   SharedPreferences ("aichat_secrets")
 *     "provider.<uuid>" -> "base64(iv):base64(ciphertext)"
 *
 *   AndroidKeyStore
 *     "aichat.secrets.v1" -> AES-256 主密钥（硬件支持时由 TEE/StrongBox 持有，不可导出）
 * ```
 *
 * **一个主密钥加密所有密钥**，而不是每个密钥一把 KeyStore 条目 ——
 * 生成 KeyStore 密钥要几十到上百毫秒，每加一个服务商就卡一下，不值得。
 *
 * ## 为什么是 GCM 而不是 CBC
 *
 * GCM 自带完整性校验。CBC 的话攻击者可以翻转密文比特让解密结果变成
 * 另一个合法值，而我们没法察觉 —— 对「一个字节都不该被改」的 API Key
 * 来说这是硬要求。代价是 GCM 的 IV 绝不能重复，所以每次加密都让
 * 系统随机生成（默认行为，见下面**没有**手动 set IV）。
 *
 * ## 失败时返回 null，不抛异常
 *
 * 换机恢复、系统清理 KeyStore、用户在开发者选项里清了凭据 —— 这些都会
 * 让密文再也解不开。这时唯一正确的反应是「当作没配过」，让用户重填，
 * 而不是让 App 崩在启动路径上。
 */
class AndroidKeystoreSecretStore(
    context: Context,
    prefsName: String = PREFS_NAME,
    private val keyAlias: String = KEYSTORE_ALIAS,
) : SecretStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override suspend fun put(alias: String, value: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 不手动设 IV：setRandomizedEncryptionRequired 默认为 true，
        // 系统会生成一个随机 IV，加密后从 cipher.iv 取回来
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())

        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = b64(cipher.iv) + SEPARATOR + b64(ciphertext)

        // 用 commit() 而不是 apply()：写的是密钥，用户点完保存可能马上发请求，
        // 而 apply() 是异步落盘，进程被回收就丢了。这里已经在 IO 线程上，不亏。
        prefs.edit().putString(alias, packed).commit()
        Unit
    }

    override suspend fun get(alias: String): String? = withContext(Dispatchers.IO) {
        val packed = prefs.getString(alias, null) ?: return@withContext null

        val parts = packed.split(SEPARATOR)
        if (parts.size != 2) {
            // 存储被外部改坏了。删掉，免得每次读都失败
            prefs.edit().remove(alias).commit()
            return@withContext null
        }

        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey(),
                GCMParameterSpec(GCM_TAG_BITS, unb64(parts[0])),
            )
            String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
        }.getOrElse { e ->
            // 主密钥没了 / 密文被改过 / 认证标签对不上。
            // 都当作「这个密钥不可用」，顺手清掉，下次用户重填即可。
            if (e is GeneralSecurityException || e is IllegalArgumentException) {
                prefs.edit().remove(alias).commit()
                null
            } else {
                throw e
            }
        }
    }

    override suspend fun remove(alias: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(alias).commit()
        Unit
    }

    /**
     * 取主密钥，不存在就生成。
     *
     * `setUserAuthenticationRequired(false)` 是刻意的：要求解锁屏幕才能解密
     * 意味着后台刷新、定时任务全部失败。API Key 的威胁模型是「设备被物理
     * 接触 / 数据库被拷走」，不是「有人拿到解锁后的手机」。
     */
    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        const val PREFS_NAME = "aichat_secrets"

        /** 主密钥在 AndroidKeyStore 里的别名。改名等于让所有已存的密钥失效。 */
        const val KEYSTORE_ALIAS = "aichat.secrets.v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val SEPARATOR = ":"
    }
}
