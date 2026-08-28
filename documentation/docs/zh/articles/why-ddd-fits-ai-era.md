---
title: "AI 越强，代码越便宜：真正值钱的是 DDD 背后的业务模型"
description: "一篇有证据边界的观点文章：AI 改变实现方式时，为什么统一语言、聚合不变量和可执行领域规格仍然重要。"
outline: deep
---

# AI 越强，代码越便宜：真正值钱的是 DDD 背后的业务模型

![AI 生成的代码流经过领域边界，形成可理解的业务模型](/images/articles/ddd-ai-era/cover.webp)

标题是一项观点命题，不是对所有团队生产率的量化预测：当生成实现变得更容易，稀缺问题会更集中在**该解决什么问题、业务规则是什么、结果如何验证**。

本文的结论是：AI 不会替团队发现正确领域模型；清晰的领域语言、边界、不变量和可执行场景，反而让人和工具更容易围绕同一个业务合同工作。

## 外部证据支持什么，不支持什么

外部研究并没有给出“AI 必然提效”或“AI 必然降效”的统一答案：

- [DORA 2025 一手报告页](https://dora.dev/research/2025/dora-report/)把 AI 描述为组织既有优势与弱点的“放大器”，并把收益归因于底层组织系统，而不是工具本身。它支持“工作系统与反馈能力很重要”，不直接证明 DDD 会带来某个量化结果。
- [METR 的 2025 随机对照研究原始发布页](https://metr.org/blog/2025-07-10-early-2025-ai-experienced-os-dev-study/)记录：16 名有项目经验的开源开发者完成 246 个任务，在该研究的 2025 年初工具与成熟仓库场景中，允许使用 AI 的完成时间增加 19%。发布方明确反对把结果泛化到多数开发者、其他领域或未来工具，页面也标注了 2026 年后续数据。因此这里只把它当作“上下文理解与验证成本不可忽略”的历史窄样本。
- [OpenAI 的 Harness Engineering 实践](https://openai.com/index/harness-engineering/)把仓库内、可版本化、可发现的知识作为 agent 工作的事实来源，并用机械检查约束边界。这是一项具体工程实践，不是所有团队的效果研究。

因此，本文不声称 AI 已让编码成本下降某个比例，也不声称采用 DDD 会提高某个比例的生产率、质量或业务结果。

## 观点：生成代码不等于理解业务

“增加修改订单状态接口”很容易被实现为 `updateOrderStatus(id, status)`。但真正决定正确性的，是接口名称没有回答的问题：

- 谁可以发起这个动作？
- 当前状态允许这个迁移吗？
- 接受后必须产生什么事实或后续工作？
- 拒绝时什么状态必须保持不变？

AI 可以参与生成、搜索、重构和验证；这些问题仍需要领域知识和明确责任。更快生成错误模型，只会更快扩大返工范围。

## DDD 提供的是可讨论的业务结构

[Eric Evans 的 DDD Reference](https://www.domainlanguage.com/ddd/reference/)是一手模式摘要，覆盖统一语言、限界上下文、聚合等定义。本文关注其中五种对人机协作都可见的结构：

| DDD 结构 | 它显式化什么 | 仍不能自动保证什么 |
| --- | --- | --- |
| 统一语言 | 同一个业务词在需求、代码与测试中的含义 | 术语本身一定正确 |
| 限界上下文 | 词义、模型所有权与依赖范围 | 上下文划分永久不变 |
| 聚合与不变量 | 哪些状态变化必须经过同一决策边界 | 外部副作用自动原子化 |
| 命令与事件 | 调用意图与已接受事实 | 所有需求都需要 CQRS/事件溯源 |
| 领域测试 | 给定历史时允许、拒绝和产生的结果 | 真实 Adapter、性能与生产恢复 |

从 AI 协作视角看，这些结构让上下文更容易定位、让不可接受的行为更容易变成机械检查。它们的价值仍取决于模型是否来自真实业务理解。

## 当前 Wow 仓库的证据

Wow 的订单示例提供了一个可检查的模型，而不是一个抽象成功故事：

```text
CreateOrder  → OrderCreated
PayOrder     → OrderPaid
ShipOrder    → OrderShipped
ReceiptOrder → OrderReceived
```

- [`Order.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain/order/Order.kt) 把改址、支付、发货和收货实现为不同命令处理行为；
- [`OrderState.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain/order/OrderState.kt) 只通过事件溯源改变状态；
- [`OrderSpec.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/order/OrderSpec.kt) 覆盖正常流程与未支付发货、库存不足、价格不一致等拒绝路径；
- [Kotlin 订单与购物车](../reference/example/order.md)记录了当前命令、事件、状态和测试合同。

这组证据能证明当前示例的行为以及 `./gradlew :example-domain:check` 覆盖的测试范围。它不能证明某条拒绝路径已经在生产中减少了多少损失，也不能证明 AI 修改这段代码时一定更快或更正确。

## 把 AI 需求变成可验证改动

假设需求是“允许已支付但未发货的订单修改地址”。稳妥流程不是让 AI 直接修改字段，而是：

1. 确认这是产品真正接受的新业务规则。
2. 找到改址、支付和发货所在的聚合边界。
3. 先增加允许路径与仍应拒绝路径的领域场景。
4. 修改最小不变量，使测试表达新合同。
5. 运行领域测试，再验证 HTTP、持久化与迁移影响。

DDD 没有替 AI 做第 1 步的业务决策。它让后续改动落在明确的决策与验证边界内。Wow 中对应的建模和测试合同分别由[聚合与不变量](../guide/domain/aggregate.md)与[领域测试套件](../guide/test-suite.md)维护。

## DDD 不是 AI 时代的默认答案

![根据业务复杂度选择清晰 CRUD 或领域建模](/images/articles/ddd-ai-era/ddd-boundary.webp)

规则很少、生命周期短、当前状态足够的内部工具，清晰 CRUD 可能是更小的正确方案。错误的统一语言、边界或测试也会被 AI 重复；把错误模型写得更结构化，不会让它变正确。

只有当业务决策、高变化规则、历史事实或跨边界协作值得保护时，DDD 的投入才有明确对象。若还需要事件溯源、读写分离与 Saga，则还要承担事件演进、最终一致性、幂等和运维成本，详见 Wow 的[适用边界](../guide/introduction.md#适用边界)。

## 团队可以从五件小事开始

1. 为一个核心场景写清用户或业务结果，而不是先写接口。
2. 找出需求、代码和测试中的同义词与歧义词。
3. 写下一个必须始终成立的不变量及其所有者。
4. 用命令、事件和成功/拒绝场景表达这条规则。
5. 把词典、决策和可执行测试版本化，让人和 AI 读取同一来源。

## 结语

“AI 越强，DDD 越重要”应被理解为一项架构观点：实现能力增长时，业务语义、边界与验证不会自动出现。它不是对 AI 生产率的普遍预测，也不是 DDD 的收益保证。

真正值得保留的资产不是代码数量，而是团队能够解释、执行、测试和演进的业务模型。AI 可以参与这个过程；业务责任仍然属于人。

## 一手来源

- [Eric Evans：DDD Reference](https://www.domainlanguage.com/ddd/reference/)——DDD 模式与定义。
- [DORA：State of AI-assisted Software Development 2025](https://dora.dev/research/2025/dora-report/)——“AI 是放大器”的组织系统结论。
- [METR：Early-2025 AI and Experienced Open-Source Developer Productivity](https://metr.org/blog/2025-07-10-early-2025-ai-experienced-os-dev-study/)——窄样本随机对照结果及泛化限制。
- [OpenAI：Harness engineering](https://openai.com/index/harness-engineering/)——仓库知识与机械边界的具体工程实践。
- [Wow：核心概念](../guide/core-concepts.md)——当前 Wow 术语与合同入口。
