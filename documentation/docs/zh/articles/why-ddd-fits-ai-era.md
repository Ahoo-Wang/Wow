---
title: "AI 越强，代码越便宜：真正值钱的是 DDD 背后的业务模型"
description: "AI 降低的是代码生成成本，领域驱动设计则把收入、体验、效率与风险背后的业务规则，转化为 AI 可理解、可验证的工程结构。"
outline: deep
---

# AI 越强，代码越便宜：真正值钱的是 DDD 背后的业务模型

![AI 生成的代码流经过领域边界，最终形成可理解的业务模型](/images/articles/ddd-ai-era/cover.webp)

_AI 可以加速代码生成，领域模型负责让代码兑现业务价值。_

> 企业不会为代码行数付费，只会为更高的收入、更好的客户体验、更低的运营成本和更可控的风险付费。AI 降低的是代码生成成本，DDD 保护的是代码最终要兑现的业务价值。

AI 可以在几分钟内生成接口、数据表、测试和部署脚本。于是问题来了：**代码越来越容易生成，还有必要学习领域驱动设计（DDD）吗？**

**AI 越会写代码，DDD 越重要。**

AI 擅长解决“怎么写”，软件最难的却是“为什么写、什么才有价值”。生成越快，模糊需求、错误边界和隐含规则也扩散得越快。没有清晰领域模型的 AI，更像一支很少质疑错误需求的高速外包团队。

## 真正贬值的是编码，真正升值的是业务结果

![AI 让编码成本下降，但业务语义、边界和正确性仍然稀缺](/images/articles/ddd-ai-era/cost-structure.webp)

_代码产量不再是瓶颈，业务结果开始决定交付价值。_

让 AI 生成一个标准 CRUD 模块，往往比开发者手写更快。但“能运行”与“产生价值”之间，隔着一整个领域。

取消订单要不要释放库存？优惠券是否退回？已经出库的订单能否取消？部分退款如何分摊满减金额？同一个客户，在销售、风控和财务系统中是不是同一种模型？

答案藏在业务经验、历史事故和默认共识中。规则错一次，损失的不是构建时间，而可能是库存、资金、履约成本和客户信任。

[METR 2025 随机对照研究](https://metr.org/Early_2025_AI_Experienced_OS_Devs_Study-paper.pdf)中，16 名熟悉各自开源项目的开发者完成了 246 个真实任务；使用当时的 AI 工具，完成时间反而增加 19%。它仅适用于 2025 年初工具、该样本与成熟仓库，不能泛化为“AI 没用”，却说明：**复杂系统的成本还包括理解上下文、判断正确性和校验。**

[DORA 2025](https://dora.dev/research/2025/dora-report/)把 AI 描述为“放大器”：模型清晰、边界稳定、测试可靠的团队能更快验证业务假设；概念混乱、规则隐形的团队，也会更快制造返工、事故和技术债。

所以，AI 时代真正升值的不是 Prompt 技巧，而是**能把战略意图转化为收入、体验、效率和风险控制的领域知识**。这正是 DDD 的主场。

## CRUD 给 AI 数据，DDD 给 AI 语义

![DDD 将 AI 需要的业务知识转化为工程结构](/images/articles/ddd-ai-era/ddd-context-engineering.webp)

_CRUD 关注数据能否保存，DDD 关注业务价值能否成立。_

数据驱动设计常从一张 `orders` 表、一个 `status` 字段和更新接口开始。它足以让 AI 生成代码，却不足以判断什么变化合法。

面对“增加修改订单状态接口”，AI 很容易生成 `updateOrderStatus(id, status)`，却绕过关键问题：谁能修改？哪些状态允许迁移？之后必须触发什么？失败时什么不能改变？

这类代码的危险不在于不能运行，而在于它**非常顺利地运行了一个错误的业务模型**：该拦截的损失没有拦截，该履行的客户承诺也没有履行。

DDD 从“业务发生了什么”开始，建立统一语言、限界上下文、聚合与不变量，并用命令和领域事件表达意图。[Eric Evans 的 DDD Reference](https://www.domainlanguage.com/ddd/reference/)系统总结了这些模式。

从 AI 协作的视角看，DDD 其实是一套天然的“上下文工程”；从经营视角看，它是在保护软件投资的回报。

| DDD 能力 | AI 协作价值 | 业务价值 |
|---|---|---|
| 统一语言 | 减少语义猜测 | 降低需求误解与返工 |
| 限界上下文 | 缩小有效上下文 | 提升团队自治与响应速度 |
| 聚合与不变量 | 设置不可绕过的护栏 | 保护资金、库存、履约与合规 |
| 命令与领域事件 | 显式表达意图与事实 | 支撑审计、运营与决策 |
| 领域测试 | 建立机械化反馈 | 缩短交付周期，降低发布风险 |

## 统一语言：给 AI 一本业务词典

![统一语言将分散术语收敛为稳定的业务词典](/images/articles/ddd-ai-era/ubiquitous-language.webp)

_同一种业务含义只有一种表达，沟通成本和返工才会下降。_

AI 只能根据上下文推断企业词义。如果产品写“会员”，数据库叫 `customer`，接口用 `user`，代码又出现 `account`，AI 很容易把四个概念错误合并。

统一语言让业务专家、产品、开发、测试和代码使用同一套术语。当“订单支付”在代码中对应 `PayOrder` 和 `OrderPaid`，AI 就不必猜测 `setStatus(PAID)` 是否等价于支付，团队也能更快判断一个功能是否真的改善支付成功率、对账效率或客户体验。

**统一语言减少的不只是沟通损耗，更是从错误理解到错误产品的昂贵返工。**

## 限界上下文：把业务边界变成 AI 的上下文边界

![销售、风控和财务上下文拥有各自的客户模型](/images/articles/ddd-ai-era/bounded-context.webp)

_边界越清楚，团队越能独立响应业务变化。_

“客户”在销售上下文中代表购买偏好，在风控中代表风险，在财务中代表应收。一个全公司共用的 `Customer` 大对象，既制造耦合，也让 AI 无法判断修改会影响哪些规则。

限界上下文明确术语的有效范围、模型的所有者和协作契约。AI 可以先定位业务域，再读取该域的模型、规则与测试，而不必把整个仓库一次性塞进上下文。对企业而言，这意味着一次营销变化不必拖着财务和风控一起发布，责任也不再消失在跨系统耦合里。

[OpenAI 的 agent-first 工程实践](https://openai.com/index/harness-engineering/)强调让仓库知识可发现，并用可预测结构与机械化边界约束 Agent。限界上下文正是这种 Agent 可读架构的业务骨架。

## 聚合与不变量：告诉 AI 什么绝不能做错

![所有状态变化必须经过聚合不变量检查](/images/articles/ddd-ai-era/aggregate-invariant.webp)

_不变量守住的不是代码洁癖，而是资金、库存、履约和合规底线。_

真正危险的不是语法错误，而是“技术上正确、业务上错误”。聚合定义一致性边界，不变量定义必须始终成立的规则：未支付订单不能发货；余额不能小于冻结金额；优惠券不能重复核销。规则应由领域模型集中守护。

当不变量被写进聚合，AI 可以重构接口、生成适配器，却不能绕过领域入口随意修改状态。架构不再依靠“请小心一点”的提示词维持，而由代码边界和测试共同执行。每一次拒绝非法状态迁移，都可能是在避免一次超卖、错付、违规或客户投诉。

**最好的 AI Guardrail，不是一段更长的 Prompt，而是一个无法轻易绕过的领域不变量。**

## 命令与事件：把模糊需求改写成意图和事实

![命令、领域规则与事件构成可追溯的业务因果链](/images/articles/ddd-ai-era/command-event.webp)

_命令表达业务意图，事件沉淀可审计、可分析的业务事实。_

`updateStatus` 描述数据库操作，`ShipOrder` 描述业务意图；`status = SHIPPED` 描述字段结果，`OrderShipped` 描述已经发生的事实。

命令让 AI 知道“用户试图做什么”，事件让 AI 知道“系统认可发生了什么”。当问题出现时，人和 AI 都可以沿着因果链追踪，而不是从字段差异中反推业务含义。这些业务事实还能服务审计、漏斗分析、运营自动化和产品决策，让代码运行记录转化为经营反馈。

命令和领域事件不等于必须采用 CQRS 或 Event Sourcing；是否引入读模型、事件存储和异步架构，应由真实复杂度决定。

## 领域测试：为 AI 建立可执行的反馈回路

![领域知识、AI 实现与领域测试构成可执行反馈闭环](/images/articles/ddd-ai-era/domain-feedback-loop.webp)

_可执行的业务规则，让交付速度与发布信心同时提高。_

需求文档告诉 AI“大概怎么做”，Given–When–Then 领域测试则定义：给定历史事件，执行命令后应该产生什么事件、拒绝什么操作、保持什么状态。它既是回归测试，也是活的规格说明。

AI 修改代码后能立即获得反馈；业务规则变化时，团队先更新场景，也就同时更新了 AI 的目标。它缩短“提出假设—实现—验证—上线”的周期，也把回归风险拦在影响收入和客户之前。拥有可执行示例的领域模型，才是一份可以交给 AI 持续工作的契约。

## 一个真实例子：让订单模型直接“告诉”AI规则

![Wow 订单领域的命令、事件、状态与拒绝路径](/images/articles/ddd-ai-era/wow-order-flow.webp)

_清晰状态链不仅保护代码正确，也保护订单收入与客户承诺。_

Wow 的订单示例把业务动作表达为一条清晰链路：

```text
CreateOrder  → OrderCreated
PayOrder     → OrderPaid
ShipOrder    → OrderShipped
ReceiptOrder → OrderReceived
```

在 [`Order` 聚合](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain/order/Order.kt#L148-L167)中，修改地址、发货和收货不是对 `status` 的任意赋值，而是独立的命令处理行为。发货前检查订单是否已经支付，收货前检查是否已经发货。[`CreateOrder` 与 `OrderCreated`](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-api/src/main/kotlin/me/ahoo/wow/example/api/order/CreateOrder.kt#L27-L66)也分别表达下单意图与结果。

对应的 [`OrderSpec`](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/order/OrderSpec.kt#L44-L171)从创建订单开始，继续验证支付、发货、收货和重复支付等分支；它还明确验证“未支付直接发货”必须失败且状态保持不变。这条规则直接避免无收入履约、后续追回与对账成本，以及错误发货对客户体验的伤害。Wow 文档中的[核心概念](https://github.com/Ahoo-Wang/Wow/blob/main/documentation/docs/zh/guide/core-concepts.md#%E8%81%9A%E5%90%88%E6%A0%B9)说明了聚合、命令、事件与状态之间的关系。

假设现在给 AI 一个需求：“允许已支付但尚未发货的订单修改收货地址。”在普通 CRUD 系统中，AI 可能找到地址字段并直接更新数据库。在这个模型中，它会发现：修改地址当前只允许 `CREATED` 状态，发货要求 `PAID`，现有测试已经定义主要状态路径。于是它必须围绕业务规则工作：确认产品意图、调整聚合约束、补充允许和拒绝场景，再运行领域测试。

DDD 没有替 AI 决定业务，却让“允许修改地址”背后的履约体验、仓配成本和订单风险变得可讨论、可实现、可验证。

## DDD 不是 AI 时代的银弹

![根据业务复杂度选择清晰 CRUD 或领域驱动设计](/images/articles/ddd-ai-era/ddd-boundary.webp)

_DDD 的投入应服务核心差异化与高风险业务，而不是服务架构表演。_

DDD 更适合 AI 时代，不代表每个项目都应该堆满聚合、工厂、仓储、Saga 和事件总线。内部通讯录、一次性活动后台或规则极少的管理页面，清晰的 CRUD 可能就是投资回报更高的答案。

错误的领域模型同样会被 AI 放大。如果统一语言本身含糊、边界划分错误、测试固化了错误规则，AI 只会更高效地复制问题。领域专家仍然不可替代，关键决策仍然需要人承担责任。

正确顺序是：先识别决定差异化、收入或重大风险的核心域，再对高变化、高价值的部分投入建模；支撑域和通用域保持简单。DDD 的目标不是让架构显得高级，而是把有限工程预算投入最值得保护的业务能力。

## 团队今天就能开始的六件事

![团队实践 DDD 与 AI 协作的六个动作](/images/articles/ddd-ai-era/six-actions.webp)

_从一个业务目标出发，把价值、规则、代码和验证连成闭环。_

1. **先写业务结果。** 明确要改善的是转化率、履约时效、库存准确率、损失率，还是客户满意度。
2. **整理统一语言。** 找出需求、代码、接口和测试中的歧义词，让业务、产品、工程和 AI 使用同一含义。
3. **划分限界上下文。** 从一个核心场景开始，明确模型所有者、协作契约和不可越过的依赖边界。
4. **把价值与风险写成不变量。** 例如“未支付订单不能发货”，让关键资金、库存和合规规则拥有唯一守护者。
5. **用业务动作和测试表达场景。** 以 `PayOrder`、`CancelOrder` 取代万能更新接口，用 Given–When–Then 覆盖成功与拒绝路径。
6. **让知识和指标进入仓库。** 将词典、上下文、决策、测试和业务指标一起版本化，让人和 AI 共享事实与反馈。

这六件事的共同目标，是把“只有老员工知道”的隐性知识，转化为人和 AI 都能读取、执行、验证，并能持续创造业务结果的组织资产。

## 结语：代码会越来越便宜，业务价值不会

![大量廉价代码与稀缺业务正确性的价值对比](/images/articles/ddd-ai-era/code-cheap-correctness.webp)

_AI 提高实现吞吐，领域模型决定这些实现能否形成经营结果。_

AI 会继续进步，样板代码会越来越便宜，重构也会越来越快。但企业仍然需要回答：什么是订单，什么是承诺，什么可以改变，什么绝不能被破坏，不同业务之间应该如何协作。

过去，DDD 帮助大型团队对抗复杂度；AI 时代，它又多了一项使命：**把收入逻辑、客户承诺、运营规则和风险底线，翻译成机器可以参与、但不能随意篡改的工程语言。**

当代码不再稀缺，领域模型会成为企业可复用、可验证、可持续演进的数字资产。能够定义正确问题、建立清晰模型、守住业务边界的团队，才能把 AI 的技术杠杆转化为业务杠杆。

所以，AI 时代不是不再需要 DDD。

恰恰相反：**AI 越会写代码，我们越需要知道什么结果值得追求、什么代码值得写。**

## 参考资料

![文章使用的方法论、工程实践、行业研究、受控研究和实现证据](/images/articles/ddd-ai-era/evidence-stack.webp)

_事实用于支撑观点，边界用于防止夸大。_

- [Eric Evans：DDD Reference](https://www.domainlanguage.com/ddd/reference/)
- [OpenAI：Harness engineering—leveraging Codex in an agent-first world](https://openai.com/index/harness-engineering/)
- [DORA：State of AI-assisted Software Development 2025](https://dora.dev/research/2025/dora-report/)
- [METR：Measuring the Impact of Early-2025 AI on Experienced Open-Source Developer Productivity](https://metr.org/Early_2025_AI_Experienced_OS_Devs_Study-paper.pdf)
- [Wow：核心概念](https://wow.ahoo.me/zh/guide/core-concepts)
