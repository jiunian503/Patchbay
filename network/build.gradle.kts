// :network —— 纯 Kotlin（JVM）模块，只负责「把一次对话请求变成 ChatStreamEvent 流」。
//
// 单独成模块的理由：
// 1. 纯 JVM，可以用 MockWebServer 跑流式测试，不需要设备，秒级反馈；
// 2. HTTP 客户端是共享基础设施 —— 将来 declarative 插件、MCP 客户端都要用它，
//    不该埋在 :data 里跟 Room 搅在一起。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api：对外暴露 ChatStreamEvent / 请求 DTO 等 domain 类型
    api(project(":domain"))
    api(libs.okhttp)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
