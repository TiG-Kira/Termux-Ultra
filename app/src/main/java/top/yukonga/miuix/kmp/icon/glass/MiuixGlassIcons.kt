// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.icon.glass

/**
 * OS4 symbol icon family, vendored from miuix PR #423 (miuix-glass-icons).
 *
 * Upstream mounts the per-icon extensions on a `MiuixIcons.Glass` nested
 * object declared in miuix-core; the maven 0.9.4 core artifact does not carry
 * that nested object, so the family is declared locally instead. When the PR
 * lands upstream and the dependency is bumped past it, delete this file and
 * retarget the icon files' receivers back to `MiuixIcons.Glass`.
 */
object MiuixGlassIcons {
    object Light
    object Normal
    object Regular
    object Medium
    object Demibold
}
