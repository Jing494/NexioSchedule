#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地预检 GitHub Actions workflow —— 把"只有 GitHub 才会报的错"提前到推送之前。

为什么有它（真事故）：2026-10-01 把 workflow 的 `env:` 段清成"只剩注释"后，
PyYAML 照样能解析（`env: null`），但 GitHub 直接判
`Invalid workflow file: (Line: 37, Col: 5): Unexpected value ''` ——
`workflow_dispatch` 返回 422，而且推分支时还会因这份"无效文件"产生一条 startup_failure 的运行
（看起来像"CI 挂了"，其实是 YAML 不合法）。

检查项（按 GitHub 的严格程度）：
  1) 全树不许有 `null` 值的键（`env:` / `with:` 之类后面只跟注释 = 实测踩过）；
  2) `env` 若存在必须是非空映射；
  3) `on:` 必须是映射、至少一个触发条件、且包含 workflow_dispatch；
  4) 每个 job 有 runs-on 与 steps；每个 step 有 name，且 uses / run 二选一；
  5) 每个 `run` 块都要通过 `bash -n`（bash 解析期错误在 CI 上很难读）；
  6) `if:` / `${{ }}` 里引用的 `steps.<id>.…` 必须真的存在（id 打错是最常见的手误）。

用法： python3 tools/check_workflow.py [文件…]    默认扫 .github/workflows/*.yml|*.yaml
退出码：任一不通过 → 1
"""
import glob
import io
import os
import re
import subprocess
import sys
import tempfile

try:
    import yaml
except ImportError:  # pragma: no cover
    print("需要 PyYAML：pip install pyyaml")
    sys.exit(2)

BAD = []


def fail(path, msg):
    BAD.append((path, msg))


def walk_nulls(node, path, ptr=""):
    """找出所有取值为 None 的键（GitHub 会把它们解析成空值并拒绝整份文件）"""
    if isinstance(node, dict):
        for k, v in node.items():
            if v is None:
                fail(path, "键 `%s%s:` 的值为空（GitHub 会判 Unexpected value ''）；"
                           "要么删掉这个键，要么给它真值" % (ptr, k))
            else:
                walk_nulls(v, path, ptr + str(k) + ".")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk_nulls(v, path, ptr + "[%d]." % i)


def check_file(path):
    text = io.open(path, encoding="utf-8").read()
    try:
        doc = yaml.safe_load(text)
    except Exception as e:
        fail(path, "YAML 解析失败: %s" % e)
        return
    if not isinstance(doc, dict):
        fail(path, "顶层不是映射")
        return

    walk_nulls(doc, path)

    if isinstance(doc.get("env"), dict) and not doc["env"]:
        fail(path, "`env:` 是空映射 —— 直接删掉这个键")
    if "env" in doc and not isinstance(doc["env"], dict):
        fail(path, "`env:` 必须是映射")

    on = doc.get("on") or doc.get(True)  # YAML 会把裸 on 解析成 True
    if not isinstance(on, dict) or not on:
        fail(path, "`on:` 缺失或不是映射")
    elif "workflow_dispatch" not in on:
        fail(path, "`on:` 里没有 workflow_dispatch（手动触发会 422）")

    jobs = doc.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        fail(path, "`jobs:` 缺失或为空")
        return

    for jname, job in jobs.items():
        if not isinstance(job, dict):
            fail(path, "job `%s` 不是映射" % jname)
            continue
        if "runs-on" not in job:
            fail(path, "job `%s` 缺 runs-on" % jname)
        steps = job.get("steps")
        if not isinstance(steps, list) or not steps:
            fail(path, "job `%s` 缺 steps" % jname)
            continue
        ids = {s.get("id") for s in steps if isinstance(s, dict) and s.get("id")}
        for i, s in enumerate(steps, 1):
            if not isinstance(s, dict):
                fail(path, "job `%s` 第 %d 个 step 不是映射" % (jname, i))
                continue
            if not s.get("name"):
                fail(path, "job `%s` 第 %d 个 step 没有 name" % (jname, i))
            if not (s.get("uses") or s.get("run")):
                fail(path, "job `%s` step `%s` 既没有 uses 也没有 run" % (jname, s.get("name")))
            if s.get("uses") and s.get("run"):
                fail(path, "job `%s` step `%s` 同时有 uses 和 run" % (jname, s.get("name")))
            blob = "\n".join(str(s.get(k, "")) for k in ("if", "run", "with", "env"))
            for ref in set(re.findall(r"steps\.([A-Za-z0-9_-]+)\.", blob)):
                if ref not in ids:
                    fail(path, "step `%s` 引用了不存在的 step id: `steps.%s`（本 job 现有 id: %s）"
                         % (s.get("name"), ref, sorted(ids) or "无"))
            run = s.get("run")
            if run:
                with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False, encoding="utf-8") as fh:
                    fh.write(run)
                    tmp = fh.name
                r = subprocess.run(["bash", "-n", tmp], capture_output=True, text=True)
                os.unlink(tmp)
                if r.returncode:
                    fail(path, "step `%s` 的 run 块 bash 语法错误: %s"
                         % (s.get("name"), r.stderr.strip()[:160]))


def main():
    targets = sys.argv[1:] or sorted(
        glob.glob(".github/workflows/*.yml") + glob.glob(".github/workflows/*.yaml"))
    if not targets:
        print("没找到 workflow 文件")
        return 0
    for p in targets:
        check_file(p)
    if BAD:
        print("❌ workflow 预检未通过（GitHub 会拒收 / 运行会 startup_failure）：")
        for p, m in BAD:
            print("   [%s] %s" % (p, m))
        return 1
    print("✅ workflow 预检通过（%d 个文件：空值键/触发条件/步骤完整性/run 语法/step id 引用）" % len(targets))
    return 0


if __name__ == "__main__":
    sys.exit(main())
