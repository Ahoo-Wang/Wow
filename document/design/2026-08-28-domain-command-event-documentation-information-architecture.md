# 领域模型、命令、事件与协作文档信息架构设计

## 背景

当前 Wow 开发指南将聚合建模、命令、事件存储、快照、Saga、事件处理器和事件补偿统一放在“领域开发”下。这个分组同时使用了开发阶段、领域概念、运行时组件和文档类型四种分类轴，导致以下问题：

- `modeling.md` 同时解释领域模型与命令 API；
- `command-gateway.md` 同时承担调用指南、完成语义、可靠性和运行时原理；
- `saga.md` 与 `event-compensation.md` 重复解释事件补偿；
- EventStore、聚合生命周期、事件总线等机制分散在指南与全局深入原理中；
- 读者需要先理解现有页面历史，才能找到一个技术事实的权威位置。

本设计不保留既有信息架构、URL 或页面兼容层。目标是直接形成没有重复事实、迁移页和历史别名的最终结构。

## 已确认的决策

- 本次只重构三个同级区域：**领域模型**、**命令**、**事件与协作**。
- 查询模块由独立任务处理；本设计不决定查询、投影和数据权限的结构、命名或正文。
- 一级分类按核心能力组织，不再保留宽泛的“领域开发”。
- 每个区域采用“概览 → 使用 → 工作原理”的局部结构。
- 一个技术事实只有一个权威页面，其他页面只提供必要摘要和链接。
- 中文是内容基线；英文目录、页面职责、示例和技术语义逐页对齐。
- 不考虑旧 URL、旧锚点和旧页面兼容性；旧页面在内容归位后直接删除。
- 不新增文档框架、生成器、内容 DSL、重定向机制或校验依赖。

## 范围

### 包含

- `documentation/docs/{zh,en}/guide/` 下领域模型、命令、事件处理、Saga、补偿及相关原理页面；
- 三个区域的概览页、目录结构、侧栏、交叉链接和入口页；
- `core-concepts`、`advanced/architecture`、`advanced/data-flow` 中与三个区域重叠的内容收口；
- WebFlux 与 API Client 扩展页中的命令专属内容归位；
- 补偿配置与补偿示例页面中的内容所有权整理；
- 中英文站点构建、路径覆盖、内部链接和结构对齐验证。

### 不包含

- `query.md`、`projection.md`、`data-access.md` 的结构和正文；
- “读模型与查询”侧栏内部设计；
- 框架源码、公开 API、OpenAPI/Schema、配置默认值或运行时行为变更；
- 新插图、主题视觉重构、搜索和分析系统；
- README、文章、角色指南的全面重写；只更新必要入口和失效链接；
- 发布、部署、PR 或合并行为。

## 核心概念边界

三个区域沿同一条业务主链分工：

```text
领域模型定义状态、事实和不变量
        ↓
命令表达意图，根据当前状态决定并追加事实
        ↓
事件处理器执行副作用，Saga 生成后续命令
        ↓
事件补偿恢复失败的事件处理函数
```

### 领域模型：状态与事实

领域模型拥有：限界上下文、聚合边界、聚合身份、状态、领域事件、不变量、事件溯源、权威历史、快照和聚合生命周期。

领域模型不拥有：命令发送 API、等待阶段、事件消费者、副作用、Saga 或补偿运行时。

### 命令：意图与完成

命令拥有：命令载荷与元数据、处理函数、命令消息、应用内/HTTP/远程调用、完成语义、幂等、错误，以及 CommandGateway 到事件追加的运行时管线。

命令不拥有：EventStore 的完整存储契约、事件消费者实现、Saga 流程或补偿状态机。

### 事件与协作：事实发生后的动作

事件与协作拥有：事件处理器、Saga、业务补偿与事件补偿的区别、事件函数分发、处理失败、持久重试和人工恢复。

事件与协作不拥有：领域事件的建模与持久化、命令入口和查询模型。

## 最终信息架构

站点侧栏用三个同级分组替换现有“领域开发”：

```text
领域模型
命令
事件与协作
读模型与查询（由独立任务维护）
接口与自动化
测试与交付
生产运维
扩展
深入原理
```

查询分组保持独立任务最终确定的结构。本任务只新增前三个分组，并更新它们相关的入口与链接。

### 领域模型

```text
guide/domain/
├─ index.md
├─ aggregate.md
├─ event-sourcing.md
├─ event-evolution.md
├─ snapshot.md
└─ lifecycle.md
```

| 页面 | 唯一职责 | 明确不负责 |
| --- | --- | --- |
| `index` | 领域模型概念图、适用读者、阅读路径与完成标志 | 详细 API、配置表和运行时源码 |
| `aggregate` | 限界上下文、聚合身份、状态、领域事件、不变量与组织模式 | 命令元数据、命令调用与事件消费者 |
| `event-sourcing` | `DomainEventStream`、确定性溯源、EventStore、权威历史与状态恢复 | 命令完成阶段、快照查询和下游副作用 |
| `event-evolution` | revision、upgrader、历史兼容、事件删除/替换和回放验证 | API 版本迁移总览与发布流程 |
| `snapshot` | 派生检查点、策略、单调保存、恢复优化与 `SNAPSHOT` 能力边界 | 查询模块结构和投影设计 |
| `lifecycle` | 状态创建/恢复、溯源状态机、删除恢复、并发顺序和聚合内部失败位置 | 命令入口、Dispatcher/Filter 顺序和存储配置 |

### 命令

```text
guide/command/
├─ index.md
├─ definition.md
├─ sending.md
├─ api-client.md
├─ completion.md
├─ reliability.md
└─ internals/
   ├─ pipeline.md
   ├─ wait-runtime.md
   └─ transport.md
```

| 页面 | 唯一职责 | 明确不负责 |
| --- | --- | --- |
| `index` | 命令生命周期、入口选择、应用/原理双轨和阅读路径 | API 手册和完整处理链 |
| `definition` | 命令载荷、元数据、目标聚合、处理函数、`AfterCommand`、`OnError`、Void | 聚合不变量设计和发送协议 |
| `sending` | `CommandMessage`、`CommandGateway`、聚合 HTTP 路由、全局门面、JSON/SSE | 远程客户端装配和精确阶段状态机 |
| `api-client` | `wow-apiclient.command`、CoApi、服务定位、同步/响应式 API、错误映射和能力上限 | 查询客户端和服务端运行机制 |
| `completion` | `SENT`、`PROCESSED` 与下游分支、函数匹配、链式等待、结果流、超时和取消 | 幂等策略和内部状态机实现 |
| `reliability` | 验证层级、`commandId`/`requestId`、存储约束、版本冲突、错误分类、未知结果和重试 | 后端配置表和补偿运行时 |
| `internals/pipeline` | Gateway → Bus → Dispatcher → Aggregate → EventStore/EventBus；确认与错误边界 | 用户调用教程和总线后端配置 |
| `internals/wait-runtime` | Wait Header、Notifier、WaitSignal、Stage/Chain 状态机、Handle、Coordinator、远程回调 | 用户如何选择阶段 |
| `internals/transport` | InMemory/Kafka/Redis 的 `SENT` 边界、LocalFirst、Void、指标与追踪入口 | 各扩展的安装和完整配置 |

`api-client` 必须明确记录当前实现边界：只调用 `/wow/command/send`；提供同步与响应式最终结果 API；不提供 SSE；`CommandRequest.WaitPlan` 不覆盖函数名和 Saga 链尾字段。不能把它描述为本地 `CommandGateway` 或完整 HTTP 协议的等价镜像。

### 事件与协作

```text
guide/event/
├─ index.md
├─ processor.md
├─ saga.md
├─ compensation.md
└─ dispatch.md
```

| 页面 | 唯一职责 | 明确不负责 |
| --- | --- | --- |
| `index` | 普通处理器、Saga、补偿的选择矩阵与主阅读路径 | 具体处理函数和运行时源码 |
| `processor` | 事件函数、过滤、响应式副作用、幂等、顺序、失败与测试 | Saga 命令生成和持久补偿生命周期 |
| `saga` | 事件生成 0..N 条命令、请求身份、业务补偿和等待集成 | 事件处理失败的持久恢复 |
| `compensation` | 即时重试与持久补偿、失败记录、恢复状态机、调度和人工介入 | Dashboard 使用手册、完整配置和部署清单 |
| `dispatch` | Domain/State EventBus、Dispatcher、函数注册、Filter、通知和补偿插入点 | 应用处理器教程和总线扩展安装 |

`saga` 只保留“业务补偿”和“事件补偿”的边界说明；事件补偿的状态机、重试和人工恢复只在 `compensation` 维护。`processor` 只说明何时进入补偿，不复制补偿配置和流程。

## 内容所有权迁移

| 现有页面/章节 | 最终归属 | 处理方式 |
| --- | --- | --- |
| `guide/modeling.md` | `domain/aggregate`、`command/definition` | 按领域/命令责任拆分后删除原页 |
| `guide/eventstore.md` | `domain/event-sourcing` | 重写并删除原页 |
| `guide/snapshot.md` | `domain/snapshot` | 重写并删除原页 |
| `guide/advanced/event-evolution.md` | `domain/event-evolution` | 移入领域模型并删除原页 |
| `guide/advanced/aggregate-lifecycle.md` | `domain/lifecycle` | 移入领域模型并删除原页 |
| `guide/command-gateway.md` | 命令区除 `definition` 外各页 | 按唯一职责拆分后删除原页 |
| `guide/extensions/apiclient.md` 命令部分 | `command/api-client` | 迁出命令正文；非命令内容由查询/扩展任务决定，本设计不规定其结构 |
| `guide/extensions/webflux.md` 命令部分 | `command/sending`、`command/completion` | 迁出命令细节；扩展页保留启用与通用集成 |
| `guide/event-processor.md` | `event/processor` | 重写并删除原页 |
| `guide/saga.md` | `event/saga` | 删除重复补偿内容后迁移 |
| `guide/event-compensation.md` | `event/compensation`、补偿配置/示例参考 | 指南保留恢复语义；配置、Dashboard、端点和部署归参考页 |
| `guide/advanced/event-bus.md` | `event/dispatch` | 与 Dispatcher/Filter 机制合并后删除原页 |

以下页面保留为跨能力入口，但不再拥有局部详细事实：

- `core-concepts`：术语摘要并链接三个区域；
- `advanced/architecture`：全系统组件关系；
- `advanced/data-flow`：跨区域主链；
- 配置参考：配置键、默认值和完整 YAML；
- 示例参考：可运行示例、Dashboard、OpenAPI 与部署证据。

## 阅读路径与交叉链接

### 建立领域模型

```text
domain/index
  → domain/aggregate
  → command/definition
  → domain/event-sourcing
  → domain/snapshot（需要恢复优化时）
```

### 发送并解释命令结果

```text
command/index
  → command/definition
  → command/sending 或 command/api-client
  → command/completion
  → command/reliability
```

### 建立事件协作

```text
event/index
  → event/processor（执行副作用）
  → event/saga（需要生成后续命令）
  → event/compensation（需要持久失败恢复）
```

### 故障定位

```text
命令失败或结果未知
  → command/reliability
  → command/internals/pipeline
  → command/internals/wait-runtime 或 transport

事件处理失败
  → event/processor
  → event/compensation
  → event/dispatch
```

关键双向链接：

- `domain/aggregate` ↔ `command/definition`；
- `command/internals/pipeline` → `domain/event-sourcing`；
- `command/completion` → `event/processor`、`event/saga`；
- `event/saga` → `command/sending`、`command/reliability`；
- `event/compensation` → `event/processor`、`event/saga`；
- `event/dispatch` → `domain/event-sourcing`。

`domain/lifecycle` 与 `command/internals/pipeline` 的交界以聚合处理为界：前者解释状态聚合怎样创建、恢复和应用事件；后者解释命令怎样经过 Gateway、Bus、Dispatcher 和 Filter 进入聚合，并在追加后发送消息。两页不能各自维护一份完整命令处理时序。

页面结尾只给出一个主要下一步和必要的旁路，不保留无优先级的长“相关主题”列表。

## 技术事实与异常处理原则

- 领域模型页面以当前聚合元数据、StateAggregate、EventStore、SnapshotStore 实现和测试为事实来源。
- 命令页面以 CommandGateway、WaitPlan/WaitState、WebFlux 路由、OpenAPI 快照和 API Client 源码为事实来源。
- 事件页面以 DomainEventDispatcher、StatelessSagaFunction、CompensationFilter 和 ExecutionFailed 状态机为事实来源。
- 文档不能把 `PROCESSED` 简化成“事件已追加”，也不能把 `SNAPSHOT` 无条件描述为“已写入新快照”。
- 文档必须区分 Saga 业务补偿与事件处理失败后的持久补偿。
- Fire-and-forget WaitSignal 通知失败只记录错误，不反向使业务命令失败；远程通知不承诺 exactly-once。
- 无法从源码、测试或运行证据确认的生产承诺必须删除或标记边界，不从旧文档继承。

## 双语规则

- `zh` 与 `en` 逐页拥有相同相对路径。
- 页面标题、章节职责、代码、配置、路由、表格字段和完成标准保持技术语义一致。
- 中文先形成批准后的事实基线，再编写英文自然表达；不通过机械逐句翻译维持一致性。
- 双语任一侧缺页、缺主要章节或技术边界不同，都阻塞验收。

## 查询任务隔离与集成

- 本任务不修改查询页面的信息架构和正文。
- 本任务声明新的 domain/command/event 目标路径；查询任务独立决定自己的内容结构。
- 两个任务集成时，如果查询页面引用被本任务移动的页面，只做机械链接对齐，不借此修改查询内容。
- 两个任务可能同时修改双语 sidebar；最终集成必须保留双方已经批准的分组，不能用任一分支整文件覆盖另一分支。

## 验收标准

### 结构

- 中英文各有 6 个领域模型页、9 个命令页和 5 个事件协作页，共 20 个页面，路径逐一对应。
- 双语 sidebar 使用三个同级分组，不再包含“领域开发”。
- 所有旧页面在内容归位后删除，不存在迁移页、重定向、别名或正文副本。
- `core-concepts`、`advanced/architecture` 和 `advanced/data-flow` 只保留跨能力摘要。
- 查询分组结构和查询页面正文不因本任务改变。

### 内容

- 每页能用一句话说明其唯一职责和明确不负责的内容。
- 领域模型、命令、事件协作之间不存在重复维护的完整阶段表、状态机、配置表或调用示例。
- 命令阶段、快照策略、事件追加、Saga、补偿和 API Client 能力边界与当前源码一致。
- 配置值只在参考页完整列出；示例与部署细节只在示例/运维参考维护。
- 每个主要任务都有明确入口、成功标志和下一步。

### 验证

至少执行：

```bash
pnpm --dir documentation docs:build
git diff --check
```

并完成以下无新增依赖的检查：

- 比较 `zh/guide/{domain,command,event}` 与 `en/guide/{domain,command,event}` 的相对路径集合；
- 扫描 `documentation/docs/{zh,en}`，确认没有指向已删除页面的内部链接；
- 扫描双语 sidebar，确认 20 个新页面各出现一次且查询分组内容未被重写；
- 检查旧页面文件均不存在；
- 检查 Markdown 中不存在 `TBD`、`TODO`、占位配置或无证据的当前能力承诺；
- 确认工作树不包含 `node_modules`、构建输出、`.superpowers/` 或其他本地状态。

## 实施分批

本设计作为一个统一信息架构实施，但按可审查批次执行：

1. **领域模型与命令定义**：建立 `domain/`，拆分 `modeling.md`，迁移事件溯源、事件演进、快照和生命周期。
2. **命令调用与运行时**：建立其余 `command/` 页面，拆分命令网关、WebFlux 和 API Client 命令内容。
3. **事件与协作**：建立 `event/`，重写处理器、Saga、补偿和事件分发管线。
4. **导航与全站收口**：更新双语 sidebar、入口、跨能力摘要和所有内部链接，执行整站验证。

每个批次结束时页面可以暂时与旧页面共存以保持构建可验证；最终批次必须删除全部旧页面。临时共存不是最终兼容设计。

## 明确不做

- 不为旧 URL 添加重定向或迁移页；
- 不创建“一页包含所有命令/领域/事件 API”的大而全手册；
- 不把查询内容吸收到快照或事件页面；
- 不复制配置参考、OpenAPI、Dokka 或示例 README；
- 不增加文档生成、翻译同步或链接检查依赖；
- 不在文档重构中修改框架实现；
- 不在设计提交中实施正式文档改动。

## 后续边界

本设计说明批准后，下一步只调用 writing-plans 技能编写实施计划。实施计划必须保留本设计的三个同级能力区域、20 页双语结构、查询任务隔离和无兼容层约束；任何改变这些边界的需求都需要重新确认设计。
