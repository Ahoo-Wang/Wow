# wow-bi 重构设计

日期：2026-10-09。状态：已确认（2026-10-09），待实施。

依据：对 `wow-bi`（`main` 代码 8.5k 行、测试 11.3k 行）和唯一调用方 `wow-spring-boot-starter` 的 `bi` 包做的只读审查，基于 `main` `41913a1e5`。文中路径都相对 `wow-bi/src/main/kotlin/me/ahoo/wow/bi/`，行号指该提交。

## 1. 第一性原理：这个模块是什么

`wow-bi` 是一个**声明式的 ClickHouse 布局协调器**。输入是聚合元数据与 `BiScriptOptions`，可选输入是 ClickHouse 现状；输出是一份让 ClickHouse 收敛到期望布局的 SQL 脚本。它本身不执行 SQL。

天然的流水线只有四步，每一步一个职责：

```
期望布局（纯函数）→ 观测现状 → 逐对象比较并决定动作 → 按动作渲染有序 SQL
   Layout             Observe           Plan                 Render
```

必须守住的不变量：

1. **摄入不丢消息。** 不在 Kafka 引擎轮询窗口内摘掉在用的 consumer（#4028 的根因）；`state_last` 只在 state 摄入暂停时重建。
2. **DEPLOY 不删数据。** store 只会创建、保留或退役，绝不被 DEPLOY 删除。
3. **可恢复。** 每条语句可重跑；中断后用同一配置重新 inspection 并生成，即可收敛。持久对象丢失时失败关闭，要求显式 RESET（§4.1）。
4. **没有兼容性负担。** 用户 2026-10-09 确认：`wow-bi` 是 BI 同步脚本生成模块，不考虑向前兼容，不留债务。对象名、comment 元数据、registry、consumer group、Keeper 路径、公开 Kotlin API 都只按当下的设计质量取舍：不写迁移代码，不为旧版本做失败关闭，不保留弃用入口。代价写进发布说明：升级后既有 BI 部署执行一次确认过的 RESET，从 Kafka earliest 重建。新代码遇到不认识的 layout 时要求 RESET——这是正确性检查，不是兼容代码。

## 2. 诊断

问题不在单个函数写得差，而在**同一事实在多处决定**。按根因归为六类：

| # | 根因 | 证据 | 后果 |
|---|---|---|---|
| B1 | **布局没有单一模型**：对象名、后缀、engine、store 列定义各写一遍 | 后缀字面量（`_store/_queue/_consumer/_local/_event`）出现在 10 个文件，包括 `ClickHouseBiDeploymentValidator.kt:179` 用 `removeSuffix("_queue") + "_consumer"` 反推名字；engine 字符串散在 5 个文件（`BiObservedDeploymentPolicy` 9 处、`BiPreparationPlanner` 6 处）；store 列定义一份写在三个 renderer 的 `renderStore` DDL 里（`ClickHouseCommandRenderer.kt:83` 等），另一份写在 `ClickHouseStoreShapeValidator.kt:203-242` 的 `commandStoreColumns/stateStoreColumns` | 加一列、改一个名字要同步 N 处；漏一处就是 DEPLOY 判错 shape 或 drift |
| B2 | **"对象变没变"决定三次** | ① registry 计划用 definition fingerprint 决定 `PENDING_UPDATE`（`BiOwnershipRegistryPlan.kt`）；② inspector 的 validator 用 catalog 比对出 drift（`ClickHouseBiDeploymentValidator.kt:102`）；③ renderer 在 RECONCILE 模式下无条件 drop+create（三个 renderer 共 14 处 `catalogMutationMode`/`isRetained` 分支） | 没有逐对象的"动作计划"。#4028 只能把 `retainedConsumerKeys` 一路穿到 render context；诊断信息另算；三处判定理论上可能不一致 |
| B3 | **期望定义和 DDL 两条生成路径** | `expectedComputedQueries`（`renderer/ClickHouseScriptRenderer.kt:122`）与 `render` 各自调用，只靠共用 SELECT builder 维持一致；`BiPreparationPlanner` 用一个默认身份的 renderer 只为算期望 SQL（`BiPreparationPlanner.kt:22`） | 验证的东西和执行的东西不是同一个对象 |
| B4 | **输出顺序维护两遍** | `BiScriptAssembly.kt:154` 拼 `statements` 顺序，`:167` 再按另一套代码拼 `script` 文本，DEPLOY/RESET 各一份 | `script` 与 `statements` 有分叉风险；`assemble` 一个函数约 200 行 |
| B5 | **包边界与职责按"谁调用"而不是按概念切** | 根包 ↔ `renderer` 互相依赖（根包 4 个文件引用 `ClickHouseScriptRenderer.` 的常量）；`expansion.plan` ↔ `expansion.type` 互相依赖（`PropertyFilter`）；对象命名 `BiTableNaming` 放在 `expansion`；观测校验拆成 inspector 侧（store shape、queue 身份、drift）和 generator 侧（归属、engine、拓扑、anchor、配置）两份，engine 接受规则各写一遍；`BiDeploymentInspection.kt` 一个文件放了 SPI、结果、异常、观测模型、元数据与 codec、consumer 身份、部署描述 7 个概念；SHA-256 摘要实现 4 份（`BiFingerprint.kt:19`、`BiDeploymentInspection.kt:335`、`BiOwnershipRegistry.kt:208`、`expansion/plan/StateExpansionNames.kt:48`），digest 正则 4 份 | 改一条规则要先找全所有副本 |
| B6 | **编排逻辑在 starter，入口重复** | `GenerateBIScriptHandlerFunction` 自己串 `prepare → inspect → generate`，自己管有界调度器和过载映射；`BiScriptGenerator.generate(namedAggregates, …)` 与 `prepare + generate(preparation, …)` 两条入口；`BiScriptPreparation` 必须携带 options 并在使用时做相等检查（`BiScriptGenerator.kt:84`）；renderer 门面有只给测试用的方法（`renderCommandStorageStatements` 等、`renderExpansionStatements(aggregate = "test.aggregate")`），每次调用都重算整份 render | 换一个调用方（CLI、批处理）就要复制编排；测试驱动了生产 API 的形状 |
| B7 | **catalog I/O 异步→同步→异步绕一圈** | ClickHouse client v2 本身返回 `CompletableFuture`，`NativeClickHouseCatalogClient` 在专用线程池里阻塞 `get()`，再用线程中断加一个 4 个原子字段的 `QueryResponseLifecycle` 状态机（`ClickHouseCatalogClient.kt:335-436`，约 90 行有效代码）处理取消和迟到响应的关闭；`ClickHouseBiDeploymentInspector` 又把整段阻塞读包回 `Mono`，并维护自己的调度器、超时与 7 个 `catch` 分支（`ClickHouseBiDeploymentInspector.kt:79-152`） | 并发正确性靠手写状态机保证，难以审查；取消路径要同时理解中断、future 和状态机三者 |

做得好的部分保留不动：拓扑方言策略（`ClickHouseTopologyDdl`）、状态展开规划（`expansion/plan`）、类型映射（`type/`）、catalog 客户端的取消与超时处理。`JsonPropertyTypeResolver`（727 行）是内聚的单一关注点，只按职责拆文件，不改逻辑。

## 3. 目标架构

```
me.ahoo.wow.bi
├── BiScriptGenerator / BiScriptOptions / BiScriptResult / BiScriptOperation   公开门面（不变）
├── layout/     BiLayout：唯一的期望布局模型（纯函数，无 SQL 方言以外的依赖）
├── catalog/    观测：CatalogClient、CatalogReader → ObservedCatalog；Verifier → 每对象 Observation
├── plan/       Reconciler：(BiLayout, Observation, Registry, Operation) → BiChangePlan
├── render/     SqlRenderer：BiChangePlan → 有序 Section → script 与 statements
├── expansion/  状态展开与类型解析（逻辑不变，解环）
└── type/       ClickHouse 类型映射（不变）
```

依赖方向单向：`layout ← catalog ← plan ← render`，`expansion/type` 只被 `layout` 使用。用一个 ArchUnit 风格的包依赖测试守住（仓库已有架构测试的做法，见 9.3.0 设计 G3）。

### 3.1 `layout`：唯一的事实来源（解决 B1、B3）

`BiLayout.of(options, plannedAggregates)` 产出每个聚合的对象图：

```kotlin
internal sealed interface BiLayoutObject {
    val key: BiObjectKey; val kind: BiObjectKind; val aggregate: String?; val engine: String
}
internal data class StoreObject(…, val columns: List<StoreColumn>, val engineSpec: ReplacingMergeTreeSpec, …)
internal data class QueueObject(…, val topic: String, val consumerGroup: String, …)
internal data class ConsumerObject(…, val source: BiObjectKey, val target: BiObjectKey, val select: String)
internal data class ViewObject(…, val select: String)
internal data class IngressChain(val queue: QueueObject, val consumers: List<ConsumerObject>)  // command 链、state 链
```

- 名字、后缀、engine、store 列只在这里定义。`ClickHouseStoreShapeValidator` 改为读 `StoreObject.columns`，renderer 的 `renderStore` 也读同一份列表。
- `select`/`target` 是期望定义本身：catalog 的 drift 比对与渲染出的 DDL 都从同一个 `ConsumerObject`/`ViewObject` 来，B3 的两条路径合成一条。
- `IngressChain` 显式表达"queue → consumer → store → state_last consumer"的依赖，#4028 的"整链保留"规则从隐式知识变成数据。
- 对象名的计算全部收进 layout，`BiTableNaming` 移入 `layout`。

### 3.2 `catalog`：观测与验证按概念合并（解决 B5 的校验拆分）

`CatalogReader` 只负责读，产出 `ObservedCatalog`（对象、列、anchor）。`BiCatalogVerifier` 把两份校验合成一份，对每个期望对象给出一个结论：

```kotlin
internal sealed interface Observation {
    data object Missing; data object Verified
    data class Drifted(val fields: Set<BiComputedDefinitionField>)
    data class Incompatible(val reason: String)        // 外来对象、engine 不符、shape 不符 → 直接失败
}
```

原先 generator 侧的 `BiObservedDeploymentPolicy`（归属、拓扑、anchor、配置指纹）与 inspector 侧的 validator（store shape、queue 身份、drift）按规则归入 verifier，engine 接受规则只写一次。`BiDeploymentInspection.Available` 携带 verifier 结果；观测模型是 `internal` 的，结果只能来自本模块的 inspector，生成器不再二次校验（§7 Q2）。

`CatalogReader.read`（`ClickHouseCatalogReader.kt:25-305`）与 `validateClusterCatalog` 拆成 standalone/cluster 两个读取器（registry 读取随 R1 删除），每个函数不超过 detekt 默认阈值。

catalog I/O（B7）：读取逻辑保持顺序式的同步代码（多步查询顺序依赖，同步写法最易读），仍在有界调度器上运行；单次查询只做"提交 future → 等待 → 解码 → 关闭"。取消时 `future.cancel` 并登记 `whenComplete { response?.close() }` 关闭迟到的响应，取代 `QueryResponseLifecycle` 状态机与线程中断。异常映射收进一个 `toInspectionException()`，inspector 的 `catch` 分支合并为一处。

### 3.3 `plan`：一处决定（解决 B2）

`BiReconciler.plan(layout, observations, anchor, operation)` 产出：

```kotlin
internal data class BiChangePlan(
    val actions: Map<BiObjectKey, BiAction>,   // Keep | Create | Replace | Drop | Retire
    val pausedChains: Set<IngressChain>,       // 链上任一 consumer 需要 Create/Replace 时整链暂停
    val inventory: DurableInventory,           // 写进 anchor 的持久对象清单（§4.1）
    val diagnostics: List<BiScriptDiagnostic>,
)
```

- 规则写在一处，例如：`Verified` 的 consumer → `Keep`；`Drifted` → `Replace`；链上有 `Replace/Create` → 整链进 `pausedChains`；未期望的 store → `Retire`，其他 → `Drop`；RESET → 全部 `Drop` 后 `Create`。
- drift 诊断与持久对象清单都从 `actions` 推出，不再各自判断。
- 这一层是纯函数，单元测试直接断言 `actions`，不用解析 SQL 字符串。

### 3.4 `render`：渲染器只翻译（解决 B4、B6 的渲染部分）

- renderer 不再知道 `CatalogMutationMode`、retained 集合：输入是一个对象和它的动作，输出语句。三个 per-stream renderer 的 14 处模式分支消失。
- `ScriptLayout` 定义唯一的 section 顺序（DEPLOY、RESET 各一张表），`statements` 与 `script` 都从同一个 `List<Section>` 派生，B4 的双份顺序消失。
- 测试专用的门面方法删除，测试改为针对 `BiChangePlan` 或单个 renderer。

### 3.5 门面与 starter（解决 B6 的编排部分）

- `BiScriptGenerator` 保持现有公开签名。
- 新增 `BiScriptService`（见 §7 Q3）：`fun generate(namedAggregates, operation): Mono<BiScriptResult>`，内部完成 prepare → inspect → generate、有界调度器和过载映射。starter 的 handler 只做 HTTP 映射（请求体、媒体类型、响应头），REST 行为逐字节不变。
- `BiDeploymentInspection.kt` 按概念拆成 `BiDeploymentInspector.kt`（SPI 与结果）、`BiObjectMetadata.kt`（元数据与 codec）、`BiDeploymentIdentity.kt`（descriptor 与 consumer 身份）、`BiDeploymentInspectionException.kt`；SHA-256 与 digest 校验收成一个 `BiDigest`。

## 4. 存储模型：只保留必要的事实

没有兼容负担后，持久在 ClickHouse 里的元数据只按"生成器下次运行需要知道什么"来设计。

| 事实 | 为什么需要 | 放在哪里 |
|---|---|---|
| 某对象归这个部署所有 | DEPLOY 只能删除自己的对象，不能按表名猜 | 每个对象的 comment：`deploymentId`、`kind`、`aggregate`、`layout` |
| 部署的配置、拓扑、consumer 身份、阶段（`STABLE`/`RESETTING`） | 配置或拓扑变化必须走 RESET；RESET 中断后用同一身份续做 | 只在 anchor 的 comment 里，每个对象不再重复 |
| 持久对象（store、queue）曾经存在过 | store 或 queue 丢失时失败关闭，而不是悄悄建空表（store 丢历史数据，Keeper 模式的 queue 丢 offset） | anchor 的 `durableInventory` |

据此：

- **删除 ownership registry 表**及其 6 态状态机、write-ahead 快照、shape 校验、渲染与读取（约 1000 行），以及 DEPLOY 的 intent/confirmation 两个 section。计算对象（view、consumer MV）缺了就重建，本来就安全，registry 为它们记录的状态和定义指纹与观测重复，是"对象变没变"的第三个判定源（B2）。
- **精简对象 comment**：去掉每个对象上重复的 `configurationFingerprint`、`topologyFingerprint`、`consumerIdentity`、`phase`；只保留归属与 `layout`。protocol 与 layout 两个版本号合成一个 `layout`。
- **幂等 DEPLOY 不再输出 `Keep` 对象的语句**（包括今天的 `CREATE OR REPLACE VIEW`），计划驱动渲染下规则统一。
- **对象名、consumer group、Keeper 路径不改**：现在的命名没有设计问题，改名只增加一次无收益的变化。

### 4.1 持久对象清单的判定规则

anchor 在脚本最后写入，晚于所有持久对象的创建。规则全部在 `BiReconciler` 一处：

| 观测 | 在清单中 | 动作 |
|---|---|---|
| 期望的持久对象缺失 | 否 | `Create`，写入清单 |
| 期望的持久对象缺失 | 是 | 失败关闭，要求 RESET |
| 期望的持久对象存在 | 否 | 收入清单（中断后重跑） |
| 未期望的 store | 任意 | `Retire`（保留数据），清单记 `RETIRED` |
| 未期望的 queue | 任意 | `Drop`，从清单移除 |
| 计算对象 | — | 只看观测：`Missing`→`Create`，`Drifted`→`Replace`，`Verified`→`Keep` |
| anchor 的 `layout` 不是当前值，或对象 comment 无法解码 | — | 失败关闭，要求 RESET |

中断恢复的论证：每条语句可重跑；持久对象先建后记，清单只会落后、不会超前，落后由第三行补上。原 registry 的 `PENDING_*` 状态因此不再需要。RESET 删除本部署拥有的全部对象后按当前 layout 重建；它的 `STABLE` anchor 写在 Kafka ingress 之前，因此只记录 store，随后的 DEPLOY 再把 queue 记入清单，清单同样不会超前。

## 5. 实施阶段

顺序的原则：先删掉不需要的东西，再重构剩下的，不把要删的代码移植进新结构。每个阶段一个 PR，合并前跑 `:wow-bi:check :wow-bi:integrationTest detekt` 和 starter 的 bi 测试。

| 阶段 | 内容 | 输出是否变化 | 证明 |
|---|---|---|---|
| **R0 护栏** | golden：standalone/cluster × {首次部署、幂等重部署、view drift、consumer drift、未期望对象清理、RESET、RESETTING 续做}；包依赖测试（先把现有两个环记为已知例外） | 否 | 新快照即基线 |
| **R1 存储模型** | §4：anchor 清单、精简 comment、删除 registry 全部代码；运维手册（中英文）改写 | 是 | golden 按新模型更新；集成测覆盖"持久对象丢失→要求 RESET""中断后重跑收敛""旧 layout→要求 RESET" |
| **R2 layout** | `BiLayout` 成为名字、engine、列、期望定义的唯一来源；`BiTableNaming` 移入；解 `expansion` 环 | 否 | golden 逐字节一致 |
| **R3 verify** | `BiCatalogVerifier` 合并两份校验；拆 `CatalogReader`；catalog I/O 去掉状态机（B7）；`BiDigest` | 否 | golden + inspector 单测/集成测（含取消、超时、迟到响应关闭） |
| **R4 plan + render** | `BiReconciler`、`BiChangePlan`；renderer 只翻译动作；单一 section 顺序；`Keep` 不出语句；删测试专用门面；解根包 ↔ renderer 环，包依赖测试去掉例外 | 是（幂等 DEPLOY 变短） | 结构部分逐字节对比后再切换 `Keep` 规则并更新 golden；集成测验证幂等部署后对象 UUID 全部不变；Kafka/ClickHouse 集成测连跑 10 次 |
| **R5 门面** | `BiScriptService` 收拢编排，starter 只做 HTTP 映射；删除 `generate(namedAggregates, …)`；观测模型收为 `internal`，删除生成器的二次校验；拆 `BiDeploymentInspection.kt` | 否（REST） | starter 测试 + ABI diff |

## 6. 不做的事

- 不改对象命名、consumer group、Keeper 路径：没有设计问题，改了没有收益。
- 不改状态展开算法与类型映射：审查没发现问题，改动只会扩大 golden 差异。
- 不为"consumer 定义变化时的 Kafka2 竞态"引入无缝替换（先建新一代 consumer 再删旧的）。command/state consumer 的 SELECT 只抽取消息信封字段，与聚合的状态结构无关；`state_last` consumer 是 `SELECT *`。它们的定义只会因 wow-bi 自身升级或人为篡改而变化，而无缝替换要引入 consumer 代际命名，并在重叠期向非 `FINAL` 的公开 view 暴露重复行。为罕见事件增加常驻复杂度，不值得。

## 7. 决定

| # | 问题 | 决定 |
|---|---|---|
| Q1 | 用 anchor 的持久对象清单替代 ownership registry | 替代（用户 2026-10-09 确认）；§4.1 保留了 registry 唯一不可替代的安全性质 |
| Q2 | 观测模型（`ObservedBiDeployment`、`ObservedBiObject`、`BiObjectMetadata`、`BiObjectMetadataCodec`、`BiDeploymentDescriptor`、`BiConsumerIdentity`）收为 `internal` | 收窄（用户 2026-10-09：无兼容负担）；`BiDeploymentInspector` 只有 ClickHouse 与 NoOp 两个实现 |
| Q3 | 新增 `BiScriptService`，编排移进 `wow-bi` | 新增 |
| Q4 | 重复入口 `generate(namedAggregates, …)` | 直接删除，不走弃用周期 |
| Q5 | 发布版本 | 9.4.0（用户 2026-10-09 确认）；发布说明写明"升级后执行一次 RESET" |

## 8. 预估

按有效代码行（去掉空行、注释、`import`/`package`）统计，现状来自 `41913a1e5`。

| 区域 | 现状 | 目标 | 主要来源 |
|---|---:|---:|---|
| ownership registry | 509 | 0 | 整体删除 |
| catalog 读取、client、inspector | 1586 | ~1150 | 删 registry 读取（~200）、状态机（~90）、合并异常映射 |
| 观测校验 | 793 | ~520 | 两份校验合一，engine 与列规则读 layout，comment 精简后少了混合指纹检查 |
| 模型与 inspection | 442 | ~360 | 去掉 registryRevision、重复摘要与正则 |
| layout（新） | — | ~250 | 吸收 `BiPreparationPlanner` 与各处名字、engine、列 |
| 编排：generator、assembly、planner → reconciler、section 表 | 645 | ~380 | 双份顺序合一，registry section 删除，暂停规则改为数据 |
| renderer | 951 | ~780 | 去模式分支、去 `expectedQueries` 第二条路径、去测试专用门面 |
| expansion + type | 1756 | ~1740 | 只解环 |
| `BiScriptService`（自 starter 移入） | — | ~80 | starter 的 handler 相应减少约 60 |
| **合计** | **6682** | **~5250** | 约 −21%；不计未改动的 expansion/type，协调核心从 ~4900 降到 ~3500（约 −29%） |

测试：registry 相关测试（~650 行）删除；新增 golden 场景与 reconciler 的纯函数测试，总量大致持平，但断言从"解析 SQL 字符串"转向"断言动作计划"。

复杂度指标（实施完成后逐项核对）：

| 指标 | 现状 | 目标 |
|---|---|---|
| "对象变没变"的判定处 | 3 | 1（`BiReconciler`） |
| 对象名后缀 / engine 字面量所在文件 | 10 / 5 | 1 / 1（layout） |
| store 列定义份数 | 2 | 1 |
| 包循环 | 2 | 0（包依赖测试守住） |
| 持久化状态机状态数 | 6（registry） | 2（清单 `ACTIVE`/`RETIRED`） |
| 输出顺序的定义份数 | 2 | 1 |
| SHA-256 摘要实现 | 4 | 1 |
| 手写并发状态机 | 1 | 0 |
| renderer 内的生命周期分支（mode / retained） | 14 | 0 |
| 最长函数 | `CatalogReader.read` ~280 行 | 不超过 detekt 默认阈值，去掉 `LongMethod`/`CyclomaticComplexMethod` 抑制 |
