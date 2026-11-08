# 数据权限：访问属性与访问策略

日期：2026-11-02。基线：`origin/main` `647b361116`。状态：已实施（D390，用户 2026-11-02，含历史跟随当前属性；A～H 全部合并，2026-11-08）。上位文档：[安全性统一讨论](2026-10-26-security-review-design.md)、[决策台账](decisions.md)。

## 1. 起因

用户指出：标签（tags）的主要用途是数据权限，要求从第一性原理重新设计，可以做破坏性变更，不考虑兼容。GA 之后，`ApplyTags`、`@Tags` 与 `wow.query.abac.*` 都会成为要兼容的公开面，所以在 GA 前一次改到位。

## 2. 现状

数据权限今天由五套机制拼成：

| 机制 | 作用 | 位置 |
| --- | --- | --- |
| 租户 | 硬隔离，属于聚合身份（`AggregateRef`） | 内核、查询准入 |
| 作用域（属主与空间，`Scope`） | 查询追加相等条件（`ScopeQueryPolicy`）；命令在内核里检查，不符答 403 `wow.aggregate.forbidden` | 查询、内核 |
| ABAC 标签 | 资源的 `Map<String, List<String>>`，与调用者的 `abac.*` 属性匹配；只约束查询 | `AbacQueryPolicy`、`wow.query.abac.*` |
| 自定义 `QueryPolicy` | 应用写代码追加过滤，按 `EventsMode` 决定事件怎么限 | 查询准入 |
| 数据集的标签列 | 数仓行按绑定的列匹配同一套 ABAC 规则 | `DatasetQueryModelProfile.tags` |

标签的来源有两个：生命周期命令 `ApplyTags`（事件 `TagsApplied`）写入，加上状态上的 `@Tags` 函数派生，派生出的键按键覆盖。函数不再返回某个键时，那个键保留上次的值，直到 `ApplyTags` 把它去掉。

ABAC 的匹配规则继承自 9.x：调用者的每个键与资源同名键的值取交集，键之间是「与」。默认放行的有三处：
- 调用者没有 `abac.*` 属性，不受限；
- 资源缺某个键，视为公开；
- 日志学会记标签之前写入的事件流，不受任何限制（直到 `migrate-data backfill-tags` 补上）。

事件按提交那一刻记下的标签判定（`EventsMode.PUSHDOWN`）。

此外（2026-11-02 盘点）：
- 作用域规则有四份拷贝：`KernelAnswers.admits`、`ScopeQueryPolicy`、`StateHandler.covers`、`CommandGateway.ownId`。
- 没有任何后端给标签建索引，只有属主与空间有索引（PostgreSQL、MongoDB）。
- CoSec 只转发 `abac.*` 前缀的属性，而 `wow.query.abac.attribute-prefix` 可改，两者可能对不上。
- 同一聚合类型的日志与快照可以放在不同的存储里（`wow.log.store` 与 `wow.snapshot.stores` 分开配置），所以「事件 × 快照」的连接一般做不到。

## 3. 第一性原理

数据权限是一个判定：**调用者的声明（claims）× 资源的属性 × 动作 → 允许或拒绝**。由此推出要求：

| # | 要求 | 今天 |
| --- | --- | --- |
| R1 | 资源属性是权威的领域事实：只由命令改变，由事件留痕，与状态同版本 | 不满足：`ApplyTags` 绕开领域校验；派生与覆盖的语义难以推理 |
| R2 | 默认拒绝：声明缺失、属性缺失，结果都是看不到 | 不满足：三处默认放行 |
| R3 | 读写一致：同一策略约束所有读路径与命令；看不到就等于不存在 | 不满足：命令不看标签；越权的命令答 403，越权的读答 404 |
| R4 | 可下推：策略是「调用者 → 资源属性上的谓词」，编译为存储过滤；点读与写在内存里用同一个求值器 | 满足（X-26 已统一求值器） |
| R5 | 可声明、可校验：属性有名字、有类型，进 Manifest；策略引用的属性在启动时校验 | 不满足：无 schema 的字符串映射，键拼错就悄悄放开或悄悄不匹配 |
| R6 | 表达力够用：「或」（属主、共享、同部门）、包含、层级（组织树）、角色豁免 | 不满足：只有「每键交集、键间与」，其余要写 `QueryPolicy` |
| R7 | 权限变更对历史生效：撤销共享、转部门之后，旧历史随之可见或不可见 | 不满足：按提交时的标签，被撤销的人仍能看到旧事件 |
| R8 | 一套机制，维护成本低（D299） | 不满足：五套 |

## 4. 设计

### 4.1 概念

- **租户**：硬隔离，属于身份，不进策略，不变。
- **访问属性**：资源上决定「谁能看、谁能改」的领域事实，在状态上声明。
- **调用者声明**：边缘认证后放进 `Caller` 的事实（`subject`、`attributes` 中的角色、部门等），来自安全上下文（CoSec 或网关注入的头，D296、D297）。
- **访问策略**：调用者声明 → 访问属性上的谓词，分 `read` 与 `write`。
- **字段遮罩**：字段级的数据权限，不在本设计内，维持现状。

### 4.2 访问属性

```kotlin
class DocumentState {
    @Access val department: String
    @Access val sharedWith: Set<String>
    @Access("org") val orgPath: String   // 物化路径，如 "/acme/sales/east"，用于层级
    var title: String
}
```

- `@Access` 标在状态的属性上（或返回值的无参函数上），只由状态派生，是纯函数。类型限于字符串、数字、枚举，以及它们的集合。可选的名字参数给出对外的属性名。
- 编译器把访问属性写进 Manifest：名字、类型、单值或多值。兼容闸门照常判定：删除、改名、改类型是破坏。
- 属主与空间是两个内置访问属性（`owner`、`space`），不需要声明；`Scope` 仍承担它的其他职责（创建时的归属、派生命令携带）。
- 删去 `ApplyTags`、`TagsApplied`、`@Tags` 以及按键覆盖的规则。要「授予」访问，写领域命令与事件（如 `ShareDocument` → `DocumentShared`），由领域校验，由事件审计。
- **存储**：访问属性就是状态字段。快照按访问属性建索引，存储后端在建表、建集合时按 Manifest 建。事件流记录、Sink 与发布流的记录都带上访问属性（系统字段 `access`，取代今天的 `tags`），分别供事件查询下推（见 4.5）与数仓的行级过滤。
- `StateEvent` 不再有单独的 `tags`（PA-33 刚加，随之去掉）：处理函数读状态即可；跨上下文的发布流不带状态时，记录里的访问属性仍可读。

### 4.3 访问策略

```kotlin
@Bean
fun documentAccess() = accessPolicy<DocumentState> {
    read { caller ->
        anyOf(
            Document.department isIn caller.claims("departments"),
            Document.sharedWith contains caller.subject,
            Document.org startsWith caller.claim("org"),
            allowIf(caller.hasRole("auditor")),
        )
    }
    write { caller -> Document.department isIn caller.claims("departments") }
}
```

- 谓词复用现有的查询过滤代数（相等、`IN`、包含、前缀、为空，以及与、或、非），编译为存储过滤下推；点读与写在内存里用同一个求值器（X-26）。属性引用由编译器生成（如 `Document.department`），类型安全。
- 调用者声明按名字从 `Caller.attributes` 读取。缺少的声明使所在的比较为假：默认拒绝，不会放开。
- 启动时按 Manifest 校验：策略引用的属性必须是该聚合声明过的访问属性；拼错即启动失败（与 D302 同理）。
- **默认**：
  - 聚合没有声明访问属性：默认策略就是今天的作用域规则（属主与空间相等），行为不变。
  - 聚合声明了访问属性，服务却没有为它给出策略：启动失败，指出缺哪一个。不提供「全部放开」的默认。
  - 公开的数据要在策略里明说（`allowAll()` 或针对某个属性值）。
- 策略写在服务里（它依赖调用者声明的来源，是部署相关的规则），以 Bean 注册；测试 DSL 可以对一个策略给定调用者与状态断言结果。

### 4.4 生效位置：读与写

- **读**：快照查询、状态点读、trace、events、SSE、数据集，统一在查询准入的同一位置追加 `read` 策略的过滤（今天 `QueryPolicy` 的位置）。看不到即 404，与不存在一致。
- **写**：命令准入在决定之前，用聚合的当前状态求值策略。内核本来就要加载状态，不多一次 I/O。
  - `read` 不满足：答 404 `wow.aggregate.not-found`，与读一致，不透露存在。
  - `read` 满足而 `write` 不满足：答 403 `wow.aggregate.forbidden`。
  - 创建命令，以及改了访问属性的命令：对决定之后的状态再求值一次 `write`，不满足即拒绝、不追加。防止把聚合创建或移动到调用者自己写不了的范围。
- **内部调用受信**（D298）：Saga、定时器、服务之间的调用不受访问策略约束，以 operator 记录。
- **跨作用域**（`crossScope`）：它改变的是租户与作用域的选择，不绕过访问策略。需要豁免的，在策略里按角色写。

### 4.5 历史跟随当前属性

一个聚合的 events 与 trace，在它**现在**可见时才可见。撤销共享、转部门，立即对它的全部历史生效。

实现：**重新盖章**。日志与快照可以在不同的存储里（§2），连接一般做不到，所以访问属性随事件流记录保存，并始终保持为聚合的当前值：
- 提交时，事件流记录带上提交后的访问属性（系统字段 `access`）。
- 一个提交改变了聚合的访问属性时，内置订阅把该聚合全部历史记录上的 `access` 改为新值。按版本做，幂等，可重放；运维面可见其进度与积压。这是对日志记录上非事实字段的更新，与今天 `TagsBackfill` 补录标签同类。
- events 查询在策略下照常下推到日志存储（PostgreSQL、MongoDB、ES 事件索引都可以），不需要连接，也不需要限定聚合。
- 从「属性改变」到「历史全部改章」之间有一个窗口：盖章订阅的延迟，正常为秒级。窗口内，跨聚合的事件搜索仍按旧属性判定；trace、状态点读与按版本的历史读不受影响，它们按聚合当前的状态同步判定。窗口写进文档；对撤销要求即时生效的应用，按聚合查看的读取已经即时生效。
- 没有 `access` 的记录（格式升级之前写入、尚未盖章的）在策略下一律看不到，直到盖章完成：默认拒绝，与今天「未记录即不受限」相反。
- `TagsBackfill` 与 `migrate-data backfill-tags` 由同一个盖章订阅取代：引入访问属性或迁移导入之后，存量的流由它补齐。

### 4.6 数据集与数仓

Sink 把访问属性写成数仓表的列（取代 `tags` 列）。数据集声明它的哪些列对应哪个聚合的哪个访问属性（取代 `DatasetQueryModelProfile.tags`），数据集查询用同一个 `read` 策略编译成对这些列的过滤。数据集没有对应列的属性，策略的那一部分判为假（默认拒绝）。

### 4.7 删除

`ApplyTags`、`TagsApplied`（及其路由、schema 与生成的 TS 客户端方法）、`@Tags`、`AggregateModel.tagsOf`、`StateEvent.tags`、测试 DSL 的 `expectTags`、`AbacQueryPolicy` 与 `AbacQueryOptions`、`wow.query.abac.*`、快照与各类记录里的 `tags` 系统字段（由 `access` 取代）、`EventsMode`（由 4.5 的统一规则取代）、数据集的 `tags` 绑定、`TagsBackfill` 与 `migrate-data backfill-tags`（由盖章订阅取代）。作用域规则的四份拷贝合为访问策略的同一个求值。公开的 `QueryPolicy` SPI 收为内部实现：应用自定义的行级过滤一律写成访问策略，所依据的字段声明为访问属性；平台服务的 `AnalyticsCatalogQueryPolicy` 随之改写。

预发布的数据不保留兼容（与 X-42 同理）：beta 用户日志里已有的 `wow.lifecycle.TagsApplied` 事件，由迁移文档给出处理方法（`@Upgrade` 并入领域事件，或重新导入），框架不再解码它。

## 5. 9.x 迁移

- 9.x 的 `StateAggregateTagsExtractor` 派生的标签：迁移技能改写为状态上的 `@Access` 属性。
- 9.x 由 `ApplyTags` 写入的标签：是数据，迁成状态字段。`migrate-data` 的映射把 9.x 快照与事件流里的标签映射到状态字段；9.x 的 `TagsApplied` 事件映射为该聚合的领域事件（由用户在 V10 定义，如 `TagsAssigned`），或在没有对应领域概念时由 `@Upgrade` 并入相邻事件。具体写法由迁移技能给出，项目 A（PA-33 的来源）作为验证。
- 9.x 的 ABAC 配置（`abac.*` 属性与匹配规则）：迁移文档给出等价的访问策略写法，并指出默认放行的三处在 V10 都改为拒绝。

## 6. 考虑过但不采用

- **只收紧 ABAC 的默认**（调用者无标签即拒绝、缺键即拒绝）：解决 R2，解决不了 R1、R3、R5、R6、R7、R8。
- **策略写成配置表达式**（`wow.access.<context>.<aggregate>.read: "department in claims.departments"`）：免写代码，但类型不安全、难测试。可在 10.x 作为 DSL 的补充。
- **历史按提交时的属性**（今天的做法）：下推便宜，但撤销后旧历史仍可见，不满足 R7。
- **历史按「事件 × 快照」连接判定**：语义即时，但日志与快照可以在不同的存储里，连接一般做不到；退回限定聚合的两段查询，又会失去跨聚合的事件搜索（Q-2 的老问题）。
- **要求有访问属性的聚合把日志与主快照放在同一个存储**：能连接，但限制了部署，也解决不了 ES 事件索引。
- **关系型授权（Zanzibar 式的关系图）**：表达力最强，但需要独立的关系存储与一致性协议，超出框架边界，列 10.x 候选。

## 7. 工作包与顺序

影响面（2026-11-02 盘点，`origin/main` `647b361116`）：主代码约 130 个文件、测试约 140 个，横跨 `:api`、编译器、Manifest、内核、运行时、SPI、四个后端、查询、边缘、宿主、`migrate-data`、TypeScript（约 5 个源文件与 40 个生成或金样文件）、7 份 Manifest 基线、约 35 个文档页与 10 个技能文件。风险最高的四处：日志的存储格式（`tags` 在 PostgreSQL、MongoDB、ES、ClickHouse 上各有形状）；线上格式与契约（`StateEventRecord` 的主题信封、`ApplyTags` 的路由与 schema）；内核热路径（每次提交、加载、重放都派生标签；决定前的作用域检查）；快照与查询的性能（访问属性要有索引）。

按文件分工，前一阶段合并后再开下一阶段；同一阶段内的工作包互不碰同一组文件：

| 阶段 | 工作包 | 范围 | 依赖 |
| --- | --- | --- | --- |
| 1 | **A. 模型**：`@Access`、编译器的读取与校验、Manifest 的访问属性一节、生成的属性引用、`AggregateModel` 的访问属性派生；删去 `@Tags`、`ApplyTags`、`TagsApplied` 与内核的标签演化；`StateEvent` 与测试 DSL 随之改 | `:api`、`compiler/*`、`api/manifest`、`core/kernel`（模型与演化部分）、`test/test-dsl` | X-53、PA-35（D388）合并之后（都改编译器与 Manifest） |
| 2 | **B. 策略**：访问策略的 DSL 与求值器（编译为过滤表达式，内存求值共用 X-26 的求值器）、调用者声明、启动校验、读路径统一追加；删去 ABAC、`QueryPolicy` 公开 SPI、`EventsMode`；数据集的访问列；计数缓存 | `query/*`、`host/spring-boot` 的查询装配、`edge/cosec` 的声明来源 | A |
| 2 | **C. 存储与盖章**：各记录的 `access` 字段、快照的访问属性索引、盖章订阅（取代 `TagsBackfill`）、主题信封、四个后端的过滤编译与映射 | `core/spi`、`backends/*`、`core/runtime` 的快照、Sink、主题条目与盖章订阅 | A |
| 2 | **D. 契约与客户端**：OpenAPI、TS 客户端与生成器、Manifest 基线、生成的客户端重新生成 | `edge/openapi`、`typescript/*`、各服务的 `manifest/baseline.json`、`src/generated/` | A |
| 3 | **E. 写侧**：命令在决定前后求值策略、404 与 403、作用域四份拷贝合一 | `core/kernel` 的准入（`KernelAnswers`）、`core/runtime/admission`、`edge/http` 的 `StateHandler` | B |
| 3 | **F. 迁移**：9.x 标签映射为状态字段、`TagsApplied` 的处理、删去 `backfill-tags`、`compare` 的 `$tags`；迁移技能与文档；以项目 A 验证 | `migration/migrate-data`、`skills/wow-migrate`、迁移文档 | A、C |
| 3 | **G. 平台与示例**：`AnalyticsCatalogQueryPolicy` 改写为访问策略；平台服务与 eShop 按需声明访问属性与策略 | `platform/*`、`examples/*` | B |
| 4 | **H. 文档一致性**：各工作包更新各自的页面，最后一遍核对（`guide/ownership`、`queries`、`datasets`、`operate/security`、参考页、技能） | `documentation/docs/**`、`skills/wow-develop` | 全部 |

规模验证：C 合并后在 Actions 上测快照查询与事件查询在策略下的开销（索引是否生效），以及盖章订阅在长历史聚合上的耗时；E 合并后测命令路径的额外开销（应近于零，状态已加载）。

## 8. 实现时与协调者定的细节

- `@Access` 允许的类型与集合的具体范围；对外属性名的命名规则（与上下文名、查询路径的规则一致）。
- 快照按访问属性建索引的方式：各后端是建单列索引、多键索引，还是由用户声明。
- 创建、改属性命令的「决定后再查」，对批量提交与热点聚合的开销。
- 盖章订阅的批量大小、对长历史聚合的开销、积压的指标与告警；ES 事件索引上的改章（今天的 re-sink 路径）。
- 访问策略在测试 DSL 里的写法。
