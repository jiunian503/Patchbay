package com.aichat.core.data

import com.aichat.domain.secret.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ProviderRepository] 的行为测试。
 *
 * 全部在 JVM 上跑（毫秒级），因为 Repository 只依赖 [ProviderDao] 与
 * [com.aichat.domain.secret.SecretStore] 两个接口 —— 这正是当初把它们
 * 抽成接口的目的。
 *
 * 重点覆盖**密钥三态**：这是这个类里唯一「写错了也不报错、但会让用户
 * 莫名其妙掉 Key」的地方。
 */
class ProviderRepositoryTest {

    /** 每个测试一套干净环境。`newId` 给确定序列，方便断言别名。 */
    private class Env(
        val tx: TransactionRunner = NoTransactionRunner,
        ids: List<String> = listOf("p1", "p2", "p3", "p4"),
    ) {
        val dao = FakeProviderDao()
        val secrets = InMemorySecretStore()
        var now = 1_000L
        private val idSeq = ids.iterator()
        val repo = ProviderRepository(
            dao = dao,
            secrets = secrets,
            tx = tx,
            newId = { idSeq.next() },
            clock = { now },
        )
    }

    private fun draft(
        baseUrl: String = "api.deepseek.com",
        model: String = "deepseek-chat",
        apiKey: String? = "sk-abcdefgh1234",
        id: String? = null,
        makeDefault: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        systemPrompt: String? = null,
    ) = ProviderDraft(
        id = id,
        name = "",
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        extraHeaders = extraHeaders,
        makeDefault = makeDefault,
        temperature = temperature,
        maxTokens = maxTokens,
        systemPrompt = systemPrompt,
    )

    // ---------------------------------------------------------------- 密钥三态

    @Test
    fun `新建时写入密钥并算出尾部提示`() = runTest {
        val env = Env()

        val id = env.repo.save(draft())

        assertEquals("p1", id)
        val row = env.dao.rows.getValue(id)
        assertEquals("provider.p1", row.keyAlias)
        assertEquals("…1234", row.keyHint)
        assertEquals("sk-abcdefgh1234", env.secrets.get("provider.p1"))
    }

    @Test
    fun `密钥绝不出现在数据库行里`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = "sk-super-secret-value-9999"))

        // data class 的 toString 会把每个字段都印出来 —— 用它当「有没有泄漏」的探针
        val serialized = env.dao.rows.getValue(id).toString()
        assertFalse("密钥不该出现在数据库实体里：$serialized", serialized.contains("sk-super-secret"))
        assertTrue(serialized.contains("…9999"))
    }

    @Test
    fun `apiKey 为 null 表示不动 编辑时保留原密钥`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = "sk-old0001"))

        env.now = 2_000L
        env.repo.save(draft(id = id, model = "deepseek-reasoner", apiKey = null))

        val row = env.dao.rows.getValue(id)
        assertEquals("provider.p1", row.keyAlias)
        assertEquals("…0001", row.keyHint)
        assertEquals("sk-old0001", env.secrets.get("provider.p1"))
        assertEquals("deepseek-reasoner", row.model)
    }

    @Test
    fun `apiKey 为空串表示清除`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = "sk-old0001"))

        env.repo.save(draft(id = id, apiKey = ""))

        val row = env.dao.rows.getValue(id)
        assertNull(row.keyAlias)
        assertNull(row.keyHint)
        assertNull(env.secrets.get("provider.p1"))
    }

    @Test
    fun `apiKey 为纯空白也按清除处理`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = "sk-old0001"))

        env.repo.save(draft(id = id, apiKey = "   "))

        assertNull(env.dao.rows.getValue(id).keyAlias)
        assertNull(env.secrets.get("provider.p1"))
    }

    @Test
    fun `编辑时不被改写的字段保持原值`() = runTest {
        val env = Env()
        val id = env.repo.save(draft())
        val created = env.dao.rows.getValue(id).createdAt

        env.now = 9_999L
        env.repo.save(draft(id = id, baseUrl = "open.bigmodel.cn/api/paas/v4", model = "glm-4"))

        val row = env.dao.rows.getValue(id)
        assertEquals(created, row.createdAt)
        assertEquals(9_999L, row.updatedAt)
        assertEquals("open.bigmodel.cn/api/paas/v4", row.baseUrl)
    }

    // ---------------------------------------------------------------- 默认服务商

    @Test
    fun `第一个服务商自动成为默认`() = runTest {
        val env = Env()
        val id = env.repo.save(draft())
        assertTrue(env.dao.rows.getValue(id).isDefault)
    }

    @Test
    fun `后来的服务商不会抢走默认标记`() = runTest {
        val env = Env()
        val first = env.repo.save(draft())
        env.now = 2_000L
        val second = env.repo.save(draft(baseUrl = "api.moonshot.cn"))

        assertTrue(env.dao.rows.getValue(first).isDefault)
        assertFalse(env.dao.rows.getValue(second).isDefault)
    }

    @Test
    fun `makeDefault 会把默认标记转移过去 且只留一个`() = runTest {
        val env = Env()
        val first = env.repo.save(draft())
        env.now = 2_000L
        val second = env.repo.save(draft(baseUrl = "api.moonshot.cn", makeDefault = true))

        assertFalse(env.dao.rows.getValue(first).isDefault)
        assertTrue(env.dao.rows.getValue(second).isDefault)
        assertEquals(1, env.dao.rows.values.count { it.isDefault })
    }

    @Test
    fun `setDefault 会清掉旧的默认`() = runTest {
        val env = Env()
        val first = env.repo.save(draft())
        env.now = 2_000L
        val second = env.repo.save(draft(baseUrl = "api.moonshot.cn"))

        env.repo.setDefault(second)

        assertFalse(env.dao.rows.getValue(first).isDefault)
        assertTrue(env.dao.rows.getValue(second).isDefault)
        assertEquals(1, env.dao.rows.values.count { it.isDefault })
    }

    @Test
    fun `删除默认服务商后自动改选另一个`() = runTest {
        val env = Env()
        val first = env.repo.save(draft())
        env.now = 2_000L
        val second = env.repo.save(draft(baseUrl = "api.moonshot.cn"))
        assertTrue(env.dao.rows.getValue(first).isDefault)

        env.repo.delete(first)

        assertNull(env.dao.rows[first])
        assertTrue("删掉默认后必须还有默认，否则发消息会报「没有配置服务商」", env.dao.rows.getValue(second).isDefault)
    }

    @Test
    fun `删除非默认服务商不影响默认`() = runTest {
        val env = Env()
        val first = env.repo.save(draft())
        val second = env.repo.save(draft(baseUrl = "api.moonshot.cn"))

        env.repo.delete(second)

        assertTrue(env.dao.rows.getValue(first).isDefault)
    }

    @Test
    fun `删除服务商同时清掉它的密钥`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = "sk-todelete"))
        assertNotNull(env.secrets.get("provider.p1"))

        env.repo.delete(id)

        assertNull(env.secrets.get("provider.p1"))
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析结果带上解密后的密钥与超时`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = "sk-live-key"))

        val resolved = env.repo.resolve(id)!!

        assertTrue(resolved.hasApiKey)
        val config = resolved.config!!
        assertEquals("sk-live-key", config.apiKey)
        assertEquals("api.deepseek.com", config.baseUrl)
        assertEquals("https://api.deepseek.com/v1/chat/completions", config.chatCompletionsUrl)
        assertEquals(30L, config.connectTimeoutSeconds)
        assertEquals(300L, config.readTimeoutSeconds)
    }

    @Test
    fun `没有密钥时解析成功但 config 为 null`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = null))

        val resolved = env.repo.resolve(id)!!

        assertFalse(resolved.hasApiKey)
        assertNull(resolved.config)
        assertNull(resolved.keyHint)
    }

    @Test
    fun `密钥被系统清掉后按未配置处理`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = "sk-willvanish"))

        // 模拟换机恢复 / KeyStore 被清：数据库行还在，密钥没了
        env.secrets.remove("provider.p1")

        val resolved = env.repo.resolve(id)!!
        assertFalse(resolved.hasApiKey)
        assertNull(resolved.config)
    }

    @Test
    fun `解析不存在的 id 返回 null`() = runTest {
        assertNull(Env().repo.resolve("nope"))
    }

    @Test
    fun `resolveDefault 在没有任何服务商时返回 null`() = runTest {
        assertNull(Env().repo.resolveDefault())
    }

    @Test
    fun `resolveDefault 返回标记为默认的那个`() = runTest {
        val env = Env()
        env.repo.save(draft(apiKey = "sk-first"))
        env.now = 2_000L
        val second = env.repo.save(draft(baseUrl = "api.moonshot.cn", apiKey = "sk-second", makeDefault = true))

        assertEquals(second, env.repo.resolveDefault()!!.id)
        assertEquals("sk-second", env.repo.resolveDefault()!!.config!!.apiKey)
    }

    @Test
    fun `显示名优先用用户起的名字 没起就用域名`() = runTest {
        val env = Env()
        val unnamed = env.repo.save(draft(baseUrl = "https://api.deepseek.com/v1"))
        assertEquals("api.deepseek.com", env.repo.resolve(unnamed)!!.name)

        val named = env.repo.save(
            ProviderDraft(name = "深度求索", baseUrl = "api.deepseek.com", model = "m", apiKey = "k")
        )
        assertEquals("深度求索", env.repo.resolve(named)!!.name)
    }

    // ---------------------------------------------------------------- 请求头

    @Test
    fun `额外请求头能往返`() = runTest {
        val env = Env()
        val id = env.repo.save(
            draft(extraHeaders = mapOf("X-Api-Version" to "2024-01", "X-Trace" to "on"))
        )

        val headers = env.repo.resolve(id)!!.config!!.extraHeaders
        assertEquals("2024-01", headers["X-Api-Version"])
        assertEquals("on", headers["X-Trace"])
    }

    @Test
    fun `空请求头不写进库`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(extraHeaders = emptyMap()))
        assertNull(env.dao.rows.getValue(id).extraHeadersJson)
    }

    @Test
    fun `空白值的请求头被丢掉`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(extraHeaders = mapOf("X-Empty" to "  ", "X-Ok" to "1")))

        val headers = env.repo.resolve(id)!!.config!!.extraHeaders
        assertEquals(setOf("X-Ok"), headers.keys)
    }

    @Test
    fun `坏掉的请求头 JSON 当空处理 不让配置整体读不出来`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = "sk-ok"))
        // 手改库 / 老版本写坏了
        env.dao.rows[id] = env.dao.rows.getValue(id).copy(extraHeadersJson = "{不是 json")

        val resolved = env.repo.resolve(id)!!
        assertTrue(resolved.hasApiKey)
        assertTrue(resolved.config!!.extraHeaders.isEmpty())
    }

    // ---------------------------------------------------------------- 失败与校验

    @Test
    fun `数据库写失败时回滚刚写入的密钥`() = runTest {
        val env = Env(tx = FailingTransactionRunner())

        try {
            env.repo.save(draft(apiKey = "sk-should-be-rolled-back"))
            fail("应当把事务异常抛出来")
        } catch (e: IllegalStateException) {
            // 预期
        }

        assertNull("数据库没写成，密钥不该留在 KeyStore 里", env.secrets.get("provider.p1"))
    }

    @Test
    fun `baseUrl 为空时拒绝保存`() = runTest {
        val env = Env()
        expectIllegalArgument { env.repo.save(draft(baseUrl = "   ")) }
        assertTrue(env.dao.rows.isEmpty())
    }

    @Test
    fun `模型名为空时拒绝保存`() = runTest {
        val env = Env()
        expectIllegalArgument { env.repo.save(draft(model = "")) }
        assertTrue(env.dao.rows.isEmpty())
    }

    @Test
    fun `保存时会去掉首尾空白`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(baseUrl = "  api.deepseek.com  ", model = " m ", apiKey = "  sk-x  "))

        val row = env.dao.rows.getValue(id)
        assertEquals("api.deepseek.com", row.baseUrl)
        assertEquals("m", row.model)
        // 密钥不去空白 —— 有些网关的 key 真的带空白，改了就连不上了
        assertEquals("  sk-x  ", env.secrets.get("provider.p1"))
    }

    // ---------------------------------------------------------- 生成参数（v6）

    @Test
    fun `三个生成参数原样存取`() = runTest {
        val env = Env()
        val id = env.repo.save(
            draft(temperature = 0.7, maxTokens = 4096, systemPrompt = "你是一个简洁的助手。"),
        )

        val row = env.dao.rows.getValue(id)
        assertEquals(0.7, row.temperature!!, 1e-9)
        assertEquals(4096, row.maxTokens)
        assertEquals("你是一个简洁的助手。", row.systemPrompt)
    }

    @Test
    fun `不填生成参数就存 null 而不是替用户填默认值`() = runTest {
        val env = Env()
        val id = env.repo.save(draft())

        // 关键：不能是 1.0 / 2048 之类。**null 才有「请求里不发这个字段」的含义** ——
        // 替用户填一个我们猜的默认值，等于悄悄改掉服务端行为，而用户看不出来
        val row = env.dao.rows.getValue(id)
        assertNull(row.temperature)
        assertNull(row.maxTokens)
        assertNull(row.systemPrompt)
    }

    @Test
    fun `只有空白的系统提示词存成 null`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(systemPrompt = "   \n\t  "))

        // 库里只该有「没有提示词」和「有提示词」两种状态，别多出「空串提示词」
        assertNull(env.dao.rows.getValue(id).systemPrompt)
    }

    @Test
    fun `系统提示词两端的空白会被去掉`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(systemPrompt = "\n  你是猫娘。  \n"))

        assertEquals("你是猫娘。", env.dao.rows.getValue(id).systemPrompt)
    }

    @Test
    fun `再次保存时把参数清空会真的清掉`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(temperature = 0.7, systemPrompt = "旧提示词"))

        env.repo.save(draft(id = id, temperature = null, systemPrompt = null))

        // 这一条钉住「null = 清除」：`ProviderDraft` 的三个参数没有独立的
        // 「清除」状态，改回 null 就是清除。整体替换式写入让它自然成立
        val row = env.dao.rows.getValue(id)
        assertNull(row.temperature)
        assertNull(row.systemPrompt)
    }

    @Test
    fun `解析结果带上三个生成参数`() = runTest {
        val env = Env()
        val id = env.repo.save(
            draft(temperature = 1.2, maxTokens = 800, systemPrompt = "只回答一个字。"),
        )

        val resolved = env.repo.resolve(id)!!

        assertEquals(1.2, resolved.temperature!!, 1e-9)
        assertEquals(800, resolved.maxTokens)
        assertEquals("只回答一个字。", resolved.systemPrompt)
    }

    @Test
    fun `没有密钥时生成参数照样解析出来`() = runTest {
        val env = Env()
        val id = env.repo.save(draft(apiKey = null, temperature = 0.3))

        // `config` 为 null 只表示「没填 Key」，不代表这些参数不存在 ——
        // 建到一半的服务商不该丢参数
        val resolved = env.repo.resolve(id)!!
        assertNull(resolved.config)
        assertEquals(0.3, resolved.temperature!!, 1e-9)
    }

    @Test
    fun `温度为负数会被拒绝`() = runTest {
        val env = Env()

        expectIllegalArgument { env.repo.save(draft(temperature = -0.1)) }

        assertTrue(env.dao.rows.isEmpty())
    }

    @Test
    fun `最大回复长度为 0 会被拒绝`() = runTest {
        val env = Env()

        expectIllegalArgument { env.repo.save(draft(maxTokens = 0)) }

        assertTrue(env.dao.rows.isEmpty())
    }

    @Test
    fun `温度不设上界 各家范围不一样`() = runTest {
        val env = Env()

        // OpenAI 系是 0–2，但本地推理服务常允许更高。卡一个我们自己定的上限，
        // 会把合法配置拦在门外 —— 所以只拦「负数」这种一定是错的值
        val id = env.repo.save(draft(temperature = 3.5))

        assertEquals(3.5, env.dao.rows.getValue(id).temperature!!, 1e-9)
    }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("应当抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 预期
        }
    }
}
