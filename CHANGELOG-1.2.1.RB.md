# Termux Ultra 1.2.1.RB 变更日志

**发布日期**: 2026-08-24  
**版本**: 1.2.1.RB (Bug 修复版本)

---

## Bug 修复

### 1. 存储页面清理按钮状态修复

**问题**: 在存储清理功能中，即使勾选了全选，确认清理按钮仍然保持不可选中状态。

**根因**: `selectedCleanablePaths` 集合在全选勾选时未正确更新，导致按钮的启用条件未满足。

**修复**: 
- 文件: `app/src/main/java/com/termux/app/activities/StorageActivity.kt`
- 修改了全选复选框的 `onCheckedChange` 回调
- 现在勾选全选时，会将所有可清理项目的路径添加到 `selectedCleanablePaths` 集合中

---

### 2. Termux Agent 技能卡片兼容性扩展

**问题**: 原本仅支持 skill 代码块格式，不支持行业标准的 tool_call XML 格式。

**修复**: 
- 文件: `app/src/main/java/com/termux/app/compose/AiTermuxEngine.kt`
- 新增 `parseToolCallBlocks()` 函数，解析 tool_call 标签
- 升级 `parseSkillBlocks()` 策略，优先识别 tool_call 格式
- 旧的 skill 代码块格式仍然支持，但会触发 AI 警告提示


**技能格式优先级**:
| 格式 | 状态 | 说明 |
|------|------|------|
| tool_call | 推荐 | 行业标准 XML 格式 |
| skill | 过时 | 原有格式，仍支持 |

---

### 3. Termux Agent 自主技能创造 (new_tool)

**新增功能**: AI 可在无限制模式中自主创造新技能，并自动保存到用户的自定义技能库中。

**实现**: 
- 文件: pp/src/main/java/com/termux/app/compose/AiTermuxEngine.kt - parseNewToolBlocks() 解析函数
- 文件: pp/src/main/java/com/termux/app/compose/AiTermuxModels.kt - saveNewTool() 存储函数
- 文件: pp/src/main/java/com/termux/app/activities/AiTermuxActivity.kt - 自动检测和保存逻辑

**特点**:
- 创造的技能自动保存到「自定义技能」中，可在后续对话中直接调用
- 支持同名技能更新（覆盖旧版本）
- 技能名称须为英文大写字母加下划线格式

---

### 4. 无限制模式重构

**改进**: 大幅精简无限制模式的安全禁令，仅保留最基本的底线要求。

**修改文件**: pp/src/main/java/com/termux/app/compose/AiTermuxModels.kt

**主要变化**:
- 移除所有安全禁令: 包括危险命令警告、路径沙盒、命令注入防护、二次确认等
- 移除 AI 幻觉禁令: AI 可自主生成内容，系统不再拦截或质疑输出
- 提示代替阻止: 系统会对 AI 幻觉输出做标记提醒用户注意甄别，但不会阻止生成
- 新增技能创造能力: 支持 <new_tool> 创造新技能
- 新增格式指引: 明确 <tool_call> 为推荐格式

**仅保留的底线要求**:
1. 不要生成明显违法的内容（如恶意代码、人身攻击等）
2. 尊重用户意愿，不要在用户明确拒绝时强制执行危险操作
3. 用户已明确知晓并主动开启无限制模式，所有后果由用户自行承担

---

### 5. 智能完成检测（移除 END_TURN）

**改进**: 移除对 [END_TURN] 标记的依赖，改用更智能的手段检测 AI 输出是否完毕。

**修改文件**: pp/src/main/java/com/termux/app/activities/AiTermuxActivity.kt

**新逻辑**:
- 通过检测 AI 回复中是否包含技能调用来判断是否需要继续交互
- 有技能调用: 执行技能后，将结果回传给 AI，继续下一轮对话
- 无技能调用: 视为回复完毕，终止对话循环
- 移除了因收不到 [END_TURN] 就重复向 AI 发送请求的问题

**优点**:
- 更稳定可靠，不依赖 AI 输出特定标记
- 减少不必要的 API 调用，节省 token 消耗
- 解决 AI 忘记输出 [END_TURN] 时的死循环问题

---

## 开发者备注

### 技能格式迁移指南

原有的 skill 代码块格式将在未来版本中删除。请提示 AI 尽快迁移到 tool_call 格式。

**旧格式（将废弃）**: 使用 skill 代码块包裹技能调用

**新格式（推荐）**: 使用 XML 格式的 tool_call 标签

详细格式说明请参见上方「Bug 修复 - 第2节」。

---

## 文件修改清单

| 文件路径 | 修改类型 | 说明 |
|---------|---------|------|
| StorageActivity.kt | Bug修复 | 修复全选按钮状态 |
| AiTermuxEngine.kt | 功能新增 | tool_call 解析、new_tool 解析 |
| AiTermuxModels.kt | 功能增强 | 无限制模式重构、saveNewTool、格式指引 |
| AiTermuxActivity.kt | 逻辑重构 | 智能完成检测、自动保存新技能 |

---

**Termux Ultra Team**
2026-08-24
