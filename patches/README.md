> ⚠️ **已退役，仅作留档**：本 fork 自 `1.6.0.2-beta33` 起改用**合并式 fork** ——
> `master` 就是发布分支（= 上游 + 定制提交），CI 直接构建并自动 `git merge upstream/master`。
> 因此 `series/` 与 `tools/check_patches.py` **不再参与构建**（保留是为了留一份"曾经定制了什么"的清单，
> 以及万一要回退旧流水线）。当前机制见根 README 与 `.github/workflows/sync-upstream-build-release.yml`。

# patches/ — 本 fork 的定制补丁序列

`series/` 里是**按顺序**应用的补丁（`git format-patch` 生成），CI 会在干净的上游代码上逐个 `git am`。

- 任一补丁失败 = 上游改了同一处代码 → 需要先在上游最新上 rebase 定制栈、再重新生成本目录（见根 README「上游改了同一处代码怎么办」）。
- 补丁由 `local-audit-v21` 分支生成；生成命令：
  ```bash
  git format-patch <上游基线>..<定制分支> -o patches/series
  ```
- 本目录**不含**内部笔记与个人数据样例（生成时已脱敏）。
