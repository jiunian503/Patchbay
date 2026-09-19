// :plugin —— 插件宿主。纯 Kotlin（JVM）模块，零 Android 依赖。
//
// ## 为什么是纯 JVM
//
// 这个模块决定「第三方代码能对这个 App 做什么」。它的每一条规则都是安全边界：
// 白名单怎么匹配、占位符会不会被注入出额外的路径段、重定向能不能跳出白名单、
// 插件工具能不能覆盖内置工具。这些东西必须**能被穷举测试**，
// 而不是「上模拟器点一遍看着没问题」。
//
// 做成 Android library 的话每个用例都要上模拟器（分钟级）；纯 JVM 走
// `:plugin:test`（秒级），一条规则一个用例，改坏了立刻知道。
//
// 需要 Android 能力的地方（安装目录、Keystore 取密钥、Node 运行时）
// 一律抽成窄接口，实现放 `:app` —— 和 `:tools` 的做法一致。
//
// ## 为什么单独一个模块，而不是塞进 :tools
//
// :tools 是「内置工具」，:plugin 是「**装载**别人写的工具」。
// 两者的关注点没有交集：前者关心某个功能怎么实现，后者关心权限、
// 清单校验、运行时适配。混在一起之后，改白名单匹配规则会碰到计算器工具，
// 这是没必要的耦合。
//
// 但两者对外的类型是**同一个** `Tool` / `ToolDefinition` —— 这是刻意的：
// 插件工具和内置工具在引擎看来完全一样，不存在「内置走特权路径」。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // 测试夹具：`Manifests`（拼清单 JSON 的小工具）要给 :data 的
    // PluginRepositoryTest 用。它依赖 :plugin 的 main 类型，所以只能走
    // testFixtures —— 放 test 源码集里别的模块看不见，
    // 而放进 main 又等于把测试工具打进发布产物
    `java-test-fixtures`
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
    // 测试夹具要用主源码集里的类型（PluginManifest / PluginSettings），
    // 所以 test 源码集必须能看见 testFixtures
    testImplementation(testFixtures(project(":plugin")))
}
