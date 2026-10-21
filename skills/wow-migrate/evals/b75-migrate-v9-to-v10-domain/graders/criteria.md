---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. 固定源版本 9.9.0 与 V10 目标 commit，说明该 commit 只是源码基线而非已发布制品；源码以 V10 编译器下编译与领域测试通过为验收，历史数据由数据迁移工具按映射表单独处理，并把运行时、数据与发布列为 MISSING EVIDENCE
2. 命令体删除身份字段（`ReceiptOrder.id`/`@AggregateId`），事件删除 `orderId`，读取改为 `Command<X>.ref.id`；`CommandMessage<X>` 改 `Command<X>`，`ServerCommandExchange<ShipOrder>` 改为命令体本身，`CommandResultAccessor` 改 `DecideScope.putResult`（不持久化）；Mono 服务改 `suspend` 或保留并经 `wow-reactor` 适配，`@Name` 限定名可保留在参数上或直接注入服务
3. `require`/`check`/异常改为返回 `Decision.reject(code, message)`，码作为 `@ErrorCode` 常量登记（默认 422，状态可另设）；删除只记日志的 `onError`；返回 `Decision` 的函数用 `@OnCommand(produces = [...])` 声明事件；`PayOrder` 的不可支付分支仍是事件而非拒绝；decide 内的 `GlobalIdGenerator` 改为 `DecideScope.ids.next()`（冲突后重新决定取新 ID 无妨），或在客户端须预知 ID 时由 ref 推导
4. `OrderCreated` 升为 `@Revision(2)` 并写 `@Upgrade(event = OrderCreated::class, from = 1)` 去掉 `orderId` 与 `totalPrice`，`totalPrice` getter 改函数；9.x 事件名 `order_created` 到 V10 `example.order.OrderCreated`（或用户确定的上下文名）写入 `migration-map.json`，上下文名 `example-service`/`example` 要明确取舍
5. 状态类：`onSourcing` 改非 private，为 `OrderOverPaid`、`OrderPayDuplicated` 补空的 `onSourcing`，`lateinit` 改可空或默认值，`StatusCapable` 可经 V10 的 `wow-models` 保留（或改为普通属性），`StateAggregateTagsExtractor` 改为状态上的 `@Tags` 成员（推导的键按键覆盖 `ApplyTags`）
6. 删除 `@AggregateRoute`/`@CommandRoute`/`@AggregateOwner`/`@Spaced` 以及 `@BoundedContext` 的 aggregates 与 alias，路由改为 `POST /{context}/{aggregate}/{id}/{命令 kebab}`（如 `change-address`、`receipt-order`）；`@StaticTenantId` 改宿主配置 `wow.aggregates.<ctx.agg>.tenant`；客户端改 `Wow-Wait`、`Wow-Request-Id`、`If-Match` 与 `application/problem+json`（`code`、`violations`、`result`），所有者与空间来自认证主体，跨作用域用 `Wow-Scope`
7. `@StatelessSaga` 改 `@Saga` + `@OnEvent`，处理函数取 `StateEvent<S>`（可加 `SagaContext`）并返回 `context.command(ref, body)` 派生的命令，`@Retry` 改订阅配置；订阅绑定由编译器生成，不为等待绑定留 `TODO(wow-migrate)`，不添加 9.x 兼容层或别名
