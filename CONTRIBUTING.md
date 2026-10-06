# 贡献指南

> **术语说明（先读这一段，避免混淆）**
>
> 本文里出现两种「tag」，含义完全不同，请按需区分：
>
> - **GitHub 标签（label）**：贴在 Issue / PR 上的彩色分类标记，用于描述*诉求类型*与*处理结论*。下文的「标签规范」专指它，与版本无关。
> - **Git 版本标签（tag）/ Release 标记**：`git tag` 打的版本号（如 `3.3.5.R7`），是发版的锚点，会触发自动出包。下文「PR 发布细则」里的 tag 均指这个。
>
> 简记：**标签 = 分类；版本 tag = 发版号。**

---

## 一、GitHub 标签（label）规范

### 标签基础定义

| 标签 | 语义说明 |
| --- | --- |
| `bug` | 代码行为违背设计，存在缺陷。严重阻断核心功能的问题，仅标记 `bug`；轻微非阻塞 bug，修复以提升体验为目标，可组合 `improve`。 |
| `improve` | 目标为**优化现有功能的体验、参数、交互或性能**。代码本身无错误，只是现有行为不够友好；可与其他标签组合，仅代表诉求类型。 |
| `enhancement` | 需要新增项目原本不存在的独立能力 / 新功能。 |
| `wontfix` | 不会进行实现。 |
| `delay` / `hold` | 暂不推进：`delay` 表示延后评审与决策；`hold` 表示已明确暂勿合并（例如等待依赖、等待设计定稿）。 |
| `from-Agent` | PR 由 Agent / 机器人提交，便于人工评审时区分来源。 |

> 其余继承自上游的通用标签（`documentation`、`duplicate`、`invalid`、`question`、`dependencies`、`java`、`github_actions` 等）按 GitHub 默认语义使用即可。

### 标签组合规则

标签可以叠加使用，但 Issue 与 PR 的适用场景不同：

1. **`bug`（单独）**
   严重 bug，阻断核心流程、造成崩溃 / 数据异常。首要目标是恢复功能，体验提升只是附带结果，不叠加 `improve`。
2. **`bug + improve`**
   仅适用于**轻微、不阻塞功能**的代码缺陷；修复这个 bug 的主要价值是改善用户体验。
3. **`improve`（单独）**
   代码逻辑完全符合当前设计，不存在 bug。仅调整参数、文案、现有逻辑的体验，不需要新增底层能力。
4. **`improve + enhancement`**
   用户诉求是优化已有功能体验，但落地实现必须新增项目原本不存在的底层能力、接口或配置项。
5. **`improve + wontfix`（仅 PR 可用，Issue 禁止该组合）**
   认可这个 PR 对应的需求方向属于体验优化（`improve`），**但当前这份 PR 的实现方案不合适，不予合并**。原 Issue 保留，可等待其他更好的实现方案。
   > Issue 场景：如果决定整体拒绝该优化诉求，**只打 `wontfix`，不搭配 `improve`**，代表需求本身不接纳。
6. **`enhancement`（单独）**
   全新独立功能需求，和现有功能的体验优化无关。
7. **`wontfix`（单独，Issue / PR）**
   完全不接纳该需求，不打算实现。
8. **`hold` / `delay`**
   用于「先别动」的状态：`hold` 表示暂勿合并；`delay` 表示延后评审。两者都不代表最终结论，待条件满足后可移除并继续流程（不要因此直接 `close`）。

### 快速判断示例

- ✅ `bug`：对话会话直接崩溃，无法继续使用
- ✅ `bug + improve`：边界场景多余提示文字，会话不受影响，修复后观感更好
- ✅ `improve`：现有轮次功能正常，希望调高默认轮次上限（已有配置项，仅修改默认值）
- ✅ `improve + enhancement`：希望调高轮次上限，但代码里没有开放自定义轮次的能力，需要新增配置入口
- ✅ `improve + wontfix`（PR）：PR 目标是调高轮次上限，但实现方式硬编码、缺少校验，不合并此 PR；需求方向保留
- ✅ `wontfix`（Issue）：用户请求永久移除轮次限制，评估风险过大，整体拒绝该需求

### 标签补充约定

- 标签是描述**诉求类型**和**处理结论**，不要过度堆砌标签；
- 关闭 Issue 时，如果标记 `wontfix`，请在评论简单说明拒绝理由；
- PR 使用 `improve + wontfix` 时，建议在 PR 评论说明：认可优化方向，但当前实现方案存在问题，本次不合并，欢迎重新设计后提交；
- 标记为 `hold` / `delay` 的 PR 应保持 **Open（可转 Draft）**，待条件满足再移除标签推进，不要 `close` 后再重开。

---

## 二、PR 发布细则

本段说明从「提一个 PR」到「正式发版出包」的完整流程。

### 1. 分支与提交规范

- **分支命名**：以类型前缀起手，语义清晰即可，例如：
  - `feat/xxx`、`fix/xxx`、`refactor/xxx`、`docs/xxx`、`chore/xxx`
  - 临时修复也可用 `fix-xxx` 形式。
- **提交信息**：采用 `type(scope): 一句话描述` 风格（与仓库历史一致），例如 `fix(oobe): 权限页按真实状态渲染`。首字母大写、不写句号。
- **变更范围**：纯文档（README / `docs/**`）改动不触发 `ci.yml` 与 `run_tests.yml`，但会触发 `gradle-wrapper-validation.yml`；改代码务必确保 CI 全绿。

### 2. PR 流程与 CI

- 发起前确保本地基于最新 `main`（先 `git fetch` 再 rebase / merge）。
- PR 默认目标分支为 `main`。
- 必过的检查：
  - `ci.yml`：各架构 `assembleDebug` 编译（README 改动会跳过）。
  - `run_tests.yml`：单元测试 + 依赖污染检查（dependency-guard）。
- 按上文「标签规范」为 PR 打上合适的标签；等待中 / 暂不合并的 PR 打 `delay` 或 `hold`，或转为 **Draft**。

### 3. 合并方式

- 合并到 `main` 采用 **squash merge**：合并后的主线条目即 PR 标题（沿用 `type(scope): 描述` 风格），保持 `--first-parent` 历史干净。
- 作者本人不能 approve 自己的 PR；如需评审意见用 `gh pr comment` 沟通。

### 4. 版本号与打 tag 发版

发版通过 **Git 版本 tag + GitHub Release（Published）** 触发，APK 由 CI 自动构建并上传，**切勿手动上传二进制**。

- **版本号位置**：`app/build.gradle` 中的 `versionName`（如 `3.3.5.R7`）与 `versionCode`（单调递增整数）。每次发版递增二者（CLI 包会在版本号后追加 `-CLI`，由构建脚本处理）。
- **tag 命名**：与 `versionName` 对齐，形如 `3.3.5.R7`；在仓库根打 tag 并通过 GitHub 创建 **Published Release**（标题 / 说明写清变更点）。
- **自动出包**：`.github/workflows/release_apk.yml` 监听 `release: published`，为每个架构并行构建并上传 `app-<abi>-release.apk` 与 universal 包到对应 Release。重复触发同一 tag 会跳过（Release 已含 APK 则不再构建）。
  - 也支持 `workflow_dispatch` 手动指定 `tag` 与是否额外构建 universal 包。
- **bootstrap 更新**：终端运行所需的内置初始化包走独立 tag `bootstrap-v1`（`BootstrapDownloader` 的 `REF`，升级 zip 须同步更新 SHA；失败会在 OOBE 引导重试），**不属于应用版本 tag**。

### 5. 预发布线（corebump）

- 来自 `corebump/*` 分支的发版**必须**作为 **prerelease / beta** 发布。
- `.github/workflows/mark-corebump-release-prerelease.yml` 会在创建 Release 时自动把这类发版标记为预发布，防止误发为稳定版。

### 6. 等待 / 暂挂状态的处理

- 当一个 PR 处于「先放着 / 不用关 / 等依赖」状态时，**转为 Draft 或打 `hold` / `delay` 保持 Open**，而**不要 `close`**。
- 原因：`close` 是不可逆的远端动作，会丢失 PR 列表可见性；Draft / hold 是可逆状态，`main` 无分支保护也不会被误合并，后续可直接继续 review 与 CI。

---

## 三、补充约定汇总

- 标签是描述**诉求类型**和**处理结论**，不要过度堆砌；
- 拒绝类结论（`wontfix` / `improve + wontfix`）请在评论给出理由；
- 版本号、tag、Release 说明三处保持一致；发版只走 tag + Release，不在 PR 里传 APK。
