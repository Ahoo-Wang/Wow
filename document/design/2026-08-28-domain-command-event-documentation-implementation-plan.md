# 领域模型、命令、事件与协作文档重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Wow 开发指南重构为“领域模型、命令、事件与协作”三个同级能力区域，交付中英文各 20 个职责唯一的页面，并删除旧信息架构。

**Architecture:** 以核心能力作为一级分类，每个区域按“概览 → 使用 → 工作原理”组织。实施时先创建可独立构建的新页面，最后一次性切换侧栏、更新全站链接并删除旧页面，避免中间提交出现失效链接。

**Tech Stack:** VitePress、Markdown、TypeScript 侧栏配置、pnpm、现有 Gradle/Kotlin 源码与测试事实。

**Spec:** [`document/design/2026-08-28-domain-command-event-documentation-information-architecture.md`](./2026-08-28-domain-command-event-documentation-information-architecture.md)

## Global Constraints

- 中文是内容基线；英文页面必须保持相同相对路径、章节职责、代码和技术语义。
- 最终必须交付每种语言 6 个 `domain` 页面、9 个 `command` 页面、5 个 `event` 页面。
- 不保留旧 URL、重定向、迁移页、别名或正文副本。
- 不修改查询、投影和数据权限页面的信息架构或正文；需要时只机械更新指向已移动页面的链接。
- 不覆盖查询任务对双语 sidebar 的已批准修改；只替换现有“领域开发”块并保留其他分组。
- 配置键和完整 YAML 只在配置参考维护；Dashboard、OpenAPI 与部署细节只在示例/运维参考维护。
- 不增加依赖、生成器、内容 DSL、翻译同步或链接检查工具。
- 不修改框架源码、公开 API、OpenAPI/Schema、CI/CD、发布或部署行为。
- 每个技术结论必须重新核对当前源码、测试或生成契约，不从旧文档直接继承未经验证的承诺。
- 只暂存当前任务列出的文件；不得提交 `.superpowers/`、`node_modules`、构建输出或其他本地状态。

---

### Task 1: 拆分聚合模型与命令定义

**Files:**
- Create: `documentation/docs/zh/guide/domain/aggregate.md`
- Create: `documentation/docs/en/guide/domain/aggregate.md`
- Create: `documentation/docs/zh/guide/command/definition.md`
- Create: `documentation/docs/en/guide/command/definition.md`
- Read: `documentation/docs/{zh,en}/guide/modeling.md`
- Read: `documentation/docs/{zh,en}/guide/core-concepts.md`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/modeling/annotation/AggregateMetadataParser.kt`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/command/annotation/CommandMetadataParser.kt`

**Interfaces:**
- Consumes: `AggregateMetadata`、`CommandMetadata`、`@AggregateRoot`、`@OnCommand`、`@OnSourcing` 的当前源码契约。
- Produces: `/guide/domain/aggregate` 作为聚合、不变量和状态/事件建模权威页；`/guide/command/definition` 作为命令载荷、元数据和处理函数权威页。

- [ ] **Step 1: 安装文档依赖并验证新页面尚不存在**

```bash
CI=true pnpm --dir documentation install --frozen-lockfile
test -f documentation/docs/zh/guide/domain/aggregate.md
```

Expected: `pnpm` 成功；`test` 以退出码 `1` 失败。

- [ ] **Step 2: 编写中文聚合页**

使用以下固定章节；示例复用 `Cart`、`CartState` 和 `Order`，不创建新模型：

```markdown
# 聚合与不变量
## 从业务边界开始
## 限界上下文与聚合身份
## 状态、领域事件与不变量
## 推荐的聚合组织方式
## 确定性状态演进
## 生命周期不变量
## 进入命令定义
```

只迁入 `modeling.md` 中的领域边界、决策表、命令/状态组合模式、溯源约定和生命周期不变量。命令注解、处理函数签名、Hook 和路由移到命令定义页。

- [ ] **Step 3: 编写中文命令定义页**

```markdown
# 定义命令
## 命令载荷与命令消息
## 目标聚合与命令元数据
## 命令处理函数
## 创建、允许创建与 Void 命令
## AfterCommand 与 OnError
## 输入验证与业务不变量边界
## 下一步：发送命令
```

明确：命令处理函数读取当前状态、检查不变量并返回事件；数据库写入和事件发布不属于处理函数。`VoidCommand` 必须按当前 Dispatcher 行为说明，不得仅写成“没有返回值”。

- [ ] **Step 4: 编写英文镜像并核对结构**

英文使用对应标题：`Aggregate and Invariants`、`Define Commands`。运行：

```bash
diff \
  <(rg '^## ' documentation/docs/zh/guide/domain/aggregate.md | sed 's/^## //') \
  <(rg '^## ' documentation/docs/en/guide/domain/aggregate.md | sed 's/^## //' ) || true
test "$(rg -c '^## ' documentation/docs/zh/guide/domain/aggregate.md)" = "$(rg -c '^## ' documentation/docs/en/guide/domain/aggregate.md)"
test "$(rg -c '^## ' documentation/docs/zh/guide/command/definition.md)" = "$(rg -c '^## ' documentation/docs/en/guide/command/definition.md)"
```

Expected: 两组章节数量相同；第一条 `diff` 只允许语言文本不同。

- [ ] **Step 5: 构建并提交**

```bash
pnpm --dir documentation docs:build
git diff --check
git add -- documentation/docs/{zh,en}/guide/domain/aggregate.md documentation/docs/{zh,en}/guide/command/definition.md
git commit -m "docs: split aggregate and command definition"
```

Expected: VitePress 构建成功，提交只包含 4 个新页面。

---

### Task 2: 建立事件溯源权威页

**Files:**
- Create: `documentation/docs/zh/guide/domain/event-sourcing.md`
- Create: `documentation/docs/en/guide/domain/event-sourcing.md`
- Read: `documentation/docs/{zh,en}/guide/eventstore.md`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStore.kt`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventSourcingStateAggregateRepository.kt`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/modeling/state/SimpleStateAggregate.kt`

**Interfaces:**
- Consumes: EventStore 追加/加载、事件流版本、状态恢复和确定性溯源契约。
- Produces: `/guide/domain/event-sourcing`，供命令管线和事件分发页引用权威历史边界。

- [ ] **Step 1: 运行路径契约并确认失败**

```bash
test -f documentation/docs/zh/guide/domain/event-sourcing.md
```

Expected: exit `1`。

- [ ] **Step 2: 编写中文页面**

```markdown
# 事件溯源
## 权威历史模型
## DomainEvent 与 DomainEventStream
## EventStore 契约
## 确定性状态溯源
## 聚合状态恢复
## 追加、版本与请求身份
## 存储实现边界
## 恢复与验证
```

删除旧页中命令阶段表和完整命令 filter chain；只说明事件何时成为权威历史，并链接 `command/internals/pipeline`。配置示例改为链接配置参考。

- [ ] **Step 3: 编写英文镜像**

英文标题使用 `Event Sourcing`，保留同样 8 个二级章节、接口签名和边界表。

- [ ] **Step 4: 验证双语契约与构建**

```bash
test "$(rg -c '^## ' documentation/docs/zh/guide/domain/event-sourcing.md)" = 8
test "$(rg -c '^## ' documentation/docs/en/guide/domain/event-sourcing.md)" = 8
rg -q 'DomainEventStream' documentation/docs/{zh,en}/guide/domain/event-sourcing.md
rg -q 'EventStore' documentation/docs/{zh,en}/guide/domain/event-sourcing.md
pnpm --dir documentation docs:build
git diff --check
```

Expected: 所有检查通过。

- [ ] **Step 5: 提交**

```bash
git add -- documentation/docs/{zh,en}/guide/domain/event-sourcing.md
git commit -m "docs: add event sourcing guide"
```

---

### Task 3: 建立快照领域页

**Files:**
- Create: `documentation/docs/zh/guide/domain/snapshot.md`
- Create: `documentation/docs/en/guide/domain/snapshot.md`
- Read: `documentation/docs/{zh,en}/guide/snapshot.md`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/snapshot/SnapshotStrategy.kt`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/snapshot/SnapshotStore.kt`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/snapshot/VersionOffsetSnapshotStrategy.kt`

**Interfaces:**
- Consumes: SnapshotStore 单调保存、all/version-offset/no-op 策略和最新状态恢复契约。
- Produces: `/guide/domain/snapshot`，作为快照策略和 `SNAPSHOT` 能力边界权威页；不改变查询模块。

- [ ] **Step 1: 运行路径契约并确认失败**

```bash
test -f documentation/docs/zh/guide/domain/snapshot.md
```

Expected: exit `1`。

- [ ] **Step 2: 编写中文页面**

```markdown
# 快照
## 快照不是权威历史
## 最新状态加载流程
## 快照策略
## SnapshotStore 与单调保存
## SNAPSHOT 阶段边界
## 恢复优化与成本
## 何时不需要快照
## 验证与下一步
```

保留快照作为默认当前状态读模型的边界说明，但不定义查询页面结构；只链接查询任务最终入口。

- [ ] **Step 3: 编写英文镜像**

英文标题使用 `Snapshots`，保持 8 个二级章节和相同策略表。

- [ ] **Step 4: 验证语义与构建**

```bash
rg -q 'version_offset' documentation/docs/{zh,en}/guide/domain/snapshot.md
rg -q 'all' documentation/docs/{zh,en}/guide/domain/snapshot.md
rg -q 'NoOp|no-op' documentation/docs/zh/guide/domain/snapshot.md
rg -q 'NoOp|no-op' documentation/docs/en/guide/domain/snapshot.md
test "$(rg -c '^## ' documentation/docs/zh/guide/domain/snapshot.md)" = "$(rg -c '^## ' documentation/docs/en/guide/domain/snapshot.md)"
pnpm --dir documentation docs:build
git diff --check
```

- [ ] **Step 5: 提交**

```bash
git add -- documentation/docs/{zh,en}/guide/domain/snapshot.md
git commit -m "docs: add domain snapshot guide"
```

---

### Task 4: 完成领域模型区域

**Files:**
- Create: `documentation/docs/zh/guide/domain/index.md`
- Create: `documentation/docs/en/guide/domain/index.md`
- Create: `documentation/docs/zh/guide/domain/event-evolution.md`
- Create: `documentation/docs/en/guide/domain/event-evolution.md`
- Create: `documentation/docs/zh/guide/domain/lifecycle.md`
- Create: `documentation/docs/en/guide/domain/lifecycle.md`
- Read: `documentation/docs/{zh,en}/guide/advanced/event-evolution.md`
- Read: `documentation/docs/{zh,en}/guide/advanced/aggregate-lifecycle.md`

**Interfaces:**
- Consumes: Tasks 1–3 的领域页面和当前 EventUpgrader/StateAggregate 生命周期事实。
- Produces: 完整 `domain/` 六页集合及其唯一入口 `/guide/domain/`。

- [ ] **Step 1: 验证领域页面集合尚未完成**

```bash
test "$(cd documentation/docs/zh/guide && rg --files domain | wc -l | tr -d ' ')" = 6
```

Expected: exit `1`。

- [ ] **Step 2: 编写事件演进双语页**

固定章节：

```markdown
# 事件演进
## 为什么持久事件需要长期兼容
## Revision 与 Upgrader
## 升级链顺序
## 字段演进
## 删除、替换与 DroppedEvent
## 历史回放验证
## 发布与回滚边界
```

不得把应用/API 版本迁移指南复制进来。

- [ ] **Step 3: 编写聚合生命周期双语页**

固定章节：

```markdown
# 聚合生命周期
## 创建或恢复状态
## 状态溯源生命周期
## 删除、恢复、Owner 与 Space
## 版本、并发与顺序
## 聚合内部失败位置
## 与命令处理管线的边界
## 源码与验证入口
```

完整 Gateway/Dispatcher/Filter 时序只链接 `command/internals/pipeline`。

- [ ] **Step 4: 编写领域模型概览双语页**

概览必须提供三条路径：首次建模、历史演进、恢复性能；每条路径给出完成标志和一个主要下一步。

- [ ] **Step 5: 验证领域目录并提交**

```bash
test "$(cd documentation/docs/zh/guide && rg --files domain | wc -l | tr -d ' ')" = 6
test "$(cd documentation/docs/en/guide && rg --files domain | wc -l | tr -d ' ')" = 6
diff \
  <(cd documentation/docs/zh/guide && rg --files domain | sort) \
  <(cd documentation/docs/en/guide && rg --files domain | sort)
pnpm --dir documentation docs:build
git diff --check
git add -- documentation/docs/{zh,en}/guide/domain
git commit -m "docs: complete domain model section"
```

Expected: 路径集合相同，构建成功。

---

### Task 5: 建立命令入口与调用页面

**Files:**
- Create: `documentation/docs/zh/guide/command/index.md`
- Create: `documentation/docs/en/guide/command/index.md`
- Create: `documentation/docs/zh/guide/command/sending.md`
- Create: `documentation/docs/en/guide/command/sending.md`
- Create: `documentation/docs/zh/guide/command/api-client.md`
- Create: `documentation/docs/en/guide/command/api-client.md`
- Read: `documentation/docs/{zh,en}/guide/command-gateway.md`
- Read: `documentation/docs/{zh,en}/guide/extensions/apiclient.md`
- Read: `documentation/docs/{zh,en}/guide/extensions/webflux.md`
- Read: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/command/`

**Interfaces:**
- Consumes: Task 1 的 `command/definition`，CommandGateway、WebFlux 命令路由、OpenAPI 全局门面和 `wow-apiclient.command` 当前源码。
- Produces: 命令区域入口、应用内/HTTP 调用权威页和远程客户端权威页。

- [ ] **Step 1: 验证命令入口尚不存在**

```bash
test -f documentation/docs/zh/guide/command/index.md
```

Expected: exit `1`。

- [ ] **Step 2: 编写发送命令双语页**

```markdown
# 发送命令
## 选择调用入口
## 构造 CommandMessage
## 应用内 CommandGateway
## 聚合 HTTP 路由
## 全局命令门面
## JSON 与 SSE 响应
## CommandResult 基础字段
## 下一步：选择完成语义
```

聚合专用路由必须以生成 OpenAPI 为事实来源，不推断 context 前缀。

- [ ] **Step 3: 编写 API Client 双语页**

```markdown
# API Client
## 能力边界
## 安装与 CoApi 注册
## CommandRequest
## 目标服务解析
## 响应式调用
## 同步调用
## 错误映射
## 当前不支持的协议能力
```

“当前不支持”必须列出 SSE、函数名匹配和 Saga 链尾字段；不得承诺与本地 Gateway 等价。

- [ ] **Step 4: 编写命令概览双语页**

概览提供四条入口：定义并发送、服务间调用、选择完成阶段、故障定位；同时列出“应用使用”和“工作原理”两条阅读轨。

- [ ] **Step 5: 验证并提交**

```bash
rg -q '/wow/command/send' documentation/docs/{zh,en}/guide/command/api-client.md
rg -q 'SSE' documentation/docs/{zh,en}/guide/command/{sending,api-client}.md
test "$(rg -c '^## ' documentation/docs/zh/guide/command/api-client.md)" = "$(rg -c '^## ' documentation/docs/en/guide/command/api-client.md)"
pnpm --dir documentation docs:build
git diff --check
git add -- documentation/docs/{zh,en}/guide/command/{index,sending,api-client}.md
git commit -m "docs: add command invocation guides"
```

---

### Task 6: 建立命令完成与可靠性页面

**Files:**
- Create: `documentation/docs/zh/guide/command/completion.md`
- Create: `documentation/docs/en/guide/command/completion.md`
- Create: `documentation/docs/zh/guide/command/reliability.md`
- Create: `documentation/docs/en/guide/command/reliability.md`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/command/wait/`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/command/RequestIdChecker.kt`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStore.kt`

**Interfaces:**
- Consumes: CommandStage/WaitPlan/WaitState、request-ID 预检和 EventStore 原子追加契约。
- Produces: 完成阶段唯一权威页与命令失败/重试唯一权威页。

- [ ] **Step 1: 验证页面尚不存在**

```bash
test -f documentation/docs/zh/guide/command/completion.md
```

Expected: exit `1`。

- [ ] **Step 2: 编写完成语义双语页**

```markdown
# 完成语义
## 选择最早满足契约的阶段
## 阶段依赖图
## SENT 与 PROCESSED
## SNAPSHOT 与下游分支
## 函数匹配
## 链式等待
## 最终结果与结果流
## 超时、取消与未知结果
```

阶段必须画成 `PROCESSED` 后的分支，不能画成全局线性链；说明早到信号暂存和 `PROJECTED` 的 last-projection 条件。

- [ ] **Step 3: 编写失败与幂等双语页**

```markdown
# 失败与幂等
## 失败发生在哪一层
## commandId 与 requestId
## 快速预检与权威确认
## EventStore 持久约束
## 版本与创建冲突
## CommandResultException
## 超时后的查询与重试
## 下游副作用幂等
```

明确：失败的 `PROCESSED` 不能证明事件未追加；重试同一业务意图必须复用稳定 `requestId`。

- [ ] **Step 4: 运行语义检查与构建**

```bash
for stage in SENT PROCESSED SNAPSHOT PROJECTED EVENT_HANDLED SAGA_HANDLED; do
  rg -q "$stage" documentation/docs/zh/guide/command/completion.md || exit 1
  rg -q "$stage" documentation/docs/en/guide/command/completion.md || exit 1
done
rg -q 'commandId' documentation/docs/{zh,en}/guide/command/reliability.md
rg -q 'requestId' documentation/docs/{zh,en}/guide/command/reliability.md
pnpm --dir documentation docs:build
git diff --check
```

- [ ] **Step 5: 提交**

```bash
git add -- documentation/docs/{zh,en}/guide/command/{completion,reliability}.md
git commit -m "docs: document command outcomes"
```

---

### Task 7: 建立命令运行时原理页

**Files:**
- Create: `documentation/docs/zh/guide/command/internals/pipeline.md`
- Create: `documentation/docs/en/guide/command/internals/pipeline.md`
- Create: `documentation/docs/zh/guide/command/internals/wait-runtime.md`
- Create: `documentation/docs/en/guide/command/internals/wait-runtime.md`
- Create: `documentation/docs/zh/guide/command/internals/transport.md`
- Create: `documentation/docs/en/guide/command/internals/transport.md`

**Interfaces:**
- Consumes: DefaultCommandGateway、CommandDispatcher filter chain、SimpleCommandAggregate、WaitCoordinator/State、InMemory/Kafka/Redis/LocalFirst 实现。
- Produces: 命令运行机制三页；应用页只链接这些页面，不复制源码时序。

- [ ] **Step 1: 验证 internals 集合尚未完成**

```bash
test "$(cd documentation/docs/zh/guide && rg --files command/internals | wc -l | tr -d ' ')" = 3
```

Expected: exit `1`。

- [ ] **Step 2: 编写处理管线双语页**

固定章节：组件地图、发送前管道、Bus 到 Dispatcher、聚合恢复与调用、内存溯源与 append、ack/事件发送顺序、`PROCESSED` 错误边界、源码入口。

- [ ] **Step 3: 编写等待运行时双语页**

固定章节：WaitPlan Header、Notifier Filter、WaitSignal、StageWaitState、ChainWaitState、Handle/Coordinator、远程回调、fire-and-forget 错误边界。

- [ ] **Step 4: 编写传输与路由双语页**

固定章节：CommandBus 契约、InMemory、Kafka、Redis、LocalFirst 双副本准入、Void、`SENT` 对照、指标与追踪入口。不得复制扩展安装和配置表。

- [ ] **Step 5: 验证命令目录并提交**

```bash
test "$(cd documentation/docs/zh/guide && rg --files command | wc -l | tr -d ' ')" = 9
test "$(cd documentation/docs/en/guide && rg --files command | wc -l | tr -d ' ')" = 9
diff \
  <(cd documentation/docs/zh/guide && rg --files command | sort) \
  <(cd documentation/docs/en/guide && rg --files command | sort)
pnpm --dir documentation docs:build
git diff --check
git add -- documentation/docs/{zh,en}/guide/command/internals
git commit -m "docs: document command runtime"
```

---

### Task 8: 建立事件处理器与 Saga 页面

**Files:**
- Create: `documentation/docs/zh/guide/event/index.md`
- Create: `documentation/docs/en/guide/event/index.md`
- Create: `documentation/docs/zh/guide/event/processor.md`
- Create: `documentation/docs/en/guide/event/processor.md`
- Create: `documentation/docs/zh/guide/event/saga.md`
- Create: `documentation/docs/en/guide/event/saga.md`
- Read: `documentation/docs/{zh,en}/guide/event-processor.md`
- Read: `documentation/docs/{zh,en}/guide/saga.md`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/`
- Read: `wow-core/src/main/kotlin/me/ahoo/wow/saga/stateless/`

**Interfaces:**
- Consumes: DomainEventFunction、StatelessSagaFunction 及其测试契约。
- Produces: 事件协作入口、普通事件处理权威页和 Saga 权威页。

- [ ] **Step 1: 验证事件区域尚不存在**

```bash
test -f documentation/docs/zh/guide/event/index.md
```

Expected: exit `1`。

- [ ] **Step 2: 编写事件处理器双语页**

```markdown
# 事件处理器
## 何时使用普通事件处理器
## 定义事件函数
## 领域事件与状态事件
## 过滤与函数匹配
## 响应式副作用
## 幂等、顺序与重投
## 失败、重试与补偿入口
## 测试与完成标志
```

投影只出现在选择对照中，不修改或复制查询任务内容。

- [ ] **Step 3: 编写 Saga 双语页**

```markdown
# Saga
## 何时使用 Saga
## 无状态 Saga 契约
## 定义 Saga 函数
## 从事件生成 0..N 条命令
## requestId 与上下文传播
## 业务补偿
## 等待集成
## 测试与失败边界
```

删除事件补偿状态机、配置、Dashboard 和部署内容，只链接 `event/compensation`。

- [ ] **Step 4: 编写事件与协作概览双语页**

概览必须包含选择矩阵：普通副作用 → Processor；跨聚合后续命令 → Saga；处理函数失败的持久恢复 → Compensation。

- [ ] **Step 5: 验证并提交**

```bash
rg -q '0\.\.N' documentation/docs/zh/guide/event/saga.md
rg -q '0\.\.N' documentation/docs/en/guide/event/saga.md
rg -q '幂等' documentation/docs/zh/guide/event/processor.md
rg -q 'Idempoten' documentation/docs/en/guide/event/processor.md
pnpm --dir documentation docs:build
git diff --check
git add -- documentation/docs/{zh,en}/guide/event/{index,processor,saga}.md
git commit -m "docs: organize event collaboration guides"
```

---

### Task 9: 收口事件补偿与分发管线

**Files:**
- Create: `documentation/docs/zh/guide/event/compensation.md`
- Create: `documentation/docs/en/guide/event/compensation.md`
- Create: `documentation/docs/zh/guide/event/dispatch.md`
- Create: `documentation/docs/en/guide/event/dispatch.md`
- Modify: `documentation/docs/zh/reference/config/compensation.md`
- Modify: `documentation/docs/en/reference/config/compensation.md`
- Modify: `documentation/docs/zh/reference/example/compensation.md`
- Modify: `documentation/docs/en/reference/example/compensation.md`
- Read: `documentation/docs/{zh,en}/guide/event-compensation.md`
- Read: `documentation/docs/{zh,en}/guide/advanced/event-bus.md`
- Read: `compensation/wow-compensation-core/src/main/kotlin/me/ahoo/wow/compensation/core/CompensationFilter.kt`
- Read: `compensation/wow-compensation-domain/src/main/kotlin/me/ahoo/wow/compensation/domain/ExecutionFailed.kt`

**Interfaces:**
- Consumes: EventCompensationFilter、ExecutionFailed 状态机、Domain/State EventBus 和 Dispatcher/Filter 管线。
- Produces: 事件补偿语义权威页、事件分发原理页；配置和可运行示例各自保留唯一详细事实。

- [ ] **Step 1: 验证事件区域尚未完成**

```bash
test "$(cd documentation/docs/zh/guide && rg --files event | wc -l | tr -d ' ')" = 5
```

Expected: exit `1`。

- [ ] **Step 2: 编写事件补偿双语页**

```markdown
# 事件补偿
## 事件补偿解决什么问题
## 即时重试与持久补偿
## 失败记录创建
## ExecutionFailed 状态机
## 调度与准备重试
## 成功、再次失败与不可恢复
## 人工介入边界
## 验证与运营入口
```

指南不包含完整配置表、Dashboard 截图清单、端点表或 Kubernetes 清单。

- [ ] **Step 3: 编写事件分发管线双语页**

固定章节：DomainEventBus/StateEventBus、Composite Dispatcher、函数注册、Filter 顺序、通知器、RetryableFilter、CompensationFilter 插入点、ack/失败边界、源码入口。

- [ ] **Step 4: 归位补偿参考内容**

把配置键、默认值和完整 YAML 合并到 `reference/config/compensation.md`；把 Dashboard、管理端点、运行示例和部署验证移到 `reference/example/compensation.md`。删除重复段落，而不是复制原指南全文。

- [ ] **Step 5: 验证事件目录、参考归属并提交**

```bash
test "$(cd documentation/docs/zh/guide && rg --files event | wc -l | tr -d ' ')" = 5
test "$(cd documentation/docs/en/guide && rg --files event | wc -l | tr -d ' ')" = 5
diff \
  <(cd documentation/docs/zh/guide && rg --files event | sort) \
  <(cd documentation/docs/en/guide && rg --files event | sort)
rg -q 'ExecutionFailed' documentation/docs/{zh,en}/guide/event/compensation.md
pnpm --dir documentation docs:build
git diff --check
git add -- documentation/docs/{zh,en}/guide/event documentation/docs/{zh,en}/reference/config/compensation.md documentation/docs/{zh,en}/reference/example/compensation.md
git commit -m "docs: consolidate event compensation and dispatch"
```

---

### Task 10: 切换导航、删除旧页面并全站收口

**Files:**
- Modify: `documentation/docs/.vitepress/configs/sidebar.zh.ts`
- Modify: `documentation/docs/.vitepress/configs/sidebar.en.ts`
- Modify: `documentation/docs/{zh,en}/guide/index.md`
- Modify: `documentation/docs/{zh,en}/guide/introduction.md`
- Modify: `documentation/docs/{zh,en}/guide/core-concepts.md`
- Modify: `documentation/docs/{zh,en}/guide/advanced/architecture.md`
- Modify: `documentation/docs/{zh,en}/guide/advanced/data-flow.md`
- Modify: `documentation/docs/{zh,en}/guide/extensions/apiclient.md`
- Modify: `documentation/docs/{zh,en}/guide/extensions/webflux.md`
- Modify mechanically: every Markdown file returned by the old-link scan in Step 4
- Delete: `documentation/docs/{zh,en}/guide/modeling.md`
- Delete: `documentation/docs/{zh,en}/guide/eventstore.md`
- Delete: `documentation/docs/{zh,en}/guide/snapshot.md`
- Delete: `documentation/docs/{zh,en}/guide/command-gateway.md`
- Delete: `documentation/docs/{zh,en}/guide/event-processor.md`
- Delete: `documentation/docs/{zh,en}/guide/saga.md`
- Delete: `documentation/docs/{zh,en}/guide/event-compensation.md`
- Delete: `documentation/docs/{zh,en}/guide/advanced/event-evolution.md`
- Delete: `documentation/docs/{zh,en}/guide/advanced/aggregate-lifecycle.md`
- Delete: `documentation/docs/{zh,en}/guide/advanced/event-bus.md`

**Interfaces:**
- Consumes: Tasks 1–9 的 20 页双语结构和查询任务当前 sidebar/页面状态。
- Produces: 最终导航、无旧链接的全站、无兼容层的最终信息架构。

- [ ] **Step 1: 运行最终结构契约并确认失败**

```bash
rg -q "text: '领域开发'" documentation/docs/.vitepress/configs/sidebar.zh.ts
test ! -f documentation/docs/zh/guide/modeling.md
```

Expected: 第一条成功，第二条以退出码 `1` 失败，证明尚未切换。

- [ ] **Step 2: 替换双语 sidebar 的领域开发块**

中文目标结构：

```text
领域模型：domain/index.html, aggregate, event-sourcing, event-evolution, snapshot, lifecycle
命令：command/index.html, definition, sending, api-client, completion, reliability,
      工作原理[pipeline, wait-runtime, transport]
事件与协作：event/index.html, processor, saga, compensation, dispatch
```

英文使用 `Domain Model`、`Commands`、`Events and Collaboration`，路径完全相同。保留“读模型与查询”及其 items 的当前内容，不整文件覆盖 sidebar。

- [ ] **Step 3: 收口共享入口和扩展页**

- `guide/index`：任务矩阵改为三个新入口；查询行保持查询任务版本。
- `introduction`、`core-concepts`：只保留术语摘要和新权威链接。
- `advanced/architecture`、`advanced/data-flow`：保留跨能力视图，删除局部完整时序。
- `extensions/apiclient`：删除命令正文，链接 `command/api-client`；非命令内容保持查询/扩展任务版本。
- `extensions/webflux`：删除命令 Header/等待细节，链接 `command/sending` 与 `command/completion`。

- [ ] **Step 4: 生成旧链接清单并按语义机械替换**

```bash
rg --pcre2 -l '\]\([^)]*(?<!domain/)(modeling|eventstore|snapshot)(\.md)?([)#])|\]\([^)]*(command-gateway|event-processor|event-compensation)(\.md)?([)#])|\]\([^)]*(?<!event/)saga(\.md)?([)#])|\]\([^)]*advanced/(event-evolution|aggregate-lifecycle|event-bus)(\.md)?([)#])' \
  documentation/docs/{zh,en} -g '*.md' | sort
```

使用以下唯一映射：

```text
modeling（聚合/不变量）       → domain/aggregate
modeling（命令定义/处理函数） → command/definition
eventstore                    → domain/event-sourcing
snapshot                      → domain/snapshot
command-gateway（发送）       → command/sending
command-gateway（等待）       → command/completion
command-gateway（错误/幂等）  → command/reliability
event-processor               → event/processor
saga                          → event/saga
event-compensation            → event/compensation
advanced/event-evolution      → domain/event-evolution
advanced/aggregate-lifecycle  → domain/lifecycle
advanced/event-bus            → event/dispatch
```

查询页面若出现在清单中，只改链接目标，不改标题、章节、示例或技术结论。

- [ ] **Step 5: 删除旧页面并运行结构检查**

使用 `apply_patch` 删除 Files 中列出的 20 个双语旧文件，然后运行：

```bash
test "$(cd documentation/docs/zh/guide && rg --files domain command event | wc -l | tr -d ' ')" = 20
test "$(cd documentation/docs/en/guide && rg --files domain command event | wc -l | tr -d ' ')" = 20
diff \
  <(cd documentation/docs/zh/guide && rg --files domain command event | sort) \
  <(cd documentation/docs/en/guide && rg --files domain command event | sort)
! rg --pcre2 -n '\]\([^)]*(?<!domain/)(modeling|eventstore|snapshot)(\.md)?([)#])|\]\([^)]*(command-gateway|event-processor|event-compensation)(\.md)?([)#])|\]\([^)]*(?<!event/)saga(\.md)?([)#])|\]\([^)]*advanced/(event-evolution|aggregate-lifecycle|event-bus)(\.md)?([)#])' \
  documentation/docs/{zh,en} -g '*.md'
! rg -n 'TBD|TODO' documentation/docs/{zh,en}/guide/{domain,command,event} -g '*.md'
```

Expected: 页面数均为 20、路径 diff 为空、旧链接和占位词扫描无输出。

- [ ] **Step 6: 运行完整验证**

```bash
pnpm --dir documentation docs:build
git diff --check
git status --short
```

Expected: VitePress 无失效内部链接或锚点；diff check 通过；状态中没有 `node_modules`、构建输出或 `.superpowers/`。

- [ ] **Step 7: 审查最终差异并提交**

```bash
git diff --stat HEAD
git diff -- documentation/docs/.vitepress/configs/sidebar.zh.ts documentation/docs/.vitepress/configs/sidebar.en.ts
git add -- documentation/docs
git diff --cached --check
git commit -m "docs: finalize domain command event navigation"
```

Expected: 最终提交只包含文档站点文件；三个新分组完整，查询分组保留独立任务结果，旧页面全部删除。
