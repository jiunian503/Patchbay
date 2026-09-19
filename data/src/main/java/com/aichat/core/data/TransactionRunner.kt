package com.aichat.core.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * 把「跑一个事务」抽象出来。
 *
 * 存在的唯一理由：**让 Repository 能在 JVM 单测里跑**。
 * 直接依赖 `RoomDatabase.withTransaction` 的话，任何一行 Repository 代码
 * 都必须在模拟器上验证（分钟级）；抽象成接口之后，测试传一个直接执行
 * block 的假实现，整个逻辑在毫秒级跑完，只有 SQL 本身留给 instrumented test。
 *
 * 生产实现见 [RoomTransactionRunner]。
 */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/** 真的开一个 SQLite 事务。 */
class RoomTransactionRunner(private val db: RoomDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}

/** 不开事务，直接跑。**只能用于测试或确定无并发风险的场景。** */
object NoTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}
