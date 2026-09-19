# R8 规则。**这个文件的存在本身就是它的主要作用。**
#
# app/build.gradle.kts 里 `proguardFiles(..., "proguard-rules.pro")` 引用了它，
# 而 AGP 在 R8 真正跑起来之前**不检查它存在**：isMinifyEnabled = false 时这个
# 参数从不被求值，文件缺失一声不响 —— 直到第一次打开混淆，才以
#
#     Supplied proguard configuration does not exist: .../app/proguard-rules.pro
#
# 挡住构建。也就是说它是一颗「等你要发布时才炸」的雷。
#
# ## 里面为什么暂时没有规则
#
# 依赖自带的 consumer rules 已经覆盖了本项目用到的全部入口：
#
#   - kotlinx.serialization：serializer 由编译器插件生成，consumer rules 保住
#     companion.serializer() 与 $$serializer。插件清单、Nav3 的路由类都靠它
#   - Room：*_Impl 由注解处理器生成，consumer rules 保住 RoomDatabase 的查找
#   - OkHttp / Compose / Navigation3 / AndroidX：各自的 consumer rules
#
# 而且本项目**零反射调用** —— 全仓库没有 Class.forName / getDeclaredField /
# getDeclaredMethod / javaClass / newInstance，也没有引入 kotlin-reflect。
# 那是 R8 最容易出事的一类，它不存在。
#
# ## 将来要加规则时写在哪
#
# 直接写在下面。**不要**改 `isMinifyEnabled` 去绕开问题 —— 那是把「构建失败」
# 换成「运行时崩」，后者要到用户手上才发现。
