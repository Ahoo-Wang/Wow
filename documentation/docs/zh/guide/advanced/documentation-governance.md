---
title: 文档治理
description: 让 Wow 文档保持权威、可发现、可复现，并且只有一个明确归属位置。
outline: deep
---

# 文档治理

Wow 只在最小且明确的权威位置维护当前文档。实施计划不是长期文档，Git 历史就是归档；实验材料只有在其他贡献者能够复现或校验时才保留。

`document/` 与 `docs/superpowers/` 中仍受跟踪的旧文件都是待迁移项，不是事实来源。清理 PR 会先把唯一且仍有效的知识或可复现实验证据迁到规范归属，再删除旧文件。

## 规范目录

| 内容 | 规范位置 | 规则 |
| --- | --- | --- |
| 项目入口 | `README.md`、`README.zh-CN.md` | 只保留最短且有用的项目说明，并链接文档站 |
| 仓库治理 | 根目录 `CONTRIBUTING.md`、`SECURITY.md`、`CODE_OF_CONDUCT.md`、`AGENTS.md`、`CLAUDE.md` | 只保留仓库级政策与 Agent 指令 |
| 产品、架构、迁移、运维与参考文档 | `documentation/docs/{en,zh}/` | 唯一长期文档主站；公开页面保持双语结构对称 |
| 文档静态资产 | `documentation/docs/public/` | 只保留当前文档引用的资产 |
| Mermaid 无法表达的图源 | `documentation/diagrams/` | PlantUML 只用于用例图等不支持的图形 |
| 可复现性能证据 | `wow-benchmarks/results/` | 证据归属能够解释它的 Benchmark 模块 |
| Agent 能力 | `skills/` | Skill 指令、引用和资产作为可执行能力就近保留 |
| 已发布模块用法 | `<module>/README*` | 模块 README 与它说明的制品放在一起 |

长期文档不得放在 `document/` 或 `docs/superpowers/`。`docs/superpowers/` 可以继续作为 ignored 的本地 Agent 工作区，但禁止 force-add 其中的 Spec、Plan、报告或 review package。

## 文档生命周期

### 长期文档

长期文档定义当前合同、架构边界、受支持迁移、运维过程或精确参考。直接更新规范页面，不要创建带日期的后继文件。公开文档变更必须保持中英文结构和技术语义对齐。

### 就近文档

模块 README 与 Skill 指令不是第二套文档站。它们只解释本地制品或可执行 Agent 能力，更广泛的概念应链接 VitePress。不要在这些文件中复制完整架构、迁移或配置指南。

### 可复现实验证据

实验材料只有同时记录以下信息才保留：

1. 被测 commit 或不可变源码版本；
2. 精确命令与参数；
3. 相关运行时、依赖和环境事实；
4. 足以校验结论的原始或派生结果；
5. 打包资产的校验和或其他完整性机制。

保留的性能证据迁到 `wow-benchmarks/results/`。重复资产、不可验证实验，以及当前文档或 Benchmark 报告已不再使用的证据直接删除。

### 临时工作材料

Spec、实施计划、review 记录、临时 QA 对比和已完成 rewrite plan 都是工作材料。工作期间放在 ignored 的本地工作区；长期结论合并进规范页面后删除这些材料，Git 历史与 Pull Request 保留实施记录。

## 何时判定为过期

满足任一条件即可判定文档过期：

- 规范 VitePress 页面已经拥有相同结论；
- 文档描述的 API、版本、类型、路由、配置或架构已经删除；
- 文档只描述一个已完成变更的实施过程，不再定义当前合同；
- 实验证据无法复现或校验；
- 新事实来源已经拥有同一主题，旧文件没有额外的受支持差异。

不要仅按日期删除。删除前必须检查入站链接，并与当前源码、测试和规范文档比较。若文件仍包含唯一且有效的知识，先迁移知识，再在同一 PR 删除旧文件。

## 目标迁移策略

文档清理的目标是让当前站点只保留通往当前主版本的直接路径，以及仍有效的运维迁移。Wow V9 的目标形态是完整 V8→V9 路径，加上当前运行时与传统架构迁移；更早的迁移链路随后归属对应 Release Tag。

当前站点仍发布 V6→V8，已有 V8→V9 文档也只覆盖 Query，而不是完整迁移路径。完整替代页面发布前必须保持这些链接有效；只有在同一 PR 发布并接入完整替代路径时，才能删除 V6→V8 导航与内容。

## 图表与资产

- Mermaid 支持的图全部使用 fenced Mermaid。
- 不在 Mermaid 源旁提交生成 SVG。
- PlantUML 只保留 Mermaid 不支持的用例图等类型，并集中到 `documentation/diagrams/`。
- 只有文档运行时无法渲染规范图源时才保留渲染资产。
- 删除无引用截图与重复资产；logo、徽章和当前产品截图放在 `documentation/docs/public/`。

## 清理流程

文档清理拆成可独立评审的 PR：

1. 盘点受跟踪文档、资产、链接和归属；
2. 把每项标记为保留、迁移、归位或删除；
3. 删除旧来源前先迁移唯一且有效的知识；
4. 把可复现实验证据归位到所属模块；
5. 删除过期链接、导航、重复资产和旧迁移页面；
6. 在 PR 正文列出迁移与删除路径，不再提交另一份清理报告；
7. 每批完成构建和 review 后再开始下一批。

最终状态会删除整个 `document/`，并清空所有受跟踪的 `docs/superpowers/` 文件。

## 计划中的自动检查

后续独立 PR 会为相关 Pull Request 增加文档布局检查。启用后，遇到以下情况失败：

- `document/` 或 `docs/superpowers/` 出现受跟踪文件；
- Markdown 出现在未批准位置，而不是根治理文件、`documentation/docs/`、`skills/`、`wow-benchmarks/` 或模块 README；
- Mermaid 支持的图新增 PlantUML 或生成的图表 SVG；
- 使用锁定依赖无法构建 VitePress 站点。

检查复用仓库脚本和现有工具链，不增加另一套文档框架或归档目录。

该 PR 合并前由 reviewer 人工执行治理规则；本文不声称当前 CI 已经能够拒绝布局违规。

## Review 清单

- 当前主题是否只有一个权威页面？
- 中英文公开页面是否结构对齐？
- 模块 README 与 Skill 是否通过链接复用，而不是复制广泛指南？
- 每个保留实验是否可复现并归属 `wow-benchmarks`？
- 被删除路径是否已无入站链接？
- PR 是否避免提交 Plan、生成构建输出和本地 Agent 状态？
- 文档布局检查、VitePress 构建和 `git diff --check` 是否通过？
