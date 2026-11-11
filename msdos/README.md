# MS-DOS 4.0 · Termux 精简版

在「功能中心 → 实用功能中心 → MS-DOS 模拟器」里运行的 MS-DOS 4.00 系统盘。
源码取自微软开源的 `microsoft/MS-DOS` 的 `v4.0/` 部分，运行时用 Termux 的
`qemu-system-i386`，支持控制台（curses）与 VNC 两种入口。

## 目录

| 路径 | 内容 |
|---|---|
| `v4.0/` | 上游 `microsoft/MS-DOS` 的 `v4.0/src` + `LICENSE`，原样保留 |
| `build/prepare.sh` | 以 vendor 源码为基底，叠加社区修复层，产出可 `nmake` 的构建树 |
| `build/make-termux-image.sh` | 从编译产物生成面向移动端的精简可启动硬盘镜像 |
| `termux/config/` | 精简后的 `CONFIG.SYS` / `AUTOEXEC.BAT` |
| `../app/src/main/java/com/termux/app/compose/MsDos*.kt` | App 侧下载、配置、启动、页面 |

## 为什么必须离线构建

`v4.0/src/TOOLS/` 里的 MASM / LINK / NMAKE / CL 是 16 位 DOS 程序，只能在
dosemu2 提供的 vm86 环境里运行；Android 内核没有 vm86，Termux 也没有 dosemu2。
因此源码改造在 x86 Linux CI 上完成，App 只下载成品镜像并启动 QEMU。

上游源码还存在 CP437 → UTF-8 误转码造成的损坏、CRLF 问题和 `SETENV.BAT`
盘符硬编码，`nmake` 直接跑会失败。`build/prepare.sh` 把
`tgies/MS-DOS` 固定 commit（`c8d7ddfe720df811e8c124810a16e34a71cc63d4`）
作为修复层，按校验和只覆盖与 vendor 不同的文件，其余文件仍来自微软原始树。

## 面向移动端的精简

目标是让纯 TCG 软件模拟、无 KVM 的手机也能秒进 `C:\>`：

- 硬盘镜像 16MB（上游默认 64MB），FAT16，单分区
- 外部命令从 39 个裁到 20 个常用工具，全部放 `C:\DOS`
- `CONFIG.SYS` 只保留 `FILES` / `BUFFERS` / `LASTDRIVE` / `STACKS`；
  不加载 ANSI.SYS、HIMEM、EMM386、SHARE、FASTOPEN 等常驻驱动
- `AUTOEXEC.BAT` 只设 `PATH` / `PROMPT` / `TEMP`，不启动 DOSSHELL
- QEMU 参数收敛为 `-M isapc -cpu 486 -m 8 -smp 1 -accel tcg,thread=single
  -vga std`，不挂网卡、USB、9p、声卡
- 用户目录用 mtools 打成独立 FAT16 数据盘 `-hdb`，不做启动盘

## 构建

```bash
bash msdos/build/prepare.sh /tmp/msdos-build
cd /tmp/msdos-build/v4.0 && ./mak.sh --flavor=msdos
bash msdos/build/make-termux-image.sh /tmp/msdos-build/v4.0 ./dist
```

产物：`dist/msdos4.img`（16MB raw）、`dist/msdos4.img.gz`、`dist/msdos4.img.gz.sha256`。
CI（`.github/workflows/msdos-assets.yml`）会把后两者发到常驻 tag `msdos-v1`
且标记 `prerelease=true` 的 Release，App 在运行时动态拉取并校验。

### 首版资产

CI 首次跑通之前，prerelease `msdos-v1` 里的 `msdos4.img.gz` 取自
`tgies/MS-DOS` release `4.0-c860389` 的 `msdos4.img`（同源 MIT 源码构建、上游已过
QEMU boot test）。为了让移动端下载体积可控，把 64MB 基线裁成 16MB：保留 MBR、
分区 VBR 与其后全部原始字节（引导代码、`IO.SYS` / `MSDOS.SYS` / `COMMAND.COM`
及 FAT 链均未改动），只同步改写分区表扇区数、`totSec16`、`totSec32` 三处长度
字段，再 `gzip -9 -n` 重打包并生成 SHA-256。

裁剪后的镜像已通过 31 项静态自检（MBR/VBR 签名、BPB 与分区表长度一致、FAT
容量、根目录前两项、`IO.SYS` 起始簇 2 且 17 簇连续、全部文件数据落在 16MB 内），
但**尚未做本项目的 QEMU 实机启动测试**，也没有套用 `termux/config/` 的精简
配置——那是 CI 构建产物的职责。CI 的 boot test 通过后用
`gh release upload msdos-v1 <gz> <sha256> --clobber` 覆盖即可：
App 每次启动比对远端 `.sha256`，哈希变化会自动重下，无需发版。

## 许可

`v4.0/LICENSE` 为 MIT，`Copyright (c) IBM and Microsoft Corporation`，必须随
任何衍生分发保留。详见 `NOTICE.md`。`MS-DOS` 是微软商标，本功能不代表微软背书。

DOSSHELL 与 SELECT.EXE 的源码未开源，未参与构建。
