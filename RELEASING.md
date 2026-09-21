# Patchbay 发版流程

> 判据与踩坑的完整记录在手册 `~/.workbuddy-ai/skills/android-toolchain-windows/SKILL.md` §79。
> 这份是**操作清单** —— 照着从上往下做就行。带 ⚠️ 的是踩过的坑。

下面所有命令都在 `ai-chat-app/` 目录下跑：

```bash
cd "C:/Users/nian/WorkBuddy AI/2026-09-18-13-49-49/ai-chat-app"
export ANDROID_HOME="C:/Users/nian/Android/Sdk"
G="C:/Users/nian/Android/tools/gradle-9.1.0/bin/gradle"
```

---

## 一次性准备（只做一次）

### 1. 生成发布密钥库

```bash
keytool -genkeypair -v -keystore patchbay-release.jks \
  -alias patchbay -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Patchbay, O=Patchbay, C=CN"
```

它**只会问你两遍口令**（「输入密钥库口令」/「再次输入新口令」），其余从 `-dname` 拿。

> 不加 `-dname` 的话它会连着问 7 个问题（姓名 / 组织 / 城市 / 省 / 国家代码），
> 而且最后的确认提示是中文 `[否]:` —— 输 `y` 不认，会绕回第一个问题。别走那条路。

> ⚠️ **这条命令会覆盖已有的密钥库。** 先 `ls patchbay-release.jks` 看一眼 ——
> 已经有了就**别重复跑**，重跑等于换签名（老用户装不上新版）。

⚠️ **这一步只做一次，做完立刻备份。**

Android 只认一个签名 —— **密钥丢了，所有老用户只能卸载重装**（不是「更新」）。
这比 mapping 更硬：mapping 丢了只是读不懂旧崩溃。

### 2. 备份到两处（互相独立）

要备份的**是两样东西**：

| 备份什么 | 说明 |
|---|---|
| `patchbay-release.jks` | 密钥本身 |
| 那个口令 | **只备份 `.jks` 等于没备份** |

**第一处：已做（2026-09-20）**

```
D:\Patchbay-发布密钥备份\
    patchbay-release.jks   密钥库本身
    口令.txt                口令 + keyAlias + 自查命令
    README.txt              这是什么 / 丢了会怎样 / 怎么验 / 还差什么
```

（`D:` 是那块卷标为「工具」的盘。）

**第二处：已做（2026-09-20）**

```
C:\Users\nian\Downloads\Patchbay-发布密钥备份\
    patchbay-release.jks   密钥库本身
    口令.txt                口令 + keyAlias + 自查命令
    README.txt              这是什么 / 丢了会怎样 / 怎么验 / 还差什么
```

**这一处的定位要说清楚，别把它当成「已经安全了」**：它在同一台机器的另一块盘上，
所以只防「一块盘坏掉」，**不防「整台机器丢 / 坏 / 被偷」**。真正防后者的是
**Boss 把整个目录发到微信里**（或存密码管理器的文件附件）—— 那一步是他的，
不在这份清单的自动流程里。做了之后**回来把这句改成「三处」**。

> ⚠️ **`README.txt` 里别写死「另一个文件」的哈希。**
> 它原来把 `口令.txt` 的 sha256 写死在里面当核对判据，而那个文件后来因为**别的原因**
> 改过一次（把「第二处请放密码管理器」改成与上面这个决定一致），哈希当场过期 ——
> 那一行就从「核对用的判据」变成「一句看着很权威的错话」。
> 正确写法是给一条**能自己算**的命令（`sha256sum` 两边对照），
> 并明说「本文件不列自己的哈希 —— 自指」。判据见 SKILL.md **§91⑧**。

> ⚠️ **备份不是「拷过去就算数」—— 要能证明它可用。** 三条判据，缺一不可：
>
> 1. **拷贝完整**：`sha256sum` 两边一致
> 2. **备份自足**：只用备份目录里的 `.jks` + 口令跑
>    `keytool -list -v -keystore <备份的.jks> -storepass <备份里的口令>`，
>    能列出别名为 `patchbay` 的证书
> 3. **指纹对得上线上**：`keytool` 报的证书 SHA-256，等于
>    `apksigner verify --print-certs <已发布的 apk>` 报的那个 —— 说明备的就是
>    **签过所有已发布版本**的那把钥匙。
>    **别在这里列版本号**（原来写的是「签过 v1.0 / v1.1 的那把钥匙」，而这句话
>    每发一版就少一分对 —— 和 `README.txt` 里写死哈希是同一个毛病，§91⑧）。
>    现在发到 `v1.2` 了，判据是把指纹对**任意一版**线上 APK：
>    `d14dd5a010143d84d0cb243485198e302db18b8b55430d370a5ee5e7b3e94b2d`。
>    **这个值是密钥的属性、不随发版变** —— 所以它可以写死，版本号不行。
>
> 想再硬一点就真签一次（**在 APK 的副本上**，别动原件）：
>
> ```bash
> # 用任意一版已归档的 APK 都行；下面是当时最新的那版
> cp dist/patchbay-1.2/patchbay-1.2.apk /tmp/t.apk
> "$ANDROID_HOME/build-tools/36.1.0/apksigner.bat" sign \
>   --ks <备份的.jks> --ks-pass pass:<口令> --ks-key-alias patchbay --key-pass pass:<口令> /tmp/t.apk
> ```
>
> 退出码 0 = **私钥真的可用**，这份备份能签出「老用户装得上」的更新包。

### 3. 建 `keystore.properties`

在 `ai-chat-app/` 下建这个文件（已在 `.gitignore` 里，不会入库）：

```properties
storeFile=../patchbay-release.jks
storePassword=<你设的口令>
keyAlias=patchbay
keyPassword=<你设的口令>
```

两个坑，都是实测出来的：

**① `storeFile` 的基准是 `app/` 模块目录，不是仓库根。**

`:app` 的 `projectDir` 是 `ai-chat-app/app`，而 `build.gradle.kts` 里的 `file()` 就以它为准。
所以密钥库放在 `ai-chat-app/` 根，这里要写 `../patchbay-release.jks`。
写错了报的是 `Keystore file not found` —— 这个一眼能看出。

**② `keyPassword` 必须和 `storePassword` 一模一样。**

`keytool` 默认生成 **PKCS12** 格式，而 PKCS12 不支持「密钥口令 ≠ 库口令」。
写成不一样会报：

```
KeytoolException: Failed to read key patchbay from store "...patchbay-release.jks":
Get Key failed: Given final block not properly padded.
Such issues can arise if a bad key is used during decryption.
```

⚠️ **这个报错完全看不出是口令问题** —— 它长得像「密钥库损坏」。
**千万别去重新生成密钥库**（那才是真的完了），把两个口令改成一样即可。

### 4. 验证签名真的生效

```bash
$G :app:assembleRelease --max-workers=2 --no-configuration-cache

ls app/build/outputs/apk/release/
"$ANDROID_HOME/build-tools/36.1.0/apksigner.bat" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

**判据**：

1. 目录里出现的是 **`app-release.apk`**，不是 `app-release-unsigned.apk`
2. `apksigner` 输出 `Verifies`，且证书 DN 是你 `-dname` 里填的那个
3. **`lib/` 下只有一个 ABI 目录**（`arm64-v8a`）：

```bash
python -c "
import zipfile
z=zipfile.ZipFile('app/build/outputs/apk/release/app-release.apk')
print(sorted(set(i.filename.split('/')[1] for i in z.infolist() if i.filename.startswith('lib/'))))
"
```

4. **`.so` 的对齐没变**（只有一个库是 16 KB 对齐的，两个 QuickJS 的仍是 4 KB）：

```bash
python tools/elf_align.py app/build/outputs/apk/release/app-release.apk
```

⚠️ 少了 `keystore.properties`（或四个键有缺的）时 AGP 会**静默退回产出 unsigned 包**，
不报错。所以「看产物名」这一步不能省。

> **release 包只带 `arm64-v8a`**（见 SKILL.md §88）。
> 要把它装进模拟器压一遍（§69 要求）时，靠的是模拟器的 **ARM 翻译层** ——
> MuMu 12 有（`getprop ro.product.cpu.abilist64` 里能读到 `arm64-v8a`），
> 别的镜像**不一定有**。装之前先看那个 prop：没有 `arm64-v8a` 就会报
> `INSTALL_FAILED_NO_MATCHING_ABIS` —— 那时**用 debug 包验**（四个 ABI 全在），
> 别去改 release 的过滤。
>
> **装完第一句看 `dumpsys package … | grep primaryCpuAbi`**，期望 `arm64-v8a`。
> 拿到 `null` 就说明包不对。
>
> 用户那边：**32 位 only 的老设备（停在 Android 9 及更早）装不上**，报的也是
> `INSTALL_FAILED_NO_MATCHING_ABIS`。这是这一刀明确的代价 —— 要照顾它们就把
> `armeabi-v7a` 加回 `buildTypes.release.ndk.abiFilters`，一行。

---

## 每次发版

### 5. 改版本号

`app/build.gradle.kts` 里的 `versionCode`（**每次 +1，不可回退**）和 `versionName`。

> 归档脚本读不出这两个值会**直接停下** —— 不会给你一个叫 `patchbay-unknown` 的目录。

> ⚠️ **别靠体积认版本。** 版本串位数相同时，两版的 APK 大小会非常接近 ——
> v1.2 是 `5,324,829` B，v1.1 是 `5,308,445` B，**只差 16 KB（0.3%）**，扫一眼
> 会以为「一样」。判定一律用 `aapt2 dump badging` 直接读
> `versionCode` / `versionName`（见第 6 步）。判据见 SKILL.md **§92 十五⑤**。

> ⚠️ **README 里写死的体积数字要跟着改。** 「32 位 only 的老设备装不上」那段引用了
> 1.0 的字节数和**这一版**的字节数 —— 没有任何测试盯着它们（`NetworkEgressTest` 只守
> 「出网请求只有 N 种」那一句）。实测踩过：v1.2 时那句写的是「大了约 1.2 MB」，
> 到 v1.4 实际已经是 1.45 MB，中间隔了两版没人动它。
> 改完核一遍：`grep -nE "[0-9],[0-9]{3},[0-9]{3} B" README.md` —— 它会命中**两处**：
> `.so` 合计与 APK 总字节（外加 v1.0 那个对照值），**每一处都要核**。
> `.so` 那处只在换 QuickJS 版本时才变，最容易漏。
> 引用体积**一律写字节数**，别写 MB —— 这个仓库里 MB 有 10⁶ 和 MiB 两种用法，
> 同一个数按两种算法写出来像两个数。

### 6. 构建 + 两条验收

```bash
$G :app:assembleRelease --max-workers=2 --no-configuration-cache
```

**改过版本号之后必须重新验收** —— 版本串进了 `AndroidManifest.xml`，APK 的字节就变了，
而验收验的就是**要发出去的那一份**。两条路，都要走：

| 验什么 | 判据在哪 | 要点 |
|---|---|---|
| **全新安装** | SKILL.md **§89**（15 条） | 装完看 `primaryCpuAbi`；压到「脚本插件」那条路（`:sandbox` 是唯一 dlopen 自己原生库的地方）；收尾记下 APK 的 **sha256** |
| **从上一版覆盖升级** | SKILL.md **§90**（8 条） | 装上一版的 APK 造出数据 → `install -r` 新版 → 逐样核对；`firstInstallTime ≠ lastUpdateTime` 才说明是更新 |

> 全新安装**验不到**升级路径上的东西（Room 迁移、密钥解密、插件数据、ABI 变化），
> 而真实用户大多是从上一版升上来的 —— 所以两条都要跑。
>
> **顺序上：两条不能在同一个安装上连着跑**（覆盖升级那条要先有一个旧版本在机器上）。
> 但先跑哪个都行 —— 只要每次从对的状态起步：`uninstall` → 装旧版 → 造数据 → `install -r`。
>
> 两条都验完**都要 `uninstall`**，否则会挡住后面所有 debug 安装。
> 顺手把 `adb reverse --remove-all` 也清掉。
>
> **另外，release 上要单独走一遍「设置 → 关于 → 检查更新」** —— 它打网络 + 走
> `kotlinx.serialization`，而 R8 要是把 `@SerialName` 剥掉，字段名会退回 Kotlin
> 属性名、`tag_name` 匹配不上，界面说「看不懂对方返回的内容」。**这个失败只在
> release 上出现，debug 包永远绿**（debug 不混淆）。判据见 SKILL.md **§92 第十一节**。
>
> **而且这一条只要说「已是最新」就够了，不必等到弹框。** `ReleaseDto` 的两个字段是
> **可空带默认值**的 ⇒ `@SerialName` 被剥掉**不抛异常**，而是字段留空 ⇒ 返回
> `Malformed`；而 `UpToDate` **只能**由 `Found` 产生。所以「已是最新」这一句同时证明
> 两个 `@SerialName` 都活着（外加 HTTPS 通了、版本比较对）。判据见 **§92 十五①**。
>
> ⚠️ **想用旧版去看「有新版本」的对话框时注意**：`检查更新` 是 v1.2 才有的，
> **v1.1 的设置页滚到底只有「服务商」一节、根本没有「关于」**。所以「装回上一版去
> 看对话框」这条路，在**第一个带这个功能的版本**上是走不通的 ——
> 只有等到 v1.3 才成立。别把「旧版找不到入口」误读成功能坏了。
>
> 全新安装那条要**真的滚到底看一眼设置页有几节** —— 「升级后会多出一节」也是判据。

### 7. 归档 APK + mapping

**先提交版本号那处改动**，再归档：

```bash
git add app/build.gradle.kts && git commit -F - <<'EOF'
发版 1.2：versionCode 2 -> 3

<这里写这一版的增量、要发的字节（大小 + sha256）、以及三条验收的结果>
EOF
```

> ⚠️ **提交信息里有反引号 / `$` 就别用 `-m`。** 用 `-m "…` 包着 `@SerialName` 这种
> 带反引号的内容时，bash 会报 `unexpected EOF while looking for matching \``，
> 而且**这条命令里在它前面写好的东西也一起不执行** —— 而下一步看起来是正常的
> （提交成功），直到归档 `README.txt` 写出「有未提交改动」才发现。
> 用 `git commit -F - <<'EOF'` 时，**`'EOF'` 的引号不能省**，否则 `$` 和反引号
> 照样会被展开。判据见 SKILL.md **§92 十五⑦**。

> 归档脚本会把 `git rev-parse --short HEAD` 和「工作区干不干净」写进 `README.txt`。
> 没提交就归档，README 里记的是**上一个** commit，后面还跟着一句
> 「⚠️ 有未提交改动」—— 而这一版发出去的到底是哪份源码，就说不清了。
>
> **归档前把临时文件清掉**（真机上 `screencap` 拉回来的截图会让 `git status` 不干净）。
> 忘了也不要紧：清完再 `python tools/archive_release.py --force` 重跑一次即可 ——
> `--force` 覆盖同目录，**APK 字节不变**（`cmp` 可证）。

然后：

```bash
python tools/archive_release.py --check-only   # 先只验配套，不落盘
python tools/archive_release.py                # 验过了再归档
```

产出 `dist/patchbay-<versionName>/`（约 45 MB，主体是 mapping）：

| 文件 | 是什么 |
|---|---|
| `patchbay-<version>.apk` | 会发出去的那一份 |
| `mapping.txt` | 只对这一份 APK 有效 |
| `map-id.txt` | 一行 hash，给 `--find-map-id` 反查用 |
| `README.txt` | 版本 / 时间 / commit / sha256 / 还原命令 |
| `NOTES.md` | **手写的** Release 说明（见第 8 步），脚本不生成它 |

> **验收之后 sha256 要对得上**：归档脚本把 APK 的 sha256 写进 `README.txt`，
> 把它和第 6 步之前记下的那个比 —— 相同才说明「真机上验过的」就是「要发的」。
> 改过版本号会重新构建，APK 的字节就变了，那时**要重压一遍 §89 那五项**。

> **为什么要验**：APK 和 mapping **不配套时 `retrace` 不报错**，会给一份行号错误但看着
> 完全合理的结果。配套判据是 APK 的 DEX 里的 `r8-map-id-<hash>` 与 mapping 里的
> `# pg_map_id: <hash>` 逐字节相同。
>
> 改过这个脚本之后，跑一遍它自己的判据测试（18 条，零依赖）：
> `python tools/tests/test_archive_release.py`

### 8. 推代码 + 建 Release

`origin` = `https://github.com/jiunian503/Patchbay.git`（**仓库名是大写 P**；GitHub 对
大小写不敏感、会重定向，但配成一致更干净）。以后改完代码：

```bash
git push
```

> ⚠️ 建仓库时**别勾任何初始化文件**（README / .gitignore / LICENSE）—— 保持**空仓库**。
> 勾了的话 GitHub 会先生成一个 commit，首次 push 会被拒（`! [rejected] master -> master
> (fetch first)`），那时得先 `git pull --rebase origin master` 再 push。

用 **HTTPS 而不是 SSH**：这台机器 `~/.ssh/` 下只有 `known_hosts`、**没有密钥**，
`git@github.com:...` 会直接报 `Permission denied (publickey)`。

**`gh` CLI 已装**（`C:\Program Files\GitHub CLI\gh.exe`，v2.101.0）。建 Release 一条命令：

```bash
GH="/c/Program Files/GitHub CLI/gh.exe"
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | sed -n 's/^password=//p')

# 把三处 1.2 换成你要发的版本（示例给的是当前最新那版）
GH_TOKEN="$TOKEN" "$GH" release create v1.2 \
  --repo jiunian503/Patchbay \
  --title "Patchbay 1.2" \
  --notes-file dist/patchbay-1.2/NOTES.md \
  --target master \
  dist/patchbay-1.2/patchbay-1.2.apk \
  dist/patchbay-1.2/mapping.txt
```

> ⚠️ **建完要当场复核两下**，别只看 `release create` 的退出码 —— 它成功 ≠ App 查得到：
>
> ```bash
> # ① 附件与属性：期望 draft:false、prerelease:false，两个附件都 state:"uploaded"
> GH_TOKEN="$TOKEN" "$GH" release view v1.2 --repo jiunian503/Patchbay \
>   --json isDraft,isPrerelease,assets \
>   --jq '{draft:.isDraft, pre:.isPrerelease, assets:[.assets[]|{name,size,state}]}'
>
> # ② 端点指向谁（App 打的就是这个）
> curl -s -H "Accept: application/vnd.github+json" -H "User-Agent: Patchbay-UpdateCheck" \
>   https://api.github.com/repos/jiunian503/Patchbay/releases/latest \
>   | python -c "import sys,json;d=json.load(sys.stdin);print(d['tag_name'],d['prerelease'],d['draft'])"
> ```
>
> **③ 再把附件拉回来对 sha256** —— 这是「用户下到的 == 真机上装过的那份」唯一的证据。
> **只核附件大小不够**（两个 5,324,829 B 的包完全可能内容不同）：
>
> ```bash
> curl -sL -o /tmp/dl.apk \
>   https://github.com/jiunian503/Patchbay/releases/download/v1.2/patchbay-1.2.apk
> sha256sum /tmp/dl.apk    # 必须等于归档 README.txt 里那个
> ```
>
> 判据见 SKILL.md **§92 十五③④**。

> ⚠️ **别加 `--prerelease`，也别用 `--draft`。** 上面这条命令创建出来的是
> 「最新发布」，而 App 里的「检查更新」打的是 `/releases/latest` ——
> 它**只返回最新的非草稿、非预发布**那一版。加 `--prerelease` 的后果最隐蔽：
> 不是「查不到」，而是**退回上一版** —— 于是用户看到的「有新版本 v1.1」
> 指向一个**更旧的** tag。草稿同理（草稿根本不出现）。
>
> 另外两条同样静默的前提：
>
> - **仓库必须保持 PUBLIC** —— 未认证的请求打私有仓库一律 404，
>   用户看到的是「没找到发布版本」，像是这个仓库从没发布过
> - **tag 要能被解析**（`v1.2` / `1.2` 都行，`release-1.2` 不行）。解析不出来时
>   界面说的是「比不了」而不是「已是最新」—— 这是有意的（宁可说不知道，
>   不说一句确定的错话）
>
> 这三条对着 `AppContainer.releases` 那个**写死**的地址，两边写了同一份说明。

> Release 说明手写在 `dist/patchbay-<version>/NOTES.md` —— 和它描述的那份 APK 放在一起，
> 半年后翻出来是一套的。**脚本不生成它，也不覆盖它**（`--force` 重归档会连它一起删掉，
> 那时要重写）。照上一版的 `NOTES.md` 抄结构：先讲这一版加了什么，再讲下载与注意事项。

⚠️ **`NOTES.md` 里的每一句「做不到 / 平台边界 / 不支持」都要有一条实测判据撑着。**
v1.4 的说明里写着「（没有 apt / curl / python）这两条是**平台边界，不是还没做**」，
而 SKILL.md §105.3–§105.5 记的恰恰相反：「`lib*.so` + `useLegacyPackaging` **就能 exec**」、
「proot + rootfs 的代价是 **67 MB 起**」，原话是「这是**取舍题**，不是「能不能做」」。
⇒ 把取舍说成禁令，等于替平台背一口它没欠的锅，用户还拿到「Android 不行」这个错结论。

写完之后逐条核这四类断言（都属于「写错了没有任何测试会红」的那种）：

| 断言 | 怎么核 |
|---|---|
| 「做不到 / 平台边界 / 不支持」 | 去 SKILL.md 找那条**实测的平台限制**；找不到就改成说**代价**（几 MB / 一个 JNI 模块） |
| 数字（体积 · 工具数 · 命令数） | **现测**，别抄上一版；体积一律**写字节数**（§107.8） |
| 「唯一能改数据的工具」这类**安全语义** | 去装配点数一遍（`BuiltinTools.all`），别凭印象 |
| 「只带 `arm64-v8a` / minSdk N」 | 读 `app/build.gradle.kts`，别凭记忆 |

⚠️ **发出去之后才发现说错了**：改线上 Release 说明是**对外动作**（`gh release edit`），
不是修 bug —— 要么单独决策改，要么在**下一版**的说明里更正。**别默认顺手改掉。**

⚠️ **两个附件都要传** —— 只传 APK 的话，以后用户发来的崩溃堆栈就永远读不懂了。

传完核对一遍附件齐没齐：

```bash
GH_TOKEN="$TOKEN" "$GH" release view v1.1 --repo jiunian503/Patchbay --json assets \
  --jq '.assets[] | "\(.name)  \(.size) B  \(.state)"'
```

期望看到两行、都是 `uploaded`，且 `.apk` 的字节数和 `dist/` 里那份一致。

**为什么用 `GH_TOKEN` 而不是 `gh auth login`**：前者不落盘（token 只在那一条命令的环境里），
后者会把它写进 `~/.config/gh/hosts.yml`。token 直接从 git 凭据管理器取（那里本来就有一份），
不必另外申请。

> `gh auth status` 会提示 `Missing required token scopes: 'read:org'` —— **不用管**，
> 个人仓库的 Release 操作只要 `repo` scope。

其他常用命令：

```bash
GH_TOKEN="$TOKEN" "$GH" release view v1.0 --repo jiunian503/Patchbay --json assets \
  --jq '.assets[] | "\(.name)  \(.size) B  \(.state)"'          # 核对附件传全了没
GH_TOKEN="$TOKEN" "$GH" release delete v1.0 --repo jiunian503/Patchbay --cleanup-tag
GH_TOKEN="$TOKEN" "$GH" release edit v1.0 --repo jiunian503/Patchbay --notes-file <新说明.md>
```

⚠️ `dist/` 不入库（45 MB）但**必须上传** —— 归档目录丢了，那一版的线上崩溃就永远读不懂了。

### 8.5 顺手核一遍仓库的对外属性

发完版多花两秒 —— 这几个字段和 README 一样**会过期，而且过期时没有任何测试会红**：

```bash
GH_TOKEN="$TOKEN" "$GH" api repos/jiunian503/Patchbay \
  --jq '{visibility, license:(.license.spdx_id // "null"), description, topics}'
```

期望：`visibility:"public"`（**私有的话「检查更新」对所有人失效**，见上面那条警告）·
`license:"NOASSERTION"`（**不是 `null`** —— `null` 是「根本没有 LICENSE 文件」，
而这里有那个自定义的「保留所有权利」LICENSE，licensee 认不出它属于哪个标准许可证；
`NOASSERTION` 才是想要的）· `description` 与 `topics` 非空。

> ⚠️ **别把 `NOASSERTION` 和 `null` 混成一句「反正不是标准许可证」** ——
> `--jq` 里的 `//` 只兜 `null`，兜不住 `NOASSERTION`。判据见 SKILL.md **§91⑨**。

### 9. 用户发来一条混淆堆栈时

```bash
python tools/archive_release.py --find-map-id <堆栈里的那个 64 位 hash>
```

它告出该用哪一份归档，然后：

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/retrace.bat" dist/patchbay-1.0/mapping.txt crash.txt
```

### 10. 全量核对所有版本的发布资产（偶尔做）

第 8 步那三条复核只核**当次那一版**。这一节核**所有版本** —— 因为 `dist/` 与 Releases
是仅有的两份副本，而「某一版的 mapping 悄悄坏了」不会有任何东西提醒你。

**四条判据，缺一不可**：

| # | 判据 | 怎么核 |
|---|---|---|
| 1 | 线上附件与本地归档**逐字节相同** | 比 sha256（**不是比大小**） |
| 2 | APK 的 DEX / mapping / `map-id.txt` **三方一致** | 见下面那段 Python |
| 3 | 归档记的源码 `HEAD` 真实存在，且在 master 历史上 | `git merge-base --is-ancestor` |
| 4 | 版本号与目录名相符 | `aapt2 dump badging` 读 `versionCode` / `versionName` |

**判据 1 有省事的办法**：GitHub 现在给每个 asset 带 `digest` 字段，
不用把 43 MB 的 mapping 拉回来：

```bash
GH_TOKEN="$TOKEN" "$GH" api repos/jiunian503/Patchbay/releases \
  --jq '.[] | "=== \(.tag_name) ===", (.assets[] | "  \(.name)  \(.size)  \(.digest)")'
```

和本地对：

```bash
cd dist && sha256sum patchbay-*/patchbay-*.apk patchbay-*/mapping.txt
```

**判据 2 为什么重要**：`map-id` 是「用户发来的堆栈属于哪一版」唯一的线索。三方里
任何一方对不上，`--find-map-id` 就会指错版本、拿错 mapping 去 retrace。
`archive_release.py` 在归档时验过 APK↔mapping，但**归档之后再没人验过**，
`map-id.txt` 更是从来没被反向核过：

```bash
python - <<'PY'
import re, zipfile
MAP_ID_IN_DEX = re.compile(rb'r8-map-id-([0-9a-f]{64})')
PG = re.compile(r'^#\s*pg_map_id:\s*([0-9a-f]{64})\s*$', re.MULTILINE)
for v in ('1.0', '1.1', '1.2'):
    d = 'dist/patchbay-' + v
    with zipfile.ZipFile(f'{d}/patchbay-{v}.apk') as z:
        blob = b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
    ids = sorted({m.decode() for m in MAP_ID_IN_DEX.findall(blob)})
    m = PG.search(open(f'{d}/mapping.txt', encoding='utf-8').read(4096))
    pgid = m.group(1) if m else None
    txt = open(f'{d}/map-id.txt', encoding='utf-8').read().strip()
    ok = len(ids) == 1 and pgid == ids[0] == txt
    print(v, 'ALL THREE MATCH' if ok else f'MISMATCH ids={ids} pg={pgid} txt={txt}')
PY
```

**2026-09-21 的实测结果（三版全过，作为基线）**：

| 版本 | APK sha256 | mapping sha256 | map-id |
|---|---|---|---|
| 1.0 | `adfdabfa…` | `8353de4a…` | `3698dbbf…` |
| 1.1 | `e78dfab9…` | `97382845…` | `92bac30a…` |
| 1.2 | `634af1c3…` | `fb116d5a…` | `f467dfa3…` |

四条判据 3/3 全过，`--find-map-id` 反查闭环成立（每个 id 都指向自己那一版）。

**顺带核一件容易忽略的**：本地 `NOTES.md` 与线上 Release 正文是否一致 ——
**线上会比本地多一个末尾空行**（GitHub 自动补的），其余应逐字相同：

```bash
GH_TOKEN="$TOKEN" "$GH" release view v1.2 --repo jiunian503/Patchbay --json body --jq '.body' \
  > /tmp/body.md && diff <(sed 's/\r$//' dist/patchbay-1.2/NOTES.md) <(sed 's/\r$//' /tmp/body.md)
```

> ⚠️ `dist/patchbay-1.0/NOTES.md` 是 **2026-09-21 从线上回填的** —— `NOTES.md` 这个
> 约定是 v1.1 才有的，v1.0 发布时说明直接写在 `gh` 命令里。回填内容与线上逐字相同，
> 只是末尾按本地风格收敛成一个换行（本地是纯 LF）。

---

## 常见报错对照

| 报错 | 真实原因 |
|---|---|
| `Given final block not properly padded` | `keyPassword` ≠ `storePassword`（PKCS12 不支持）—— **别去重新生成密钥库** |
| `Keystore file not found` | `storeFile` 路径基准搞错了，它相对 `app/` 模块而不是仓库根 |
| 产物是 `app-release-unsigned.apk` | `keystore.properties` 不存在，或四个键有缺的 |
| `keystore.properties 缺了这些键：[...]` | 配置阶段就报。补全，或者整个删掉退回 unsigned |
| 归档脚本报「不配套」 | APK 和 mapping 来自两次不同的构建，重跑一次 `assembleRelease` |
| 归档脚本报「读不出 versionCode」 | `app/build.gradle.kts` 的写法变了，改脚本里那两个正则 |
