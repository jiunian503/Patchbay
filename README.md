# Patchbay

Android 原生的 AI 对话 App —— **本地 BYOK**：自己填 API Key，会话与密钥只存在本机，
没有自建后端。核心是一个**集成平台**：内置工具和插件（声明式 / MCP / 脚本）在宿主眼里
是同一个东西，能力靠往外接。

## 下载

**[最新版本 →](https://github.com/jiunian503/Patchbay/releases/latest)**

- `patchbay-<版本>.apk` —— 安装包。minSdk 28（Android 9 及以上），**arm64-v8a**
- `mapping.txt` —— 混淆映射表。**只给开发者还原崩溃堆栈用，正常使用不用下载**

装之前要允许「未知来源」（侧载，不在任何应用商店里）。

> ⚠️ **32 位 only 的老设备装不上**，报 `INSTALL_FAILED_NO_MATCHING_ABIS`。
> 2019 年起 Google Play 就要求 64 位，**任何 64 位 ARM 手机都一定带 `arm64-v8a`** ——
> 所以正常手机不受影响；只有停在 Android 9 及更早、且 CPU 只有 32 位的设备装不了。
>
> 这是为了体积做的取舍。脚本插件要带 QuickJS 的原生库 —— 这一版 APK 里有 3 个
> `.so`、合计 1.19 MB，而且是**不压缩存储**的（R8 缩不掉，压缩也没意义，
> 加载时要 mmap）。多带一个 ABI 就多一份，所以只留了 `arm64-v8a`。
> 代价是这一版比 1.0（4.04 MB，那时还没有脚本插件）大了约 1.2 MB。

## 本地优先

会话、服务商配置、API Key 全在这台设备上：

- **API Key 用 AndroidKeyStore 加密后保存**，不会上传到任何服务器，也不会写进聊天数据库。
  请求直接从这台设备发往你填的地址
- **崩溃堆栈只写在本机**（设置 → 诊断 → 崩溃记录，可查看 / 复制 / 清除），**不接任何第三方上报**。
  那一行**只在真有崩溃记录时才出现** —— 没崩过就看不到，这是故意的：
  点进去是一页空白的话，用户只会以为功能坏了
- 出网请求只有四种，每一种的去向都写在这儿：模型请求（你填的服务商）、联网搜索（你选的后端）、
  `fetch_url` 抓网页（**地址由模型挑，所以每一次都要你点确认**）、插件声明的白名单主机

## 功能

**对话**

- 多服务商 BYOK，每个服务商可以单独设系统提示词 / 温度 / 最大回复长度
- 流式输出、重新生成、编辑重发、置顶、导出 Markdown
- 会话分页：往上翻自动加载更早的消息

**检索**

- 本地全文检索，中文可以直接搜（FTS + 中文处理），结果里命中词高亮

**工具**（界面上按风险从低到高排，高风险动作先确认）

| 工具 | 做什么 |
|---|---|
| `get_current_time` | 当前时间 |
| `calculate` | 表达式求值 |
| `device_info` | 设备信息 |
| `search_history` | 只读本地会话 |
| `search_web` | 联网搜索，**不配置也能用**（内置 Bing），也可以换成自己的后端 |
| `fetch_url` | 抓取网页正文 |

**插件** —— 三种形态，在宿主眼里是同一个东西

| 形态 | 清单里写什么 | 适合什么 |
|---|---|---|
| 声明式 | 一份 HTTP 调用配置 | 对接现成的 REST 接口，零代码 |
| MCP | 一个 Streamable HTTP 地址 | 接已有的 MCP 服务 |
| 脚本 | 内联一份 JS 源码 | 要算、要拼、要判断的活 |

> 清单 schema 里还有一个 `native`（随 APK 打包的二进制）。它**能通过校验**，
> 但当前宿主还不支持，装了会明确告诉你「这个插件暂时不会提供任何工具」——
> 而不是给你一个空工具列表让你以为没装上。

- 插件只能经宿主白名单访问网络，**拿不到系统权限** —— 它们不是独立安装的 App
- **脚本插件跑在独立进程里**（QuickJS 沙箱）：死循环、崩溃、`while(true)` 杀掉的是那个进程，
  主进程和你的会话不受影响
- 声明了 `filesystem` 权限的插件会拿到一个**只有它自己看得见**的目录
  （上限 8 MB / 256 个文件，卸载插件一并清掉）
- 装插件：「插件」页右下角的 **「安装插件」** 按钮。五条路：输入框直接粘清单、
  「试试内置示例」、「从剪贴板」、「从文件…」、「从网址…」

**其他**

- 宽屏 / 横屏适配，顶栏毛玻璃
- 明暗主题跟随系统

## 从源码构建

需要 **JDK 17** 与 Android SDK（compileSdk 36）。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

跑测试：

```bash
./gradlew :domain:test :network:test :chat:test :data:test \
          :tools:test :plugin:test :app:testDebugUnitTest
```

真机用例（需要连一台设备，且方法名**不能带空格**）：

```bash
./gradlew :app:connectedDebugAndroidTest :data:connectedDebugAndroidTest
```

还有一组零依赖的工具链判据（不需要 Gradle）：

```bash
python -m unittest discover -s tools/tests
```

## 结构

| 模块 | 职责 |
|---|---|
| `:app` | UI / 导航 / 依赖装配 |
| `:chat` | 对话编排 + 工具循环 |
| `:network` | OkHttp 流式 |
| `:domain` | **纯逻辑，零 Android** |
| `:tools` | 内置工具 |
| `:plugin` | 插件宿主 |
| `:data` | Room + FTS4 |
| `plugin/examples` | 示例插件 |
| `tools` | 构建 / 验收 / 归档脚本 |
| `spike` | 早期技术验证的报告与探针（历史留档） |

三条边界值得说明：

1. **`:chat` 不依赖 `:plugin`** —— 工具经 `:domain` 的 `ToolRegistry` 注入，
   而 `:tools` 与 `:plugin` 对外是**同一个** `Tool` 类型
2. **`:tools` 与 `:plugin` 是纯 JVM 模块** —— 外部能力靠窄接口由 `:app` 注入。
   一旦引 Android 或 AAR，整模块会变成 Android library，所有测试从秒级变分钟级
3. **工具在界面上的顺序 = 风险从低到高**

`:sandbox` 是一个**独立进程**（`android:process=":sandbox"`），只用来执行第三方脚本：
`exported="false"`，且不初始化 App 的任何东西（不建数据库、不装崩溃留痕）。
进程被隔离之后，插件里的 `while(true)` 或段错误带走的只有它自己。

## 写一个插件

插件是一份 JSON 清单，放在一个能通过 HTTP 取到的地方就行。最小的声明式插件：

```json
{
  "id": "com.example.hello",
  "name": "打个招呼",
  "version": "1.0.0",
  "runtime": "declarative",
  "permissions": {
    "network": ["api.example.com"],
    "filesystem": "none"
  },
  "entry": {
    "declarative": {
      "baseUrl": "https://api.example.com",
      "auth": { "type": "none" }
    }
  },
  "tools": [
    {
      "name": "hello",
      "description": "向示例服务打个招呼。只在用户明确要求打招呼时使用。",
      "parameters": {
        "type": "object",
        "properties": { "name": { "type": "string" } },
        "required": ["name"]
      },
      "request": {
        "method": "GET",
        "path": "/hello",
        "query": { "name": "{{name}}" }
      }
    }
  ]
}
```

要点：`baseUrl` 的主机必须同时出现在 `permissions.network` 里（否则安装就报错）；
声明式插件的**每个**工具都必须给 `request`，否则宿主不知道发什么请求。
上面这段是能装的 —— 它由 `ReadmeManifestTest` 盯着，改坏了测试会红。

脚本形态则是把 `runtime` 改成 `"script"`，加上 `entry.script`（入口文件名、内存上限、
超时），并用 `files` 把源码内联进清单 —— 这样一份清单就是一份自足的文档。

完整字段见 [`plugin/manifest.schema.json`](plugin/manifest.schema.json)，
可直接运行的例子见 [`plugin/examples/`](plugin/examples/)（`weather` 是声明式，`csvstat` 是脚本）。

> 注意：`permissions.network` 是**白名单**，宿主按主机名全等匹配。插件拿不到任何
> 没声明的东西 —— 脚本形态连 `fetch` 都是宿主注入的，QuickJS 本身没有 IO 能力。

## 发版

见 [`RELEASING.md`](RELEASING.md)。

## 许可

**本仓库目前没有声明许可证。** 按默认的著作权规则，这意味着你不能复制、修改或再分发
这里的代码 —— 如果你需要某个许可证，请开一个 issue 说明用途。

> 注：`plugin/examples/` 下两个示例插件的清单里写的是 `"license": "MIT"`，
> 那是示例插件自身的声明，不覆盖仓库其余部分。
