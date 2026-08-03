# Wow Agent Skills

本目录提供四个以用户主要交付结果划分的 Wow Agent Skills。每次任务只选择一个 Primary Skill，由它负责从取证到完成验证，不在执行过程中切换到其他 Wow Skill。

这些 Skills 的目标不是复制框架文档或维护 API 百科，而是提供当前源码无法直接表达的工作流、架构不变量、安全边界和完成证据。所有具体 API、配置、默认值、模块名和生成契约都必须在目标 checkout 或精确目标 tag 中重新确认。

## Skills

| Skill | Primary outcome | Boundary |
|---|---|---|
| `wow-develop` | 设计、实现、测试、重构或解释 Wow 行为 | 不用于已有 diff 的审查、已有故障的诊断或 v6→v8 迁移 |
| `wow-review` | 输出 findings、质量判断、合并准备度，或完成 review-and-fix | 不用于症状驱动的根因诊断或迁移专项审查 |
| `wow-debug` | 复现、定位并解释已有故障，或完成 diagnose-and-fix | 不用于主动功能开发或普通 diff review |
| `wow-migrate` | 将已有 Wow v6 应用迁移到固定的 Wow v8 版本 | 不用于首次采用 Wow、从 v8 开始的常规升级或普通 v8 故障 |

## Selection order

按主要交付结果选择，不按涉及的组件名选择：

1. 现有 v6→v8 兼容、数据或切换是主问题：`wow-migrate`。
2. 存在失败、hang、错误状态或可复现症状，目标是根因：`wow-debug`。
3. 目标是 findings、批准或合并准备度：`wow-review`。
4. 目标是设计、修改、测试或解释 Wow：`wow-develop`。
5. 与 Wow 行为或 API 无直接关系：不激活本包。

`review-and-fix` 始终由 `wow-review` 完成；`diagnose-and-fix` 始终由 `wow-debug` 完成；不要在任务中转入另一个 Wow Skill。

## Content model

- `SKILL.md` 只保存入口契约、执行顺序、授权边界和 reference 选择规则。
- `references/` 保存稳定决策规则、源码发现方法和风险边界，不保存易漂移的 API 清单。
- `assets/` 保存可复制到输出中的模板，不作为推理资料默认加载。
- `scripts/` 只保存重复、确定且容易手写出错的操作；当前仅迁移静态审计适合脚本化。
- 每个 Skill 的 `evals/` 保存黑盒 activation/behavior 用例；它们不由运行时 Skill 主动加载。
- 仓库根目录的 `scripts/validate_wow_skills.py` 是维护工具，不进入插件运行时资源。
- 不维护通用 Markdown、HTML、自然语言或代码风格 parser。

## Validation

运行完整包校验：

```bash
python3 scripts/validate_wow_skills.py
```

校验器执行以下确定性检查：

- 使用 `skill-creator` 的 `quick_validate.py` 校验每个 Skill；
- 校验 `agents/openai.yaml`、`plugins.json`、本地 Markdown 链接和 shell 脚本；
- 校验 activation/behavior eval 的 schema、Skill 覆盖和冲突覆盖。

结构校验不能证明自动激活或输出正确。路由验证必须在全新任务中只发送 `evals/activation.jsonl` 的原始 prompt，并从客户端 activation trace 记录实际 Primary Skill。行为验证使用 `evals/behavior.jsonl` 的原始任务和 fixture，检查读取顺序、授权边界、实际命令与产物；不得显式指定 Skill 或泄露预期答案。

每个 behavior case 都有唯一 `fixtureId`。`isolated-git-worktree` fixture 要求 runner 从 `EVAL_SUBJECT` 解析并记录精确 commit，在隔离且初始 clean 的 Git worktree 中执行，并应用 `setup` 指定的可审计 patch；需要 diff 的 case 还必须解析并记录 `EVAL_BASE`。`copied-directory` fixture 必须复制仓库内目录并记录全部内容的 SHA-256。`workspace.clean` 比较执行前后的完整 `git status --porcelain=v1 --untracked-files=all`，而不是假定当前工作目录干净。`sandbox.noExternalRead` 与 `sandbox.noExternalMutation` 必须由 runner 在网络、connector 和工具调用层阻断并记录，不能用 shell 命令正则替代。缺少解析后的 revision、setup/content checksum、基线、sandbox trace 或前后快照时，该 case 不得记为通过。

## Distribution

`plugins.json` 显式列出可分发的四个 Skill。Ahoo Skills Hub 负责同步、生成和验证插件产物；Wow 仓库拥有并维护 Skill 内容。本次架构是有意的破坏性重写，不分发旧名称或兼容别名；发布后，既有安装必须刷新或重新安装插件，再确认四个 Skill 均可发现。
