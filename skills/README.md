# Wow Agent Skills

本目录提供四个按用户主要交付结果划分的 Wow Agent Skills。每次任务只选择一个 Primary Skill，由它负责从取证到完成验证，不在执行过程中切换到其他 Wow Skill。

这些 Skills 不复制框架 API 文档，而是补充工作流、架构不变量、授权边界和完成证据。具体 API、配置、默认值、模块名和生成契约必须在目标 checkout 或精确目标 tag 中重新确认。

所有 Skill 仅服务于使用或引入 Wow 的下游应用，Wow 框架仓库自身一律不激活。下游任务还必须确认主要交付对象确实是 Wow 行为、代码或迁移：目标应用源码存在 `me.ahoo.wow` import 或 `wow-*` Gradle/Maven dependency/starter，或任务明确要引入、使用、解释或迁移到 Wow。仅在否定、比较或排除语境中提到 Wow 不能触发本包；DDD、CQRS、Event Sourcing、aggregate、saga、projection、command gateway、Spring、Reactor、Kotlin 或 Java 等通用词也不能。

V9 是当前维护基线和默认术语。`wow-develop`、`wow-review` 与 `wow-debug` 仍可服务 V8 下游应用，但必须先从目标构建与解析依赖确认实际 Wow 版本，再应用精确符号、默认值或 V9 规则；无法确认时标记版本结论未验证。V8 到 V9 的旧类型、配置和行为映射只保存在 `wow-migrate`。

## Skills

| Skill | Primary outcome | Boundary |
|---|---|---|
| `wow-develop` | 设计、实现、测试、重构或解释 Wow 行为 | 不用于已有 diff 审查、已有故障诊断或数据切换迁移 |
| `wow-review` | 输出 findings、合并准备度，或完成 review-and-fix | 不用于症状驱动诊断或迁移专项审查 |
| `wow-debug` | 复现、定位已有故障，或完成 diagnose-and-fix | 不用于主动功能开发或普通 diff review |
| `wow-migrate` | 破坏性版本、生成/运行时契约或 Wow-managed 存储/数据迁移 | 不用于无历史/兼容转换的首次采用、无已知破坏且无数据迁移的常规 v8 升级或普通故障 |

## Selection order

按主要交付结果选择，不按涉及的组件名选择：

1. 跨主版本、同主版本 Wow source/config/generated/runtime 破坏性变化，或 Wow-managed 存储/历史数据的转换、对账、切换及不兼容写入回滚是主问题：`wow-migrate`。
2. 存在失败、hang、错误状态或可复现症状，目标是根因：`wow-debug`。
3. 目标是 findings、批准或合并准备度：`wow-review`。
4. 目标是设计、修改、测试或解释 Wow：`wow-develop`。
5. 与 Wow 行为或 API 无直接关系：不激活本包。

`review-and-fix` 始终由 `wow-review` 完成；`diagnose-and-fix` 始终由 `wow-debug` 完成。

## Content model

- `SKILL.md` 只保存入口契约、核心流程、授权边界和 reference 选择规则。
- `references/` 保存稳定决策、源码发现方法和风险边界，按需加载。
- `assets/` 保存可复制到输出中的模板，不作为推理资料默认加载。
- Skill 内的 `scripts/` 只承载重复、确定且容易手写出错的操作；当前仅 `wow-migrate/scripts/audit-v6-usage.sh` 符合这一边界。
- `evals/activation.jsonl` 保存 `prompt`、可选 raw setup `fixture` 与 evaluator-hidden `expectedSkills`；`evals/behavior.jsonl` 保存 `prompt`、目标 `skill`、人工 rubric `expectedBehavior` 和可选 raw setup `fixture`。
- eval 数据不属于安装后工作流，也不由 Skill 加载；维护者可将其交给标准 Agent eval 工具，或在全新任务中执行人工前向评估。

安装后的四个 Skill 仅依赖各自目录中的 `SKILL.md`、`agents/` 和按需资源，不依赖仓库根目录的维护脚本。

## Validation

运行轻量结构校验与边界回归测试：

```bash
python3 -S scripts/validate_wow_skills.py
python3 -S -m unittest scripts.test_validate_wow_skills
```

validator 只使用 Python 标准库，检查：

- `SKILL.md` frontmatter、Skill 名称和目录一致性；
- `agents/openai.yaml` 必需字段及 `$skill-name` 默认提示；
- `plugins.json` include 与四个 Skill 目录的一致性；
- `references/`、`assets/`、`scripts/` 引用存在且不能越出 Skill 目录；
- 运行时 Skill 内容不能引用父目录或本机绝对文件系统路径；
- activation/behavior JSONL 可解析、ID 全局唯一且 Skill 引用有效。

静态校验不会证明自然语言触发一定正确，也不会执行行为用例或证明 API、脚本和生产迁移正确。行为质量应通过全新任务进行前向评估：只把 prompt 与必要 setup 提供给 Agent，隐藏期望项，再由维护者或标准 eval 工具根据真实 diff、命令结果和最终证据评分。不要为执行这些数据重新建设仓库专属 runner。

## Distribution

`plugins.json` 显式列出可分发的四个 Skill。Ahoo Skills Hub 负责同步、生成和验证插件产物；Wow 仓库拥有并维护 Skill 内容。本架构不分发旧名称或兼容别名；发布后，既有安装必须刷新或重新安装插件，再确认四个 Skill 均可发现。
