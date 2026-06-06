# wow-query 单元测试全面重写设计

## 概述

对 `wow-query` 模块进行全面的单元测试重写，目标是 100% 源文件覆盖、统一测试风格、补充缺失测试。

## 现状分析

- 21 个测试文件，覆盖 45 个源文件中的约 25 个
- converter 层（6 个文件）完全无测试 — 最大覆盖空白
- 部分 DSL 测试过于单薄（SortDsl 1 个测试、PaginationDsl 1 个测试）
- 所有现有测试通过，已使用 FluentAssert，已有 License 头
- 仅依赖 `wow-tck`（`MOCK_AGGREGATE_METADATA`）

## 设计原则

1. **一对一文件结构** — 每个源文件对应一个 Test 文件，保持目录布局
2. **自包含 fixtures** — Mock 对象定义为 Test 文件内部类，不引入外部依赖
3. **DSL 大方法按类别拆分** — 比较类、集合类、时间类、逻辑类、嵌套类各一个测试方法
4. **统一 FluentAssert** — 全部使用 `.assert()` 风格
5. **100% 源文件覆盖** — 每个公开 API 都有至少一个测试

## 测试文件清单

### dsl/ — 10 个 Test 文件

| 文件 | 操作 | 说明 |
|------|------|------|
| ConditionDslTest | 重写 | 拆分巨型方法为 ~8 个分类测试（比较/集合/时间/逻辑/嵌套/ID/属性引用/参数化），保留 ParameterizedTest |
| SortDslTest | 重写 | 增加：空 sort、property reference asc/desc、多字段排序 |
| PaginationDslTest | 重写 | 增加：默认值、边界值测试 |
| ProjectionDslTest | 重写 | 增加：property reference include/exclude、空 projection |
| PagedQueryDslTest | 重写 | 增加：仅 condition、仅 pagination、空查询默认值 |
| ListQueryDslTest | 重写 | 增加：无 projection、无 condition、limit=0 默认值 |
| SingleQueryDslTest | 重写 | 增加：空查询默认值、仅 condition |
| BetweenStartTest | 新增 | data class 属性测试 |
| NestedFieldDslTest | 新增 | `nested()`、`withNestedField()`、blank nestedField |
| QueryableDslTest | 新增 | 覆盖 `projection()`、`condition()`、`sort()` 的两种重载 |

### filter/ — 4 个 Test 文件

| 文件 | 操作 | 说明 |
|------|------|------|
| ContextsTest | 重写 | 保持现有逻辑，增强 null 返回测试 |
| QueryTypeTest | 新增 | 枚举值 `isDynamic` 属性全覆盖 |
| QueryContextTest | 新增 | DefaultQueryContext 的 setQuery/getQuery/setResult/getResult/rewriteQuery/rewriteResult/setAttribute/getAttribute/type cast 方法 |
| MaskingDynamicDocumentQueryFilterTest | 新增 | 用 Mock 实现测试 abstract 类的 filter 和 maskDynamicDocument |

### converter/ — 5 个 Test 文件（全部新增）

| 文件 | 说明 |
|------|------|
| FieldConverterTest | 测试 FieldConverter 接口和默认实现 |
| ConditionConverterTest | 用 MockConcreteConditionConverter 测试 AbstractConditionConverter 所有 Operator 转换 |
| SortConverterTest | 用 MockConcreteSortConverter 测试 AbstractSortConverter asc/desc 转换 |
| ProjectionConverterTest | 用 MockConcreteProjectionConverter 测试 include/exclude 转换 |
| DeleteConditionGuardTest | 重写 — 增加边界用例（nor 条件、or 条件、嵌套 deleted） |

### mask/ — 3 个 Test 文件

| 文件 | 操作 | 说明 |
|------|------|------|
| DataMaskingKtTest | 重写 | 保持现有覆盖，统一 MockMaskingData 为内部类 |
| StateDataMaskerRegistryTest | 重写 | 统一使用内部 MockMasker，增加 EventStreamMaskerRegistry 测试 |
| DefaultAggregateDataMaskerTest | 重写 | 统一 MockStateDataMasker 为内部类 |

### snapshot/ — 6 个 Test 文件

| 文件 | 操作 | 说明 |
|------|------|------|
| NoOpSnapshotQueryServiceTest | 重写 | 保持覆盖，统一风格 |
| SnapshotStatesKtTest | 重写 | 不再依赖 MockK mock，使用真实 MaterializedSnapshot |
| CountSnapshotQueryContextTest | 重写 | 增加 ownerId/spaceId rewrite 测试 |
| DefaultSnapshotQueryHandlerTest | 重写 | 保持覆盖 |
| AbacQueryFilterTest | 重写 | 保持覆盖，将内部 EmptyAbacQueryFilter/MockAbacQueryFilter 移到文件内 |
| MaskingSnapshotQueryFilterTest | 重写 | 统一 Mock fixtures |

### event/ — 3 个 Test 文件

| 文件 | 操作 | 说明 |
|------|------|------|
| NoOpEventStreamQueryServiceFactoryTest | 重写 | 保持覆盖 |
| DefaultEventStreamQueryHandlerTest | 重写 | 保持覆盖 |
| MaskingEventStreamQueryFilterTest | 重写 | 增加 single/dynamicSingle/paged/dynamicPaged/count 测试 |

## 不需要测试的文件

- `QueryDslMarker.kt` — 纯注解类，无行为
- 纯接口定义（ConditionConverter、SortConverter、ProjectionConverter、FieldConverter、EventStreamQueryService、EventStreamQueryServiceFactory 等）— 通过实现类间接测试

## 统计

- 重写: 15 个文件
- 新增: ~9 个文件
- 总计: ~24 个测试文件
- 预计测试用例数: ~120-150 个

## 依赖

- 保持现有 `build.gradle.kts` 不变
- 继续依赖 `wow-tck`（`MOCK_AGGREGATE_METADATA`）
- 新增测试 fixtures 均为各 Test 文件的内部类
