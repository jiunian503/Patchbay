// :domain —— 纯 Kotlin（JVM）模块，零 Android 依赖。
//
// 放这里的都是可以在普通 JVM 上跑单元测试的纯逻辑：
// 中文分词（CjkText）、流式 Markdown 渲染调度（StreamingMarkdownRenderer）、
// LLM 协议模型与 SSE 流式解析（llm 包）。
// 好处是这些核心逻辑的测试不需要模拟器/真机，秒级反馈。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api 而非 implementation：对外暴露的 DTO 带 @Serializable，
    // 消费方（:data、:plugin、:app）需要能解析这些类型。
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
