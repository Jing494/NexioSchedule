# 本复刻维护清单

这份清单只服务于本 fork：**合并上游时，下面这些内容必须保留**。
它们大多不在上游仓库里，或只存在于 README 的 fork 区块中；一旦被合并覆盖，CI 会直接红（见 tools/ci_check.py 的「本复刻文档」门禁）。

## 必须保留的文件 / 区块

| 内容 | 说明 |
|---|---|
| README.md 的 fork 区块 | 以 <!-- fork-only:start --> 与 <!-- fork-only:end --> 包住；含下载入口、签名/安装说明、与上游差异、鸣谢入口 |
| CREDITS.md | 上游作者、流水线思路、PR 贡献者、本复刻维护者 |
| LOCAL-CHANGES.md | 本地定制清单与合并指南；历史章节可标「已停用」，不要整段删 |
| THIRD-PARTY-NOTICES.md | 第三方组件许可证与修改声明 |
| docs/FORK-MAINTENANCE.md | 本文件 |
| .github/workflows/build-release.yml | 本 fork 的发布流水线（上游没有） |
| tools/ci_check.py | 本 fork 的质量门禁（上游没有） |
| patches/README.md | 旧补丁机制的留档说明 |

## 合并上游时

1. 先 fetch/merge 上游，遇到 README 冲突时保留 fork 区块与上游正文两边，不要整份取上游。
2. 合并后跑 python3 tools/ci_check.py .；「本复刻文档」相关门禁必须全绿。
3. 看 git diff --stat：CREDITS.md、LOCAL-CHANGES.md、THIRD-PARTY-NOTICES.md、docs/FORK-MAINTENANCE.md 不应出现删除。
4. 若上游改了 README 的「特别致谢」或「开源协议」，保留其正文，并确认 fork 区块仍完整。

## MD 自查范围（2026-10-24 扩大）

- **当前状态类**：README（fork 区块、功能/构建/安装/许可）、CREDITS、THIRD-PARTY-NOTICES、docs/FORK-MAINTENANCE、patches/README。
- **历史类**：LOCAL-CHANGES 的升基准/定制记录、docs/superpowers/plans 下的上游计划、iosApp/Tests 下的上游说明。历史内容可以保留旧事实，但涉及已移除功能时要标注「已停用」。
- **禁止**：当前状态类文档里出现已移除功能（如手表互联/wear 变体）的现时表述；历史章节可保留，但需注明移除日期。
- 每次改版本号、门禁条数、仓库地址、CI 文件路径时，同步更新上述 MD。
