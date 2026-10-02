#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""凭据外泄扫描 —— 本地推之前跑，CI 里再挡一道。

为什么有它：本仓库的 CI 要拿 keystore（base64）+ 两个口令 + alias，以及一个 Gitee token。
这些值一旦写进源码、workflow、commit message、Issue 或日志里，就等于把签名权和发布权交出去了。
所以每次改动都得能**证明**没漏，而不是"我记得没写"。

扫描范围（默认 --tree --tracked --messages，全开最严）：
  --tree      工作区所有文本文件（含未跟踪；跳过 .git/build/二进制）
  --tracked   git 跟踪的文件（= 会被推上去的那些）
  --messages  所有 ref 可达的 commit message
  --objects   所有 ref 可达对象的**内容**（最严、最慢；发布前本地跑一次）
  --value V   额外按**具体值**比对（例如 32 位 hex 的 Gitee token —— 它没法用正则可靠识别）

判定：
  · 硬命中（必须修）：GitHub PAT、`access_token=<值>` / `oauth2:<值>@` 形式的 token、
    各家云厂商 key、私钥文件头；
  · 结构命中：仓库里跟踪了 keystore / .env / keystore.properties 这类本不该入库的文件；
  · 输出永远**只给类型和位置**，命中片段一律打码（不会把凭据本体打进终端或日志）。

用法：
  python3 tools/check_secrets.py
  python3 tools/check_secrets.py --objects
  python3 tools/check_secrets.py --value "$GITEE_TOKEN"
退出码：命中 → 1
"""
import argparse
import io
import os
import re
import subprocess
import sys

HARD = [
    ("GitHub PAT", re.compile(r"ghp_[A-Za-z0-9]{20,}")),
    ("GitHub 细粒度令牌", re.compile(r"github_pat_[A-Za-z0-9_]{20,}")),
    ("GitHub OAuth/App 令牌", re.compile(r"gh[osu]_[A-Za-z0-9]{20,}")),
    ("URL 里带 access_token", re.compile(r"[?&]access_token=[A-Za-z0-9_\-\.]{16,}")),
    ("oauth2 内嵌凭据", re.compile(r"oauth2:[A-Za-z0-9_\-\.]{16,}@")),
    ("x-access-token 内嵌凭据", re.compile(r"x-access-token:[A-Za-z0-9_\-\.]{16,}@")),
    ("云厂商 key（AWS）", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("云厂商 key（Google）", re.compile(r"AIza[0-9A-Za-z_\-]{35}")),
    ("云厂商 key（Slack）", re.compile(r"xox[baprs]-[A-Za-z0-9\-]{10,}")),
    ("云厂商 key（OpenAI 风格）", re.compile(r"sk-[A-Za-z0-9]{20,}")),
    ("私钥文件头", re.compile(r"-----BEGIN (RSA |OPENSSH |EC |DSA |PGP )?PRIVATE KEY-----")),
    ("keystore 内嵌 base64（形如 KEYSTORE_BASE64: <长串>）",
     re.compile(r"(?i)keystore_base64\s*[:=]\s*[\"']?[A-Za-z0-9+/]{200,}={0,2}")),
]
# 不该被跟踪的文件（值不在仓库里，但文件本身也不能进版本库）
# 只列"无论内容如何都不该入库"的：私钥容器与凭据文件。
# ★ 特意**不**含 *.pem / *.key：公开证书（例：上游手表 rpk 的 sign/*.pem）是合法入库的，
#   真正的私钥由下面的内容头规则（-----BEGIN … PRIVATE KEY-----）抓 —— 否则会误拦发布。
FORBIDDEN_TRACKED = re.compile(
    r"(^|/)(\.env(\..+)?|keystore\.properties|.*\.(jks|keystore|p12|pfx)|id_rsa|id_ed25519|\.credentials)$")
SKIP_DIRS = {".git", "build", ".gradle", ".idea", "node_modules", ".kotlin"}
SKIP_EXT = {".apk", ".idsig", ".aar", ".jar", ".zip", ".png", ".jpg", ".jpeg", ".webp",
            ".ttf", ".otf", ".so", ".dex", ".bin", ".pb", ".zst"}
MAX_BYTES = 3 * 1024 * 1024

hits = []


def mask(s):
    """打码：只留前 3 位与长度，绝不回显凭据本体"""
    s = s.strip()
    return (s[:3] + "…") if len(s) > 3 else "…"


def scan_text(where, text, extra_values):
    for name, rx in HARD:
        for m in rx.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            hits.append("%s:%d 命中【%s】（%s）" % (where, line, name, mask(m.group(0))))
    for v in extra_values:
        if v and len(v) >= 12 and v in text:
            hits.append("%s 命中【显式给定的凭据值】（%s）" % (where, mask(v)))


def scan_tree(extra):
    for base, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            p = os.path.join(base, f)
            if os.path.splitext(f)[1].lower() in SKIP_EXT:
                continue
            try:
                if os.path.getsize(p) > MAX_BYTES:
                    continue
                with io.open(p, encoding="utf-8", errors="replace") as fh:
                    scan_text(p, fh.read(), extra)
            except OSError:
                continue


def scan_tracked(extra):
    out = subprocess.run(["git", "ls-files", "-z"], capture_output=True).stdout.decode(errors="replace")
    for rel in [x for x in out.split("\0") if x]:
        if FORBIDDEN_TRACKED.search(rel):
            hits.append("仓库跟踪了不该入库的文件：%s" % rel)
        try:
            with io.open(rel, encoding="utf-8", errors="replace") as fh:
                scan_text(rel, fh.read(), extra)
        except OSError:
            continue


def scan_messages(extra):
    out = subprocess.run(["git", "log", "--all", "--format=%H%n%s%n%b"], capture_output=True).stdout
    scan_text("commit-message", out.decode(errors="replace"), extra)


def scan_objects(extra):
    objs = subprocess.run(["git", "rev-list", "--objects", "--all"], capture_output=True).stdout.decode()
    names = sorted({l.split(" ", 1)[0] for l in objs.splitlines() if l.strip()})
    proc = subprocess.run(["git", "cat-file", "--batch"], input="\n".join(names).encode(),
                          capture_output=True)
    scan_text("git-object", proc.stdout.decode(errors="replace"), extra)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tree", action="store_true")
    ap.add_argument("--tracked", action="store_true")
    ap.add_argument("--messages", action="store_true")
    ap.add_argument("--objects", action="store_true")
    ap.add_argument("--value", action="append", default=[])
    a = ap.parse_args()
    if not (a.tree or a.tracked or a.messages or a.objects):
        a.tree = a.tracked = a.messages = True
    ran = []
    if a.tree:
        scan_tree(a.value); ran.append("工作区")
    if a.tracked:
        scan_tracked(a.value); ran.append("跟踪文件")
    if a.messages:
        scan_messages(a.value); ran.append("提交信息")
    if a.objects:
        scan_objects(a.value); ran.append("全历史对象")
    print("凭据外泄扫描（%s；显式比对值 %d 个）" % ("、".join(ran), len(a.value)))
    if hits:
        print("❌ 发现 %d 处可疑：" % len(hits))
        for h in hits:
            print("   ", h)
        print("   处理：从文件/提交里删掉，并**立刻吊销该凭据**（历史里的值改不掉）。")
        return 1
    print("✅ 未发现凭据外泄（模式 %d 类；跟踪文件白名单校验已跑）" % len(HARD))
    return 0


if __name__ == "__main__":
    sys.exit(main())
