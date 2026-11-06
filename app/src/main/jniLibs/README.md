# 预编译原生库

## arm64-v8a

| 文件 | 来源 |
| --- | --- |
| `librustdesk.so` | [TiG-Kira/rustdesk-core](https://github.com/TiG-Kira/rustdesk-core) `677f0e9`（对齐 RustDesk 1.5.1） |
| `libc++_shared.so` | 同上产物（NDK r28c 自带，`librustdesk.so` 的 `DT_NEEDED` 依赖） |

重建方式：在 `rustdesk-core` 仓库跑 `Build RustDesk Core` workflow
（`workflow_dispatch`，参数 `upstream_tag=1.5.1`、`abi=arm64-v8a`），下载产物覆盖本目录。

只提供 arm64-v8a：其余 ABI 没有 `librustdesk.so`，
`RustDeskCore.isAvailable()` 返回 false，RustDesk 会话自动退回官方客户端深链。
