# 总览卡片重设计改动说明

## 目标
- 去除卡片上过于花哨的装饰性圆圈，改成简洁大气、内容优先的风格。
- 保持设备尺寸自适应能力，同时统一卡片尺寸。
- 提示与 Agent 卡片的竖向模式保持原样。

## 已实现的改动

### 1. 统一卡片容器 `OverviewCardContainer`
- 新增 `OverviewCardContainer(...)` 作为所有总览卡片的统一外壳。
- 尺寸统一：
  - 宽版固定高度 `160.dp`（`WIDE_CARD_HEIGHT`）。
  - 正方形版本使用 `aspectRatio(1f)`，确保宽高严格 1:1。
- 背景色自动跟随亮/暗色主题：亮色 `#FAFAFA`，暗色 `#1C1C1E`。
- 圆角统一为 `20.dp`，内边距 `16.dp`。

### 2. 通用辅助组件
- `CardSectionHeader`：左侧小图标 + 标题 + 编辑按钮，风格统一。
- `CardStatusBadge`：圆角胶囊状态标签，运行/后台/休眠/冻结等状态一目了然。
- `CardProgressBar`：纯色进度条（目前备用）。
- `CardMetricItem`：大数字 + 小标签组合，CPU/内存等核心数据居中展示。
- `ProcessItemRowCompact`：进程列表的紧凑行，显示进程名、状态、CPU、内存。

### 3. 各卡片重设计
- **SessionsCard**：左右两个色块分别显示运行中/已停止会话数，使用绿色/红色主题，无装饰圆圈。
- **CpuMonitorCard**：宽版左侧大百分比 + 右侧曲线图；正方形版本上下布局，底部带状态标签。
- **GpuMonitorCard**：支持 GPU 可用/仅历史数据/从未可用三种状态，宽版与正方形自适应布局。
- **MemoryMonitorCard**：与 CPU 卡片统一风格，显示总内存与占用百分比。
- **ProcessListCard**：顶部横向滚动状态标签（运行/后台/休眠/冻结），下方列出前 N 条进程（宽版 4 条，正方形 3 条）并显示 CPU/内存占用。
- **StopAllCard**：居中展示删除图标与运行中会话数，点击二次确认。
- **FeatureCenterCard**：保留渐变背景，但尺寸改为统一的 `160.dp` / `1:1`，标题与文案更紧凑。
- **ResourceActionCard**：统一容器风格，显示操作图标、描述、分类标签，点击启动对应动作。

### 4. 移除/清理
- 移除了旧版卡片中的装饰性渐变圆圈、装饰性圆环背景。
- 删除了旧的 `ProcessItemRow`（被 `ProcessItemRowCompact` 替代）。
- 删除了部分使用 `LocalConfiguration` 动态计算 `cardHeight` 的旧逻辑，改为固定常量/自适应宽高比。

### 5. 未改动的部分
- 提示与 Agent 卡片的竖向模式保持原样。
- 卡片拖拽/编辑/添加逻辑、卡片类型枚举、数据刷新等外部调用方式未变更。

## 文件位置
- 主要修改：`app/src/main/java/com/termux/app/compose/OverviewScreen.kt`
- 设计预览：`overview_cards_redesign_preview.html`

## 后续建议
- 由于本文件只负责 UI 设计落地，编译/语法问题已交由 Code Agent 继续处理。

