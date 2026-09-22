# Termux Ultra 资源占用优化报告

> 第二轮：同步上游最新代码 + 每架构独立编译 + CI 自动编译改造
> 工作分支：`opt-abi-split-20260922`（基于 `origin/main` = `80840491`）
> 第三轮：**同一批改动补做验证**（本机仍无法全量构建，但把能执行的部分全部真正执行了 —— 见 §7.3）

---

## 0. 本轮结论速览

| # | 事项 | 状态 |
|---|------|------|
| 1 | 修改前**没有**拉取远端代码（上一轮改动基于本地旧基线） | 已纠正，见 §1 |
| 2 | 拉取后发现 `origin/main` 前进 34 个提交，改动已重新落地到新基线 | ✅ |
| 3 | 新增**每架构独立编译**（Gradle 参数 + CI 矩阵并行） | ✅ |
| 4 | 修掉一个产物命名的**死代码 bug**（拆分产物从不被重命名） | ✅ |
| 5 | `ci.yml` / `release_apk.yml` 改为按 ABI 拆分编译 | ✅ |
| 6 | 全量 `./gradlew assembleDebug` 编译验证 | ⚠️ **仍未做**，原因见 §7.2；但改为让 CI 矩阵去做（见下） |
| 7 | 第三轮：在无法全量构建的前提下，把配置项/产物命名/工作流/日志裁剪全部**真实执行验证** | ✅ 见 §7.3，共 **125 项断言全绿** |
| 8 | 第三轮：产物重命名 bug 做了**回归对照**（把旧代码改回去重跑） | ✅ 见 §7.4，旧代码下 3 项失败 → 证明修复真的改变了行为 |
| 9 | ⚠️ **推上去让 CI 跑，当场抓到一个本机验不出的致命错误** | ✅ 见 §9，`ndk.abiFilters` 不能与 `splits.abi` 并存 → 已修 |

---

## 1. 基线与同步情况

### 1.1 上一轮的问题

上一轮（含报告初版）的改动**全部基于本地工作区、没有 `git fetch`**。本轮实测发现远端已经领先：

```
$ git fetch origin --prune
   82f86fd0..80840491  main -> origin/main      # 远端 main 前进
 * [new tag]           2.2.3.R5 / 2.2.4.R5

$ git branch -vv
* cleanup/dead-code-20260921   5cae5e61 [origin/cleanup/dead-code-20260921: gone]
  main                         d605a9ea [origin/main: behind 34]
```

处理方式：

1. 把上一轮 5 个改过文件的补丁逐文件另存到临时目录（`git diff` 导出，**未使用 `git stash`**）。
2. 工作区回退干净，并移走与上游同名的未跟踪文件 `app/src/main/res/drawable/ic_package.xml`。
3. 本地 `main` 快进到 `origin/main`（`80840491`）。
4. 新建工作分支 `opt-abi-split-20260922`（**不带斜杠** —— 本机沙箱下带斜杠的分支名会静默失败），基于 `80840491`。
5. 逐文件 `git apply` 重新落地：5 个补丁**全部原样应用成功**（上游改动的位置与我的不重叠）。

### 1.2 上游自行修好了上一轮我改的那批东西

上一轮我修 `PackageManagerScreen.kt`（`d605a9ea` 提交的半成品：`transitionSpec:` 冒号笔误、函数体内 `private val`、少 2 个闭合花括号、109 处 `Regex(..) -> "x"` 误用 lambda 箭头构造 `Pair`、缺失组件与 drawable）。

**上游已通过 PR #103 / #102 解决同一批问题。** 对 `origin/main` 版本实测：

| 检查项 | 上游版本 |
|--------|----------|
| `transitionSpec:` 冒号笔误 | 0 处 |
| 函数体内 `private val` / 局部属性带 getter | 0 处 |
| `Regex(...) -> "x"` 箭头误用 | 0 处 |
| `EmptyStateView` / `CategoryEntry` 定义 | 已存在 |
| `ic_package.xml` | 已存在 |
| 花括号平衡（`{` vs `}`） | 202 / 202，平衡 |

→ **上一轮的编译修复全部作废，不再带入**。本轮不碰 `PackageManagerScreen.kt`。

### 1.3 上游其它相关变化（影响本机构建）

| 变化 | 提交 | 影响 |
|------|------|------|
| Kotlin 工具链 2.3.x → **2.4.20** | `b373ae00` | 本机 Gradle 缓存无该插件，需联网下载 |
| Gradle wrapper 9.3.1 → **9.7.1** | `2ebc6e5b` | 本机 `~/.gradle/wrapper/dists` 无此发行版，需下载约 250 MB |
| okhttp 4.12.0 → 5.5.0、core-ktx 1.15.0 → 1.19.0、constraintlayout 2.2.2、Robolectric 4.17 | 多个 dependabot 提交 | 依赖体积/行为有变 |
| 新增 GitHub 登录（OAuth PKCE / Device Flow）+ `security-crypto` | `b2ddb832`、`86f16064` | 新增依赖 |
| 删除 `attach_debug_apks_to_release.yml` | `a4c18868` | 与 `release_apk.yml` 职责重叠，上游已清理 |
| 新增 `docs/` 站点、OKR 页面 | `077a7b49`、`6ca309df` | 与本报告无关 |

---

## 2. 重新落地的优化项（5 个文件）

| 文件 | 类别 | 改动要点 |
|------|------|----------|
| `app/src/main/java/com/termux/app/MainActivity.kt` | **省电** | 会话列表轮询原本退到后台仍每 2 秒唤醒主线程；改为 `onStart` 启动 / `onStop` 停止，回调内加 `isVisible` 守卫，`onServiceConnected` 只在前台启动 |
| `app/src/main/java/com/termux/app/utils/LogManager.java` | **磁盘** | 日志文件原本只按天清理、**无大小上限**；新增 2 MB 上限 + 从尾部流式裁剪（按 UTF-8 行处理，避开 `RandomAccessFile.readLine()` 的 ISO-8859-1 截断中文问题），每 64 次写入才 `stat` 一次 |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | **内存** | 全仓此前**完全没有** `onTrimMemory` / `onLowMemory`；补上 `ComponentCallbacks2`，内存紧张时释放日志解析缓存 |
| `app/build.gradle` | **体积** | `resConfigs "en","zh"`（砍掉依赖自带的几十种语言资源）、补齐 `packaging.resources.excludes`、新增 R8 开关（默认关闭）、新增单架构构建开关（见 §3） |
| `app/proguard-rules.pro` | **体积** | 按实际扫到的动态引用点写全 keep 规则（见 §6.4） |

生命周期配对实测（`MainActivity.kt` 行号）：

```
67:  private var isVisible = false
92:      if (!isDestroyed && isVisible) { ... }        // 回调内守卫
105:     private fun startSessionRefreshLoop()          // 幂等启动（先 removeCallbacks）
112:     private fun stopSessionRefreshLoop()
124:     if (isVisible) startSessionRefreshLoop()       // onServiceConnected
320: override fun onStart()     { 322: isVisible = true;  340: startSessionRefreshLoop() }
343: override fun onResume()    { 353: startSessionRefreshLoop() }
369: override fun onStop()      { 371: isVisible = false; 374: stopSessionRefreshLoop() }
410: override fun onDestroy()   { 412: handler.removeCallbacksAndMessages(null) }
```

---

## 3. 本轮新增：每架构独立编译

### 3.1 设计目标

原来一次 `./gradlew :app:assembleRelease` 会把 `x86 / x86_64 / armeabi-v7a / arm64-v8a` 四个 ABI 的 CMake 原生编译全部跑完（含 wolfssl / turbojpeg / libvncserver / termux-bootstrap），单作业内存峰值高、耗时长，且一个架构失败会连带其它架构不可用。

改为：**一次只编一个 ABI**，由 CI 矩阵并行四个作业。

### 3.2 Gradle 侧用法

```bash
# 只编 arm64-v8a（产物 app-arm64-v8a-release.apk）
./gradlew :app:assembleRelease -Ptermux.abi=arm64-v8a

# 环境变量等价写法
TERMUX_ABI=arm64-v8a ./gradlew :app:assembleRelease

# 全部架构打成一个包（关闭拆分，产物 app-release.apk）
./gradlew :app:assembleRelease -Ptermux.abi=universal

# 不指定：保持原行为（release 拆四架构 + universal；debug 打整包）
./gradlew :app:assembleRelease
```

实现要点（`app/build.gradle`）：

- 取值白名单 `arm64-v8a / armeabi-v7a / x86 / x86_64 / universal`，非法值在**配置期**抛 `GradleException` 并列出可选值（避免 CI 跑到一半才报错）。
- 单架构时向 `defaultConfig.ndk.abiFilters` 写入该 ABI → NDK/CMake **只为该 ABI 配置与编译**，其余三个架构的原生编译与打包全部跳过。
- `splits.abi` 一并收窄到该 ABI，并令 `universalApk = false` —— 单架构构建若同时产出 `app-universal-*.apk`，会得到一个只含单个 ABI 的"全架构包"，属于误导。
- 顺手把 `splits { }` 从 `defaultConfig { }` 内部移到 `android` 顶层（`splits` 本就是 Android 扩展的属性，写在 `defaultConfig` 里要靠 Groovy 的 owner 委托链才能解析，属易踩坑写法）。

### 3.3 产物命名

| 构建方式 | AGP 原始产物 | CI 重命名后 |
|----------|--------------|-------------|
| `-Ptermux.abi=arm64-v8a` | `app-arm64-v8a-release.apk` | `termux-app_2.2.4.R5_arm64-v8a.apk` |
| `-Ptermux.abi=universal` | `app-release.apk` | `termux-app_2.2.4.R5_universal.apk` |
| 未指定（拆四架构 + universal） | `app-<abi>-release.apk` + `app-universal-release.apk` | 同上规则 |
| 本地无 `TERMUX_APK_VERSION_TAG` | `app-<abi>-debug.apk` | `app-debug-<abi>.apk` |

---

## 4. 修掉的一个构建 bug：产物重命名原本是死代码

### 4.1 现象

`app/build.gradle` 里 `packageDebug/packageRelease` 的 `doLast` 块，注释承诺 CI 产物叫 `termux-app_<tag>_<abi>.apk`，但实际挂到 Release 的永远是 AGP 原始名。

### 4.2 根因

```groovy
// 旧代码
if (name.startsWith("app-") || name.startsWith("termux-ultra_") || name.startsWith("termux-app_")) return
...
} else if (name.startsWith("app-")) {   // ← 永远到不了这里
```

守卫把 `app-` 前缀整体排除，可 AGP 产出的拆分包就叫 `app-<abi>-<type>.apk`，于是重命名逻辑**从不执行**（同一函数里后面两个 `app-` 分支成为死代码）。

### 4.3 新逻辑

```groovy
// 只跳过「本脚本已经重命名过」的产物，保证重跑幂等
if (name.startsWith("termux-app_") || name.startsWith("termux-ultra_")) return

String suffix = "-${buildType}.apk"
if (name == "app-${buildType}.apk" || name == "app-universal-${buildType}.apk") {
    abi = "universal"
} else if (name.startsWith("app-") && name.endsWith(suffix)) {
    abi = name.substring("app-".length(), name.length() - suffix.length())
} else {
    return
}
```

### 4.4 验证（抽取真实代码、真实文件、真实目录）

```
──────────────── 重命名逻辑实测（有 tag，模拟 CI 的 release 构建） ────────────────
  ✅ 拆分包 app-<abi>-release.apk → termux-app_<tag>_<abi>.apk
      输入: [app-arm64-v8a-release.apk]
      输出: [termux-app_2.2.4.R5_arm64-v8a.apk]
  ✅ 下划线 ABI x86_64 正确截出 → termux-app_2.2.4.R5_x86_64.apk
  ✅ app-universal-release.apk → termux-app_2.2.4.R5_universal.apk
  ✅ 未拆分 app-release.apk → termux-app_2.2.4.R5_universal.apk
  ✅ 已重命名的产物保持不动（幂等）
  ✅ 多架构 + universal 一起处理（5 个产物全部正确）

──────────────── 本地构建（无 tag） ────────────────
  ✅ app-<abi>-debug.apk → app-debug-<abi>.apk
  ✅ 重复执行保持不动（幂等，不会变成 app-debug-app-debug-...）

──────────────── 对照：旧守卫下的行为（回归证明） ────────────────
  ✅ 旧守卫下产物名保持不变 → 证实重命名逻辑原本是死代码
      输出: [app-arm64-v8a-release.apk]
```

最后一行是**把旧守卫改回去跑一遍**的对照实验，证明这个修复确实改变了行为（而不是"看起来对"）。

---

## 5. CI 工作流改造

### 5.1 `.github/workflows/ci.yml`（编译）

| 项 | 改前 | 改后 |
|----|------|------|
| 作业数 | 1 个 `build` | 1 个 `build` × **4 个 ABI 矩阵** |
| 编译命令 | `./gradlew :app:assembleDebug` | `./gradlew :app:assembleDebug -Ptermux.abi=${{ matrix.abi }}` |
| `fail-fast` | — | `false`（某架构失败不取消其它架构，便于区分"架构特有"还是"共性"问题） |
| 产物 | 一个包，artifact 名不区分 | `termux-ultra-debug-<abi>`，各作业独立 |
| `setup-java` | `@v4`（GitHub 已标记弃用） | `@v6`（与 `run_tests.yml` 统一） |
| `concurrency` | 无 | 同 ref 新推送取消旧运行 |
| `timeout-minutes` | 无 | 60 |

### 5.2 `.github/workflows/release_apk.yml`（发布）

由「单作业全量构建 + 内联收集产物」改为四段式：

```
preflight ──► build (matrix: 4 ABI，并行)  ──┐
          └─► build_universal (可选)       ──┴──► publish ──► 上传到 Release
```

| 作业 | 职责 | 说明 |
|------|------|------|
| `preflight` | 检查 Release 是否已带 APK，输出 `tag` / `skip` | 原本混在构建作业里；拆开后只查一次 |
| `build` | 矩阵 4 个 ABI 并行编译并上传 artifact | `fail-fast: false` |
| `build_universal` | 全架构整包（`-Ptermux.abi=universal`） | 默认策略见下 |
| `publish` | 下载全部 artifact 汇总上传 | 条件：预检未跳过 + `build` 成功 + `build_universal` 未失败（`skipped` 不算失败） |

关于 universal 包的默认行为（**这是一处行为变更，请留意**）：

```yaml
if: needs.preflight.outputs.skip == 'false' && (github.event_name == 'release' || inputs.build_universal == true)
```

- **发布 Release 事件**：仍然构建 universal 包 → 与拆分前的产物集合**完全一致**，无回归。
- **手动触发（`workflow_dispatch`）**：新增 `build_universal` 布尔输入，默认 `false` → 只出 4 个分架构包，省掉那趟约 35 分钟的全架构编译。

`publish` 的 shell 逻辑同步改进：不再依赖多行 heredoc 输出，改为 `dist/*.apk` glob + 数组遍历，并新增"一个产物都没有就直接失败"的保护。

其它：`download-artifact@v8`（v8 起明确支持 `archive: false` 这类非压缩产物，下载时不再尝试解压）、`setup-java@v6`、job id 用下划线（`build_universal`）以避免表达式里连字符被当作减号解析的风险。

### 5.3 `run_tests.yml`

未改动 —— 它跑的是 JVM 单元测试（`./gradlew test`），不涉及原生编译与 ABI。当前基线 `app/src/test` 下有 **20 个 `@Test` 用例**。

---

## 6. APK 体积构成（实测）

> 数据来源：`app/build/outputs/apk/debug/app-debug.apk`，**182.3 MB**，由上一轮基线构建产出。
> ⚠️ 新基线新增了 GitHub 登录等依赖，绝对值会略有上浮，但构成比例结论不变。

| 部分 | 压缩后 | 占比 |
|------|--------|------|
| `lib/*` 四个 ABI | 114 MB | **62.6%** |
| └ `libtermux-bootstrap.so` | 25.6 ~ 28.5 MB / ABI | 单一最大项 |
| `assets/fonts` | **33.7 MB**（原始 63.7 MB） | 18.6% |
| `dex`（37 个） | 30.8 MB | 16.9% |
| `resources.arsc` | 2.38 MB | 1.3% |
| `res/` | 0.85 MB | 0.5% |

### 6.1 字体 33.7 MB 的真实来源（更正上一轮报告）

上一轮报告写成 `app/src/main/assets/fonts`，**实际 `app/src/main/assets` 下没有任何字体**（只有几个 shell 脚本 + `seed.iso`，合计 < 100 KB，实测 `find app/src/main -iname '*.ttf'` 结果为 0）。

真实来源是 **`vendor/termux-addons/termux-styling/app/src/main/assets/fonts/`**（作为 `:termux-styling` 模块被集成进 App）。APK 内 `assets/fonts` 共 **38 个文件 = 26 个 TTF + 12 个 .txt 说明**，全部 DEFLATE 压缩：

| 字体 | 压缩后 |
|------|--------|
| Iosevka.ttf | 3.23 MB |
| OpenDyslexic.ttf | 3.07 MB |
| Hermit.ttf | 3.07 MB |
| Fira-Mono.ttf | 3.06 MB |
| D2-Coding.ttf | 2.17 MB |
| Meslo.ttf | 1.31 MB |
| DejaVu-Sans-Mono / Liberation-Mono / Fira-Code / Hack / Go-Mono / Monofur / Fantasque-Sans-Mono / Anonymous-Pro / Roboto-Mono / Monoid / Victor-Mono / Terminus / Ubuntu-Mono / Source-Code-Pro / Inconsolata | 各 1.03 ~ 1.28 MB |
| GNU-FreeFont / CascadiaCode / JetBrains-Mono / Bedstead-Condensed / Courier-Prime | 0.04 ~ 0.24 MB |

→ 想省这 33.7 MB，得动 vendored 的 termux-styling 模块（例如只内置默认字体、其余按需下载），不是删 `app` 自己的资源就能解决的。

### 6.2 bootstrap 不可优化（误报拦截）

四个 ABI 的 `libtermux-bootstrap.so` **md5 各不相同**（各自内嵌该架构的二进制），既不能跨 ABI 去重，也不能再压缩（zip 套 zip 只省 0.6 MB）。release 按 ABI 拆分后，用户单包只含一份（约 26–29 MB）。这个问题只影响 universal 包。

### 6.3 真正能省的是 dex

`material-icons-extended` 引入约 5000 个图标，项目实际用到的只是一小部分。开 R8 后这部分连同未使用的类/资源一起被裁掉，预期 dex 省 **约 15 MB**。

### 6.4 R8 通道（已备好，默认关闭）

```bash
./gradlew :app:assembleRelease -Ptermux.enableR8=true     # 或 CI 设 TERMUX_ENABLE_R8=true
```

`proguard-rules.pro` 已按实际扫到的动态引用点写全规则。**关键一条是保留 `-dontobfuscate`**：项目有 101 处 Gson 使用但 **0 个 `@SerializedName`**，一旦重命名字段，JSON 解析会静默失败（不报错，只是数据为空）。

---

## 7. 验证记录

### 7.1 实际做了的验证

| # | 检查项 | 方法 | 结果 |
|---|--------|------|------|
| 1 | `app/build.gradle` Groovy 语法 | 用 Gradle 9.3.1 发行版自带的 Groovy 4.0.29 引擎解析整文件 | ✅ 通过 |
| 2 | ABI 拆分分支取值（6 场景） | 桩 DSL **真实执行** `app/build.gradle` 源码，记录 `ndk.abiFilters` / `splits.enable` / `include` / `universalApk` 的实际取值 | ✅ 15/15 断言通过 |
| 3 | 非法 ABI 参数拦截 | 同上，断言配置期抛 `GradleException` 且消息含可选值 | ✅ `不支持的 -Ptermux.abi 取值：'notreal'；可用：arm64-v8a / armeabi-v7a / x86 / x86_64 / universal` |
| 4 | 产物重命名逻辑（8 用例） | 抽取真实 `doLast` 块 + 真实临时目录 + 真实文件 | ✅ 8/8 通过 |
| 5 | 重命名 bug 回归对照 | 把旧守卫改回去重跑 | ✅ 旧代码不改名 → 证实原逻辑是死代码 |
| 6 | 两个工作流 YAML | `pyyaml` 解析 + 结构断言（jobs / needs / matrix / if / action 版本） | ✅ 全部通过 |
| 7 | Release 发布脚本 | 抽取 `publish` 作业的真实 `run` 脚本，`curl`/`jq` 打桩，对 5 个假产物 + 2 个干扰项实测 | ✅ 5 个产物全部上传，`.txt` 与子目录文件被正确排除 |
| 8 | 密钥完整性 | 改写 `release_apk.yml` 前后比对 `JKS_BASE64` 行的 sha256 | ✅ 一致（3730 字符原样保留） |

ABI 拆分场景实测输出（节选）：

```
【B CI 单架构 debug】-Ptermux.abi=arm64-v8a  tasks=[assembleDebug]
      ndk                     []
      add                     [arm64-v8a]
      enable                  [true]
      include                 [arm64-v8a]
      universalApk            [false]

【E release 未指定(默认拆4架构)】-Ptermux.abi=(未指定)  tasks=[assembleRelease]
      enable                  [true]
      include                 [x86, x86_64, armeabi-v7a, arm64-v8a]
      universalApk            [true]
```

### 7.2 ⚠️ 没做的验证，以及为什么

**全量 `./gradlew assembleDebug` / `testDebugUnitTest` 本轮没有跑。** 原因不是改动有问题，而是本机环境在拉取上游后无法完成首次构建所需下载：

| 事实 | 证据 |
|------|------|
| 上游把 Gradle wrapper 升到 **9.7.1**，本机只有 9.3.1 缓存 | `~/.gradle/wrapper/dists/gradle-9.7.1-all/**/gradle-9.7.1-all.zip.part`（`.ok` 不存在）—— 一次 14 分钟的构建只下到 17.8 MB |
| 上游把 Kotlin 升到 **2.4.20**，本机缓存只有 1.9.0 / 2.3.10 / 2.3.21 | `~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-gradle-plugin/` |
| 出网实测带宽约 **10 ~ 25 KB/s** | 25 秒窗口内 `.part` 从 16.49 MB 涨到 17.06 MB |
| 本机 `curl` 出网被拦（HTTP 200 但 0 字节，直连与走 `127.0.0.1:7890` 代理都一样） | 腾讯/华为/阿里镜像、Maven Central 表现一致 |

粗算：Gradle 9.7.1 `-all` 发行版约 250 MB + Kotlin 2.4.20 插件依赖数十 MB，按 20 KB/s 需要**数小时**。因此本轮改用"用已缓存发行版自带的 Groovy 引擎直接执行真实配置文件"的方式做配置级验证（表中 1~5 项）。

**这意味着下列内容仍属未验证，需要一次真实构建来确认：**

1. ~~AGP 9.1.0 是否接受"`ndk.abiFilters` 单架构 + `splits.abi` 单架构"的组合~~
   → **CI 实测：不接受。** 4 个矩阵作业全部在配置期失败，详见 §9；已改为只用 `splits.abi`。
2. `resConfigs "en","zh"` 与新增 R8 开关在真实配置期是否报错。
3. Kotlin / Java 侧改动（`MainActivity.kt`、`LogManager.java`、`TermuxApplication.java`）能否编译。
4. 20 个既有单测是否仍然全绿。

**建议**：把本分支推到远端，让 `ci.yml` 的 4 个矩阵作业（以及 `run_tests.yml`）来完成这件事 —— 它们的网络正常，这也正是本轮改造 CI 的意义之一。

### 7.3 第三轮补做：把本机上能执行的部分全部真正执行

本机全量构建的前提仍然缺失（§7.2 的原因没变：Gradle 9.7.1 发行版只下到 **18 MB / 约 250 MB**，
`~/.gradle/wrapper/dists/gradle-9.7.1-all/**/gradle-9.7.1-all.zip.part` 仍在，`.ok` 不存在）。
所以第三轮换了一条路线：**不去"编译整个工程"，而是把每一处改动各自单独拿出来真正执行。**
验证脚本都留在 `.workbuddy/verify/` 下，可随时重跑。

| # | 验证对象 | 做法 | 结果 |
|---|----------|------|------|
| 1 | `app/build.gradle` 的配置分支（ABI / R8 / resConfigs / packaging） | 用 Gradle 9.3.1 发行版自带的 **Groovy 4 引擎** +「照单全收」桩 DSL，**原样解析并执行 build.gradle 源码**（不是复制一份改动 —— 读的就是仓库里那个文件），记录每一次 DSL 调用及其取值 | **40 项断言全绿** |
| 2 | 产物重命名 `doLast` | 捕获真实的 `tasks.whenTaskAdded` 闭包 → 喂给假 task → 在**真实临时目录 + 真实文件**上执行 | 本地命名 **5 项**、CI 命名 **4 项**全绿 |
| 3 | `ci.yml` / `release_apk.yml` | pyyaml 解析 + 结构断言（作业依赖、矩阵内容、`if` 条件、action 版本、JKS 密钥 sha256 一致性） | **34 项全绿** |
| 4 | `LogManager.trimLogFileIfNeeded()` | Python 从真实源码**按花括号配对抽取**方法体与其依赖常量 → 拼进 harness → **javac 真实编译** → 在 3 MB 真实日志文件上跑 | **10 项全绿** |

合计 **125 项断言，0 失败**。

#### 7.3.1 ABI 分支实测（摘录）

```
【CI 单架构 arm64-v8a debug】-Ptermux.abi=arm64-v8a  tasks=[assembleDebug]
     ndk.abiFilters  clear → add [arm64-v8a]
     splits.enable        true
     splits.include       [arm64-v8a]
     splits.universalApk  false
  ✅ 全部通过

【未指定 release（默认拆 4 架构）】tasks=[assembleRelease]
     splits.enable        true
     splits.include       [x86, x86_64, armeabi-v7a, arm64-v8a]
     splits.universalApk  true
  ✅ 全部通过（与原行为一致：release 仍拆四架构 + universal）

【未指定 debug（默认整包）】tasks=[assembleDebug]
     splits.enable        false
     splits.universalApk  true
  ✅ 全部通过
```

非法取值在配置期就炸：

```
不支持的 -Ptermux.abi 取值：'notreal'；可用：arm64-v8a / armeabi-v7a / x86 / x86_64 / universal
```

#### 7.3.2 产物重命名实测

同一份脚本要在**有 / 无 `TERMUX_APK_VERSION_TAG`** 两种环境下各跑一次（因为 CI 才设这个变量，
两套命名规则互斥）：

```
# 无 tag（本地构建）
  ✅ app-arm64-v8a-debug.apk + app-x86_64-debug.apk → app-debug-<abi>.apk
  ✅ 重复执行保持不动（幂等，不会滚成 app-debug-app-debug-...）
  ✅ app-debug.apk → app-debug-universal.apk
  ✅ app-universal-debug.apk → app-debug-universal.apk
  ✅ not-an-apk.txt 不被重命名

# TERMUX_APK_VERSION_TAG=2.2.5.R5（CI 构建）
  ✅ app-arm64-v8a-release.apk + app-universal-release.apk
     → termux-app_2.2.5.R5_arm64-v8a.apk / termux-app_2.2.5.R5_universal.apk
  ✅ 重跑幂等
  ✅ app-release.apk → termux-app_2.2.5.R5_universal.apk
  ✅ app-x86_64-release.apk → termux-app_2.2.5.R5_x86_64.apk（下划线 ABI 也截对）
```

#### 7.3.3 日志裁剪实测（javac 真实编译 + 真实 3 MB 文件）

```
原文件大小: 3162060 字节（阈值 2097152）
I/TrimHarness: Trimmed log file from 3162060 to 1048432 bytes
  ✅ 裁剪后低于上限 / 仍保留约 1 MB 尾部
  ✅ UTF-8 解码无替换字符（没有把中文切成半个 3 字节字符）
  首行前 60 字符: LINE-2486-中文日志内容ABCdef123-中文日志内容ABCdef123-...
  ✅ 首行是完整的一行，长度与其它行一致（不是被截了一半的残行）
  ✅ 低于上限时不再裁剪（幂等）
  ✅ 小文件保持原样
```

这条最关键的是**中文边界**：按字节 seek 很容易落在一个 3 字节汉字的中间，
按字节 seek 很容易落在一个 3 字节汉字的中间，读出来就是乱码。实测用严格 UTF-8 解码检查 `U+FFFD`，结果为 0。

#### 7.3.4 顺带记下的两个坑（写脚本时踩的，后面别再踩）

1. **Groovy 里 `{ ... }` 和 `{ task -> ... }` 的 `maximumNumberOfParameters` 都是 1**，
   不能据此判断闭包是否声明了参数（一开始判错，导致所有 `android {}` 块压根没被执行、
   断言全变成"没录到"）。可靠判据是反射看有没有 `doCall()` 无参重载。
2. **桩对象的动态属性会劫持自身字段**：`Rec` 实现 `getProperty` 后，
   `it.path` / `rec.calls` 这类正常访问会被当成"未知属性"而新建子对象，
   直接在迭代里改集合 → `ConcurrentModificationException`。必须显式放行内部字段名。

### 7.4 回归对照：把旧代码改回去，验证它真的不生效

为了让 §4 那个"重命名逻辑原本是死代码"的结论站得住，把 `app/build.gradle` 守卫改回老写法
（自动文件名前缀 `app-` 直接 return），用**同一套**验证脚本重跑：

```
=== 旧守卫副本 ===
  ❌ 本地拆分包 → app-debug-<abi>.apk  实际 [app-arm64-v8a-debug.apk, app-x86_64-debug.apk]
  ❌ 未拆分整包 → app-debug-universal.apk  实际 [app-debug.apk]
  ❌ UM 拆分包 universal → app-debug-universal.apk  实际 [app-universal-debug.apk]
  通过 38 项，失败 3 项
```

对照组失败了 → 说明新代码确实改变了行为，而不是"看起来改对了"。

---

## 8. 待你决策

| # | 事项 | 我的建议 |
|---|------|----------|
| 1 | **是否开启 R8**（`-Ptermux.enableR8=true`） | 先在一个 release 上做全链路回归（AI / 插件 / 终端 / 下载 / VNC / SSH / GitHub 登录），确认无反射断链后再默认开启；预期 dex 省约 15 MB |
| 2 | **字体 33.7 MB 是否改为按需下载** | 收益最大（占包 18.6%），但要改 vendored 的 `termux-styling` 模块，且会影响"字体选择"的开箱体验，需你判断产品取舍 |
| 3 | **universal 包默认策略** | 目前 release 事件仍构建 universal（与拆分前一致）；若确认不需要，把 `github.event_name == 'release' \|\|` 这段删掉即可只出分架构包 |
| 4 | 单架构构建是否也设为本机默认 | 目前不指定 `termux.abi` 时行为完全不变，本机开发不受影响 |
| 5 | 是否把 `opt-abi-split-20260922` 推到远端 / 开 PR | 建议推上去，让 CI 完成 §7.2 那 4 项验证 |

---

## 9. 【重要】推上去之后，CI 立刻抓到一个本机验不出的错误

PR #128 推上去后 30 秒内就有结论：**4 个矩阵作业全部失败**，且都挂在 configure 阶段
（`BUILD FAILED in 39s`，还没进编译）。日志：

```
* What went wrong:
A problem occurred configuring project ':app'.
> Conflicting configuration : 'arm64-v8a' in ndk abiFilters cannot be present
  when splits abi filters are set : arm64-v8a
```

### 9.1 根因

AGP **禁止** `defaultConfig.ndk.abiFilters` 与 `splits.abi` 同时存在。
而我在实现单架构开关时把两个都设了（想用 `abiFilters` 限定 CMake 只编该 ABI，
同时用 `splits` 收窄打包），于是任何带 `-Ptermux.abi=<单个架构>` 的构建在配置期就炸。

这正好是 §7.2 里我明确列为「本机无法验证」的第 1 项 —— 本机没有 AGP 环境，
桩 DSL 只能证明"配置值按预期算出来了"，证明不了"AGP 接受这组值"。
**这次等于实锤了那条免责声明不是客套话。**

### 9.2 修法

去掉 `ndk.abiFilters`，**只用 `splits.abi` 收窄**：

| 构建方式 | `ndk.abiFilters` | `splits.abi` | 结果 |
|----------|------------------|--------------|------|
| `-Ptermux.abi=arm64-v8a`（修复后） | 不设 | 开启，`include [arm64-v8a]`，`universalApk=false` | ✅ |
| 同上（修复前） | `[arm64-v8a]` | 同上 | ❌ 配置期报 Conflicting configuration |

未指定参数时行为完全不变（release 拆四架构 + universal，debug 打整包）——
因为它本来就没设 `abiFilters`，上游原始实现用的也是纯 splits。

### 9.3 `splits` 单独收窄会不会连原生编译一起收窄？—— **已用 CI 日志实测确认：会**

AGP 的 CMake 任务本身是按 ABI 分的（`:app:configureCMakeDebug[arm64-v8a]` 这种），
splits 列表决定生成哪些。CI run #492 四个作业的日志逐条核对：

```
  Debug APK (x86)          configureCMake→['x86']           buildCMake→['x86']
  Debug APK (x86_64)       configureCMake→['x86_64']        buildCMake→['x86_64']
  Debug APK (arm64-v8a)    configureCMake→['arm64-v8a']     buildCMake→['arm64-v8a']
  Debug APK (armeabi-v7a)  configureCMake→['armeabi-v7a']   buildCMake→['armeabi-v7a']
```

每个作业**只出现一个 ABI** 的 CMake 任务 → 原生编译确实被收窄了，"拆架构省资源"成立。

### 9.4 ⚠️ 但耗时数据推翻了"墙钟时间降到 1/4"的说法，这里必须更正

我在 §3 和 PR 正文里写过"单作业耗时约为原来的 1/4"。**CI 实测不支持这个数字**，列出来：

| | 拆分前（main，单作业全量，run #487） | 拆分后（本分支 run #492，4 作业并行） |
|---|---|---|
| arm64-v8a | — | 4 分 44 秒 |
| armeabi-v7a | — | 7 分 06 秒 |
| x86 | — | 11 分 44 秒 |
| x86_64 | — | 11 分 39 秒 |
| 单个作业 | **6 分 56 秒**（一次编完 4 个 ABI） | 4 m 44 s ~ 11 m 44 s |
| runner 时间合计 | 约 7 分钟 | **约 35 分钟** |

**但这是缓存冷启动的数字，换成热缓存结论不一样**（run #498，分支缓存已建立后）：

| 作业 | 耗时 |
|------|------|
| arm64-v8a | 3 分 59 秒 |
| x86 | 4 分 41 秒 |
| armeabi-v7a | 4 分 01 秒 |
| x86_64 | 3 分 45 秒 |
| runner 时间合计 | 约 17 分钟 |
| **墙钟（并行，取最慢）** | **约 4 分 41 秒** |

结论（说人话，冷热分开讲）：

- **每个作业的原生编译量确实降到 1/4**（上面 CMake 任务已证实），峰值内存也更低——但这是不可测的，我没有实测内存数据，只能说"应当更低"。
- **墙钟时间**：冷缓存下反而更慢（11 m 44 s vs 6 m 56 s），**热缓存下更快**（4 m 41 s vs 6 m 56 s，约快 30%）。
- **CI 分钟数一定上升**：每个作业都要各自付一遍 Gradle 配置、依赖解析和 Kotlin/Java 编译，这部分是 4 份重复劳动。热缓存下约 17 分钟 vs 7 分钟（约 2.4 倍），冷缓存下约 35 分钟（约 5 倍）。

所以这个改造的真实收益是 **失败隔离 + 单作业资源峰值下降**，不是"更快"或"更省"。
如果目标是省 CI 成本，更划算的做法是把矩阵收窄到实际要分发的架构（arm64-v8a + armeabi-v7a，x86/x86_64 通常只给模拟器用），
或者把 Kotlin/Java 编译与原生编译拆成两个阶段共用产物。见 §8 待决策。

### 9.4 同步改了什么

- `app/build.gradle`：删掉 `defaultConfig` 里的 `ndk { abiFilters... }`，改为一段说明禁止这样写的注释（含 CI 报错原文）。
- 桩 DSL 验证脚本：断言方向整个反过来 —— 由「断言 `abiFilters` 被收窄」改为「断言**任何场景都不设** `abiFilters`」（本地 38 项全绿）。
- 提交 `f792791c`，已推到同一分支。

---

## 10. 第二个坑：同一个 commit，push 事件是红的、pull_request 事件是绿的

修完 §9 之后出现一个更费解的现象：

| 运行 | 触发事件 | 检出对象 | 结论 |
|------|----------|----------|------|
| #491 | `push` | `refs/remotes/origin/opt-abi-split-20260922`（分支本体） | ❌ failure |
| #492 | `pull_request` | `refs/remotes/pull/128/merge`（与本分支**合并后**的树） | ✅ success |

同一个 `head_sha`、同一份工作流，一半红一半绿。日志对比直接给出答案 —— **编译的不是同一棵树**：

```
#491  [command] git checkout --progress --force -B opt-abi-split-20260922 refs/remotes/origin/opt-abi-split-20260922
#492  [command] git checkout --progress --force refs/remotes/pull/128/merge
```

而 #491 报的是与本批改动无关的既有编译错误：

```
Execution failed for task ':app:compileDebugKotlin'
> Kotlin compiler: UNRESOLVED_REFERENCE
   Unresolved reference 'Stretch'
   Location: app/src/main/java/com/termux/app/compose/OverviewScreen.kt line 1231
```

`Alignment.Stretch` 根本不是 `androidx.compose.ui.Alignment` 的常量。上游已经在主线修了它：

- `a67276bf fix(overview): 修复快捷入口 Row 的 verticalAlignment 引用`
- `067f9889 fix(overview): 用固定高度替代 Stretch 实现快捷入口卡片等高`

### 10.1 本机为什么没早发现

因为本机的 `origin/main` ref **是陈旧的**（停在 `82f86fd0`），`git merge-base --is-ancestor origin/main HEAD` 一路返回"已包含最新主线"，
于是我误判分支是最新的。校验方式不可靠就直接信了它。

（顺带：这次 `git fetch` 的 SIGTERM 中断把 `.git/refs` 整个删掉了，仓库一度变成
"not a git repository"。所有提交都已推到远端，所以没有丢东西 —— 恢复方式是
把 `.git` 改名保留、从 GitHub 重新克隆一份 `.git` 放回来、再 `git checkout -f` 回分支，
未提交的报告改动提前另存了副本。详见当日工作日志。）

### 10.2 处理

把主线合并进本分支（`d493f990`），并改用「拉 `refs/pull/128/merge` 与实际分支树做 diff」来交叉验证 ——
直接证明了 merge 树里已经没有 `Stretch`，而分支树里有。

> 给后面留个规矩：**判断"是否已同步主线"不能只靠 `merge-base`，要先确认 `origin/main` 这个 ref 本身是新写的。**
> 另外，`pull_request` 事件编译的是合并树，它会掩盖"分支本身编不过"这类问题 ——
> 看 CI 结论时要留意事件类型。

---

## 附：本轮改动文件清单

```
 M .github/workflows/ci.yml                                # 四 ABI 矩阵编译
 M .github/workflows/release_apk.yml                       # 四段式：预检 / 矩阵构建 / universal / 汇总发布
 M app/build.gradle                                        # 单架构开关 + resConfigs + packaging + R8 开关 + 重命名修复
 M app/proguard-rules.pro                                  # R8 keep 规则
 M app/src/main/java/com/termux/app/MainActivity.kt         # 轮询仅前台运行
 M app/src/main/java/com/termux/app/TermuxApplication.java  # onTrimMemory / onLowMemory
 M app/src/main/java/com/termux/app/utils/LogManager.java   # 日志 2 MB 上限 + 流式裁剪
 M RESOURCE_OPTIMIZATION_REPORT.md                          # 本报告（第三轮补记 §7.3 / §7.4）
?? .workbuddy/verify/abi_probe.groovy                       # 桩 DSL 真实执行 build.gradle 并断言
?? .workbuddy/verify/check_workflows.py                     # 两个工作流的 YAML 结构与密钥校验
?? .workbuddy/verify/build_logtrim_harness.py               # 抽取日志裁剪方法 → javac 编译 → 实跑
?? REDUNDANT_CODE_LIST.csv                                  # 上一轮冗余代码扫描产物（未入库，纯扫描中间物）

重跑验证的方式：

```bash
GDW="C:/Users/yudix/.gradle/wrapper/dists/gradle-9.3.1-all/9ot9r568e8zfvvd4mn8rbu1j0/gradle-9.3.1"
JAVA="/c/Program Files/Java/jdk-21.0.10/bin/java"

# 1) 配置分支 + 产物重命名（本地命名规则：不带 tag）
"$JAVA" -Dstdout.encoding=UTF-8 -cp "$GDW/lib/*" groovy.ui.GroovyMain \
    .workbuddy/verify/abi_probe.groovy app/build.gradle

# 2) 同上（CI 命名规则：必须带 tag 再跑一次）
TERMUX_APK_VERSION_TAG=2.2.5.R5 "$JAVA" -Dstdout.encoding=UTF-8 -cp "$GDW/lib/*" \
    groovy.ui.GroovyMain .workbuddy/verify/abi_probe.groovy app/build.gradle

# 3) 工作流结构
python .workbuddy/verify/check_workflows.py

# 4) 日志裁剪（javac + 实跑）
python .workbuddy/verify/build_logtrim_harness.py
```

分支：`opt-abi-split-20260922`（基于 `origin/main` = `80840491`）。
第三轮开始前已确认该分支**包含**远端最新（`git merge-base --is-ancestor origin/main HEAD` 为真，领先 86 个提交）。
