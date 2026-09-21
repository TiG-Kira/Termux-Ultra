---
title: Documentation Home
lang: en
ref: index
permalink: /en/
description: Termux Ultra official documentation — user manual, feature guide, and plugin development docs.
---

<div class="hero">
  <h1>Termux Ultra Docs</h1>
  <p>An Android terminal emulator and Linux environment built on top of Termux. UI is written in Jetpack Compose with the Miuix (HyperOS) design language, and it bundles VNC remote desktop, SSH, a file manager, proot containers, QEMU virtual machines, one-tap resource deployment, an AI assistant, and a full plugin system.</p>
</div>

<div class="badges">
  <span class="badge">Android 8.0+ (API 26)</span>
  <span class="badge">Package com.termux</span>
  <span class="badge">AGPL-3.0</span>
  <span class="badge">Based on Termux v0.119.0</span>
</div>

## The three docs

<div class="cards">
  <a class="card" href="{{ '/en/manual/' | relative_url }}">
    <span class="card-title">📖 User Manual</span>
    <span class="card-desc">Installation, uninstallation, first run, terminal sessions, file manager, VNC/SSH, containers and VMs, settings and backup, AI assistant configuration, logging, and FAQ.</span>
  </a>
  <a class="card" href="{{ '/en/features/' | relative_url }}">
    <span class="card-title">🧩 Features</span>
    <span class="card-desc">Architecture, dual terminal engines (Classic / Nova), Miuix UI, integrated-tool mechanism, plugin capability matrix and permission model, AI assistant, and the security engine.</span>
  </a>
  <a class="card" href="{{ '/en/plugins/' | relative_url }}">
    <span class="card-title">🔌 Plugin Development</span>
    <span class="card-desc">Full manifest.json reference, permissions, resource cards, agent skills, system prompt, H5 pages and the JS bridge, Compose JSON DSL, host action bridge, packaging and debugging.</span>
  </a>
</div>

## Quick start

1. Download the APK for your architecture from [GitHub Releases](https://github.com/TiG-Kira/Termux-Ultra/releases).
2. Make sure **no** Termux / Termux plugin from another source is installed (signing must match — see the user manual).
3. The first launch opens the OOBE wizard; follow it to finish initialization.
4. The bottom bar has four tabs — **Terminal / Files / Remote / Resources** — and you can also swipe horizontally between them.

> If a Termux build from F-Droid or the Play Store is already installed, uninstall everything first and then reinstall all APKs from this repository. Otherwise you will hit `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` or `signatures do not match`.

## Branches and versions

| Branch | Base | Version | Status |
|--------|------|---------|--------|
| `main` | upstream Termux `v0.119.0-beta.3` | 2.x.x.R5 | Stable mainline — features, bug fixes, architecture work |
| `release/r1-r4` | upstream Termux `v0.118.3` | 1.8.0.R4 | v1.8.x historical snapshot, no new features, emergency fixes only |
| `archived/corebump/2.x` | upstream Termux `v0.119.0-beta.3` | 2.0.0.R5 | Archived snapshot preserving 2.x development steps |

## Links

- [GitHub repository](https://github.com/TiG-Kira/Termux-Ultra)
- [Releases (stable APKs)](https://github.com/TiG-Kira/Termux-Ultra/releases)
- [CI builds (beta artifacts)](https://github.com/TiG-Kira/Termux-Ultra/actions/workflows/ci.yml)
- [Open an issue](https://github.com/TiG-Kira/Termux-Ultra/issues)
- [Upstream Termux](https://github.com/termux/termux-app)
- [Installable packages (termux-packages)](https://github.com/termux/termux-packages)
