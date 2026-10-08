# 安全策略

> 最后梳理：2026-10-08 · 对应代码基线：main（`versionName 3.4.0.R7`）
> 本文由本项目的构建 / 运维记忆与当前代码核对整理。文中行号会随重构漂移，发现失效请以 `main` 实际内容为准并顺手修正。

---

## 安装来源

APK 只从本仓库的 Release 页面获取，其它渠道的同名安装包一律不要安装。

下载后先比对 Release Notes 里给出的 SHA-256 再装。Release 页面之外的所谓「官方包」「更新包」都没有背书。

---

## 已落实的安全机制

以下是**已经在线运行**的控制项，梳理成清单以便审计；改动它们时请当作破坏性变更对待。

### 供应链完整性

| 控制 | 位置 | 说明 |
| --- | --- | --- |
| Gradle Wrapper 校验和 | `.github/workflows/gradle-wrapper-validation.yml` | 全仓 6 个 wrapper（根工程 + `vendor/termux-addons` 5 个）逐个比对官方 SHA-256 清单。**必须用 `gradle/actions/wrapper-validation@v6**——v3.5.0 的清单未收录本仓的 Gradle 9.7.1，会误判失败。 |
| 依赖污染门禁 | `.github/workflows/dependency-guard.yml` | 起因是 #143 事故：Dependabot 只改了 vendor 子模块，diff 完全看不出问题，但 Gradle 单版本策略把主 app 传递依赖顶了上去（当时 `androidx.biometric` 1.4.0-alpha07 导致 `NoClassDefFoundError`）。因此该门禁**不看 diff**，直接解析 `:app:debugRuntimeClasspath` 与基线比对；以 `workflow_call` 形式挂在单元测试之前跑。 |
| Dependabot 策略 | `.github/dependabot.yml` | 每日扫描。**任何 `ignore` 条目都必须写明原因**（`androidx.biometric` 的忽略理由与恢复前置条件已内联注释），Kotlin 工具链用 `groups` 强制整组升级。 |
| Bootstrap 完整性固定 | `app/.../utils/BootstrapDownloader.kt` | 首启动在线拉取 bootstrap zip，**逐架构固定 SHA-256** 并校验 zip 魔数 `PK\x03\x04`；多镜像仅提供可用性回退，**任一产物都必须通过同一份哈希比对**才算成功。改 zip 必须同步 `EXPECTED_SHA256` 与 tag `bootstrap-v1`。 |
| 产物签名校验 | CI release 流水线 | `strip_so.py` 重打包 → `zipalign -p 4096` → `apksigner sign`（v1+v2+v3）→ 覆盖前执行 `zipalign -c` 与 `apksigner verify` 双校验，失败保留 Gradle 原包。 |

### 凭据存储

- **GitHub OAuth Token**：`app/.../github/GitHubSessionStore.kt` 用 `EncryptedSharedPreferences`（键 AES256-SIV / 值 AES256-GCM，主密钥 AES256-GCM）。
  历史缺陷已修：早期每次调用重新派生 `MasterKey`，任一环节异常被 `runCatching` 吞掉，造成「存得进去、读不出来」的登录态丢失；现改为进程内缓存句柄 + `commit()` 同步落盘，并在加密通道不可用时降级到普通 prefs（会打日志，不静默失败）。
- **认证方式**：使用 GitHub **Device Flow**，不再监听本地回环端口（早期的 `ServerSocket` + PKCE 方案已删除），从而避免了端口占用 / 本机其它进程抢答回调的问题。

### 攻击面：组件导出

`app/src/main/AndroidManifest.xml` 中对外暴露的组件：

- `.app.SplashActivity`（LAUNCHER）、`.HomeActivity`（IOT_LAUNCHER alias）、`.app.activities.SettingsActivity`、`.filepicker.TermuxFileReceiverActivity`、`.VncUriReceiverActivity`（alias）
- `.app.BootReceiver`：`enabled="false"`，仅在设置中开启集成工具时运行时启用
- 受权限保护的：`RUN_COMMAND` 自定义权限（signature 级）保护的 Service/Receiver、`MANAGE_DOCUMENTS` 保护的 DocumentsProvider、`BIND_QUICK_SETTINGS_TILE` / `BIND_JOB_SERVICE` 组件

其余 Activity 均为 `exported="false"`，插件的 `PluginWebViewActivity` / `PluginComposeActivity` 同样不外露。

### 插件沙箱

`app/.../plugin/PluginSecurity.kt` 提供能力门禁：

- **命令执行**：校验「插件存在 / 已启用 / 持有 `ROOT_EXECUTE` 或 `TERMUX_SESSION_ACCESS`」，并按黑名单评定风险等级（`rm -rf /`、`dd if=`、`mkfs`、`iptables`、`su`/`sudo` 等判定 HIGH）。**该检查有真实调用点**：`PluginManager.executeShellCommand` → `PluginSecurity.canExecuteShellCommand`，是 WebView JS bridge `TermuxUltra.exec()` 的唯一通路。
- **文件访问**：限定在 `/data/data/com.termux` 与 `/data/local/tmp` 内，且做了 `canonicalPath` 规范化后再带分隔符比较——这一点是刻意为之，直接用 `startsWith` 会让 `/data/data/com.termux-evil/...` 绕过前缀检查。

---

## 已知风险与技术债

透明披露，欢迎提 PR 收敛。评估为「中」及以下，多数需要在功能性和开发效率之间权衡。

| # | 问题 | 级别 | 位置 | 现状 |
| --- | --- | --- | --- | --- |
| 1 | AI 提供方 API Key **明文**存于 SharedPreferences | 中 | `ai_termux_prefs`（`AiTermuxModels.kt`），键 `api_key`、`fallback_online_api_key` | `MODE_PRIVATE` 在同一 UID 下足够，但 **root 设备或 debug 包可被 `run-as` 直接读出**。建议迁移到 `EncryptedSharedPreferences`（`GitHubSessionStore` 是可复用的现成范式），或至少按要求才落盘。 |
| 2 | 应用内更新**未校验 APK 完整性** | 中 | `ApkDownloader.kt` `downloadAndInstall()` | 下载任意 URL 到 `getExternalFilesDir` 后立刻 `ACTION_VIEW` 拉安装器，**没有 SHA-256 也没有证书/包名校验**，与 `BootstrapDownloader` 的做法不一致。建议补齐哈希固定 + 校验包名与签名，二者缺一都不足以拦截替换攻击。 |
| 3 | 插件 WebView 设置偏松 | 中 | `PluginWebViewActivity.kt:189–206` | `allowUniversalAccessFromFileURLs` / `allowFileAccessFromFileURLs` 均为 `true`，`mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW`，页面上挂了 `TermuxUltra` JS bridge。页面本身以 `file://` 从插件目录加载且**无完整性校验**。Activity 未导出，风险依赖「插件来源可信」这一前提——建议把上述开关改为按需开启而不是全开。 |
| 4 | `PluginSecurity` 部分方法**仅用于 UI 展示** | 低 | `PluginSecurity.kt` | `canAccessFileSystem` / `canModifyAgent` / `canAccessInternet` / `validatePluginIntegrity` 目前**没有调用点**；实际被引用的是 `canExecuteShellCommand`（真拦截）与各 `getXxxDisplayName` 展示函数。即权限声明目前是「告知用户」而非「强制隔离」。 |
| 5 | 全局允许明文 HTTP | 低 | `res/xml/network_security_config.xml` + `android:usesCleartextTraffic="true"` | `base-config` 全局开，不限 real loopback。初衷是支持本地 Ollama / llama-server / 纯 IP 端点，但实际效果覆盖**所有**域名。`domain-config` 段（127.0.0.1 / localhost / ::1）因 `base-config` 已全开而形同虚设。建议翻转：默认拒绝，仅对本地与用户显式配置的端点放行。 |
| 6 | R8 默认关闭 | 低 | `app/build.gradle` `enableR8` | 决策原因是 `TypeToken` 泛型签名被剥离导致 VNC 连接崩溃（详见 2026-09-28 记录），不是疏忽。`proguard-rules.pro` 保留为 opt-in。代价是**反编译几乎零门槛**，APK 体积也偏大。属有意取舍，重开需连带解决 `-keepattributes Signature` 的命中范围问题。 |
| 7 | 高危权限面偏大 | 低 | `AndroidManifest.xml` | `MANAGE_EXTERNAL_STORAGE`、`WRITE_SECURE_SETTINGS`、`DUMP`、`READ_LOGS`、`SYSTEM_ALERT_WINDOW`、`PACKAGE_USAGE_STATS` 等在 Termux/AVNC 场景下难以避免，但意味着一旦应用自身被攻破，影响范围就是整机。建议逐步改为按需动态申请。 |
| 8 | `testkey_untrusted.jks` 入库 | 信息 | `vendor/termux-addons/termux-boot/app/build.gradle` | 这是 **AOSP 标准的公开测试密钥**（名字里的 `untrusted` 即为警示），非秘密、不构成泄漏，但请勿用于任何正式签名流程。 |

---

## 报告漏洞

**请不要用公开 Issue 报告安全问题。**

请走 GitHub 私有漏洞报告（仓库 → Security → Report a vulnerability），或直接在 Security Advisory 开私有草案。我们会视情况邀请你参与验证。

请尽量附带：

- 影响的版本 / commit SHA、设备 Android 版本与 ABI
- 最小复现步骤（AVD 环境与 `logcat` 片段尤佳）
- 你认为的影响面（本地提权 / 数据泄漏 / 远程触发 / 供应链）
- 是否已有可行的利用路径，以及是否有初步修复思路

响应预期：确认收到 7 天内；确认存在后按严重级别排期，修复发布前不公开细节。

---

## 贡献者安全约定

提交 PR 前自检：

1. **不提交任何密钥**：`.jks` / `.keystore` / `.p12` 已被 gitignore，但注意 GHA 的 workflow 文件、配置文件、文档示例都算「明文仓库」，一律用 `${{ secrets.* }}` 引用。
2. **改动依赖时**：
   - 只碰 vendor 也要跑 `dependency-guard`（它会作为 `run_tests.yml` 的第一个作业自动执行），因为 Gradle 单版本策略会把主 app 的传递依赖一起抬上去；
   - 新增 `dependabot.yml` 的 `ignore` 条目必须写明原因与恢复前置条件；
   - Kotlin 工具链（KGP / compose 插件 / serialization 插件）必须整组升。
3. **更新外部产物哈希**：改 bootstrap zip 或引入新的在线下载，必须同时更新 `EXPECTED_SHA256` 与对应 tag，不能只换文件。
4. **保持既有 Thor 门禁**：`allowUniversalAccessFromFileURLs`、全局明文、R8 开关等每一处「放宽」都是带注释的有意取舍，收窄欢迎，放宽请在 PR 描述里说明威胁模型。
5. 新增涉及凭据的存储，优先复用 `GitHubSessionStore` 的 `EncryptedSharedPreferences` 用法（含降级路径与日志），不要新发明一套。
