---
title: 文档治理
description: 让 Wow 文档保持权威、可发现、可复现，并且只有一个明确归属位置。
outline: deep
---

# 文档治理

Wow 按读者决定每类文档的位置，每类只放一处。本页给出原则、由原则得出的位置，以及每类文档的生命周期。

## 原则

1. **一份文档只有一类读者、一项职责。** 位置由读者决定：用户读文档站，模块维护者读模块旁边的文档，Agent 读 `AGENTS.md`。
2. **一个事实只有一个归属。** 每个当前事实只有一个权威页面；其他文档用一句话概括并链接过去。
3. **现状原地维护，历史交给 Git。** 直接更新归属页面，不新增带日期的后继文件。计划、评审记录和被取代的草稿不保留：Pull Request 与 Git 历史就是归档。
4. **结论要有别人能重跑的证据。** 测量结果只有附带 commit、命令、环境与原始结果时才保留。
5. **生成的合同不是文档正文。** ABI 转储、API 报告、公开面清单和生成的客户端是受检查的产物，只能重新生成，不能手改。

## 规范目录

| 内容 | 读者 | 规范位置 |
| --- | --- | --- |
| 项目入口 | 评估者 | `README.md`、`README.zh-CN.md` |
| 仓库政策 | 贡献者 | `CONTRIBUTING.md`、`SECURITY.md`、`CODE_OF_CONDUCT.md` |
| Agent 指令 | 编码 Agent | 根目录 `AGENTS.md`，以及需要独立规则的子树中的 `AGENTS.md`（`typescript/` 与各 TypeScript 包）；`CLAUDE.md` 只指向 `AGENTS.md` |
| 产品、架构、迁移、运维与参考文档 | 用户 | `documentation/docs/{en,zh}/`，唯一长期文档站；公开页面保持双语结构对称 |
| 文档静态资产 | 用户 | `documentation/docs/public/` |
| Mermaid 无法表达的图源 | 维护者 | `documentation/diagrams/`（随第一个文件创建） |
| 跨 JVM 模块或全仓的设计记录 | 维护者 | `documentation/designs/` |
| 单个 TypeScript 包或应用的设计 | 该包的维护者 | `<package>/docs/design/` |
| 已决定待完成事项的台账 | 维护者 | `docs/compat-debt.md`（保留到 v10 的兼容）、`docs/todo.md`（JVM）、`typescript/wow-view-engine/docs/design/todo.md`（视图引擎）、`typescript/MIGRATION.md`（从 fetcher 迁入的包） |
| 发布手册 | 发布维护者 | `AGENTS.md`（版本、兼容性、CI）、`typescript/RELEASING.md`、`typescript/RELEASE_NOTES_TEMPLATE.md` |
| 可复现性能证据 | 维护者 | `wow-benchmarks/results/` |
| Agent 能力 | 编码 Agent | `skills/`；第三方 Skill 副本放在 `.claude/skills/` |
| 已发布模块用法 | 模块使用者 | `<module>/README.md` 与 `README.zh-CN.md` |
| 生成的合同 | 评审者 | `<module>/api/*.api`、`typescript/*/test/api/*.api.md`、`typescript/*/test/surface/*.txt` |

不要在 `document/` 或 `docs/superpowers/` 下跟踪文件。`docs/superpowers/` 继续作为 ignored 的本地 Agent 工作区，禁止 force-add 其中的 Spec、Plan、报告或 review package。

## 文档生命周期

### 长期文档

长期文档定义当前合同、架构边界、受支持迁移、运维过程或精确参考。在改变行为的同一个 Pull Request 中直接更新它的规范页面。公开文档变更必须保持中英文结构和技术语义对齐。

### 就近文档

模块 README、包内设计文档与 Skill 指令不是第二套文档站。它们只解释本地制品或可执行 Agent 能力，更广泛的概念应链接 VitePress。不要在这些文件中复制完整架构、迁移或配置指南。

### 设计记录

设计记录解释代码为什么是现在的形状：问题、约束、兼容边界、决定以及被否决的方案。跨 JVM 模块的记录放在 `documentation/designs/`，命名为 `YYYY-MM-DD-<topic>-design.md`；单个 TypeScript 包或应用的设计留在它自己的 `docs/design/`。

- 开头写明日期、源码基线和状态：提议、已确认、实施中、已实施或已取代。
- 实现合并时更新状态，并把每个用户可见的合同移到对应的文档站页面。文档站可以链接设计记录说明缘由，但合同本身不依赖设计记录。
- 新记录取代旧记录时，在旧记录开头标明已取代并链接后继；没有入站链接、且后继已涵盖其缘由时删除旧记录。
- 分步实施计划与评审记录属于工作材料（见下文）。只有当测试或源码注释把某份计划当作它所编码的规格引用时，计划才暂时保留。

### 台账

台账列出已决定但未完成的事项，每条写明原因、完成判据和落点。做完就删除该条，不打勾、不归档。评审中尚未处理的发现应写进对应台账，而不是留在评审记录里。

### 可复现实验证据

实验材料只有同时记录以下信息才保留：

1. 被测 commit 或不可变源码版本；
2. 精确命令与参数；
3. 相关运行时、依赖和环境事实；
4. 足以校验结论的原始或派生结果；
5. 打包资产的校验和或其他完整性机制。

保留的性能证据放在 `wow-benchmarks/results/`，与能重跑它的基准放在一起。重复资产、不可验证实验，以及当前文档或 Benchmark 报告已不再使用的证据直接删除。

### 临时工作材料

确认前的 Spec、实施计划、评审记录、临时 QA 对比和已完成的 rewrite plan 都是工作材料。工作期间放在 ignored 的本地工作区或 Pull Request 中；长期结论合并进规范页面或设计记录后删除这些材料。

## 何时判定为过期

满足任一条件即可判定文档过期：

- 规范页面已经拥有相同结论；
- 文档描述的 API、版本、类型、路由、配置或架构已经删除；
- 文档只描述一个已完成变更的实施过程，不再定义当前合同或缘由；
- 实验证据无法复现或校验；
- 新事实来源已经拥有同一主题，旧文件没有额外的受支持差异。

不要仅按日期删除。删除前必须检查入站链接，并与当前源码、测试和规范文档比较。若文件仍包含唯一且有效的知识，先迁移知识，再在同一 Pull Request 删除旧文件。

## 目标迁移策略

文档站收敛到通往当前主版本的直接路径，以及仍有效的运维迁移。Wow V9 的目标形态是完整 V8→V9 路径，加上当前运行时与传统架构迁移；更早的迁移链路随后归属对应 Release Tag。

当前站点仍发布 V6→V8，已有 V8→V9 文档也只覆盖 Query，而不是完整迁移路径。完整替代页面发布前必须保持这些链接有效；只有在同一 Pull Request 发布并接入完整替代路径时，才能删除 V6→V8 导航与内容。

## 图表与资产

- Mermaid 支持的图全部使用 fenced Mermaid。
- 不在 Mermaid 源旁提交生成 SVG。
- PlantUML 只保留 Mermaid 不支持的用例图等类型，并集中到 `documentation/diagrams/`。
- 只有文档运行时无法渲染规范图源时才保留渲染资产。
- 删除无引用截图与重复资产；logo、徽章和当前产品截图放在 `documentation/docs/public/`。

## 清理进度

旧的 `document/` 目录与受跟踪的 `docs/superpowers/` 文件已经删除；其中唯一且有效的知识此前已迁到文档站页面、设计记录或 `wow-benchmarks/results/`。

剩余清理沿用同一流程，每批一个可独立评审的 Pull Request：

1. 盘点受跟踪文档、资产、链接和归属；
2. 把每项标记为保留、迁移、归位或删除；
3. 删除旧来源前先迁移唯一且有效的知识；
4. 把可复现实验证据归位到所属模块；
5. 删除过期链接、导航、重复资产和旧迁移页面；
6. 在 Pull Request 正文列出迁移与删除路径，不再提交另一份清理报告；
7. 每批完成构建和 review 后再开始下一批。

已知候选：`typescript/docs/` 与 `typescript/storybook/docs/` 下受跟踪的评审记录（先把其中未处理的发现移进视图引擎或 JVM 台账，再删除），以及 `documentation/designs/` 中已实施的分步计划（引用它们的测试改为指向设计记录后删除）。

## 计划中的自动检查

后续独立 Pull Request 会增加文档布局检查。启用后，遇到以下情况失败：

- `document/` 或 `docs/superpowers/` 出现受跟踪文件；
- Markdown 出现在[规范目录](#规范目录)与 `.github/` 模板之外；
- Mermaid 支持的图新增 PlantUML 或生成的图表 SVG；
- 使用锁定依赖无法构建 VitePress 站点。

检查复用仓库脚本和现有工具链，不增加另一套文档框架或归档目录。该检查合并前由 reviewer 人工执行治理规则；本文不声称当前 CI 已经能够拒绝布局违规。

## Review 清单

- 当前主题是否只有一个权威页面，并且与行为变更在同一个 Pull Request 中更新？
- 中英文公开页面是否结构对齐？
- 模块 README、包内设计与 Skill 是否通过链接复用，而不是复制广泛指南？
- 新增或修改的设计记录是否写明状态，用户可见的合同是否都已进入文档站？
- 每个保留实验是否可复现并归属 `wow-benchmarks`？
- 被删除路径是否已无入站链接？
- Pull Request 是否避免提交 Plan、评审记录、生成构建输出和本地 Agent 状态？
- VitePress 构建和 `git diff --check` 是否通过？
