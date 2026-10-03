#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Kotlin 花括号/圆括号结构自检（差分式，几乎无误报）。

为什么要它：手工解合并冲突时最容易犯的错就是**多吃/少吃一个 `}`** ——
2026-10-03 解 `HolidaySettingsScreen.kt` 时就多留了一个 `}`（连带留下一行旧文案），
门禁 40 条照样全绿、只有 CI 编译才会炸。为了不再浪费一轮 CI，做成本地自检。

判定方式（避开朴素扫描器的误报）：
  · 一个**结构合法**的 Kotlin 文件，扫到文件尾的净深度必然是 0；
    但模板字符串/字符字面量会让朴素扫描器算出非 0（本项目 `AndroidBridge.kt` 就是 +8）。
  · 所以不比"必须为 0"，而是比**同一文件在两个父提交里的净深度**：
    手工解冲突**不应该改变**一个文件的净深度 ——
    与任一父提交相同 = 正常；与两个父都不同 = 极可能是被手误改坏了。

用法：
  python3 tools/check_braces.py            # 对比 HEAD^1（合并提交则同时比 HEAD^1/HEAD^2）
退出码：有可疑文件 → 1
"""
import io
import os
import subprocess
import sys


def depth(text: str) -> int:
    """朴素但一致的净深度（模板串/字符字面量会带来固定的偏差，差分时抵消）"""
    d = 0
    i, n = 0, len(text)
    in_str, esc = False, False
    while i < n:
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
            elif c == "{":
                d += 1
            elif c == "}":
                d -= 1
        i += 1
    return d


def git(*args):
    r = subprocess.run(["git"] + list(args), capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def files_at(ref):
    out = git("ls-tree", "-r", "--name-only", ref)
    if out is None:
        return set(), {}
    names = {l for l in out.splitlines() if l.endswith(".kt")}
    depths = {}
    for name in names:
        blob = git("show", "%s:%s" % (ref, name))
        if blob is not None:
            depths[name] = depth(blob)
    return names, depths


def main():
    parents = [p for p in ("HEAD^1", "HEAD^2") if git("rev-parse", "--verify", "-q", p)]
    if not parents:
        print("没有父提交可比较（首次提交？），跳过")
        return 0
    pnames, pdepths = {}, {}
    for p in parents:
        names, depths = files_at(p)
        pnames[p] = names
        pdepths[p] = depths
    bad, checked = [], 0
    for path in sorted(set().union(*pnames.values())):
        if not path.endswith(".kt") or not os.path.exists(path):
            continue
        cur = depth(io.open(path, encoding="utf-8", errors="replace").read())
        parent_vals = [pdepths[p][path] for p in parents if path in pdepths[p]]
        if not parent_vals:
            continue
        checked += 1
        if cur not in parent_vals:
            bad.append((path, cur, parent_vals))
    print("Kotlin 结构自检：比对 %d 个 .kt（父提交：%s）" % (checked, "、".join(parents)))
    if bad:
        print("❌ 以下文件的净括号深度与两个父提交都不同（八成是解冲突时多吃/少吃了一个 `}`）：")
        for path, cur, pv in bad:
            print("   %s：当前 %d，父提交 %s" % (path, cur, pv))
        return 1
    print("✅ 全部一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
