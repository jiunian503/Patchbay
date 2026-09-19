package com.aichat.core.data

import com.aichat.domain.secret.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConversationRouter] 的行为测试 —— 「会话黏住服务商」的语义守在这里。
 *
 * 全部在 JVM 上跑。路由只依赖两个 DAO 接口和 [ProviderRepository]，
 * 所以不需要模拟器。
 *
 * 重点覆盖**两条退化路径**：会话还没钉过、钉着的服务商被删了。
 * 这两条都是「不写就会静默走错」的地方 —— 前者会让同一会话前后两轮
 * 用上不同服务商，后者会让会话永久卡死。
 */
class ConversationRouterTest {

    private class Env {
        val messages = FakeMessageDao()
        val conversationDao = FakeConversationDao(messages)
        val providerDao = FakeProviderDao()
        val secrets = InMemorySecretStore()
        var now = 1_000L

        val providers = ProviderRepository(
            dao = providerDao,
            secrets = secrets,
            tx = NoTransactionRunner,
            newId = { "generated-${providerDao.rows.size + 1}" },
            clock = { now },
        )

        val router = ConversationRouter(conversationDao, providers, clock = { now })

        /** 建一个服务商。id 直接用传入的，方便断言。 */
        suspend fun provider(
            id: String,
            model: String = "model-$id",
            makeDefault: Boolean = false,
            apiKey: String? = "sk-$id",
        ): String {
            providers.save(
                ProviderDraft(
                    id = id,
                    name = id,
                    baseUrl = "api.$id.com",
                    model = model,
                    apiKey = apiKey,
                    makeDefault = makeDefault,
                )
            )
            return id
        }

        /** 建一个会话，`providerId` 为 null 就是「还没钉过」。 */
        fun conversation(id: String, providerId: String? = null, model: String? = null) {
            conversationDao.rows[id] = ConversationEntity(
                id = id,
                title = "",
                providerId = providerId,
                model = model,
                createdAt = 0,
                updatedAt = 0,
            )
        }

        fun routeOf(id: String): Pair<String?, String?> {
            val row = conversationDao.rows.getValue(id)
            return row.providerId to row.model
        }
    }

    // ---- 正常路径 ----

    @Test
    fun `没钉过的会话用默认服务商并把它钉上`() = runTest {
        val env = Env()
        env.provider("p1", makeDefault = true)
        env.provider("p2")
        env.conversation("c1")

        val resolved = env.router.resolve("c1")

        assertEquals("应该用默认的那个", "p1", resolved?.id)
        // 钉上这个副作用是重点：不钉的话，用户中途改了默认服务商，
        // 同一个会话的下一轮就会换人 —— 那正是「黏住」要避免的
        assertEquals("解析完应该把它钉在会话上", "p1" to "model-p1", env.routeOf("c1"))
    }

    @Test
    fun `钉过的会话不受默认变更影响`() = runTest {
        val env = Env()
        env.provider("p1")
        env.provider("p2", makeDefault = true)
        env.conversation("c1", providerId = "p1", model = "model-p1")

        val resolved = env.router.resolve("c1")

        assertEquals("钉住的是 p1，即使默认已经变成 p2", "p1", resolved?.id)
        assertEquals("解析不该改动已有的归属", "p1" to "model-p1", env.routeOf("c1"))
    }

    @Test
    fun `没有默认时退到第一个服务商`() = runTest {
        val env = Env()
        env.provider("p1")          // 没有任何一个标了默认
        env.conversation("c1")

        assertEquals("p1", env.router.resolve("c1")?.id)
    }

    @Test
    fun `模型跟着服务商配置走而不是钉死`() = runTest {
        val env = Env()
        env.provider("p1", model = "old-model", makeDefault = true)
        env.conversation("c1", providerId = "p1", model = "old-model")

        // 用户去服务商编辑页把模型名改了（模型下线、改名是常事）
        env.provider("p1", model = "new-model", makeDefault = true)

        assertEquals(
            "改服务商配置里的模型名应该立刻对所有会话生效 —— " +
                "否则模型下线之后老会话会永久坏掉，只能一个个去换",
            "new-model",
            env.router.resolve("c1")?.model,
        )
    }

    // ---- 退化路径 ----

    @Test
    fun `钉着的服务商被删了就退回默认并改钉`() = runTest {
        val env = Env()
        env.provider("p2", makeDefault = true)
        env.conversation("c1", providerId = "gone", model = "model-gone")

        val resolved = env.router.resolve("c1")

        assertEquals("退回默认", "p2", resolved?.id)
        assertEquals(
            "必须改钉 —— 不改的话下次发消息还要再走一遍「找不到」，" +
                "而且用户会一直看到会话指向一个不存在的服务商",
            "p2" to "model-p2",
            env.routeOf("c1"),
        )
    }

    @Test
    fun `一个服务商都没配时返回 null`() = runTest {
        val env = Env()
        env.conversation("c1")

        assertNull("没配服务商是正常状态，返回 null 让界面去引导填 Key", env.router.resolve("c1"))
        assertEquals(
            "没有可用的服务商时不该往会话上写任何东西",
            null to null,
            env.routeOf("c1"),
        )
    }

    @Test
    fun `服务商还没填 Key 时仍然返回它`() = runTest {
        val env = Env()
        env.provider("p1", makeDefault = true, apiKey = null)
        env.conversation("c1")

        val resolved = env.router.resolve("c1")

        assertEquals("p1", resolved?.id)
        // 返回的 provider 的 config 为 null，界面据此显示「去填 Key」。
        // 这里不能返回 null —— 那会让界面以为「一个服务商都没配」，
        // 把用户引到「新建服务商」而不是「填上 Key」
        assertNull("这个服务商确实还没有 Key", resolved?.config)
    }

    @Test
    fun `会话不存在时也不崩`() = runTest {
        val env = Env()
        env.provider("p1", makeDefault = true)

        // 没建过这个会话：setRoute 会更新 0 行，静默无副作用
        assertEquals("p1", env.router.resolve("nope")?.id)
    }

    // ---- 主动更换 ----

    @Test
    fun `repin 把会话改钉到另一个服务商`() = runTest {
        val env = Env()
        env.provider("p1", makeDefault = true)
        env.provider("p2")
        env.conversation("c1", providerId = "p1", model = "model-p1")

        assertTrue(env.router.repin("c1", "p2"))

        assertEquals("p2" to "model-p2", env.routeOf("c1"))
        assertEquals("改钉之后解析出来就是 p2", "p2", env.router.resolve("c1")?.id)
    }

    @Test
    fun `repin 到不存在的服务商时不改动`() = runTest {
        val env = Env()
        env.provider("p1", makeDefault = true)
        env.conversation("c1", providerId = "p1", model = "model-p1")

        assertFalse("服务商不存在时应该如实返回 false", env.router.repin("c1", "gone"))

        assertEquals(
            "失败时会话必须保持原来的归属 —— 静默改成 null 的话，" +
                "用户会看到会话变成「没有服务商」而不知道为什么",
            "p1" to "model-p1",
            env.routeOf("c1"),
        )
    }
}
