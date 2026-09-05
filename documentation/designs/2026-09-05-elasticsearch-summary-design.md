# 查询模块第四阶段：Elasticsearch 单次汇总搜索

日期：2026-09-05

状态：范围及无分组汇总拒绝部分结果的行为变化已确认；本书面设计待审阅。

基线：`26fb4ac9c`，Wow `9.0.8`。前置工作见[第三阶段设计与结果](2026-09-05-query-execution-contracts-design.md)。

## 目标与授权边界

Elasticsearch 无分组汇总使用一次原生搜索，省去 PIT 创建和关闭的两次客户端调用。汇总搜索显式设置 `allowPartialSearchResults(false)`，发生原生分片失败时整体报错，避免返回不完整汇总。

用户已明确接受这项失败行为收紧。此前约定保持的公开方法、JSON/token 协议与成功结果合同继续保留；本次例外仅适用于 ES 的无分组汇总，不扩展到分组分页、普通查询或 MongoDB。

正常的单次订阅由 `open PIT → search → close PIT` 改为 `search`。这里的“三次变一次”指这些客户端 API 调用，不包含 Schema 加载、客户端内部重试、网络分片请求或调用方显式 retry。

## 已确认的现状与证据

唯一生产入口为 `AbstractElasticsearchQueryBackend.aggregate`，它编译计划后调用 [ElasticsearchAggregationPager.execute](../../wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt)。Snapshot 和 EventStream 共用该实现。

当前 `execute` 在判断 `groupSources.isEmpty()` 前进入 `pointInTime.use`，因此无分组查询也需要创建、续用和关闭 PIT，但只执行一次搜索。当前 PIT 创建未设置部分结果参数，随后的搜索也未设置该参数。

两者的原生默认策略并不相同：PIT 创建默认拒绝不可用分片，搜索可以按集群默认设置返回部分结果。因此省去 PIT 不是对所有失败情形完全等价的重排。依据：[PIT API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-open-point-in-time)、[Search API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-search-2)、[ES 9.2.6 PIT 请求源码](https://github.com/elastic/elasticsearch/blob/v9.2.6/server/src/main/java/org/elasticsearch/action/search/OpenPointInTimeRequest.java#L41)。

研究阶段在当前 Testcontainers 的 ES `9.2.6` 上使用独立临时索引做了原生 REST 实验。单索引双分片确认结果如下：

| 情况 | 原 PIT 流程 | 单次默认搜索 | 单次严格搜索 |
|---|---|---|---|
| 查询开始时一个主分片不可用 | PIT 创建返回 503 | 返回 200 和部分 count | 返回 503 |
| PIT 创建成功，随后一个分片执行 runtime script 失败 | PIT 搜索返回 200 和部分 count/sum | 原生默认允许部分结果 | 返回 400 |

HTTP 状态是本次实验观察值，不把它固定为跨版本的框架错误协议。script 失败由测试故意注入，用于验证原生策略，不表示 Wow 现有数值脚本必然有同一缺陷。

原始记录在忽略目录 `build/query-summary/native-single-index-probe.json` 与 `native-partial-results-probe.json`；实验创建的索引和 PIT 均已成功清理。未更改已有索引或集群设置，也没有重复运行前三阶段测试。

## 方案选择

采用用户已确认的“单次严格搜索”。保留现有 PIT 可以维持原双阶段行为，但保留两次额外调用；单次默认搜索则会放宽原来对不可用分片的拒绝。为模拟双阶段结果新增探测请求、全局配置或失败回退会增加复杂度，本阶段不采用。

## 生产范围与内部结构

生产改动仅限 [ElasticsearchAggregationPager.kt](../../wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt)。

将 `indexName` 保留为该内部类的私有值。`execute(plan)` 先判断是否无分组：

- 无分组：调用本类的私有搜索函数，不传 PIT Session；响应继续由现有 `summary(plan)` 转换为一行结果。
- 有分组：继续由现有 `pointInTime.use` 包装 `grouped(plan, pit)`，保留现有分页和资源释放。

私有搜索函数复用现有公共请求部分，以可空 PIT Session 表示两条已知路径：

| 请求设置 | 无分组汇总 | 分组分页 |
|---|---|---|
| 查询目标 | `index(indexName)` | 既有 `pit.id` 与 keepAlive |
| `allowPartialSearchResults` | 显式 `false` | 保持既有未显式指定行为 |
| `size` | 0 | 0 |
| `trackTotalHits` | false | false |
| rootQuery / runtimeMappings / aggregation | 复用现有计划和构建逻辑 | 复用现有计划和构建逻辑 |
| 最新 PIT ID 更新 | 无 PIT，不执行 | 保留现有更新 |

没有 Session 时只允许无分组分支调用；所有分组页仍传入 Session。参数只在类内使用，不新增公开模式、配置开关、抽象接口或第二套请求构建器。

保留构造器现有 `batchSize` 与 `keepAlive` 校验，避免因分支变化扩大参数合同。现有 PIT helper 可以继续随 pager 创建；本阶段不增加懒初始化或全局资源缓存。

## 必须保留的执行合同

### 订阅、错误与取消

请求构建与 `client.search` 继续在 `Mono.defer` 中。未订阅时不发送请求；每次订阅、repeat 或 retry 独立发起搜索并产生结果，不复用 Future、响应或可变 JSON 节点。

无分组汇总依靠 ES 原生 `allow_partial_search_results=false` 处理分片失败和搜索超时，不在应用侧复制一套分片/超时校验。原生异常沿现有响应式链传播，失败不转换成空汇总行，也不自动回退到 PIT 或默认搜索。取消交给 Reactor 与客户端，不另外发送不存在的 PIT 清理请求。

分组分页继续保留当前的最新 PIT ID 更新，以及完成、错误和取消时的关闭逻辑。PIT 关闭失败仍按现有逻辑记录日志，不借本次改动改变其错误优先级。

### 索引与别名

使用原 `indexName`，不把 Schema mapping 解析得到的物理索引名替换进搜索请求。现有命名与 Factory 允许该名称是单物理索引 alias；别名过滤和 search routing 继续由 ES 原生处理。普通索引、别名目标、缺失或关闭索引的错误沿既有客户端通道传递，不增加宽松的索引选项。

单次汇总读取搜索执行时的可见数据，不承诺跨请求快照；分组多页仍需要 PIT。没有新增时间点参数或快照 token。

### 聚合与结果

复用 `plan.aggregation(...)`、`summary(plan)` 和现有 JSON 归一化，保留 rootQuery、runtime mappings、所有 nested/filter 层级及指标构造。

- 无 `elements` 时 COUNT 使用根文档作用域；存在 `elements` 时使用最内层展开元素作用域。
- 搜索成功且为空输入时仍返回一行：COUNT=0、ANY=null、数值指标=null。
- 有效数值贡献计数、非有限数值拒绝、ANY 的原生选择语义、字段别名和列名保持不变。
- 分组排序、composite afterKey、分页容量和有界 Top-N 全部保持原实现。

不改 Gateway、Schema 准入、权限/脱敏、编译器、Query AST、Spring wiring、MongoDB、存储布局、公开 API、生成协议、依赖或版本号。

## 测试与验收依据

### 聚焦单元合同

扩展现有汇总测试或增加聚焦测试类，使用实际编译计划和受控客户端响应：

1. 正常无分组汇总捕获 SearchRequest，断言原 `indexName`、无 PIT、`allowPartialSearchResults=false`、size=0、trackTotalHits=false，以及原 rootQuery/runtime/aggregation 内容。
2. 未订阅零调用；一次订阅只调用一次 search，open/close PIT 均为零。原实现会在此合同失败，从而提供最小 RED 证据。
3. 根级与嵌套汇总保留数据、COUNT、空输入和数值归一化；已有合同直接复用，不重写算法作为测试 oracle。
4. 客户端失败原样传播；同一 Publisher 重复订阅和 retry 获得独立请求/结果；取消传播到当前搜索，且不触发 PIT 操作。测试桩不能自己在每次订阅新建响应来掩盖生产的延迟边界。
5. 复用并加强有分组对照，断言请求仍携带 PIT、部分结果参数未被汇总分支覆盖，afterKey 与最新 ID 关闭路径不变。

不新增测试框架、公共 fixture、Detekt suppression 或执行服务。阶段计划根据现有测试类大小选择同文件独立类或聚焦新文件。

### 真实后端验证

使用现有 ES Testcontainers 与查询集成测试。Snapshot/EventStream 已有根级、嵌套、全部指标、空汇总和 JSON 类型合同，继续执行这些覆盖。

新增范围集中于变化本身：

- 单索引 alias 的 filter/search routing 仍有效；通过原逻辑名称发起汇总，断言实际计数和指标。
- 单索引两个主分片，其中一个不可用：无分组严格搜索报错，不产生汇总行。
- 两个分片都可用，但一个分片在查询期发生故意注入的执行错误：拒绝原先可返回的部分汇总。

失败注入仅作用于测试创建的资源，例如该索引的分配限制或测试专用 runtime script；不修改集群级设置、不破坏已有索引。所有索引/PIT/客户端资源必须由 fixture 或 finally 清理，使用有界状态等待，不用固定 sleep 假定分片就绪。

这些测试应调用生产 pager/Backend，而不是只断言原生 REST 的参数行为。原生实验已经证明默认差异，实施测试进一步证明修复路径实际采用了新策略。无须在 CI 中反复复制整个旧 PIT 实现作为对照。

### 检查与报告

先执行能触发旧实现失败的汇总单测，再实现最小变更；之后完成 ES 查询单测、Detekt 和真实查询集成。已通过且对应代码未变化的检查不重复执行。Detekt 自动格式化产生的改动纳入审查。

性能验收是确定性的客户端调用数变化：成功的无分组单次订阅由 3 次变为 1 次，分组分页调用/释放合同不退化。本阶段不新增 JMH 矩阵，不把减少两次调用表述为固定延迟或吞吐改善百分比。

实现阶段同时更新[中文聚合文档](../docs/zh/guide/query/aggregation-query.md)与[英文聚合文档](../docs/en/guide/query/aggregation-query.md)，说明 ES 无分组汇总拒绝部分结果，区分成功空输入与查询失败，并明确这不是 MongoDB 或所有 Backend 的统一策略。文档中无需暴露私有 helper 等实现细节。

验证日志、请求计数与实验产物放在忽略目录 `build/query-summary/`。最终报告列明实际命令、测试数、参数和行为变化；不提交生成输出。

## 完成标准

1. ES 无分组汇总只发起一次独立搜索，零 PIT 创建/关闭，原生部分结果参数显式为 false。
2. 正常与空输入、根级与嵌套、Snapshot 与 EventStream、逻辑索引 alias 的成功结果合同通过验证。
3. 不可用分片与查询期分片执行失败均不产生部分汇总；错误、retry、订阅隔离和取消合同成立。
4. 分组查询仍沿用原 PIT 分页、afterKey、排序和完成/错误/取消释放逻辑。
5. ES 相关单测、Detekt、真实集成与文档检查通过；生产改动只有一个现有 pager 文件。

书面设计确认后使用 `writing-plans` 制定计划，沿用已选择的子代理实施与独立审查流程。其他后端或查询形态的部分结果策略仍需单独依据和授权。
