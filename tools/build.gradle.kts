// :tools —— 内置工具包。纯 Kotlin（JVM）模块，零 Android 依赖。
//
// ## 为什么是纯 JVM
//
// 工具是「模型能做什么」的全部来源，也就是这个 App 最需要被反复测试的部分。
// 做成 Android library 的话，每个用例都要上模拟器（分钟级）；
// 保持纯 JVM 则全部走 `:tools:test`（秒级）。
//
// 需要 Android 能力（设备信息、剪贴板）的工具不直接调 Android API，
// 而是依赖一个窄接口（见 DeviceInfoSource），由 :app 注入实现 ——
// 单测里塞一个假实现就能覆盖全部逻辑。
//
// ## 为什么单独一个模块，而不是塞进 :domain
//
// :domain 是「协议与纯逻辑」，不该知道 HTTP 客户端、不该知道设备。
// 工具是**可插拔的功能单元** —— 将来插件宿主装进来的第三方工具
// 与内置工具在结构上应该是同一种东西，所以给它们一个自己的家。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":domain"))

    // OkHttp 从 :network 传递而来（:network 用 api 暴露了它）
    implementation(project(":network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
