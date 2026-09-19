package com.aichat.domain.secret

/**
 * 敏感字符串的存取（目前只有 API Key 一种）。
 *
 * ## 为什么单独抽一个接口
 *
 * 「怎么存密钥」在 Android 上是个真问题：明文 SharedPreferences 会被
 * 任何拿到 root 或备份文件的程序读走，而用户填进我们这个 App 的
 * 是**他自己的** API Key —— 泄漏了是直接扣钱的事。
 *
 * 但加解密实现（AndroidKeyStore）只能在设备上跑，把接口放在 :domain
 * 之后，上层（Repository、UI）就不依赖 Android，能用内存实现跑单测。
 *
 * ## 实现方必须遵守的约定
 *
 * - **绝不落明文**。任何形式的明文持久化（SharedPreferences、文件、日志）都不允许。
 * - [get] 在密钥不存在、或存在但已无法解密（换机、恢复出厂、系统清 KeyStore）时
 *   一律返回 null，**不要抛异常**。用户看到的是「Key 失效了请重新填」，
 *   而不是一个崩溃。
 * - [put] 会覆盖同 [alias] 的旧值。
 */
interface SecretStore {

    suspend fun put(alias: String, value: String)

    suspend fun get(alias: String): String?

    suspend fun remove(alias: String)

    /** 别名是否已配置。用于 UI 判断「要不要提示用户填 Key」。 */
    suspend fun contains(alias: String): Boolean = get(alias) != null
}

/**
 * 内存实现。给单测和 Compose Preview 用，**不要**用在生产路径上 ——
 * 进程一退就没了。
 */
class InMemorySecretStore(
    initial: Map<String, String> = emptyMap(),
) : SecretStore {

    private val values = LinkedHashMap<String, String>(initial)

    override suspend fun put(alias: String, value: String) {
        values[alias] = value
    }

    override suspend fun get(alias: String): String? = values[alias]

    override suspend fun remove(alias: String) {
        values.remove(alias)
    }
}
