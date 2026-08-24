# 聚合查询字段 Schema 契约设计

## 背景

Wow 8.11.1–8.11.3 将聚合可查询字段以 `x-wow-query-fields` 字符串数组写入每个聚合的 `SingleQuery`、`CountQuery`、`ListQuery` 和 `PagedQuery` RequestBody。字段集合属于聚合级元数据，却在 OpenAPI 中按请求类型重复四次；字段较多时会明显放大文档。

本次变更明确破坏旧协议，不保留数组形式或 Wow 8.10 `properties.field` 形式的兼容逻辑。

## 目标

- 每个聚合只发布一份可查询字段集合。
- 字段集合继续作为可生成客户端类型的字符串枚举 Schema。
- 四类聚合查询 RequestBody 通过同一个 `$ref` 关联字段 Schema。
- Wow 是字段枚举及 Schema 名称的唯一来源。

## 非目标

- 不兼容 Wow 8.11.1–8.11.3 的 `x-wow-query-fields: string[]`。
- 不兼容 Wow 8.10 的 `AggregatedCondition.properties.field`。
- 不把字段集合移动到 Tag 或根级扩展。
- 不恢复 `AggregatedCondition` 或旧查询请求结构。
- 不增加协议版本协商、配置开关或迁移适配层。
- 不规定下游消费者的解析、测试、CI 与发布策略。

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

## 错误处理

显式组件 Schema 注册拒绝空 key。若显式 Schema 与类型生成器产生的 Schema 同名，`finish()` 立即失败并报告 component key，不允许静默覆盖。

聚合查询字段 Schema 必须是字符串 enum，扩展值必须是指向该 Schema 的 Reference。生成阶段不以跳过 Schema、输出内联数组或空 enum 的方式降级。

## 测试策略

### 模块测试

- `DefaultOpenAPIComponentContextTest`：显式 schema 注册返回正确 Reference，并在 `finish()` 后进入 `schemas`；同名生成 schema 冲突失败。
- `ExampleDomainOpenAPITest`：每个聚合只有一个 `*AggregatedFields` Schema；四类 RequestBody 的扩展引用相同 Schema；enum 包含真实嵌套字段且不含空字符串。
- OpenAPI snapshot：字段数组只存在于 `*AggregatedFields.enum`，RequestBody 中仅出现 `$ref`。

```bash
./gradlew :wow-openapi:check
```

### 实际服务契约验证

模块测试和快照不能替代运行时证明。实现完成后必须主动构建并启动一次 `example-server` 的实际 distribution，通过 HTTP 获取真实 `/v3/api-docs`；不得直接读取测试快照或在测试中调用 `RouterSpecs` 代替服务请求。

验证流程：

1. 运行 `./gradlew :example-server:installDist`。
2. 从 `example/example-server/build/install/example-server` 启动发行包，使用 distribution 自带的 in-memory storage 配置，不依赖 MongoDB 或 Elasticsearch。
3. 按条件轮询 `/actuator/health`，确认服务 ready；不使用固定 sleep。
4. 请求 `http://127.0.0.1:<port>/v3/api-docs` 并保存本次响应到临时文件。
5. 对实际响应至少断言：
   - `components.schemas.example.cart.CartAggregatedFields` 存在，类型为字符串 enum；
   - enum 包含 `state.items.productId`，且不包含空字符串；
   - `example.cart.SingleQuery`、`CountQuery`、`ListQuery`、`PagedQuery` 的 `x-wow-query-fields.$ref` 都等于 `#/components/schemas/example.cart.CartAggregatedFields`；
   - RequestBody 的 `x-wow-query-fields` 不再是数组；
   - 字段枚举只在 `CartAggregatedFields` Schema 中出现一次。
6. 无论成功或失败都停止服务；失败时输出服务日志和实际 `/v3/api-docs` 响应位置。

这是实现完成后的单次主动验收，不向仓库增加 verifier 源码、Gradle task、脚本或 CI 接线。执行者在最终交付中报告实际请求地址、断言结果和日志/响应位置即可。

## 完成条件

- 每个聚合的字段枚举在序列化 OpenAPI 中只出现一次。
- 四类聚合查询 RequestBody 指向同一个 `*AggregatedFields` Schema。
- Wow OpenAPI 不再输出数组形式的 `x-wow-query-fields`。
- `:wow-openapi:check` 通过，OpenAPI 快照符合新协议。
- 实现完成后已主动启动一次实际服务，并通过真实 `/v3/api-docs` 契约验证。
