# 查询模块第二阶段：聚合编译结果复用

日期：2026-09-05

状态：已于 2026-09-05 实施并完成验证；Mongo `count_only/16` 的微小性能影响仍不确定。

生产代码基线：`27fe81b40`；基准冻结提交：`8ae1c4ddc`；候选生产提交：`0f215fbef`；Wow `9.0.8`。前置工作见[第一阶段设计与结果](2026-09-05-query-resolution-refactoring-design.md)。

## 目标与范围

在 MongoDB 和 Elasticsearch 聚合编译器内部复用已取得的字段 Binding、Schema 和原生表达式，减少重复解析与对象构造，同时保持编译输出和查询行为。

生产改动限定为两个现有内部编译器：

- [MongoAggregationCompiler](../../wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt)
- [ElasticsearchAggregationCompiler](../../wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt)

测试扩展对应的 `MongoAggregationCompilerTest` 与 `ElasticsearchAggregationCompilerTest`；性能验证使用现有 `wow-benchmarks` 的 JMH 构建。

公开 Gateway、Backend、Schema 接口及其职责不变。Query JSON、Schema HTTP、生成 OpenAPI/schema、存储布局、Cursor 协议、Spring 配置和版本号不变。不增加依赖、Gradle 模块或构建插件，不修改生产类的可见性。

本阶段不重构普通查询的 Sort/Projection、分页器、Schema 生命周期、索引构造策略、HTTP Guard 或脱敏流程。

## 现状与选择

现有 `QueryModelSchema.resolveFieldSchema(field, capability)` 已能返回匹配的字段 Schema；其中的 `binding(capability)` 同时提供物理路径。它支持逻辑字段与 resolved alias，后端无需另建解析接口。

| 路径 | 当前重复工作 | 本阶段目标 |
|---|---|---|
| ES 已绑定普通聚合字段 | 先 `resolveFieldSchema`，再 `resolvePhysicalField` | 复用首次返回的 Binding |
| ES 已绑定 DateHistogram | 私有字段解析调用两次 Resolver，日期语义再调用一次 | 一次字段解析同时提供物理路径与日期语义 |
| MongoDB Terms/Histogram 分组 | `$match` 与 `$group` 分别解析同一字段 | 一个分组编译一次，生成两个原生输出 |
| MongoDB DateHistogram 分组 | 两个阶段分别构造 `dateInput` | 两个阶段复用一个日期输入表达式 |
| MongoDB unknown root | 首次解析缺失后再次调用相同 Resolver | 直接采用已确定的逻辑路径回退 |

以上是源码调用路径分析，不是耗时测量。研究阶段运行两种聚合编译器的现有测试：MongoDB 27 项、ES 12 项，共 39 项通过，无失败、错误或跳过。

采用各后端内部复用已有 Schema 信息的方案。另一方案是新增共享解析接口，但现有返回值已经足够，新增接口会扩大公共合同，而且仍需容纳两种后端不同的日期兼容策略，因此不采用。

## 架构与数据流

现有数据流保持为：Gateway 在同一 Schema 快照上完成准入 → Backend 接收 `ResolvedQuery` → 对应编译器生成原生 BSON 或 Elasticsearch 计划 → 现有后端执行。

所有复用值只属于当前一次 `compile` 调用：

- 读取同一 `QueryModelSchema` 中已解析的字段 Schema 和 Binding；
- MongoDB 分组的过滤条件与分组表达式在同一次私有编译中生成；
- ES 的日期物理路径和语义来自同一个字段解析结果；
- 不缓存跨查询结果，不把原生表达式保存到 Schema、Backend 或全局状态。

不引入 Planner、Registry、第二棵查询 AST 或统一编译服务。私有 helper 与原生 `Document`、`Bson`、现有 ES 计划足以表达本阶段数据流。

## MongoDB 设计

### 分组只编译一次

把当前分别生成分组过滤条件与分组表达式的两轮处理，整理为一个私有的分组编译函数。使用 `Pair<Bson, Any>` 返回 `(filter, expression)`，调用方按原分组顺序填充过滤列表与原生 `Document` 分组键。

- Terms：一次获取完整物理路径，同时生成 exists/non-null 过滤和字段引用。
- Histogram：一次获取物理路径和 scalar/singleton 输入，同时生成数值过滤与现有 floor/divide/multiply 分组表达式。
- DateHistogram：一次生成 `dateInput`，用于非空过滤以及现有 `$dateTrunc`/`$toLong` 分组表达式。

私有 `group` 方法接收已构造的分组键，只继续编译累加器。没有 groupBy 时仍使用 null 分组键，保持 summary 行为；避免为该路径新增无用的分组 `Document`。

最终管道顺序、分组顺序、别名、贡献计数、投影、有效排序与 limit 均保持不变。原生表达式构造后不再修改；复用只发生在同一编译结果的两个使用位置，不跨编译共享可变 `Document`。

分组处理合并后，每个分组的语义校验仍在其输入解析时完成。表达式包装仅消费该输入，不重复字段解析，也不把后续分组的校验提前。保留异常类型及首个语义失败的选择。

### 字段与日期回退

私有普通字段解析保持如下顺序：

1. 计算完整逻辑字段，执行一次 `resolveFieldSchema`。
2. 有对应 Binding 时返回其完整物理路径。
3. 无 Binding 且逻辑字段是明确声明的字段时，保留原 capability 缺失异常。
4. 否则使用物理 parent 加原相对字段；无物理 parent 时直接返回逻辑字段路径，不再次调用 `resolvePhysicalField`。

`dateInput` 优先使用一次 `resolveFieldSchema` 返回的 temporal Binding 和语义。解析未匹配时，只需检查 `schema.fields[logicalField]` 是否为明确声明：已匹配的动态 temporal Binding 已由 Resolver 返回，未匹配的动态后代本来就走兼容回退，不需要再次调用 `schema.field` 派生完整 Schema。

- 未声明或动态 capability 缺失：保留原始路径/物理 parent 回退和原生 `$toDate`。
- 明确声明但缺少 temporal Binding：保留 `QuerySchemaValidationException`。
- `Temporal.Date`：保留现有 scalar/singleton 与安全 `$convert`。
- `Temporal.Epoch`：保留整数检查、精度、负数向下取整和时间单位转换。
- 已绑定但缺少支持的 temporal semantic：保留原异常。

不修改 `epochDate`、`scalarOrSingleton`、单位换算或数值表达式运算算法。

## Elasticsearch 设计

### 复用字段 Binding

普通聚合字段的私有解析从一次 `resolveFieldSchema` 返回值读取对应 `binding(capability).physicalField`，替代随后再次调用 `resolvePhysicalField`。

这条路径覆盖 Terms、Histogram、Any、简单 Numeric 以及算术表达式中的字段出现位置。每个出现位置独立解析，不引入跨表达式字段缓存。

找不到 Binding 时保留现有顺序：明确声明却缺能力的字段失败；其余字段使用物理 parent 加原相对字段，无 parent 时使用逻辑路径。

### 日期路径与语义同源

`dateField` 只解析一次字段 Schema，并从同一结果取得 temporal Binding 的物理路径与 semantic type。必要的物理路径回退由本编译器私有 helper 复用，不以重新调用 Resolver 实现复用。

ES 的日期兼容规则与 MongoDB 分开保留：

- 匹配 temporal Binding 且为 `Temporal.Date`：使用该物理路径。
- 匹配 Binding 且为 `Temporal.Epoch`：保留现有参数化 date runtime field、名字、参数与脚本。
- 未匹配 temporal Binding，但 `schema.field(logicalField)` 可以识别该字段，包括动态派生字段：仍拒绝缺失的 temporal capability。
- 完全缺少字段 metadata：保留原物理路径回退，由原生 date histogram 执行。
- 已绑定但 temporal semantic 不受支持：保留原异常。

不改写 Painless 脚本、时间单位因子、日期分组间隔、时区、runtime mapping 生命周期、贡献计数或有效排序。

## 共同语义边界

| 边界 | 必须保持的行为 |
|---|---|
| resolved alias | `resolveFieldSchema` 的 alias 命中优先于同名声明缺 capability；不能用 `schema.field` 直接替代该解析 |
| 相对字段 | 继续使用 `parent.append(field)`；parent=`body`、field=`body.data` 对应 `body.body.data`，不能换成 `absoluteTo` |
| 物理路径 | 编译器需要完整物理路径；不能改用带 physicalParent 的公共 `resolvePhysicalField` 后误取相对路径 |
| element 遍历 | 现有 `schema.field` 维护 resolvedParent，另一解析提供 alias-aware 物理路径；本阶段不合并两者 |
| 数值相对路径 | 保留首次 Resolver 的既有路径校验及异常，不跳过解析去拼接一个本应拒绝的路径 |
| 兼容性 | 保留明确声明缺能力与未知字段回退之间的区别，以及两后端的日期差异 |
| 结果所有权 | 每次 compile 拥有自己的原生节点，不新增共享可变状态，不改变结果 ObjectNode 的订阅隔离 |
| 公共合同 | 仅重排两个内部编译器的工作，继续由 Gateway/Schema 决定准入 |

## 验证设计

### 功能与工作量回归

扩展现有编译器测试，断言实际 BSON/ES 计划及必要的字段解析调用次数，防止重复解析重新进入路径。次数断言针对一次字段出现位置或一个分组，不禁止多个独立指标各自解析同名字段。

重点覆盖：

- MongoDB 同一分组的 `$match` 输入与 `$group` 输入一致，summary 的 null 分组键及管道顺序保持不变。
- 两后端的逻辑字段、resolved alias、能力缺失、未知字段和完整物理 parent 路径。
- parent 与相对字段重名前缀、多层 element、数值相对路径拒绝。
- MongoDB 动态 temporal 兼容回退，以及 ES 对相同 metadata 缺能力情形的原有拒绝。
- Date/Epoch 转换、缺 temporal semantic 的异常、分组时区及周起点。
- 连续 compile 的原生对象不跨调用共享；复用不改变最终序列化的 BSON/ES 结构。

沿用 JUnit、FluentAssert 和现有 fixture；不为本次重构新增测试框架，不以源码文本检查代替编译结果断言。现有测试已经覆盖的合同直接复用。

先运行两个聚合编译器的聚焦测试，再运行两后端的相关检查与查询集成测试。MongoDB/ES Testcontainers 验证真实聚合、日期精度及嵌套元素执行；共享查询模块的合同检查用于确认边界未变。测试失败与环境缺失分别报告。

### 聚合编译 JMH

在现有 `wow-benchmarks` 的 JMH Java 源集中直接调用两个内部 Kotlin 编译器的 JVM 方法。研究阶段已核对其 JVM 可调用签名；该基准依赖保持在测试/基准侧，不新增生产 API、反射桥或 Gradle 配置。

基准只测 `compile(query, schema)`，不订阅 Publisher，不启动数据库，不把网络、结果解码或 Schema 构造计入编译耗时。Schema 与符合现有准入的 Query 在 Setup 中准备；结果由 JMH 消费，Setup 核对计划或 BSON 中的预期物理路径。

使用以下 28 个场景：两种后端 × 七种查询形态 × 宽度 1/16。宽度指分组数或指标数，均在现有上限内。

| 查询形态 | 输入和用途 |
|---|---|
| known_terms | 已绑定的 Terms 分组，逻辑与物理路径不同 |
| unknown_terms | 没有该字段 metadata 的 Terms 分组，验证兼容回退成本 |
| known_histogram | 已绑定的数值 Histogram 分组，验证物理路径与 scalar/singleton 输入复用 |
| known_epoch | 使用 resolved alias 的 Epoch 日期分组，微秒单位，验证路径与日期输入复用 |
| unknown_date | 无该字段 metadata 的日期分组，保留两后端原生回退 |
| known_metric | 已绑定的普通 Numeric 指标；MongoDB 原本只解析一次，是对照场景 |
| count_only | 只有 Count 指标，没有分组或字段解析，监测通用编译开销回退 |

动态字段与嵌套 parent 的语义由功能/集成测试覆盖，不为此扩大参数笛卡尔积。

使用同一份基准源码对照优化前后生产实现；保留基线 jar、JSON、日志、提交及环境记录。沿用第一阶段的 JDK 17、单线程、固定 256 MiB 堆、3 forks、5 次 200ms 预热、10 次 200ms 测量及 GC profiler。

记录完整矩阵的 `ns/op`、误差范围和 `gc.alloc.rate.norm`（B/op）。B/op 仅表示编译期分配，不表示 retained heap。目标场景应取得可复现的耗时或分配量收益；没有字段解析等对照不应出现可复现回退。误差重叠时不宣称耗时改善，疑似回退做针对性成对复测。不能将编译微基准比例外推为数据库查询端到端加速比例。

原始产物放在忽略的 `build/aggregation-compiler/` 下，第一阶段产物保持独立；不提交生成输出。

## 实施与验证结果

ES Binding 复用由 `00c67137f` 完成，Mongo 分组输入复用由 `0f215fbef` 完成；两项生产改动均通过独立审查。验证发现新增测试的格式与 Mongo 测试类大小不符合 Detekt，`a083ac9cb` 只整理原测试文件，并保留全部测试名、断言和 fixture 语义；修正也通过独立复审。最终生产范围仍只有两个内部编译器，没有改动公共接口、模块、依赖或生成协议。

同一基准源码的 baseline/candidate 各完成 28 个场景。基准源码 SHA-256 为 `9448776016ee1d039cf674e4b5ba2ca4b39f217ed9f911e4ab0c3097c8465dd7`；baseline jar 为 `5d60fe4f55b1f23f31645d63e1d0bccb41cf47ca31d4204674ce1890d0ee63bc`，candidate jar 为 `7120a25510a6fae7086905341265e3815d67f83a3fa4f6d6c4099a9bfe240631`。完整 28 行对照和原始 JSON/日志保存在忽略的 `build/aggregation-compiler/comparison.csv`、`baseline.*`、`candidate.*`。

目标场景在两个宽度下的代表性结果如下。耗时列为 baseline → candidate；括号中是候选相对变化，误差重叠时不声明耗时改善。

| Backend / shape | Width 1 ns/op / B/op | Width 16 ns/op / B/op |
|---|---:|---:|
| ES known_terms | 343.942 → 323.841 (-5.8%) / 2448.001 → 2381.334 (-2.7%) | 2086.829 → 2366.635（误差重叠）/ 13992.006 → 13314.673 (-4.8%) |
| ES known_histogram | 335.784 → 314.506 (-6.3%) / 2456.001 → 2389.334 (-2.7%) | 2144.815 → 1890.418 (-11.9%) / 14514.672 → 13858.672 (-4.5%) |
| ES known_epoch | 472.288 → 442.373 (-6.3%) / 3576.001 → 3448.001 (-3.6%) | 4646.347 → 3951.916 (-15.0%) / 31014.423 → 28944.011 (-6.7%) |
| Mongo known_terms | 420.778 → 389.687 (-7.4%) / 3472.001 → 3413.334 (-1.7%) | 2900.139 → 2740.651（误差重叠）/ 20640.008 → 20362.674 (-1.3%) |
| Mongo known_histogram | 686.616 → 528.264 (-23.1%) / 7069.335 → 5552.001 (-21.5%) | 7170.779 → 5164.806 (-28.0%) / 78256.019 → 55280.014 (-29.4%) |
| Mongo known_epoch | 1544.990 → 1004.128 (-35.0%) / 17408.004 → 10786.669 (-38.0%) | 21361.054 → 12542.363 (-41.3%) / 243152.058 → 138010.701 (-43.2%) |

known_metric 对照未出现误差不重叠的变慢。`count_only/16` 首轮矩阵在两后端都显示候选较慢，因此用冻结 jar 进行原参数成对复测：ES 信号未重现；Mongo 两次复测方向不一致，且两版本均出现相同的分配模式。随后单列的五对长预热诊断使用 10 × 500ms 预热、10 × 500ms 测量和交替顺序，候选/baseline 比值为 `1.0513`、`0.9707`、`1.0314`、`0.9515`、`1.0920`，均值 `1.0194`。该诊断参数不同于原矩阵，不替换原结果；正负方向都有，未证实稳定变慢或改善，小幅影响仍不确定。

原矩阵另有两处误差不重叠的 ES 分配增加：`count_only/1` 为 `1256.000 → 1277.335 B/op`，`unknown_terms/16` 为 `13333.338 → 13349.338 B/op`。冻结 jar 的原参数成对复测均未重现：前者为 `1256.000376 ± 0.000007 → 1256.000378 ± 0.000005 B/op`，后者为 `13333.337927 ± 6.780181 → 13325.338000 ± 5.125368 B/op`，时间区间也均重叠。原矩阵与 fork 数据继续保留；现有证据不支持把这两处视为稳定分配代价。

最终回归包括 Query 合同 394 项、Mongo 单元测试 275 项、ES 查询单元测试 145 项、Mongo 查询集成 85 项及 ES 查询集成 93 项，共 992 项，均为 0 failure/error/skip；Mongo 与 ES Detekt 通过。Mongo 的 275 项包含全部 165 项查询单测，未重复执行或计数其过滤子集。Testcontainers 集成测试实际执行，未静默跳过。Gradle 验证报告 Gradle 10 弃用提示，单元测试 JVM 报告 CDS 只支持 boot loader class sharing 的提示；这些非失败提示不改变验证结果。

这些性能数字只覆盖 `compile(query, schema)`。B/op 只表示编译期分配，不表示 retained heap；结果不代表数据库查询的端到端加速。目标路径的收益不能扩展成所有场景加速，Mongo `count_only/16` 也不能据现有数据声称零开销或完全排除退化。

## 完成标准与后续

1. MongoDB 每个分组只解析并构造一份输入；ES 同一聚合字段出现位置复用一次 Schema 解析结果。
2. 消除 MongoDB unknown root 的二次 Resolver 调用，保留两种后端各自的日期回退与异常。
3. 输出的 BSON/ES 计划、相关公共合同和真实查询结果通过回归验证。
4. 28 行基准完整对照，报告收益、无收益或代价，不把源码调用次数当作实测时间。
5. 生产变更只涉及两个现有编译器，无模块、依赖、公共接口、Schema 生命周期或生成协议改动。

实施计划已完成，`f3be596f0..ea89fadc3` 已通过最终独立审查。本阶段不因未决的小幅 Count 对照影响引入缓存或新的编译框架。后续工作见[第三阶段：订阅隔离与投影结果合同](2026-09-05-query-execution-contracts-design.md)。
