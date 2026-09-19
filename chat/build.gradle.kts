// :chat —— 对话核心（应用层）：会话编排 + 工具循环。
//
// 单独成模块的理由：这里是产品最核心、最容易出错的逻辑（工具循环、上下文组装、
// 错误回灌），必须能用假 client 跑完整单测。放在 :app 里就只能跑 Android 测试，
// 迭代成本高一个数量级。
//
// 依赖方向：:chat → :network → :domain，:chat → :domain。
// 它**不依赖** :plugin —— 工具通过 :domain 里的 ToolRegistry 接口注入，
// 插件宿主将来实现这个接口即可。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":domain"))
    api(project(":network"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
