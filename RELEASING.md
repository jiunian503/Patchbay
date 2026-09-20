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

**第二处：还没做** —— 上面那处在同一台机器的同一块盘上，**不构成第二处**。
放密码管理器的文件附件（Bitwarden / 1Password 都支持）、另一台机器、或加密 U 盘。

> ⚠️ **备份不是「拷过去就算数」—— 要能证明它可用。** 三条判据，缺一不可：
>
> 1. **拷贝完整**：`sha256sum` 两边一致
> 2. **备份自足**：只用备份目录里的 `.jks` + 口令跑
>    `keytool -list -v -keystore <备份的.jks> -storepass <备份里的口令>`，
>    能列出别名为 `patchbay` 的证书
> 3. **指纹对得上线上**：`keytool` 报的证书 SHA-256，等于
>    `apksigner verify --print-certs <已发布的 apk>` 报的那个 —— 说明备的就是
>    签过 v1.0 / v1.1 的那把钥匙
>
> 想再硬一点就真签一次（**在 APK 的副本上**，别动原件）：
>
> ```bash
> cp dist/patchbay-1.1/patchbay-1.1.apk /tmp/t.apk
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

### 6. 构建

```bash
$G :app:assembleRelease --max-workers=2 --no-configuration-cache
```

### 7. 归档 APK + mapping

**先提交版本号那处改动**，再归档：

```bash
git add app/build.gradle.kts && git commit -m "v1.1：versionCode 2 / versionName 1.1"
```

> 归档脚本会把 `git rev-parse --short HEAD` 和「工作区干不干净」写进 `README.txt`。
> 没提交就归档，README 里记的是**上一个** commit，后面还跟着一句
> 「⚠️ 有未提交改动」—— 而这一版发出去的到底是哪份源码，就说不清了。

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

GH_TOKEN="$TOKEN" "$GH" release create v1.1 \
  --repo jiunian503/Patchbay \
  --title "Patchbay 1.1" \
  --notes-file dist/patchbay-1.1/NOTES.md \
  --target master \
  dist/patchbay-1.1/patchbay-1.1.apk \
  dist/patchbay-1.1/mapping.txt
```

> Release 说明手写在 `dist/patchbay-<version>/NOTES.md` —— 和它描述的那份 APK 放在一起，
> 半年后翻出来是一套的。**脚本不生成它，也不覆盖它**（`--force` 重归档会连它一起删掉，
> 那时要重写）。照上一版的 `NOTES.md` 抄结构：先讲这一版加了什么，再讲下载与注意事项。

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

### 9. 用户发来一条混淆堆栈时

```bash
python tools/archive_release.py --find-map-id <堆栈里的那个 64 位 hash>
```

它告出该用哪一份归档，然后：

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/retrace.bat" dist/patchbay-1.0/mapping.txt crash.txt
```

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
