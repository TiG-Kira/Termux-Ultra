---
title: 文档首页
lang: zh
ref: index
permalink: /
description: Termux Ultra 官方文档 — 使用手册、功能讲解与插件开发文档。
---

<div class="hero">
  <h1>Termux Ultra 文档</h1>
  <p>基于 Termux 二次开发的 Android 终端模拟器与 Linux 环境。UI 采用 Jetpack Compose + Miuix（HyperOS 设计语言），集成 VNC 远程桌面、SSH、文件管理、proot 容器、QEMU 虚拟机、一键资源部署、AI 助手与完整插件系统。</p>
</div>

<div class="badges">
  <span class="badge">Android 8.0+ (API 26)</span>
  <span class="badge">包名 com.termux</span>
  <span class="badge">AGPL-3.0</span>
  <span class="badge">上游基底 Termux v0.119.0</span>
</div>

## 三份文档

<div class="cards">
  <a class="card" href="{{ '/zh/manual/' | relative_url }}">
    <span class="card-title">📖 使用手册</span>
    <span class="card-desc">安装、卸载、首次启动、终端会话、文件管理、VNC/SSH、容器与虚拟机、设置与备份、AI 助手配置、日志调试与常见问题。</span>
  </a>
  <a class="card" href="{{ '/zh/features/' | relative_url }}">
    <span class="card-title">🧩 功能讲解</span>
    <span class="card-desc">整体架构、双终端引擎（经典 / Nova）、Miuix UI、集成工具机制、插件系统能力矩阵与权限模型、AI 助手与安全增强引擎。</span>
  </a>
  <a class="card" href="{{ '/zh/plugins/' | relative_url }}">
    <span class="card-title">🔌 插件构建文档</span>
    <span class="card-desc">manifest.json 全字段、权限声明、资源卡片、Agent Skill、System Prompt、H5 多页面与 JS Bridge、Compose JSON DSL、宿主 Action 桥、打包与调试。</span>
  </a>
</div>

## 可视化看板

<div class="cards">
  <a class="card" href="{{ '/okr/' | relative_url }}">
    <span class="card-title">📊 团队 OKR 雷达图</span>
    <span class="card-desc">按开源仓库 8 大能力域（终端内核、插件生态、AI 助手、远程容器、界面动效、工程基建、文档本地化、社区开源）对比产品 / 技术 / 设计三组团队的目标与实际达成度，含整体完成率、最强与最弱维度、环比趋势，达成情况按超额 / 接近 / 不足着色。</span>
  </a>
</div>

## 快速开始

1. 到 [GitHub Releases](https://github.com/TiG-Kira/Termux-Ultra/releases) 下载对应架构的 APK。
2. 安装前确认设备上**没有**其它来源的 Termux / Termux 插件（签名必须一致，详见使用手册）。
3. 首次启动会进入 OOBE 引导页，按提示完成初始化。
4. 底部导航栏四个页签：**终端 / 文件 / 远程 / 资源**，也支持左右横滑切换。

> 若设备上已装过 F-Droid 或 Play 商店版本的 Termux，请先全部卸载再从本仓库重新安装全部 APK，否则会出现 `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` 或 `signatures do not match`。

## 分支与版本

| 分支 | 基底 | 版本 | 状态 |
|------|------|------|------|
| `main` | 上游 Termux `v0.119.0-beta.3` | 2.x.x.R5 | 正式版主线，承接功能开发、Bug 修复与架构优化 |
| `release/r1-r4` | 上游 Termux `v0.118.3` | 1.8.0.R4 | v1.8.x 历史快照，停止功能更新，仅修紧急阻断 Bug |
| `archived/corebump/2.x` | 上游 Termux `v0.119.0-beta.3` | 2.0.0.R5 | 归档快照，保留 2.x 内部开发步骤 |

## 相关链接

- [GitHub 仓库](https://github.com/TiG-Kira/Termux-Ultra)
- [Releases（正式版 APK）](https://github.com/TiG-Kira/Termux-Ultra/releases)
- [CI 构建（测试版 Artifacts）](https://github.com/TiG-Kira/Termux-Ultra/actions/workflows/ci.yml)
- [提交 Issue](https://github.com/TiG-Kira/Termux-Ultra/issues)
- [上游 Termux](https://github.com/termux/termux-app)
- [应用内可安装的软件包（termux-packages）](https://github.com/termux/termux-packages)
