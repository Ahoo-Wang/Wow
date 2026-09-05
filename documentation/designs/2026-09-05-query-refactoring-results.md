# 查询模块重构交付记录

范围从 `4a64789b6` 到生产代码 `90467dd0f`，覆盖共享查询解析、MongoDB/Elasticsearch 编译和执行。Spring、WebFlux 与公开查询契约经过兼容性验收，职责仍在原模块内。未增加依赖、模块或公开 API。

## 原始修订来源

本记录引用的原始本地验收与 JMH 修订由永久证据分支 [`evidence/query-refactoring-2026-09-05`](https://github.com/Ahoo-Wang/Wow/tree/evidence/query-refactoring-2026-09-05) 保留。原生 Stack rebase/squash 产生的 head 是后续版本，不能替代实际测量所用的以下修订：

| 角色 | 原始完整 SHA |
|---|---|
| 基线 | `4a64789b627acc26f93fe3725df02d3a38a27ebd` |
| 验收生产代码 | `90467dd0f1cdc02d8192c55e9585876bfe7b6208` |
| 真实数据库 JMH 候选 | `f096ec5772ecf749f1c5cd470b50d4df07be09f3` |
| 归档 tip（含后续可维护性修复） | `285587f542a08d924b0447e600cbdb6c6a673ab6` |

单分支克隆可显式获取证据分支并核对预期归档 tip：

```sh
git fetch origin refs/heads/evidence/query-refactoring-2026-09-05:refs/remotes/origin/evidence/query-refactoring-2026-09-05
git show -s --format=%H refs/remotes/origin/evidence/query-refactoring-2026-09-05
```

第二条命令预期输出 `285587f542a08d924b0447e600cbdb6c6a673ab6`。该分支保留可解析的公开源码修订；本记录提到的本地忽略 XML、JSON、日志、冻结 jar 等产物未随此次来源修正发布。

## 行为与实现

| 改动 | 结果 |
|---|---|
| 动态字段解析 | 按能力建立声明字段的反向索引；逐级寻找路径祖先，消除每次查询的全字段扫描。保留最深匹配、路径校验和元素作用域规则。 |
| 查询兼容性与聚合编译 | 直接累计兼容性等级；在一次编译中复用字段绑定、分组过滤与日期表达式，减少重复解析和临时集合。 |
| ES 游标订阅 | 每次订阅新建客户端搜索，支持同一 Publisher 的 repeat/retry 与取消隔离。 |
| Mongo 查询投影 | 允许投影结果没有 `_id`；已有但非法或为 null 的标识仍报错。存储转换合同不变。 |
| ES 无分组汇总 | 一次直接搜索完成，不再打开和关闭 PIT；保留原索引或别名。分组聚合仍使用 PIT。 |
| 游标组装 | Mongo 每页准备一次隐藏字段清理路径；ES 首次构建排序即配置缺失值顺序。保持 token、并列顺序和投影规则。 |

ES 无分组汇总有一项已确认的行为收紧：显式使用 `allow_partial_search_results=false`，部分分片失败时返回错误，避免把不完整统计作为成功结果。调用方应保留错误处理；本次不提供部分结果模式。中英文聚合查询指南已同步。

## 累计验收

在生产代码 `90467dd0f` 上运行：

```sh
./gradlew :wow-query:check :wow-mongo:check :wow-elasticsearch:check \
  :wow-webflux:check :wow-spring:check :wow-spring-boot-starter:check --console=plain
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --console=plain
```

| 模块 | 单元及本地合同测试 | 数据库集成测试 |
|---|---:|---:|
| wow-query | 394 | — |
| wow-mongo | 288 | 170 |
| wow-elasticsearch | 222 | 143 |
| wow-webflux | 321 | — |
| wow-spring | 19 | — |
| wow-spring-boot-starter | 237 | — |
| 合计 | 1481 | 313 |

共 1794 项，失败、错误和跳过均为零，模块检查与 Detekt 通过。`check` 不包含数据库 `integrationTest`，两者分别运行、分别计数。原始 XML 与日志保存在本地忽略目录 `build/query-delivery/acceptance-xml/` 和 `acceptance-summary.json`。

这是分层验收：HTTP 测试覆盖进程内路由，Spring 测试覆盖注册，数据库 TCK 大多直接调用 Backend；另有真实 Gateway→Mongo 的投影与脱敏测试。未运行一次网络 HTTP→Spring 自动注册→Gateway→真实数据库的完整链路测试，也未运行外部 CI 或生产负载。

## 局部性能证据

下列结果来自各阶段同源 JMH 对照，只描述对应输入和测量边界：

| 场景 | 观察结果 |
|---|---|
| 2048 个静态字段、1 个动态根的物理路径 miss | 8582→278 ns/op。 |
| 16 个已知 epoch 字段的聚合编译 | Mongo 耗时减少 41.3%、分配减少 43.2%；ES 耗时减少约 15.0%。 |
| Mongo 4 个嵌套隐藏字段的游标页清理 | 10/1000 行耗时减少 44.5%/48.6%，分配减少 45.5%/58.1%。包含输入 Document 构造和 token 编码，不含数据库及 ObjectNode 转换。 |
| ES 游标请求与排序组装 | flat/nested、2/16 字段四组耗时减少 12.9%–34.3%，分配减少 5.4%–29.7%。未订阅或访问数据库。 |

动态反向索引也有成本：128 个动态字段加 32 个静态字段的 Schema 构造约增加 36,968 B/次，完整矩阵耗时增加 20.6%，定向复测增加 26.1%。2048 个静态字段加 1 个动态根的构造耗时增加 20.8%，定向复测增加 9.0%。这里测的是分配，不是 retained heap。少量 count-only 聚合的变化仍不确定；无隐藏字段的 Mongo 游标控制组耗时区间重叠。不能据此宣称所有查询加速或完全没有回退。

ES 排序初版曾使普通 nested 排序变慢约 20%；一次私有 inline 模板细化后，最终对照的普通排序分配保持相同，未再出现明确回退。初版和最终数据均保留。阶段报告与原始数据位于 `build/query-resolution/`、`build/aggregation-compiler/`、`build/query-cursor-cleanup/`、`build/query-cursor-sort/`；前两阶段的详细结果也记录在对应实施计划中。

## 真实数据库性能

使用现有 `QueryGatewayBackendBenchmark`，显式选择 `cursor100,summary` 与 `result=dynamic`，默认参数矩阵保持不变。基准补充固定类型 Schema，以满足游标排序的既有 EXACT/SINGLE 合同；保留 `COMPATIBLE`。首次预检因此失败，修正夹具后四组通过；失败日志单独保留，没有作为性能样本。

基线为 `4a64789b6` 加同一基准源码；候选为 `f096ec577`，生产代码仍为 `90467dd0f`。两版源码和 benchmark class 的 SHA-256 完全一致，冻结 jar、补丁和完整运行清单保存在 `build/query-delivery/performance/`。

- 环境：Apple M4 Pro、24 GiB、macOS 26.6.2、Zulu JDK 17.0.7；任务专属 MongoDB 7.0.40 与 Elasticsearch 9.2.6 容器。验收集成测试采用仓库既有 MongoDB 6.0.6/ES 9.2.6，不能将两个 Mongo 版本混写。
- 负载：1000 条固定种子快照；游标首屏返回 100 条，仅投影 payload，按 group 加身份字段稳定排序；无分组汇总为 count 和 sum(group)。Setup 验证完整 payload 顺序、隐藏字段、nextCursor 及汇总 1000/7468。
- 参数：单线程、3 forks、256 MiB 客户端堆、5×1s 预热、10×1s 测量，JMH SampleTime 与 GC profiler。固定顺序为基线→候选，未并行运行其他本任务构建或测试。
- 边界：Gateway→Schema/Backend→数据库→动态结果；游标包含 nextCursor 编码，不含续页解码。播种、Setup 检查、teardown、HTTP 和 Spring 自动注册不在测量内。分配量只测客户端 JVM，不包含数据库进程。

| 后端 / 操作 | 基线均值 ± 误差 ms | 候选均值 ± 误差 ms | 均值变化 | P95 ms | 客户端 KiB/op | 均值区间 |
|---|---:|---:|---:|---:|---:|---|
| elasticsearch / cursor100 | 1.396 ± 0.010 | 1.341 ± 0.007 | -3.9% | 2.060 → 1.831 | 226.7 → 226.0 | 候选更低 |
| elasticsearch / summary | 1.405 ± 0.011 | 0.455 ± 0.002 | -67.6% | 2.050 → 0.595 | 115.1 → 52.9 | 候选更低 |
| mongo / cursor100 | 1.397 ± 0.009 | 1.437 ± 0.009 | +2.9% | 1.997 → 1.956 | 261.7 → 227.4 | 候选更高 |
| mongo / summary | 1.535 ± 0.009 | 1.535 ± 0.008 | 约 0% | 2.005 → 1.939 | 70.0 → 69.8 | 重叠，方向不确定 |

误差为 JMH 的 99.9% 区间，描述本轮样本，不代表跨运行环境波动。ES 汇总在本轮平均耗时减少 67.6%，与省去 PIT 打开和关闭相符；ES 游标均值小幅降低，Mongo 汇总的均值区间重叠。Mongo 游标首轮均值增加 2.9%，但 P95 与客户端分配下降，需要单独保留这个信号，不能以局部清理基准收益替代它。

针对 Mongo 游标，仅追加一次候选→基线的反向复核，参数和冻结 jar 不变：基线 `1.579 ± 0.023 ms`、候选 `1.428 ± 0.007 ms`，均值变化转为 -9.6%；P95 为 `2.417→1.815 ms`，客户端分配减少 12.8%。基线在两轮间从 1.397 漂移到 1.579 ms，而候选为 1.437→1.428 ms。因此无法确认稳定的 Mongo 游标延迟改善或回退；两轮一致支持约 13% 的客户端分配减少。原四组表不被复核覆盖，也不继续重复测量择优。

完整均值、P50/P95/P99、区间和分配见本地产物 `comparison.json`、`confirmation-comparison.json`；原始 JSON、日志、执行顺序、命令、版本、源码与 jar 校验值一并保留。本轮不据此宣称生产吞吐或并发、长尾负载的改善。
