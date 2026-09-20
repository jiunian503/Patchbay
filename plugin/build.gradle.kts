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
import org.gradle.api.tasks.PathSensitivity

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

tasks.test {
    // 把 schema 文件的路径告诉 JVM 单测。
    //
    // `ManifestModelTest` 要读 `manifest.schema.json`，拿它和 Kotlin 模型
    // 逐字段比对。用系统属性传路径，比让测试去猜工作目录可靠 ——
    // 猜错了会以「文件不存在」的形式失败，看起来像 schema 丢了，
    // 而真正要证明的是「schema 和模型一致」，它自己先走错路就没有说服力。
    // （:app 那边的 assets / examples 也是这么传的，见 app/build.gradle.kts）
    systemProperty("patchbay.schemaFile", "$projectDir/manifest.schema.json")

    // 作者参考版示例的路径。它就在**本模块自己的目录**下（`plugin/examples/`），
    // 所以这里不用去问 rootProject —— 少一处跨模块的路径假设。
    //
    // 为什么要让 :plugin 也知道它：那几份清单是**插件协议的参考实现**，
    // 而 `plugin/examples/csvstat`（唯一的 script 示例）**故意不随包发**
    // （它的运行时还没实现），于是 :app 那边遍历 assets 的测试根本看不到它。
    // 结果是：协议里 `script` 这一支的示例从来没被解析过，改错了没人知道。
    systemProperty("patchbay.examplesDir", "$projectDir/examples")

    // 仓库根那份 README 的路径。
    //
    // 为什么 :plugin 要管 README：那份文档里有一段手写的示例清单，而
    // **示例清单属于本模块的协议**。作者照着它抄，抄出来的东西要能装 ——
    // 所以 `ReadmeManifestTest` 把那段 JSON 喂给真的 `ManifestParser`。
    //
    // 用 rootProject 而不是 `$projectDir/../README.md`：README 就在仓库根，
    // 说清楚这件事比省一次引用更值 —— 也免得哪天 :plugin 被挪到别的层级。
    systemProperty("patchbay.readmeFile", rootProject.file("README.md").absolutePath)

    // **把 schema 声明成测试任务的输入。**
    //
    // 这条不能省：Gradle 只看测试任务的声明输入（源码、classpath），
    // 而这里读的是一个普通文件。只改 schema、不改测试源码时，任务会被判
    // UP-TO-DATE 而**整个跳过** —— 于是「schema 和模型漂移了」这个唯一要抓的
    // 场景，会以「测试通过」的形式安静地失效。那比没有测试更糟，因为它是绿的。
    //
    // RELATIVE 而不是默认的 ABSOLUTE：换机器、换 checkout 目录不该触发重跑，
    // 文件内容变了才该。
    inputs.file("$projectDir/manifest.schema.json")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // 示例目录同理，而且这里**更容易**踩 UP-TO-DATE：示例是 JSON 数据，
    // 改它不会动任何源码。不声明的话，「示例清单被改坏了」会以「测试通过」
    // 的形式安静地失效 —— 和上面 schema 那条是同一个坑（§38）。
    inputs.dir("$projectDir/examples")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // README 同理，而且是这里**最容易**漏掉的一个：它是仓库根的 Markdown，
    // 改它不会动任何源码、也不在 :plugin 目录下。不声明的话，
    // 「README 里的示例清单被改坏了」会以「测试通过」的形式安静地失效 ——
    // 而 `ReadmeManifestTest` 存在的**全部意义**就是抓这一种改动。
    inputs.file(rootProject.file("README.md"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
