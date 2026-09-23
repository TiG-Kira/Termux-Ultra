#!/usr/bin/env python3
"""依赖污染判定器。

判定「一次依赖声明改动，是否让 :app 实际解析到的依赖图发生了声明之外的变化」。

两类污染：
  1. SCOPE_ESCALATION（作用域外溢）
     改动的不是 :app 自己的清单文件，但 :app 解析出的依赖版本却变了。
     典型就是 #143：改的是 vendor/termux-addons/termux-api 的 build.gradle，
     但 :app 依赖 project(':termux-api')，Gradle 取最高版本，主 app 跟着被顶。
     diff 上完全看不出来，只有解析依赖图才发现。

  2. COLLATERAL（连带升级）
     有坐标发生了版本变化，但它并不在本次「声明改动」的坐标集合里 ——
     即被别人顺带拽上来的。

输入为若干文件（便于在 CI 里分步生成、也便于本地复现）：
  --base-deps       基线（PR base）解析出的坐标清单，每行 group:artifact:version
  --head-deps       PR head 解析出的坐标清单
  --changed-files   本次变更的文件列表，每行一个路径
  --diff            变更清单文件的 unified diff 文本
  --summary-out     写出的 Markdown 摘要（挂到 $GITHUB_STEP_SUMMARY）
  --github-out      追加写入的 GITHUB_OUTPUT 文件

退出码恒为 0 —— 本脚本只做「判定」，跑不跑测试、失败与否由 workflow 决定。
"""

import argparse
import os
import re
import sys

# group:artifact:version 形式的坐标（版本里可能带 -alpha / +build 等）
COORD_RE = re.compile(
    r"(?<![A-Za-z0-9_.\-])"
    r"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-+]+)"
    r"(?![A-Za-z0-9_.\-])"
)

# 属于 :app 自身的依赖清单：只有动这些文件，:app 的依赖图变化才算「预期内」
APP_SCOPED_MANIFESTS = (
    "app/build.gradle",
    "app/build.gradle.kts",
    "gradle/libs.versions.toml",
)


def read_lines(path):
    if not path or not os.path.isfile(path):
        return []
    with open(path, encoding="utf-8", errors="replace") as fh:
        return [ln.strip() for ln in fh if ln.strip()]


def parse_deps(path):
    """group:artifact:version -> {group:artifact: version}"""
    result = {}
    for line in read_lines(path):
        ga, _, version = line.rpartition(":")
        if ga and version:
            result[ga] = version
    return result


def declared_coordinates(diff_text):
    """从 diff 的 +/- 行里抽出被声明改动的 group:artifact。"""
    declared = set()
    for line in (diff_text or "").splitlines():
        if not line.startswith(("+", "-")):
            continue
        if line.startswith(("+++", "---")):
            continue
        for group, artifact, _version in COORD_RE.findall(line):
            declared.add(f"{group}:{artifact}")
    return declared


def diff_graphs(base, head):
    """返回 [(group:artifact, old, new)]，含新增/移除（用 — 表示）。"""
    changed = []
    for ga in sorted(set(base) | set(head)):
        old = base.get(ga)
        new = head.get(ga)
        if old != new:
            changed.append((ga, old or "—", new or "—"))
    return changed


def markdown_table(rows, headers):
    if not rows:
        return ""
    out = ["| " + " | ".join(headers) + " |",
           "|" + "|".join(["---"] * len(headers)) + "|"]
    for row in rows:
        out.append("| " + " | ".join(f"`{c}`" for c in row) + " |")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-deps")
    ap.add_argument("--head-deps")
    ap.add_argument("--changed-files")
    ap.add_argument("--diff")
    ap.add_argument("--summary-out")
    ap.add_argument("--github-out")
    ap.add_argument("--annotations-out", help="每行一条 ::warning:: 文案，供 workflow 逐条抛出注解")
    args = ap.parse_args()

    changed_files = read_lines(args.changed_files)
    base = parse_deps(args.base_deps)
    head = parse_deps(args.head_deps)

    # 依赖图对比失败（任一清单缺失/为空）时无法判定，交回给 workflow 走兜底
    if not base or not head:
        verdict = "unknown"
        changed = []
        collateral = []
        scope_escalation = False
    else:
        changed = diff_graphs(base, head)
        declared = declared_coordinates(
            open(args.diff, encoding="utf-8", errors="replace").read()
            if args.diff and os.path.isfile(args.diff) else ""
        )
        collateral = [row for row in changed if row[0] not in declared]
        app_scoped = any(
            f in APP_SCOPED_MANIFESTS or f.startswith("app/") and f.endswith((".gradle", ".gradle.kts"))
            for f in changed_files
        )
        scope_escalation = bool(changed) and not app_scoped
        verdict = "polluted" if (collateral or scope_escalation) else "clean"

    polluted = verdict == "polluted"

    lines = []
    lines.append("## 依赖污染检查")
    lines.append("")
    if verdict == "unknown":
        lines.append("⚠️ **未能完成依赖图对比**（基线或当前分支的解析结果缺失），已放行常规单元测试。")
        lines.append("")
        lines.append("这种情况通常是 Gradle 解析失败导致，建议人工确认本次依赖改动的影响范围。")
    elif verdict == "clean":
        lines.append("✅ 未发现依赖污染：`:app` 的依赖图变化均在声明范围内（或根本没有变化）。")
    else:
        lines.append("⚠️ **检出依赖污染**：`:app` 实际解析到的依赖发生了声明之外的变化。")
        lines.append("")
        lines.append("> 这类改动在 diff 上看不出来，但会改变主 app 运行时的真实依赖版本，")
        lines.append("> 历史上曾因此导致 `VncActivity` 启动即崩溃（#143）。")
        lines.append("")
        if scope_escalation:
            lines.append("**作用域外溢**：本次没有改动 `:app` 自己的依赖清单，")
            lines.append("但 `:app` 的依赖图仍然变了 —— 多半是某个 vendor/子模块的改动被 Gradle")
            lines.append("单版本策略带进了主 app。")
            lines.append("")
        if collateral:
            lines.append("**连带升级**：以下坐标的版本发生变化，但并未出现在本次声明改动里：")
            lines.append("")
            lines.append(markdown_table(collateral, ["坐标", "原版本", "新版本"]))
            lines.append("")

    if changed:
        lines.append("### `:app` 依赖图全部变化")
        lines.append("")
        lines.append(markdown_table(changed, ["坐标", "原版本", "新版本"]))
        lines.append("")
    if changed_files:
        lines.append("### 本次改动的清单文件")
        lines.append("")
        lines.extend(f"- `{f}`" for f in changed_files)
        lines.append("")

    summary = "\n".join(lines) + "\n"
    if args.summary_out:
        with open(args.summary_out, "w", encoding="utf-8") as fh:
            fh.write(summary)
    print(summary)

    # 注解文案需要单行，且不能包含 ::warning:: 的转义字符，这里只放坐标与版本
    if args.annotations_out:
        with open(args.annotations_out, "w", encoding="utf-8") as fh:
            for ga, old, new in changed:
                fh.write(f"{ga}: {old} -> {new}\n")
            for f in changed_files:
                fh.write(f"改动的清单文件: {f}\n")

    # 供 workflow 决定后续行为
    if args.github_out:
        with open(args.github_out, "a", encoding="utf-8") as fh:
            fh.write(f"polluted={'true' if polluted else 'false'}\n")
            fh.write(f"verdict={verdict}\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
