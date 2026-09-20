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
import org.gradle.api.tasks.PathSensitivity

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

tasks.test {
    // 仓库根那份 README 的路径。
    //
    // 为什么 :tools 要管 README：README 里那张工具表是对「这个 App 能做什么」
    // 最直接的回答，而工具由**本模块**定义。表里的名字和 `BuiltinTools.all()`
    // 漂移了不会有任何东西报错 —— 只有人真去看才发现，而人不会每次都去看。
    // `BuiltinToolsTest` 里那条「README 的工具表与内置工具一一对应」用它。
    //
    // 和 :plugin 一样用 rootProject 而不是 `$projectDir/../README.md`：
    // README 就在仓库根，把这件事说清楚比省一次引用更值。
    systemProperty("patchbay.readmeFile", rootProject.file("README.md").absolutePath)

    // **必须声明成输入。** 不声明的话，只改 README 不会动任何源码，
    // 任务会被判 UP-TO-DATE 而整个跳过 —— 于是这条测试唯一要抓的那个场景
    // 会以「测试通过」的形式安静地失效。那比没有测试更糟，因为它是绿的（§38）。
    inputs.file(rootProject.file("README.md"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
