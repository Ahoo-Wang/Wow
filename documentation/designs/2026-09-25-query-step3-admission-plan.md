# 查询迁移第 3 步：入口策略、准入与错误目录

目标架构见 [2026-09-24-query-target-architecture-design.md](2026-09-24-query-target-architecture-design.md)（§5.3、§5.4、§5.9、§11 N6、附录 A 第 3 步）。

第 3 步改动面大，拆成五个 PR，按顺序合并，每个都要求：
- 错误文案黄金测试（`QueryRestErrorContractTest`，240 条）与路由契约测试（`QueryRouteContractTest`）不变，除非该 PR 明确声明了某条变更；
- OpenAPI 快照不变；
- 改动后端的 PR 在 CI 的集成测试通过。

## 现状

| 目标 | 现状 |
|---|---|
| 入口三态 | 没有入口概念。HTTP 的预算与门控在 wow-webflux 的 `HttpQueryGuard` 里，进程内调用不受限 |
| 读取一次 | Gateway 在 `deferContextual` 中读取一次调用方范围（`queryScope()`），已满足 |
| 准入第 1～3 步 | `QueryPreparer`：改写 → 调用方范围 → `QueryPolicy` → 模型默认范围，顺序已与目标一致 |
| 准入第 4～6 步 | 游标追加排序在 `CursorQueries.kt`；校验在 `validateQuery`；解析与规范化散在后端编译器里 |
| 错误目录 | 没有。第 2 步已让准入的校验失败携带 `QueryViolation` |

## 3a：入口

- `QueryEntry { HTTP, IN_PROCESS, UNSPECIFIED }`（wow-query），经 Reactor context 传递：`Context.withQueryEntry(entry)`、`ContextView.queryEntry()`，缺省为 `UNSPECIFIED`。
- HTTP 适配器在 `withQueryContext` 里与范围一起写入 `HTTP`。它是全部查询路由共用的那一处（路由契约测试已证明 47 条网关路由都经过它）；路由契约测试的 `assertRanInHttpQueryContext` 增加入口断言。
- Gateway 在订阅开始时与范围一起读取一次入口，之后扩展改写 context 不影响已读取的值。
- 扩展与策略内部发起的嵌套查询：提供 `inProcessQuery { }`，在清掉继承的范围与入口、并显式写入 `IN_PROCESS` 的 context 中执行。
- 开关 `wow.query.require-explicit-entry`（默认 `false`）：开启后 `UNSPECIFIED` 的查询被拒绝。
- 本 PR 不改变任何对外行为：入口还没有被任何策略读取。

## 3b：入口策略

- 预算与门控从 `HttpQueryGuard` 移到 wow-query 的 `QueryEntryPolicy`，由 Gateway 在准入第 0 步执行：
  - 计量对象是调用方提交的原样查询加边缘范围，在 schema 获取与第 1 步之前检查，所以现有的检查顺序、错误码与文案不变；
  - `HTTP` 入口使用配置的预算与门控；`IN_PROCESS` 与 `UNSPECIFIED` 不限，可以显式配置。
- 留在 HTTP 适配器的：返回行数上限、`limit=0` 改写为默认列表大小、空闲超时、非 SSE 缓冲、请求体解码。
- 范围缺失时的处理：`wow.query.http.require-scope`（默认 `false`，保持旧行为）；开启后只有 `AUTHENTICATED` 来源的范围计数（范围来源在第 8 步交付，此前开启即要求非空的边缘范围）。
- `HttpQueryGuard` 的配置属性按新位置重新命名；Spring 配置属性不在兼容范围内。

## 3c：`QueryAdmission` 与 `AdmittedQuery`

- `QueryPreparer` 更名并扩展为 `QueryAdmission`，按目标架构 §5.4 的第 0～6 步执行，产出 `AdmittedQuery<Q>`（构造函数 internal，只有准入能创建）：
  - 第 4 步：游标追加的唯一排序从 `CursorQueries.kt` 移入；
  - 第 5 步：`validateQuery`；
  - 第 6 步：解析字段引用、规范化（相对时间统一取一个服务端 `now`、降级运算符、展平逻辑节点），规范化时为带字段的节点分配新实例，并以节点身份登记 `ResolvedField`。
- 后端签名改为只接受 `AdmittedQuery`；Mongo、ES 编译器从 `AdmittedQuery` 取解析结果，不再自己查找字段与规范化。
- `FilterNormalizer` 从后端移到准入第 6 步。
- TCK 与测试经 `QueryAdmission` 取得 `AdmittedQuery` 再调用后端。
- 这是第 3 步中最大的一个 PR，可能再按“过滤 / 排序与投影 / 聚合”拆分。

## 3d：错误目录

- `QueryErrorCatalog`（wow-query）集中定义对外错误码、HTTP 状态与文案模板，由 `QueryViolation` 渲染。
- 覆盖第 2 步未覆盖的来源：绑定、Profile、游标、入口预算、后端原生检查（后端改抛结构化违规）。
- 未预期的异常统一返回既有的 500 文案。
- 黄金测试盘点出的 REST 文案缺陷（遗留 `condition` 请求体的 500、COUNT 请求体未掩盖的原始文案、`bindingErrors` 泄漏 Jackson 内部类名等）在本 PR 修复，**但每一条都会改变 REST 可见的文案，需先经用户逐条确认**；未确认的保持现状。

## 3e：N6 `BEFORE_NOW` / `AFTER_NOW`

- 新运算符：AST 数据类与 wire 名、`FilterOperatorSpec` 一行（FIELD、RANGE、TEMPORAL、正常开销）、两个后端的翻译分支、DSL 函数、规范化时用第 6 步的服务端 `now` 换算为范围。
- TCK 用例与 OpenAPI 快照同步更新。
