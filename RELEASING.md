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

建议：① 密码管理器的文件附件（Bitwarden / 1Password 都支持）；② 另一台机器或加密 U 盘。
**别只放在这台机器上。**

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

⚠️ 少了 `keystore.properties`（或四个键有缺的）时 AGP 会**静默退回产出 unsigned 包**，
不报错。所以「看产物名」这一步不能省。

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

```bash
python tools/archive_release.py --check-only   # 先只验配套，不落盘
python tools/archive_release.py                # 验过了再归档
```

产出 `dist/patchbay-<versionName>/`（约 45 MB，主体是 mapping）。

> **为什么要验**：APK 和 mapping **不配套时 `retrace` 不报错**，会给一份行号错误但看着
> 完全合理的结果。配套判据是 APK 的 DEX 里的 `r8-map-id-<hash>` 与 mapping 里的
> `# pg_map_id: <hash>` 逐字节相同。
>
> 改过这个脚本之后，跑一遍它自己的判据测试（18 条，零依赖）：
> `python tools/tests/test_archive_release.py`

### 8. 上传到 GitHub Release 附件

**这一步目前是手工的**（本机没装 `gh` CLI）。

`origin` 已经配好了（`https://github.com/jiunian503/patchbay.git`），但**仓库还没建**。
到 GitHub 上新建一个仓库，然后：

```bash
git push -u origin master
```

⚠️ **建仓库时别勾任何初始化文件**（README / .gitignore / LICENSE）—— 保持**空仓库**。
勾了的话 GitHub 会先生成一个 commit，首次 push 会被拒：

```
! [rejected]  master -> master (fetch first)
```

那时候得先 `git pull --rebase origin master` 再 push。**不如一开始就别勾。**

用 **HTTPS 而不是 SSH**：这台机器 `~/.ssh/` 下只有 `known_hosts`、**没有密钥**，
`git@github.com:...` 会直接报 `Permission denied (publickey)`。
HTTPS 首次推送会弹一个凭据窗口（Git for Windows 自带的凭据管理器），走一次 GitHub 登录就行。
真想用 SSH 的话，先 `ssh-keygen -t ed25519` 再把公钥加到 GitHub 账号里。

然后到 GitHub 上建 Release（tag 用 `v1.0` 之类），把 `dist/patchbay-1.0/` 里的
**`patchbay-1.0.apk` 和 `mapping.txt`** 作为附件传上去。

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
