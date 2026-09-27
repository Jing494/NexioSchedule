#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 patches/series 与源码分支重新生成的补丁一致（对 git 版本/序号差异免疫）。

比较前做归一化：
  1) 去掉 format-patch 尾部的签名行（`-- ` 与随后的 git 版本号）——两边 git 版本常不同；
  2) 去掉主题里的 `[PATCH NN/total]` 计数（补丁数量一变，所有文件都会变）。
再逐文件比对哈希与文件名集合。
用法： python3 check_patches.py <仓库补丁目录> <重新生成的目录>
"""
import hashlib, os, re, sys

def normalize(text: str) -> str:
    # 去掉尾部 "-- \n<git version>\n"
    text = re.sub(r"\n-- \n[^\n]*\n?$", "\n", text)
    # 主题计数归一化
    text = re.sub(r"^Subject: \[PATCH \d+/\d+\]", "Subject: [PATCH]", text, flags=re.M)
    return text

def digest_dir(d):
    out = {}
    for name in sorted(os.listdir(d)):
        if not name.endswith(".patch"):
            continue
        with open(os.path.join(d, name), encoding="utf-8", errors="replace") as f:
            out[name] = hashlib.sha256(normalize(f.read()).encode()).hexdigest()
    return out

a, b = digest_dir(sys.argv[1]), digest_dir(sys.argv[2])
only_a = sorted(set(a) - set(b)); only_b = sorted(set(b) - set(a))
diff = sorted(n for n in set(a) & set(b) if a[n] != b[n])
print(f"  仓库补丁 {len(a)} 个 / 重新生成 {len(b)} 个")
if only_a: print("  仅在仓库里:", only_a)
if only_b: print("  仅在新生成:", only_b)
if diff:   print("  内容不一致:", diff)
if only_a or only_b or diff:
    print("  ❌ 不一致：补丁可能是手改的，或源码分支没同步推上去")
    sys.exit(1)
print("  ✅ 一致（已归一化 git 版本与 PATCH 计数）")
