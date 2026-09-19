// :data —— Android 库模块，承载 Room 数据库、加密的密钥存储、以及各 Repository 的落盘实现。
//
// 单独成模块的理由：数据库是唯一需要 Android 运行时（Context、SQLite、AndroidKeyStore）
// 的核心层，把 :domain 保持成纯 JVM 之后，领域逻辑的单测才能在秒级跑完。
// 模块内部用 KSP 生成 Room 代码（KAPT 已进入维护模式，不再使用）。
//
// 命名空间从 com.aichat.core.database 改成了 com.aichat.core.data ——
// 这里已经不只放数据库了（密钥、Repository 都在），旧名字会误导。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    // extraHeaders / tool_calls 这些列存的是 JSON 字符串，需要序列化
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aichat.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        // 只跑 instrumented test 时用不到 testInstrumentationRunner，
        // 但配上以后接 androidx.test 才不会报缺 runner。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // 迁移测试（MigrationTestHelper）要从 assets 里读导出的 schema JSON。
        // 用 KSP 的 room.schemaLocation 参数导出时，AGP **不会**自动把
        // schemas 目录挂进 androidTest 的 assets —— 必须手动挂，否则
        // MigrationTestHelper 会报「找不到 schema 文件」。
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    // 导出 schema。AppDatabase 明确不用 fallbackToDestructiveMigration()，
    // 那就必须有 schema 文件才能手写 Migration —— 这是它的前置条件。
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // 数据库需要 CjkText 做入库切分与查询构造
    api(project(":domain"))
    // ProviderEntity -> ProviderConfig 是跨模块类型，所以用 api 而不是 implementation
    api(project(":network"))
    // ConversationStore 接口定义在 :chat，本模块提供它的 Room 实现。
    // 用 api 而不是 implementation：RoomConversationStore 的构造参数与返回类型
    // 都是 :chat 的类型，不暴露出去的话，依赖 :data 的模块没法把它的实例
    // 赋给 ConversationStore 变量（编译期就会报「找不到父类型」）。
    api(project(":chat"))

    // PluginRepository 要解析清单、还要产出 PluginHost 认识的 InstalledPlugin。
    // 用 api：这些类型出现在它的公开签名里，依赖 :data 的模块必须能看见它们。
    api(project(":plugin"))

    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // JVM 单测：DAO 与 TransactionRunner 都是接口，可以手写假实现，
    // 于是 Repository 的全部逻辑都能在秒级跑完，不必等模拟器。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // `Manifests`（拼清单 JSON 的测试夹具）来自 :plugin 的 testFixtures ——
    // 测 PluginRepository 必须喂真实的清单文本，手写对象就跳过了
    // 「作者的 JSON 长什么样」这一层
    testImplementation(testFixtures(project(":plugin")))

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // MigrationTestHelper：迁移测试必须比对导出的 schema JSON，
    // 手写建表 SQL 去模拟 v1 迟早会跟真 schema 漂移
    androidTestImplementation(libs.androidx.room.testing)
}
