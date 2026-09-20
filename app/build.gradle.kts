plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    // Kotlin 包名 / 资源命名空间。**故意不跟着 applicationId 改。**
    //
    // AGP 7 起 namespace 与 applicationId 是两个东西：前者只决定 R 类、生成的
    // BuildConfig 和资源命名空间，**不影响装到设备上的身份**。
    //
    // 而本项目恰好可以完全不动它 —— 全仓库**零 `R.` 引用、零 `BuildConfig`
    // 引用**（`buildConfig = false`），也没有任何跨包引用 `com.aichat.R`，
    // 所以改它只是白搬 168 个文件。更硬的理由是 Room：schema 目录名取自
    // `@Database` 类的全限定名（`data/schemas/com.aichat.core.data.AppDatabase/`），
    // 挪包名就得同时挪那个目录，而迁移测试正是靠它建出「老版本库」的。
    //
    // 代价只有一个：读代码时包名和 App 名对不上。这是常规做法，忍了。
    namespace = "com.aichat"
    compileSdk = 36
    defaultConfig {
        // **唯一「发布后改不了」的东西。**
        //
        // 不能留 `com.aichat` —— 那是别人的域名，上架或发版随时可能撞名。
        // 用 `io.github.<用户名>` 是因为唯一性由 GitHub 命名空间保证，不必先买域名。
        // **换 GitHub 用户名 = 换 applicationId = 老用户装不上新版**，
        // 所以这个值一旦发过版就不能再动。
        //
        // 改它的副作用是「设备上会多出一个 App」：Android 按 applicationId 认身份，
        // 旧包 `com.aichat` 的数据不会迁过来。还没发布时无所谓，发布后等于
        // 所有用户的本地数据凭空消失。五十四轮改这一次，就是为了以后不必再改。
        //
        // **用户名已确认（五十六轮）**：五十四轮这里写的是 `io.github.nian.patchbay`，
        // 那个 `nian` 是**我替 Boss 假设的**占位值（他当时把定名权交了出来）。五十六轮
        // 他给出真值 `jiunian503`，于是改掉 —— 还没发版，代价为零。
        // **从这一版起，这个字符串冻结。**
        applicationId = "io.github.jiunian503.patchbay"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // 跑 app 的 instrumented test（NavScopingTest）需要显式指定，
        // 别依赖 AGP 的默认值 —— 默认值改了的话报错是「找不到 runner」，
        // 和「测试没配好」长得一模一样，查起来要绕一圈
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 开混淆。实测三档（同一个 commit）：
            //
            //   debug                      36,410,283
            //   release 不混淆              25,706,839
            //   release 开混淆               3,997,760   ← 比 debug 小 89%
            //
            // 敢开，是因为 R8 最大的风险源在本项目不存在：**零反射**（全仓库没有
            // Class.forName / getDeclaredField / javaClass / newInstance，也没引
            // kotlin-reflect），而用到的库（kotlinx.serialization、Room、OkHttp、
            // Compose、Navigation3）都自带 consumer rules。
            //
            // 真机验过（release APK 拿 debug key 签了再装）：会话数据保留、插件页
            // 解析出 2 个插件（MCP + 声明式）、设置页的密钥标记还在（KeyStore 解密
            // 正常）、发一条消息走完流式 + 工具循环（模型看到全部 11 个工具）。
            // R8 也没生成 missing_rules.txt —— 它没发现「被反射用到但没 keep」的东西。
            //
            // **mapping.txt 必须归档**（app/build/outputs/mapping/release/mapping.txt，
            // 约 42 MB）：混淆后的崩溃堆栈要靠它还原，而且它只对那一版 APK 有效 ——
            // 丢了就永远读不懂那一版的线上崩溃。它现在被 .gitignore 的 `build/` 挡在
            // 仓库外（对的，42 MB 不该入库），所以发布流程里要有「归档 mapping」这一步。
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      unitTests.all {
        // 把 assets 目录告诉 JVM 单测。
        //
        // 单元测试拿不到 Android 的 AssetManager，而「随包发的示例清单能不能装」
        // 又必须测（那是用户第一眼看到的东西）。用系统属性把路径传进去，
        // 比让测试去猜工作目录可靠 —— 猜错了会以「文件不存在」的形式失败，
        // 看起来像是清单丢了，实际是路径不对
        it.systemProperty("patchbay.assetsDir", "$projectDir/src/main/assets")
        // 作者参考版示例的位置（`plugin/examples/*/manifest.json`）。
        //
        // 同样用系统属性传，不用 `File("..").canonicalFile` 去猜 —— 猜错了会以
        // 「目录不存在」的形式失败，看起来像是示例文件丢了，实际是路径不对。
        // 而这条测试要证明的恰恰是「两份文件一致」，它自己先走错路就没有说服力了
        it.systemProperty("patchbay.examplesDir", "${rootProject.projectDir}/plugin/examples")

        // **把这两个目录声明成测试任务的输入。**
        //
        // 有几条测试直接读文件系统里的这些文件（不是 classpath 资源），
        // 而 Gradle 默认不知道这层依赖 —— 只改示例文件、不改测试源码时，
        // 测试任务会被判定 UP-TO-DATE 而**整个跳过**。
        // 「示例文件变了」恰恰是那些测试唯一要抓的场景，于是它们会以
        // 「通过」的形式安静地失效 —— 那比没有测试更糟，因为它看起来是绿的。
        //
        // 用 RELATIVE 路径敏感度：换台机器、换个 checkout 目录不该触发重跑，
        // 文件内容变了才该
        it.inputs.dir("$projectDir/src/main/assets").withPathSensitivity(PathSensitivity.RELATIVE)
        it.inputs.dir("${rootProject.projectDir}/plugin/examples")
            .withPathSensitivity(PathSensitivity.RELATIVE)
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // 本项目模块。
  // :chat 用 api 暴露了 :network 与 :domain，:data 用 api 暴露了 :chat，
  // 所以这一处就能拿到对话核心、网络客户端、领域模型、数据库与密钥存储。
  implementation(project(":data"))
  // 内置工具包。模型能做什么全在这里，所以它必须是独立、可单测的一块
  implementation(project(":tools"))
  // 插件宿主。:data 用 api 暴露了它，但装配插件是 :app 的直接职责
  // （AppContainer / ToolRegistryHolder 都直接引用 PluginRegistry），
  // 显式写出来，别让「能编译过」依赖别人恰好用了 api 而不是 implementation
  implementation(project(":plugin"))

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // 毛玻璃（Apache-2.0）。只碰 :app —— 它本来就依赖全部模块，所以加进来
  // 不会污染 :tools / :plugin 的纯 JVM 纪律。
  //
  // 1.x 是**单 artifact**（源捕获和效果都在这一个里）；2.0 才拆成
  // haze / haze-blur / haze-blur-materials 好几个。为什么钉在 1.6.10
  // 而不是更新的版本，见 libs.versions.toml 里那段注释。
  implementation(libs.haze)
}
