# 聚合查询字段 Schema 契约设计

## 背景

Wow 8.11.1–8.11.3 将聚合可查询字段以 `x-wow-query-fields` 字符串数组写入每个聚合的 `SingleQuery`、`CountQuery`、`ListQuery` 和 `PagedQuery` RequestBody。字段集合属于聚合级元数据，却在 OpenAPI 中按请求类型重复四次；字段较多时会明显放大文档，并要求下游生成器自行合成字段类型。

本次变更明确破坏旧协议，不保留数组形式或 Wow 8.10 `properties.field` 形式的兼容逻辑。

## 目标

- 每个聚合只发布一份可查询字段集合。
- 字段集合继续作为可生成客户端类型的字符串枚举 Schema。
- 四类聚合查询 RequestBody 通过同一个 `$ref` 关联字段 Schema。
- Wow 是字段枚举及 Schema 名称的唯一来源；消费者不推导或合成 Schema。
- Fetcher Generator 只实现新协议，并在协议缺失或错误时快速失败。

## 非目标

- 不兼容 Wow 8.11.1–8.11.3 的 `x-wow-query-fields: string[]`。
- 不兼容 Wow 8.10 的 `AggregatedCondition.properties.field`。
- 不把字段集合移动到 Tag 或根级扩展。
- 不恢复 `AggregatedCondition` 或旧查询请求结构。
- 不增加协议版本协商、配置开关或迁移适配层。

## OpenAPI 契约

每个聚合注入一个 `*AggregatedFields` Schema：

```yaml
components:
  schemas:
    compensation.execution_failed.ExecutionFailedAggregatedFields:
      type: string
      enum:
        - aggregateId
        - state.status
        - state.retryState.retries
```

该聚合的四类查询 RequestBody 均使用 `x-wow-query-fields` 指向同一 Schema：

```yaml
components:
  requestBodies:
    compensation.execution_failed.CountQuery:
      x-wow-query-fields:
        $ref: "#/components/schemas/compensation.execution_failed.ExecutionFailedAggregatedFields"
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/wow.api.query.FilterExpression"
```

`SingleQuery`、`ListQuery` 和 `PagedQuery` 使用完全相同的扩展引用。扩展名称保持 `x-wow-query-fields`，其唯一合法值是 OpenAPI Reference Object，不再接受数组。

Schema key 沿用原有生成模型命名：

```text
<contextAlias>.<aggregateAlias>.<AggregateTypeSimpleName>AggregatedFields
```

例如：

```text
compensation.execution_failed.ExecutionFailedAggregatedFields
example.cart.CartAggregatedFields
```

枚举值来自 `commandAggregatedFieldPaths()` 的排序结果，原样发布，不补回旧协议中的空字符串。

## Wow 实现设计

### 显式组件 Schema 注册

`OpenAPIComponentContext` 增加一个聚焦的显式 Schema 注册入口，接受 component key 和 `Schema<*>`，注册后返回对应 `#/components/schemas/...` Reference。`DefaultOpenAPIComponentContext` 在 `finish()` 时将显式 schemas 与类型生成器产出的 schemas 合并。

注册规则：

- key 不得为空；
- 同一个 key 可被相同聚合的多个路由重复注册，最终只保留一项；
- 显式 schema 不得覆盖类型生成器产生的同名 schema，冲突时直接失败；
- 显式组件始终进入 `components.schemas`，因为该协议必须通过 `$ref` 引用，不受普通类型 schema 的 inline 设置影响。

不为这一项能力引入新的 registry、provider 或 SPI；复用 `OpenAPIComponentContext` 现有的组件收集与 `RouterSpecs` 合并流程。

### 查询组件

`QueryComponent` 为聚合创建 `StringSchema`：

- key 使用上述确定性命名；
- enum 使用已排序的 `commandAggregatedFieldPaths()`；
- 注册后取得 Reference；
- 四个 aggregated RequestBody 将该 Reference 写入 `x-wow-query-fields`。

删除当前四处直接写入字段数组的逻辑。通用 `wow.CountQuery`、`wow.ListQuery` 和 `wow.PagedQuery` 不属于具体聚合，继续不发布 `x-wow-query-fields`。

## Fetcher Generator 设计

`AggregateResolver.fields()` 只处理 operation id 以 `.snapshot.count` 结尾的聚合 count 操作，并执行以下步骤：

1. 解析 operation 的 RequestBody component。
2. 读取 `x-wow-query-fields`。
3. 要求其为合法 OpenAPI Reference Object。
4. 通过现有 `keySchema()` 解析字段 Schema。
5. 将得到的 `KeySchema` 赋给 operation tag 对应的聚合。

不再解析 count body 的 `FilterExpression`，也不在 Fetcher 中生成或注入 `AggregatedFields` Schema。后续 `ModelGenerator` 和 `QueryClientGenerator` 继续使用已有 `KeySchema` 流程，无需修改公共生成模型。

## 错误处理

对于聚合 count 操作，以下情况都属于无效 Wow OpenAPI 契约并立即抛出带上下文的错误：

- RequestBody 不存在或无法解析；
- 缺少 `x-wow-query-fields`；
- 扩展值不是 Reference Object；
- Reference 不在 `components.schemas` 中；
- 目标 Schema 不是非空字符串 enum。

错误信息至少包含 operation id 和失败的 component/ref。不得以跳过聚合、退化为 `string` 或合成空 enum 的方式继续生成。

## 测试策略

### Wow

- `DefaultOpenAPIComponentContextTest`：显式 schema 注册返回正确 Reference，并在 `finish()` 后进入 `schemas`；同名生成 schema 冲突失败。
- `ExampleDomainOpenAPITest`：每个聚合只有一个 `*AggregatedFields` Schema；四类 RequestBody 的扩展引用相同 Schema；enum 包含真实嵌套字段且不含空字符串。
- OpenAPI snapshot：字段数组只存在于 `*AggregatedFields.enum`，RequestBody 中仅出现 `$ref`。

验证命令：

```bash
./gradlew :wow-openapi:check
```

### Fetcher

- `AggregateResolver` 单元测试使用 `$ref` 扩展并断言得到既有 `KeySchema`。
- 覆盖扩展缺失、非 Reference、目标缺失和非字符串 enum 的失败信息。
- 使用 Wow 新契约 OpenAPI 执行一次真实代码生成，确认生成 `ExecutionFailedAggregatedFields` 及查询客户端。
- 删除旧数组及 `properties.field` 测试。

验证命令：

```bash
pnpm --filter @ahoo-wang/fetcher-generator test
pnpm --filter @ahoo-wang/fetcher-generator build
```

## CI 与发布顺序

这是 Wow 与 Fetcher Generator 的协同破坏升级：

1. Wow 合入并发布包含新 Schema/$ref 契约的版本。
2. Fetcher Generator PR 同时实现新协议并把真实生成工作流升级到该 Wow 镜像。
3. `generator-test` 改为构建并执行当前 checkout 的 Generator；当前“安装 npm latest”的做法无法在发布前验证本 PR，会形成发布与 CI 的循环依赖。
4. Fetcher 的 `generator-test` 与 `integration-test` 通过后合入并发布 Generator。
5. 发布后再以已发布包对同一 Wow 镜像执行一次 smoke test，作为发布证明而非 PR 源码检查。

Fetcher 修复 PR 将覆盖 PR #1351 的镜像升级内容，因此 #1351 应在该 PR 合入后关闭或重建，不单独合并。升级窗口内旧 Generator 无法消费新 Wow，新 Generator也不消费旧 Wow；这是本设计明确接受的破坏边界。不得以临时 fallback 延长双协议状态。

## 完成条件

- 每个聚合的字段枚举在序列化 OpenAPI 中只出现一次。
- 四类聚合查询 RequestBody 指向同一个 `*AggregatedFields` Schema。
- Wow OpenAPI 不再输出数组形式的 `x-wow-query-fields`。
- Fetcher Generator 不再读取 `FilterExpression.properties.field` 或接受字段数组。
- Wow 与 Fetcher 的定向测试、真实生成以及 PR #1351 两个失败工作流全部通过。
