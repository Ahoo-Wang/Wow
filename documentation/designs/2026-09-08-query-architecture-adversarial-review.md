# 查询模块整体架构：对抗审查与验证证据

日期：2026-09-08。基线：`d82e5175dbd5ebe06ca3f63ac149f9ce0c6f62e7`，Wow `9.0.10`。

目标文档：[完整重构设计](2026-09-08-query-architecture-redesign.md)。最新验收见[PR 前深度复核验收](#pr-readiness)，兼容栈及数值清理合同见[第 18 节](#debt-cleanup-acceptance)。前 18 节保留不同阶段的验证证据；所有计数、状态及“最终”结论仅适用于对应阶段的代码。

设计和实施范围覆盖整个查询子系统，包括 Schema、原生语义、执行链、授权、HTTP、API/DSL、工厂路由、模型专属行为及受影响消费者。整体架构验收按主设计第 11 节逐项核验，历史 Schema 专项结果不代替最终结果。

设计在第 10 节的职责审查中收窄了 Filter SPI：删除 next 和结果变换。前述/下述 P1–P7、Q1–Q3 是历史候选的证据，不证明最新请求 SPI 已通过测试；R8 的失败记忆方案已被更窄的所有权边界替代。

第 16 节是整轮重构验收，第 17 节是其后全面 review 发现的 13 项缺陷修复验收；它们不证明后续债务清理代码已通过验证。旧原型不计入生产测试数量。

## 1. 设计阶段的验证层次

| 层次 | 本次实际完成 | 证明范围 |
|---|---|---|
| 独立设计审查 | Mongo 事实/Schema 边界审查、ES 执行/HTTP/重试边界审查；修订并复核 | 找出承诺矛盾、接口时序漏洞，不等于执行证明 |
| MongoDB 原生验证 | MongoDB 7.0.40，16/16 断言 | 实际 BSON 查询/投影/元素匹配语义；使用手写的现有算法等价谓词，未调用 Wow Backend |
| Elasticsearch 原生验证 | Elasticsearch 9.2.6 / Lucene 10.3.2，26 HTTP 请求，73/73 断言 | 实际 text/keyword/alias/source/runtime/nested 语义；未调用 Wow Backend |
| 一次性接口/时序原型 | 7/7 JUnit 测试，使用当前六种 Query DTO、实际 Reactor 和 Kotlin 编译 | 新泛型拦截签名、冷订阅、一次性 next、重试位置、超时顺序及终端范围追加可实现 |
| 新 Schema/Compiler/HTTP 完整实现 | 未执行 | 动态值索引、原生能力与公共许可分离、派生元数据、原生预检、整条受管后端集成仍需实施后验证 |

不把 89 条原生断言加 7 项原型测试合称为“96 项新架构测试”。它们采用不同粒度、验证不同对象。前几轮的 513 项既有单元测试以及 7/10 项聚焦测试也不计为本轮新增候选验证。

## 2. 已关闭的对抗发现

| 编号 | 反例及原问题 | 修订后的合同 | 状态 |
|---|---|---|---|
| R1 / P1 | 数值声明对应的实际集合含字符串。keyset 根本不返回违约行，无法通过结果检查发现；“数据违约必然报错”不可实现 | 声明与编码成立是保证前提；只对可观测且合同要求拒绝的违约失败；不承诺检查未返回数据 | 独立复核已关闭 |
| R2 / P1 | Filter 对 next.retry，只重订阅下游，无法重做之前的 Principal/Schema 获取 | next 一次性委托；禁止 Filter 内重试、主动订阅、跨请求缓存；外层 Gateway 新订阅重进治理链 | 原型 P4 及独立复核已关闭 |
| R3 / P1 | list/SSE 已交付第一页后第2页失败，整体 retry 会重发第1页 | 框架无默认自动重试；交付后错误终止；调用方显式重试承认重放语义 | 原型 P6 及独立复核已关闭 |
| R4 / P2 | timeout 放在 collectList 后，6 秒内持续正常产出的流仍会被 3 秒总等待截断 | timeout 放在完成 Filter/Mask/物化的元素流上，之后才缓冲 JSON | 原型 P5 及独立复核已关闭 |
| R5 / P2 | “重新调用 Gateway 方法才获取 Schema”排除了同一个冷 Publisher 的外层 retry | 每次外层重新订阅读取当前发布 Schema；不要求重新调用方法、不保证返回不同实例 | 文本已修正；与原型 P2/P4 一致 |
| R6 / P1 | 若先把授权/HTTP 范围混进用户查询，后续普通 Filter.withFilter(MatchAll) 可清除它们 | 外层固定捕获请求范围/身份；普通变换之后，由终端追加请求范围和专用 ABAC 条件 | 自审发现并修订；原型 P7 验证追加顺序，真实身份集成待实施 |
| R7 / 架构边界 | Provider 按 Mask 裁剪原生能力后，无法区分“后端不支持”与“公共规则禁止”；Schema 又承担许可语义 | Provider 保留原生能力和保护声明关系；公共规则唯一实现静态许可；Gateway 与元数据构造函数复用；HTTP 只展示派生有效能力 | 已修订职责和输入输出；下述设计判定表为契约审查，尚非候选实现测试 |

一次性 next 检查只检测协议误用，不隔离恶意插件。禁止主动订阅、发出后修改等仍是受信扩展的编程合同；不为此新增插件沙箱或通用状态管理框架。

### R7 的设计判定表

| 原生能力 | 关联保护/作用域限制 | 原生 Schema 是否改变 | Gateway 字段级结论 | Metadata |
|---|---|---|---|---|
| 支持 | 无 | 否 | 允许，仍需组合校验 | 展示 |
| 支持 | 有 | 否，保留能力与绑定 | 按公共规则拒绝 | 隐藏该能力 |
| 不支持或必要依据不足 | 无 | 否 | 能力不足而拒绝 | 不展示 |
| 支持且自身无 Mask | 别名指向受保护值 | 否 | 与源值一致拒绝 | 与源值一致隐藏 |

本轮文档修订不新增运行时测试计数，前述 89 条原生断言及 7 项原型结果不证明 R7 已实现。实施时必须检查：调用元数据构造不改变原生 Schema；实际准入与视图复用同一函数；保护别名不旁路；动态 ABAC 不进入共享 Schema 缓存；refresh 后准入和视图各自使用一份完整快照。

## 3. MongoDB 原生反例与正常对照

环境：`mongo:7.0`，实际服务端 **7.0.40**，Linux/aarch64；镜像 ID `sha256:039796be5c9ac1457093d604aac6e19e7ab5af9a31875bcd7a98b4881389876d`。使用独立、无网络且未映射端口的临时容器，不操作已有基准库。

| 编号 | 输入/操作 | 实际结果 | 设计约束 |
|---|---|---|---|
| M1 | a=Int32(1), b="x", c=Int32(2)，按 v/_id 升序；size=1 的 $gt/$eq keyset | 完整顺序 `[a,c,b]`，分页 `[a]→[c]→[]`，漏 b | 禁止把“原生 BSON scalar”直接等同于可安全续页的同族值 |
| M2 | Int32(1), Long(1), Double(2), Decimal128(3)，同样分页 | `[a,b,c,d]`，包含同值时的 id 分支 | 数值类型集合无需因为不是单一类型而全部拒绝；仅证明这组有限样本 |
| M3 | `{state.name:1,state.secret:0}` | 原生错误 **31254**，不能在 inclusion 中执行该 exclusion | 编译器必须检查已证明存在的原生组合限制 |
| M4 | `{state.name:1,_id:0}` | 返回 `{state:{name:"Ada"}}` | 保留合法主键例外 |
| M5 | `{state:1,state.name:1}` | 原生错误 **31249**，path collision | 同向父子投影按公共子树语义去掉冗余后代 |
| M6 | `{state:1}` | 返回完整 state 子树 | M5 归一化后的正常对照 |
| M7 | `items=[{name:"x"}]` 与 `items=["x"]` | 两者 $type 都为 array；对象子字段 elemMatch 只匹配前者，标量 elemMatch 只匹配后者 | 能力生成需要元素结构，顶层 types={array} 不够 |

Mongo 脚本手写与当前 `MongoCursorFilterCompiler` 等价的升序谓词，而不是通过 JVM 编译器生成。因此它证明现有算法的原生反例，不证明 Wow 全链路重现。原型没有实现新的跨类型算法，目标设计仍选择在已有策略无法证明时拒绝能力。

本地证据：

- 原生报告（本地产物：`build/query-schema-redesign/mongo/report.md`）
- 实际结果与断言（本地产物：`build/query-schema-redesign/mongo/results.json`）
- 命令与清理记录（本地产物：`build/query-schema-redesign/mongo/commands.json`）
- 可重跑脚本（本地产物：`build/query-schema-redesign/mongo/run.py`）、mongosh 用例（本地产物：`build/query-schema-redesign/mongo/verify.js`）

命令：`python3 build/query-schema-redesign/mongo/run.py`。第一次脚本发生 mongosh 闭包 ReferenceError，未作为成功证据；修正后完整重跑通过。两次自有容器均已清理，失败记录也保留在本地证据目录。

## 4. Elasticsearch 原生反例与正常对照

环境：实际服务端 **9.2.6**、Lucene **10.3.2**；镜像 digest `docker.elastic.co/elasticsearch/elasticsearch@sha256:e5673d86bb6a41ed543329ec094fc93d5ef749d32cc87a4150a15d520ad9c670`。独立单节点临时实例，512m 堆、安全关闭、仅本机随机端口。

| 编号 | 实際请求及结果 | 设计约束 |
|---|---|---|
| E1 | title=text：全文 match 命中，原文 term 不命中、token term 命中；默认 text sort HTTP 400 | EXACT_MATCH、全文 SEARCH、SORT 不能由“字符串类型”统一推导 |
| E2 | title.keyword：原文 term、sort 可用；source 投影 title.keyword 返回空对象，投影 title 有值 | 操作物理路径和源投影路径必须分开 |
| E3 | alias 指向 keyword：term/sort 可用，source 投影 alias 为空 | 新设计删除 resolvedField 时仍需保留真实 source/response 映射及别名保护 |
| E4 | enabled:false 对象的未映射子字段：source 能返回，exists/term 无匹配 | 不能把 PRESENCE 或索引能力作为所有投影的前提 |
| E5 | runtime keyword：term 命中，fields 有计算值，source 为空 | 当前 source 返回机制不应伪称支持 runtime 源投影；本次不新增 fields 取值结果合并功能 |
| E6 | object 数组的 color AND large 命中文档1/2；同样 nested 约束仅命中2 | 同样的叶子 keyword 和局部单值不代表相同元素语义 |
| E7 | color AND red 正向对照，object/nested 均仅命中1 | 防止以空结果或错误查询当作元素范围正确的证据 |

全部成功搜索还断言 `timed_out=false`、`_shards.failed=0`。没有通过改变既有集群设置注入故障；ES 部分失败转换仍由前轮当前 Backend mock 证据支持，候选实现的实际失败策略需实施后专项验证。

本地证据：

- 原生报告（本地产物：`build/query-schema-redesign/elasticsearch/report.md`）
- 73 个断言及清理记录（本地产物：`build/query-schema-redesign/elasticsearch/report.json`）
- 可重跑脚本（本地产物：`build/query-schema-redesign/elasticsearch/verify_native.py`）

命令：`python3 build/query-schema-redesign/elasticsearch/verify_native.py`。01–26 请求文件保留了请求体、实际 URL、状态码和原始响应。

## 5. 候选接口/时序原型

一次性 Kotlin 原型仅在忽略的 build 目录，通过临时 Gradle init 增加测试源与独立测试任务。没有修改项目构建文件，也没有实现新 Schema 构造器或生产 Backend。

| 编号 | 实际检查 | 结果 |
|---|---|---|
| P1 | 两个泛型拦截方法使用真实 ISingleQuery/IListQuery/IPagedQuery/ICursorQuery/FilterExpression/AggregationQuery；保持六种实际结果形状，无 RawResult cast | 编译及执行通过 |
| P2 | 构造 Publisher 时零动作；repeat 后新 Context/新 fixture Schema；同次校验/执行固定同对象；原 Query 不变；值变换发生在 Mask 前 | 通过 |
| P3 | 原生静态拒绝后模拟执行计数为0；Filter 未委托 next 就成功空完成被拒绝 | 通过 |
| P4 | Filter 内 retry 无法第二次订阅 next；外层 retry 重新经过授权和 Provider | 通过 |
| P5 | 每2秒产出一行，共3行；3秒 timeout 放在 collectList 前允许6秒完成，放在后面则失败 | 正反对照通过 |
| P6 | 第1行后失败的流，retry(1) 重复交付第1行；默认无 retry 仅交付一次并失败 | 正反对照通过 |
| P7 | 普通 Filter 将用户条件改成 MatchAll 后，终端仍追加独立 tenant 与 owner 条件；原 Query 不变 | 通过；仅验证条件顺序，不模拟真实身份服务 |

P2/P4 的 Provider fixture 每次创建不同 Schema，目的是证明重入读取和同代传递。真实缓存 Provider 在未 refresh 时可合法返回相同实例，不将“必须创建新 Schema 对象”写成生产合同。

运行命令：

```bash
./gradlew -I build/query-schema-redesign/prototype.init.gradle :wow-query:schemaContractPrototypeTest --console=plain
```

结果：**BUILD SUCCESSFUL，7 tests / 0 failures / 0 errors / 0 skipped**。本地 原型源码（本地产物：`build/query-schema-redesign/prototype/SchemaContractPrototypeTest.kt`）、init 脚本（本地产物：`build/query-schema-redesign/prototype.init.gradle`）、运行日志（本地产物：`build/query-schema-redesign/prototype.log`）。

未验证：多 Filter 的生产注册、真实 ABAC 集成、真实 Schema 动态规则、原生编译器的零 I/O 检查、PIT 取消与关闭失败、完整 HTTP 协议。接口原型的成功不替代这些验收。

## 6. 实施阶段必须保留的攻击矩阵

| 攻击 | 必须保持的最终结果 | 验证层 |
|---|---|---|
| 不支持的指定搜索字段 | 明确拒绝，不能扩大为全模型搜索 | Schema + Gateway + 原生请求检查 |
| 显式字段禁用某能力但父动态模板允许 | 不回退到模板恢复被禁用能力 | Schema 单测 |
| Map 值是数组/对象而非容器自身类型 | 按值模板与作用域解析；不凭父 OBJECT 复制能力 | Source + Schema + 两后端 |
| 普通单值叶子处在数组祖先内 | 根级 cursor 不因叶子 SINGLE 获得许可 | Schema + Backend |
| 未标记 Mask 的别名指向受保护源或物理值 | cursor/group/metric 均不能绕过保护 | Schema + Mask + HTTP |
| 生成 HTTP 有效能力后再执行原生检查 | 原生能力与绑定仍存在；许可判断与元数据使用同一公共规则 | Schema 不变性 + Gateway + Metadata |
| 两个逻辑 sort 指向同一物理字段 | 执行前拒绝 | Backend + 零调用断言 |
| EventStream 投影 payload 而省略 bodyType | 执行前拒绝；省略 payload 的对照允许 | Gateway + 两后端 |
| native 已知跨类型游标 | 不提供 CURSOR_SORT / 拒绝；数值同族给正向对照 | Adapter + 实库 |
| projection 父子重复或非法 mixed inclusion/exclusion | 前者按子树语义归一化，后者保留原生合法例外且拒绝非法组合 | Compiler + 实库 |
| Schema refresh 与正在执行的请求交错 | 同次请求只用原快照，新订阅读取当前发布值 | 受控异步单测 |
| Schema 等待不结束、JSON 持续产出、SSE 闲置 | 超时范围和闲置语义正确，关闭 timeout 不改变 JSON 缓冲 | Reactor 虚拟时间 + WebFlux |
| 普通 Filter 重写查询或内部 contextWrite | 无法移除外层捕获的请求范围/身份；专用 ABAC 在终端追加 | Gateway + 真实授权/HTTP 集成 |
| ES timed_out、失败 shard、count 部分失败 | 保留错误，不能成功返回部分 total/末页 | 客户端响应单测 + 故障集成 |
| 取消打开/使用 PIT 的查询 | 资源按客户端及 usingWhen 合同释放或被有界 keep-alive 回收；不能把未取得资源的竞态谎称可即时关闭 | 客户端受控 Future + 实库 |
| 结果发出后改写、跨订阅共享 ObjectNode | 合同测试拒绝；不会为缓存坏行为添加每层深复制 | Backend TCK |

只有这些目标实现在真实后端和接入层跑通，才可以标记“重构验收通过”。本次没有用文档检查、已有测试或原生脚本替代它。

### 6.1 最终整体设计的闭环复核

主设计 2.1 节新增 A01–A14，按当前生产入口区分已知反例、结构问题和应保留机制，并关联本报告 M/E/P/R 证据。11.1 节给出整体重构退出条件；这些编号是实施验收项，不是已经通过的新测试。

本轮补充的反向检查及处理：

| 反向检查 | 设计结果 | 证据边界 |
|---|---|---|
| 只保持方法名，却要求调用者传 Schema 或消费新结果容器 | 10.1 固定 QueryGateway 包名、泛型、继承关系、十个方法及输入输出形状；分别验证源码/接口二进制调用/DTO JSON | 接口合同已明确，目标兼容测试待实施 |
| 高优先级声明未设置值却抹掉低优先级；显式 null 又无法清除 | 保留输入阶段 Unset/Set；普通叶子按既有优先级合并，同级冲突失败 | 已核对 QuerySchemaMerger/QuerySchemaDeclaration；不为删除类型而牺牲必要语义 |
| 新动态模板合并出“新类型 + 旧 items”不一致结构 | items/additionalProperties 显式整体替换，不能移除独立合并的保护声明；最终结构一致性检查失败则不发布 | 新值结构为目标合同，尚无生产实现 |
| 借完全重构重写已经正确的刷新机制，或无限保留旧链 | 2.1 明确保留项；10.2 明确旧生产路径退出；11.1 同时要求正例与负例 | 文档自审，不计入原型或原生测试数量 |
| Schema 对每个字段提供 permit 方法，Backend 再重复授权 | 3.1 固定“事实查找 → 公共准入 → 原生编译”的不同责任；原生 Backend 不重复 ABAC/Mask | 职责合同，实施时检查生产依赖和调用路径 |

本节的文档闭环复核没有再次运行不变的数据库脚本或原型，也未增加其成功计数；后续新增的整体边界实验单独记于第 9 节。最终闭环是“问题有依据、设计有处理、所有者唯一、兼容性明确、验收可执行”；不是“缺陷已在生产代码中修复”。

## 7. 环境清理与产物范围

Mongo 两个自有测试容器、ES 一个自有测试容器均已删除并检查不存在。既有 `wow-benchmark-mongo` / `wow-benchmark-elasticsearch` 仍运行，未对其数据执行本次测试操作。

可重跑脚本和原始结果在 `build/query-schema-redesign/`，属于本次本地产物，不纳入版本控制；本文保留必要环境、输入输出和结论。长期 CI 用例应在实施阶段迁入现有测试层。本次 tracked 交付仅设计与审查文档；没有生产实现、生成合同、依赖、模块或发布工作流修改。

## 8. 文档自检

两份文档完成本地链接存在性、代码围栏配对、占位符和行尾空白检查；架构图使用已安装 Mermaid 11.17.2 和 jsdom 29.1.1 完成语法解析。裸 Node 的首次 Mermaid 检查因缺少 DOM 环境失败，补上已有 jsdom 后通过；未通过替换 sanitizer 或安装新依赖绕过校验。

文档位于 `documentation/designs/`，不修改 VitePress 站点路由/内容，因此本次只执行针对文档本身的检查，没有把整个站点构建计为设计验证。检查产物在 `build/query-schema-redesign/document-check.json`。

## 9. 整体架构完成后的对抗验证

本轮攻击对象是扩展协议、强制治理和结果交付的组合，非重复 Schema 原生检查。发现三个设计缺口，已修订主设计第 4、5 节。

| 编号 / 严重性 | 可执行反例 | 修订 | 关闭依据与剩余范围 |
|---|---|---|---|
| R8 / P1 | Filter 只订阅一次 next，但 onErrorReturn 将授权失败恢复为成功；一次性标记通过 | 终端保留原始失败，外层接收值/完成前检查；普通 Filter 不得恢复受管下游失败 | Q1 反例与局部修订对照通过；生产多 Filter、空恢复及部分流错误合同待实现 |
| R9 / P1 | Filter 把 secret 原值复制到 public，最终只 Mask secret，public 仍泄露 | 明确结果 Filter 保持字段身份，禁止敏感值搬迁和协议元信息篡改；任意形状映射放在已脱敏输出之后 | Q2 反例及保持字段身份正例通过；这是受信扩展约束，不声称能自动识别任意恶意代码 |
| R10 / P2 | 输入 limit=2，结果 Filter 每行复制一次，collectList 实际收集4行 | HTTP 在最终流、缓冲前检查配置的实际输出行上限；超限错误并取消，不静默截断 | Q3 扩增反例与超限拒绝/正常完成对照通过；生产 HTTP 分页/游标/聚合接入待实现 |

一次性隔离原型使用当前项目的 Reactor、Jackson 和 JUnit，运行命令：

```bash
./gradlew -I build/query-schema-redesign/architecture.init.gradle :wow-query:architectureAdversarialTest --console=plain
```

实际结果：BUILD SUCCESSFUL；3 tests，0 failures，0 errors，0 skipped。首次 init 脚本未跳过不含 wow-query 的附属构建，导致配置失败；加上空项目判断后重跑通过。未修改生产代码或仓库构建配置。

证据：原型源码（本地产物：`build/query-schema-redesign/prototype/QueryArchitectureAdversarialTest.kt`）、隔离任务（本地产物：`build/query-schema-redesign/architecture.init.gradle`）、运行日志（本地产物：`build/query-schema-redesign/architecture.log`）、JUnit 结果（本地产物：`build/query-schema-redesign/architecture-results/TEST-me.ahoo.wow.query.redesignprobe.QueryArchitectureAdversarialTest.xml`）。这 3 项只验证边界反例及局部机制，不是生产 Gateway/HTTP 的新架构验收，不与前述 7 项接口原型合并宣称完整覆盖。

同时复核整体契约：QueryGateway 包名、泛型、继承、参数名（Kotlin 命名参数）、参数空值约束、返回形状均纳入兼容边界；DSL 只构造逻辑 Query；工厂按聚合返回 Backend/Provider 配对；失败不隐式切换后端；模型差异留在 Snapshot/EventStream。源码核对没有代替目标二进制兼容或真实装配测试。

结论：本轮找到的三项设计缺口已明确处理，整体方案可供最终评审。生产实施仍须验证不可吞错、多 Filter/取消竞争、HTTP 实际输出边界、API 二进制调用、模型/装配和实库执行；现阶段不能宣称整体实现通过对抗验证。

## 10. 高内聚、低耦合与热路径成本对抗审查

本轮对第 9 节修订继续反向攻击，发现“补丁修好了局部漏洞，却让 Gateway 承担插件执行监管”的结构问题。最终采用删除能力来修订设计，而非继续加状态。

| 编号 | 攻击 | 最终决定 | 验证性质 |
|---|---|---|---|
| R11 / 架构 | next 暴露执行所有权，Gateway 又维护一次性与失败状态；每新增扩展行为都可能要求新监管 | QueryFilter 仅 prepare → Mono<Q>；不给执行 Publisher 和结果；终止观测独立且不能替换结果 | 从接口消除执行流别名；旧 R2/R8 状态方案退出；新 SPI 编译与生产时序待实施 |
| R12 / 职责 | 结果 Filter 持有原值，Mask 只能靠“不复制”的约定保证位置 | 删除核心结果 Filter；固定 Mask/物化；应用映射放到已脱敏 Gateway 输出之后 | 旧 Q2 证明原设计风险；新结构不向扩展交付原始结果，非恶意插件沙箱 |
| R13 / 性能 | “公共纯函数”仍可逐字段扫描全 Schema；别名保护全量闭包可平方增长 | 发布期事实索引，查询访问相关路径/来源；不建立字段对闭包，不缓存身份许可 | 算法/工作量审查；尚未测得目标复杂度或速度 |
| R14 / 性能 | 流式 API 返回 Flux 却内部全量收集，或每层复制 JSON/反复物化 | 明确一次归一化、固定 Mask、一次物化；只有 HTTP JSON 做有界缓冲；背压和预取列入验收 | 设计约束；真实 Backend 内存和背压测试待实施 |

本轮不新增运行测试成功计数。主设计第 4 节是最新扩展合同，第 11.2 节明确热路径成本与基准矩阵。历史泛型中间件原型不能直接迁入生产；其价值是证明为什么需要收窄扩展边界。

结论：职责更集中在各自所有者，Gateway 删除插件执行监管状态，Schema 保持事实合同，HTTP 保持传输职责。低耦合由变更影响和依赖方向验收，高性能由目标实现基准验收；本次完成设计级审查，不宣称已证明生产性能。

## 11. 收窄扩展合同的可执行复核

新原型验证请求扩展不拥有执行流，采用真实 Query DTO、Reactor 和 JUnit；没有实现生产 QueryContext、Schema 或授权服务。

| 编号 | 场景 | 结果 |
|---|---|---|
| N1 | prepare 泛型接收 single/list/paged/cursor/count/aggregate 六种当前输入 | 编译及执行通过 |
| N2 | prepare 返回 Mono.empty | 准备错误，终端执行计数为 0 |
| N3 | prepare 内 onErrorReturn 恢复自身错误，随后终端拒绝 | 原始终端错误仍传播，无旁路失败状态 |
| N4 | 普通请求变换清除用户 filter，终端再追加固定范围；外层 repeat | 范围保留、输入未修改、准备和授权各执行两次 |
| N5 | 准备尚未结束时取消 | 准备收到取消，Backend 执行计数为 0 |

命令：`./gradlew -I build/query-schema-redesign/narrow-filter.init.gradle :wow-query:narrowFilterContractTest --console=plain`。结果：BUILD SUCCESSFUL，5 tests，0 failures，0 errors，0 skipped。

证据：源码（本地产物：`build/query-schema-redesign/prototype/NarrowQueryFilterContractTest.kt`）、隔离任务（本地产物：`build/query-schema-redesign/narrow-filter.init.gradle`）、日志（本地产物：`build/query-schema-redesign/narrow-filter.log`）、JUnit（本地产物：`build/query-schema-redesign/narrow-filter-results/TEST-me.ahoo.wow.query.redesignprobe.NarrowQueryFilterContractTest.xml`）。这 5 项属于新请求 SPI 的局部可行性证据；不替代多 Filter 真实注册、身份 Context、数据库取消和生产 API 兼容测试，也不证明吞吐提升。

新增职责发现 R15：旧措辞把“缺索引”笼统列为能力不可用，混淆语义可执行性和执行成本。当前 Mongo Adapter 的能力判断使用类型/编码依据，并不要求性能索引。主设计已修正：原生能力由操作真实机制决定；索引性能治理归后端运维/应用，不能让 Schema 变成成本优化器。这是源码与合同复核，没有新增数据库性能结论。

同时明确终止观察只在完整管道外安装一次，未配置不产生额外观察工作；正常空结果与空 prepare 区分；HTTP 超时和 Gateway 取消各自记录真实信号。没有新增观察总线、运行状态机或错误记忆槽。

## 12. 终止语义与结果所有权复核

本轮核对当前 QueryGateway 的物化位置、SchemaMasker 的原地处理和 Snapshot Gateway 的结果类型，验证固定管道不需要新增完成状态机或多层复制。

| 编号 | 可执行检查 | 结果与设计结论 |
|---|---|---|
| T1 | Backend Mono 成功后，物化 map 抛错 | 提前 doOnSuccess 已记录成功，完整管道终止为 ON_ERROR；Gateway 观察必须位于物化之外 |
| T2 | Mono.usingWhen 数据成功、异步清理失败 | 不发出成功值，传播含清理原因的错误；复用原生资源合同，不在 Gateway 重建清理状态 |
| T3 | 每订阅创建独占 ObjectNode，原地 Mask，调用者修改第一次结果后再次订阅 | 第二份结果不受影响；不需要每个处理阶段复制；正常空流仍为完成 |

命令：`./gradlew -I build/query-schema-redesign/termination.init.gradle :wow-query:queryTerminationContractTest --console=plain`。实际结果：BUILD SUCCESSFUL，3 tests，0 failures，0 errors，0 skipped。

证据：源码（本地产物：`build/query-schema-redesign/prototype/QueryTerminationContractTest.kt`）、隔离任务（本地产物：`build/query-schema-redesign/termination.init.gradle`）、日志（本地产物：`build/query-schema-redesign/termination.log`）、JUnit（本地产物：`build/query-schema-redesign/termination-results/TEST-me.ahoo.wow.query.redesignprobe.QueryTerminationContractTest.xml`）。测试是 Reactor/Jackson 的管道模型，不是新 Backend 或 HTTP 完整实现。

主设计第 5.1 节补充三个不同边界：Backend 资源完成、Gateway 物化完成、HTTP 网络交付完成。各层只报告自己的完成；资源所有者清理，观察器不发起清理或重试；独占结果由固定管道处理。真实 PIT 取消竞态、HTTP 网络失败和性能数值仍须实施后验证。

本轮设计收口：已有问题具备目标职责、退出路径及验收条件；继续实施前需要的是真实目标接口和端到端验证，不是继续扩展框架层次或重复已有反例。最新合同以主设计第 4、5、11 节为准，历史中间件方案仅保留为审查证据。

## 13. 并行实施前的独立规约审查

本轮使用独立子代理只读核对规约、计划和当前源码，另两个子代理分别实施 Mongo 投影和 ES 响应完整性；共享接口与文档由协调者独占。

独立审查提出并已复核关闭的五项合同缺口：动态值递归成员不具体、lookup/保护索引输出及所有者不明确、真实 HTTP 范围接入点遗漏、SPI 注册消费者迁移滞后、泛型 prepare 过度承诺。规约已补 6.1.1 的结构与事实 lookup、第 3 节范围 Context/授权接口、第 4 节条件变换范围；计划补全部相关 Walker/Handler/Registrar 及文件所有权。

后续复核又发现 QueryScope 和旧 HttpQueryGuardFilter 退出被排在 Gateway 之后，已归入 G1 同批编译/HTTP 接线；G1/H1 集成未闭合前不能宣称新管道切换完成。当前未在 CoSec 源码找到相应直接消费，未凭模块名扩大改动范围。

规约复核通过不等于实现验收；Mongo/ES 分别完成红绿测试后，仍需独立代码审查及最终集成结果。当前并行实施进度以实施计划工作包表和本地 SDD ledger 为准。

### M1/E1 生产实现审查与原生反馈

两名实现子代理采用互斥模块写入，第三名子代理独立复核规格与代码；协调者复核实际差异和综合测试。M1 预审发现二次复杂度去重和 _id 单向例外不完整，均已修订：祖先 Set 查询替代字段两两扫描；真实 Mongo 证明 `_id:1 + 普通字段:0` 合法，规范化为纯 exclusion 保持游标补取语义；跨方向 _id 子树冲突继续拒绝。没有将该原生规则上移到 Schema/Gateway。

E1 审查确认 ordinary/cursor/PIT list/summary/grouped/count 全部消费入口先检查完整性，PIT id 更新在检查之前；错误继续通过原 Publisher 传播，没有重试/恢复或改变聚合口径。清理吞错及静态聚合构造顺序为既有后续范围，不用 E1 通过掩盖它们。

独立审查报告及实施报告保存在本计划 `.superpowers/sdd/2026-09-08-query-architecture-implementation-plan/`，属于本地工作产物；长期验收以仓库生产测试和集成测试为准。

## 14. CURSOR_SORT 独立能力与来源事实审查

本工作包由 Mongo、ES 原生实现和公共保护/metadata 三个子任务并行完成，协调者负责共享常量、公共准入及调用方 fixture。独立审查发现并要求修正：

- 公共保护把 resolvedField 当作 response 来源会误关联；已分开命名空间，新增正反例。
- 别名候选反复 BFS 会重复遍历；改为多源队列与来源去重，不将父子路径合并成等价类。
- 无 Mask Schema 构建完整保护索引属于无效成本；新增结构断言先失败，改为不构建，复核关闭。
- Mongo 稀疏逻辑声明不能覆盖已知 validator 数组祖先；补“仅叶子声明+原生数组父”的能力拒绝反例。
- ES alias 必须保持真实排序目标；补数组目标 alias 拒绝、标量 alias 正常排序和目标+alias 物理重复拒绝。

普通 SORT 不再作为游标准入依据，公共层不再硬编码 ES 元数据名或判定物理重复。两后端 cursor 全链使用 CURSOR_SORT 绑定，包括 Mongo 补取字段的响应位置。元数据构造已移出 Schema 成员，并复用公共 cursor 保护规则；其他聚合能力的保护/metadata 完整迁移仍属后续工作。

每次测试通过只证明当时覆盖的场景；上述原生来源反例由独立审查在第一次 native check 通过后找到，因此不以旧绿灯替代修订后的验收。实际最终结果写入实施计划工作包记录。

本包最终独立复审关闭两项 native P1 和共享性能 P2。协调者综合检查通过，API/query/Mongo/ES 单测 115/402/302/236、Schema HTTP 8、Mongo/ES 实库 87/95 均无失败/跳过；冻结 API 和 JSON 基线复核通过。新增 native alias/nested 组合及数值极值未穷举，完整性能基准仍未执行。结果与命令以 `cursor-final-verification.json` 和 `cursor-final-check.log` 为准。

## 15. 值树与固定执行链接入后的实证审查

本轮发现并修复：声明按长度排序误覆盖同长度字段；匿名双数组层级仅比较最后父路径造成作用域遗漏；Map 明确属性被受保护 additionalProperties 覆盖；PHYSICAL 路径省略 Item 后的同源数组别名漏脱敏；UNKNOWN 联合分支绕过字符串保护；数组别名中整数分支与多层数组的 Mask 域恢复错误。上述 Mask 五组反例已由独立 Java probe 与 JUnit 复验，包含不同 Map 例外名单的别名组合。证据见 `build/query-architecture-migration/schema-mask-probe/` 和 `schema-mask-adversarial-2.log`。

保护源模式保留固定键排除名单，不能把 Map 默认规则传播给明确属性；逻辑、投影、响应、物理仍是不同命名空间。PHYSICAL 关系省略数组遍历层，回到逻辑/响应时按目标值结构恢复成员域。Mask 只执行声明支持的字符串分支，显式整数分支保留；未确定 wire shape 的联合分支拒绝发布。

ES 完整集成测试还发现 `ignore_above` 的数据丢失语义：source 中保留而索引忽略的值会破坏 exists、精确匹配、排序及聚合的能力证明，可能令基于缺失判断的权限范围误放行。该项在原生 mapping fact/能力判断中修复，不在 ABAC 或 Schema 中植入 ES 特例；最终实库复验尚未完成，不能标为通过。

当前 Mongo 276 项单测、170 项完整集成测试通过；query 334、schema 247、OpenAPI 140 项单测通过。ES/外围剩余失败及完整性能验收仍在进行，不能用本节证明全部目标已完成。

## 16. 最终实现与独立复验

最终生产代码已切换为 Schema 事实供给、Gateway 公共准入和固定交付、Backend 原生执行。没有保留 ResolvedQuery、旧兼容模式、结果属性袋、next 中间件或第二条执行链。

| 验收项 | 最终所有者与测试证据 | 结果 |
|---|---|---|
| A01/A03/A05：Schema 纯事实与完整结构 | QueryValueSchema、QueryValueLookup、QueryPathTemplate；QueryValueSchemaTest、QueryValueLookupTest、QueryModelSchemaTest、QuerySchemaMergerTest | 逻辑树不持有原生绑定或 Query；精确键阻断动态回退；完整数组祖先与 UNION/UNKNOWN 分开 |
| A02/A06：原生类型与游标 | Mongo/ES Adapter、原生 Sort/Cursor Compiler；两 AdapterTest、CursorQueriesTest、MongoCursorFilterCompilerTest、ElasticsearchSortCompilerTest 及实库查询测试 | 已知类型集合不压成未知；跨比较族、数组祖先、重复物理排序按所属层拒绝 |
| A04/A12：公共许可与元数据 | QuerySchemaValidation、QueryFieldProtection、QuerySchemaMetadata；同名测试及 schema-guard-probe | 不把指定全文字段降级为全模型搜索；metadata 派生有效许可而不修改原生能力 |
| A07：Mongo 投影 | MongoProjectionCompilerTest、AbstractMongoQueryBackendTest、MongoQueryProjectionResultTest | 父子冗余规范化；非法混用零 count/find 调用；保留合法 `_id` 例外 |
| A08/A09、R11/R12、N/T：固定管道 | QueryGatewaySubscriptionTest、QueryGatewayContractTest、QueryScopeTest、AbacQueryPolicyTest | prepare 不能恢复终端失败；空 prepare 拒绝；scope/身份不能被重写移除；观察器无结果或执行所有权 |
| A10、R14：HTTP 完整等待与交付 | HttpQueryGuardTest、QueryRequestScopeHandlerTest、七种 Handler 的测试 | Schema 等待计入超时；在 collect 前检查闲置和实际行数；JSON/SSE、取消、关闭限额保持独立合同 |
| A11、R15：ES 部分失败与资源 | AbstractElasticsearchQueryBackendTest、ElasticsearchCursorSubscriptionTest、ElasticsearchPointInTimeTest、聚合执行测试 | 静态聚合先于 PIT；拒绝失败分片/超时；保留最新 PIT 和清理错误；不重放已交付流 |
| A13：发布与刷新 | DefaultQueryModelSchemaProviderTest、QueryGatewaySubscriptionTest | 每订阅固定一代；并发刷新完整发布；失败保留旧代；不缓存身份许可 |
| A14：模型语义与 Mask | Snapshot/EventStream GatewayTest、SchemaMaskerTest、FilterNormalizerTest、两端 AggregationCompilerTest、schema-mask-probe | Mask 在最终物化前；事件 bodyType 合同；聚合根及元素过滤共享一次时间基准 |
| 装配、配置与 API | RoutingSnapshot/EventStreamQueryBackendFactoryTest、Spring/starter/WebFlux/CoSec/OpenAPI 检查、冻结调用方 | 成对绑定和既有默认路由；旧 validation-mode 显式报迁移错误；QueryGateway API 保留 |
| R13 与性能 | 冻结 old/target JMH、实库配对和工作量校正 | 构造与热查询分开；不以已失效的中间倍率或关闭治理宣称提升 |

### 16.1 最后一轮发现及关闭依据

- ES `ignore_above` 实际造成 source 有值、索引无值，存在性权限条件可能误放行；`null_value` 实际令 source:null 命中 sentinel term/exists。两条均以真实 ES 得到 RED，再在原生 mapping fact/能力判断中修复并 GREEN。source 投影保持，字段操作无依据时拒绝。没有在 Schema 或 ABAC 添加 ES 条件分支。
- JSON Schema allOf 的 enum 被错误覆盖；四条失败反例覆盖局部 enum 与 allOf、两个 allOf、空交集和 UNION 分支裁剪，修复为交集后全模块通过。声明源优先级规则未被混为 JSON Schema 约束交集。
- 五组独立 Mask 攻击覆盖明确 Map 键例外、物理数组别名、STRING|UNKNOWN、混合整数/字符串数组、多层数组。修复后独立 Java 探针和生产单测均通过；最终优化后再次运行探针。
- 聚合根与元素过滤原先各自读取时间；现在由原生聚合编译入口取一次 Instant 并传入所有层，固定时刻测试覆盖层间一致性。该证据是实际编译路径的确定性测试，不宣称跨午夜实库事故复现。
- 热路径分析删除无 Mask 查询的全量保护扫描、重复路径物化及成功路径提前构造异常，保留发布期索引和响应形状检查；修复后的代码重新完成整套检查再冻结测量。

### 16.2 最终验证命令与计数

```bash
./gradlew :wow-api:check :wow-query:check :wow-schema:check \
  :wow-mongo:check :wow-elasticsearch:check :wow-spring:check \
  :wow-webflux:check :wow-cosec:check :wow-spring-boot-starter:check \
  :wow-openapi:check :wow-mongo:integrationTest \
  :wow-elasticsearch:integrationTest :wow-benchmarks:jmhJar \
  --continue --console=plain
```

上述综合检查执行成功；最后的 Mask 修订又执行 query check、两个后端完整 integrationTest 和 JMH 构建。按各模块最终 XML 汇总：单测 API 114、query 340、schema 251、Mongo 277、ES 223、Spring 19、WebFlux 299、CoSec 6、starter 235、OpenAPI 140，共 1,904；完整实库 Mongo 170、ES 146，共 316；总计 2,220，失败/错误/跳过均为零。各轮结果不重复加数；TCK 随真实后端的集成用例执行，不额外计数；没有声称运行全部仓库测试。

冻结 Java/Kotlin class 共执行 20 次接口调用，七个 JVM 描述符与旧基线一致，11 份通用 JSON 及 Snapshot/EventStream 专属物化样例对照通过。旧 caller class 哈希不变。代理链接验证与真实 Backend 执行测试分别提供证据，不能互相替代。

用户文档的中英文迁移已更新；`pnpm docs:build`、`node --test test/*.test.mjs` 通过。OpenAPI 只调整递归 Schema metadata 组件快照，端点合同快照未变。

本地原始证据在 `build/query-architecture-migration/`：`query-architecture-optimized-final-check.log`、`performance-final/freeze.json`、`gateway-schema-verification/`、`BaselineRunner-optimized-final.log`、`ModelBaselineRunner-optimized-final.log`、两个 `schema-*-probe-optimized-final.log`、`null-value-red.*`/`null-value-green.*`、`config-clock-green.log`、`docs-build-retry-2.log`、`docs-tests.log`。构建产物不提交。

### 16.3 保证边界

本轮验证目标架构在受信声明、已知编码及所列 native 机制下的合同。未观测的历史脏数据、任意自定义 codec、所有数据库配置的完备性和线上容量不在证明范围。ES 全模型全文搜索保留 native default-field 语义，不能把字段级丢值拒绝扩大解释成 source 等价全文搜索。含未知保护域的 Schema 拒绝发布，不为通过查询而降低保护。

PIT 在取得句柄后的正常、失败和取消路径有清理验证；句柄尚未交付的取消竞态只保证后端租约到期的有界回收。没有增加框架自动重试或任意插件执行权限。

“先进性”限于对旧实现可核对的职责边界、信息完整性、攻击面收窄和测得的成本；不宣称行业领先、所有用例提速或已经发布。

### 16.4 最终性能反证与 Mask 修订

原生首轮部分明显差异在反序/较长预热后不稳定，因此不据此宣传整体端到端提速。实际存在字段的 Mask 基准发现每值集合迭代造成约 50% 额外分配；直接成员查询与既有 MaskNode 的严格字符串域优化将分配恢复持平，64 字段千行最终较旧版快约 6%–7%。整数/小数/布尔/自定义类型/NULL/联合域由 SchemaMaskScalarDomainTest 覆盖，原有别名/数组对抗探针再次通过。没有移除形状判断或建立新执行路径。

最后一轮新增一个语义回归测试，query 为 340 项，总数 2,220。相关代码位于 Mask 内部；Schema、Gateway、Backend、HTTP 的职责未移动。最终产物仅 5 个 Mask 相关 class 与首轮目标不同，其他 Wow class 字节相同。性能的构建来源、局限和残余成本均在配套报告中保留。

最后的文档独立审查还校正了默认 tags 为空与授权 Publisher 为空的区别、Unavailable 绑定的订阅失败时点、FilterType/Order 筛选排序及观察失败独立记录，规约与实际合同一致。

## 17. 全面 review 的 13 项修复闭环

全面 review 在上一轮通过的测试以外确认了 2 个 P1、11 个 P2，撤回当时的最终验收判断。用户随后授权修复。本节以 review 前的工作区副本为修复基线，不将整次架构重构的 Git diff 误当作本轮修复 diff。

| 编号 | 实际修复与职责归属 | 回归证据 |
|---|---|---|
| F01 | 两种 LOAD handler/factory 接入同一配置 HttpQueryGuard；JSON 完成有界缓冲后输出，SSE 保留流式语义 | LoadQueryBoundaryTest 的请求/实际行数、默认超时、取消、晚到错误；starter 双工厂装配 |
| F02 | tenant/owner/id/版本区间进入独立 QueryScope，prepare 只修改用户 Query | 实际 Snapshot/Event Gateway 到存储边界，替换 MatchAll 后路径条件、默认 tenant、ACTIVE 仍保留 |
| F03 | 声明合并对非空子路径保留 NULL 分支，仅递归其他分支；不把 scalar 变成 object | QuerySchemaMergerTest 的 nullable union 子字段补丁与 scalar 反例 |
| F04 | 元素入口只验证 ELEMENT_SCOPE；读取保护值的 group、ANY、数值表达式仍受限 | QuerySchemaValidationTest：COUNT/公开字段指标通过，secret 指标和分组拒绝 |
| F05 | Mongo 按一致的非空操作值域解析日期表示，Adapter 不为冲突单位授能力 | MongoNativeConstraintsTest 与实库 nullable Epoch histogram |
| F06 | ES nested 内外层分别排除 NULL，仍要求非空 OBJECT carrier | Adapter 正反例及实库 `[null,{code:A}]` |
| F07 | ES 保留 source 开关/裁剪事实，发布投影可交付性；缺 source 的 hit 报错，不过滤成空结果 | 5 种读取与 cursor lookahead；实库 disabled source 的 count/summary 独立成功、裁剪 alias 显式投影 |
| F08 | native 根/object/nested 的 disabled 状态传递到索引后代；source 事实独立 | root/object disabled、alias/multifield 单测与实库正反例 |
| F09 | 保留 normalizer 事实，撤销无法证明无损的字面值能力，沿用现有无损 multifield 选择 | 原生 lowercase 反例、exact sibling 正例、普通全文能力对照 |
| F10 | Mongo 对完整 filter 检查 text 数量和 NOR 位置；不禁止合法 indexed OR | find/aggregation 编译反例与实库 indexed OR |
| F11 | Mongo 普通 sort 拒绝超过 32 键、重复物理位置和已知独立并行数组 | 32/33、不同方向/alias、单数组加 scalar 及实库排序 |
| F12 | Mongo 按 ZoneId 规范化固定偏移；不支持秒偏移时在 I/O 前拒绝 | UTC/GMT 前缀、UTC、数值偏移、region 对照与实库 histogram |
| F13 | 保护传播按 namespace/path 索引相交边，去重边和 visited；保护查询按路径索引命中 | 现有 Mask 值域测试、map slot 重排与 named exclusion 新回归；SchemaProtectionBenchmark |

### 17.1 最终验证

最终单元测试按模块各计一次：API 114、query 345、schema 251、Mongo 285、ES 231、Spring 19、WebFlux 308、CoSec 6、starter 236、OpenAPI 140，合计 **1,935**。真实 MongoDB 6.0.6 集成 **172**，Elasticsearch 9.2.6 集成 **151**，合计 **323**；总计 **2,258**，失败/错误/跳过均为零。

`final-check-2.log` 中两后端集成已全部完成，但当轮仍因 ES 格式规则退出非零；格式修正后 `final-check-3.log` 的十个相关模块 check、benchmark detekt 和 JMH 构建整体成功。首次新 ES 测试的 Query builder 重载歧义和旧 fixture 隐式丢弃缺 source hit 的假设也已修正，未放宽生产完整性检查。

冻结的 Java/Kotlin caller class 哈希不变：实际执行 20 次 API 调用；7 份 JVM 描述符、11 份通用 JSON、Snapshot/Event 专属物化样例一致。原始 Java/Kotlin 命名参数调用源码另编译到新目录并执行，未覆盖旧 class。链接和 wire 验证不代替上述真实 Backend 集成测试。

三个独立实现包由不同审查代理复核，共享 Schema/保护索引另经独立审查；最终跨模块审查与协调者验证共同收口。所有代码和文档保留在当前工作区，未提交、推送或发布。

原生测试使用仓库现有 Testcontainers fixture 新建的一次性容器，未使用已有 benchmark 服务。测试进程结束后容器已回收；外部已有容器未操作。原始 RED、GREEN、XML 汇总、API 校验、冻结基准和修复前源文件副本在 `build/query-architecture-migration/review-fixes/`；历史 review 保留在 `full-review/REVIEW.md`。

### 17.2 明确保留的边界

- ES source 裁剪后，ALL 与 exclude-only 请求保守拒绝；调用者可显式 include 完整可交付字段。count 与无 source 依赖的聚合仍可用。不能将这个支持范围表述为任意裁剪模式都可恢复完整文档。
- Mongo 并行数组检查针对 Schema 已知的独立数组；真实数据违背受信声明仍属于执行错误或未观测的数据合同违约。没有把公共元素作用域限制放宽成任意数组后代排序。
- 保护索引避免扫描无关字段；存在真实别名交集或宽泛动态模板时，仍需遍历相关关系。构建不包含 JSON Schema 生成、元数据 I/O，性能数字不等同于端到端查询提速。
- review 末尾的 ES 数值量化精度和多值数值表达式合同观察未计入 F01–F13，也未在本轮偷偷改变合同；它们不作为已解决问题宣传。

## 18. 本轮债务清理验收 {#debt-cleanup-acceptance}

本轮按用户要求保留旧 `Condition`、`Operator`、`ConditionDsl` 及其转换、构造器和 REST 兼容边界至 10.0.0。这是明确保留的兼容合同，不是本轮未清债务；内部 Schema/Backend/Filter SPI 不因此恢复兼容桥。

默认删除范围仅由 Snapshot Gateway 在最终公共校验前决定。`FilterNormalizer` 与两后端 Compiler 不注入 `ACTIVE`；直接 Backend 调用的 `MatchAllFilter` 不限定删除状态，调用者必须显式提供所需范围。Gateway 原有 ACTIVE/ALL/DELETED 规则保持。

数值合同保留已有 singleton 数组支持：直接 `FIELD` 与 `BINARY` 字段叶子均按当前记录忽略 null 后恰好一个存储数值参与，零个或多个不贡献值，重复值分别计数。已声明标量保留原生 metric 快路径与精度；数组/混合形状规范化贡献值，算术采用有限 Double。运行时数据须符合逻辑数值模型，不增加 source 扫描、数组 zip/笛卡尔积或公开 Schema 精度字段；Histogram/DateHistogram 的分桶合同保持。

数值 EQ/IN/RANGE 遵循原生存储精度。ES `scaled_float` factor=10 的 source=1.04 可以命中 EQ(1.01)、不命中 EQ(1.11)，double 对照不命中 EQ(1.01)。count 统计原生条件命中的记录，不是任意精度 source 等值验证，也不是 NUMERIC 参与值数量。公开合同及原生依据见[数值参与值与精度](../docs/zh/guide/query/aggregation-query.md#numeric-contributions)和[数值比较](../docs/zh/guide/query/filter-expression.md)。不承诺大整数/Decimal128/舍入边界的 SUM(x) 与 SUM(x+0) 逐位相等。

本轮已完成清理。最新单测按模块各计一次：API 114、query 345、schema 251、Mongo 286、ES 233、Spring 19、WebFlux 308、CoSec 6、starter 236、OpenAPI 140，共 **1,938**。实库 MongoDB 6.0.6 为 **173**、Elasticsearch 9.2.6 为 **153**，共 **326**；合计 **2,264**，失败、错误和跳过均为零。第 17 节的 2,258 属于清理前版本，未与本轮相加。

三条默认范围反例先失败后通过；数值反例先证实 Mongo `[null,7]` 未贡献、ES 直接多值指标仍聚合全部值，再验证两端的 root/nested、数组/nullable/混合形状及实际 scalar 分支一致。矩阵同时覆盖 SUM/AVG/MIN/MAX、直接 FIELD 与 x+0、空数组/null/singleton/多值/重复值，以及 ES scaled_float 与 double 对照。原有 singleton 数组、空 summary、除零、溢出、Decimal 与展开聚合 TCK 继续通过。

`debt-cleanup/full-check.log` 中所有测试和两后端集成已通过，但初次因 Mongo/ES 格式检查失败退出非零；`format.log` 修正格式后，`final-check.log` 的十个相关模块 check、benchmark detekt 与 JMH 构建整体成功。没有将初次非零退出记录成全项成功。

QueryGateway 的原冻结 Java/Kotlin 调用方、7 份 JVM 描述符、11 份通用 JSON、模型专属 JSON 和独立源码编译运行验证通过。45 个相关公共 API/DSL/legacy 源文件与清理前逐字节一致；API 另两个文件仅补充数值合同 KDoc。旧 Condition 兼容入口、转换、构造器及 10.0.0 移除约定保持。

默认规则与数值实现分别经过独立审查，并完成跨模块复核。Schema 仍提供事实，模型默认归 Gateway，数值表示及执行归原生 Backend。ES 标量指标的原生路径有回归断言；数组/混合字段为统一贡献值使用已有 runtime 表达式。此次验证 JMH 构建，未新增吞吐提速结论；历史性能数值保留其冻结来源。

验证产物集中在 `build/query-architecture-migration/debt-cleanup/`：`test-results.json`、`api/verification.json`、`compatibility-source-check.json`、`numeric-red.log`、`numeric-green.log`、`final-check.log`、`final-freeze.json` 和独立审查报告。临时 Testcontainers 已回收，现有 benchmark 服务未操作。交付留在当前工作区，未提交、推送或发布。


## 19. PR 前深度复核验收 {#pr-readiness}

提交前将完整重构差异移到最新 `origin/main` 基线 `fd59bbdfb185dea1d401d827881b3477d91d73c3`，保留全部查询改动。该基线已使用 Kotlin 2.4.20；旧性能报告继续绑定原冻结产物，不把新编译器的构建结果替代历史测量。版本仍为 9.0.10，本 PR 不承担破坏性版本发布。

独立审查覆盖 Schema/JSON/Mask、Mongo、Elasticsearch，协调者核对 Gateway/Scope/ABAC/HTTP、消费者及兼容边界。复核发现以下五项问题，均已修复并经过另一位审查者复核：

| 编号 | 根因与修复所有者 | 永久回归及原生证据 |
|---|---|---|
| S1 / P1 | 新增 named property 覆盖原受保护 additionalProperties 时丢失 Mask。声明合并先继承既有保护，再合并显式叶；同优先级源顺序和结构替换同样保留保护 | QuerySchemaMaskInheritanceTest：nested/dotted、实际脱敏输出、对象/数组、换型拒绝、双向源顺序；同一个原始声明的 public named key 仍合法 |
| S2 / P2 | nullable allOf 的非 NULL 域相离时，共同 NULL 被错误丢弃。JSON 声明交集保留双方及 enum 都允许的 NULL；不能通过收窄移除 Mask | JsonQuerySchemaSourceTest：nullable/null-only、disjoint nullable、UNION、enum/required/metadata、Mask 拒绝及非 nullable 反例 |
| M1 / P2 | Mongo scalar/array 联合域绕过并行数组排序检查。原生 Sort Compiler 递归识别任意 ARRAY 分支 | MongoNativeConstraintsTest 与真实 Mongo 并行数组反例；AbstractMongoQueryBackendTest 验证 paged 在 count/find 前失败，scalar 对照合法 |
| M2 / P2 | Mongo 原有 number 家族别名与 long/int 做字面交集时错误清空存储类型。Validator Adapter 在集合合并前展开为四种具体 BSON 数值类型 | Adapter 双向 allOf、非数值交集反例；真实 validator 元数据经 Adapter 与 Compiler 生成条件，查询 NumberLong 记录 |
| E1 / P2 | ES 数值表达式和 epoch runtime field 的宽泛 catch 把下层脚本失败变成 null/空结果。原生 Compiler 删除吞错，保留缺失值、有限值、除零和溢出守卫 | ElasticsearchNumericContractTest：scalar/array FIELD、BINARY 及 epoch 分组五种映射脚本错误均以执行错误终止；原有数值参与合同继续通过 |

最终单测按模块各计一次：API 114、query 351、schema 254、Mongo 289、ES 233、Spring 19、WebFlux 308、CoSec 6、starter 236、OpenAPI 140，共 **1,950**。实库 MongoDB 6.0.6 **174**、Elasticsearch 9.2.6 **158**，共 **332**；合计 **2,282**，失败、错误、跳过均为零。第 18 节的 2,264 是清理阶段计数，不与本节相加。

修复后的四个核心模块单测和两后端完整集成测试在 `deep-fixes-green.log` 整体成功。整理过大的 Mask 测试类及格式后，十个相关模块 check、benchmark detekt 和 jmhJar 在 `final-check-2.log` 整体成功；前面的格式失败记录不视为成功验收。额外 mixed paged 零 I/O 回归随最终 Mongo 全量单测通过。

冻结 Java/Kotlin caller class 保持原哈希，实际链接及调用执行通过；7 份 JVM 描述符、11 份通用 JSON、Snapshot/Event 专属 JSON 一致，独立源码编译与运行通过。QueryGateway API 和旧 Condition/Operator/ConditionDsl 兼容栈保持；内部 Schema/Backend/Filter SPI 和递归 schema metadata 的迁移要求仍见迁移指南。

Schema 继续提供结构及原生事实，Gateway 负责公共治理和默认删除范围，Backend 负责原生表示与执行，WebFlux 负责传输边界。没有新增通用执行计划、兼容模式、依赖或模块。数值精度和 singleton 数组合同、ES 裁剪 source 的支持范围以及受信声明前提沿用第 17–18 节；本轮只验证 JMH 构建，不新增性能提升结论。

文档构建、2 项文档测试、172 个相对链接以及主设计 Mermaid 解析通过。

验证产物在本地 `build/query-architecture-migration/pr-preflight/`，包括独立审查、原生 RED/GREEN、`test-results.json`、`api/verification.json` 和最终检查日志。临时容器已回收，原有 benchmark 服务未操作。本节记录提交前证据；GitHub CI 与 Codex review 的结果以 PR 最新提交对应的审核记录为准，不以本地复核代替。

## 20. 通用策略与 WebFlux 接入验收 {#query-policy-acceptance}

本节记录 2026-09-09 的代码提交 `b9e43876a19655af36ba62f1acf7779175a25a13`，对应 [PR #3213](https://github.com/Ahoo-Wang/Wow/pull/3213)。它补齐第 19 节之后的通用策略接入；版本属性为用户指定的 9.0.11，但记录时发布和 dev/prod 更新仍暂停，不能据版本属性宣称制品已发布。

| 职责 | 该提交的合同与验证 |
|---|---|
| Gateway | `AbstractQueryGateway` 持有策略列表快照，统一执行 Snapshot/EventStream 的 `QueryPolicy`。所有 prepare 完成后，以捕获的身份和同一准备上下文评估策略，AND 合并后应用默认条件和公共校验；不再提供可绕过该列表的子类钩子 |
| QueryFilter / QueryPolicy | prepare 可替换逻辑请求；Policy 只返回附加 `FilterExpression` 或错误。重复订阅重新取得身份，空 Publisher、同步抛错和异步错误都阻止后续策略及 Backend 调用 |
| 模型差异 | 两个 Spring Registrar 都装配 `QueryPolicy`。具体策略以 `QueryContext` 判断适用范围；ABAC 仅在 Snapshot 读取标签，其他模型返回 `MatchAllFilter`。Snapshot 默认删除范围保持，EventStream 不增加删除谓词 |
| Schema / Backend | Schema 继续保存不可变结构、能力、绑定和保护事实；Gateway 调用公共校验，Backend 接收 `(query, schema)` 并完成原生编译执行。直接 Backend 调用仍是需要显式提供范围和准入的受信基础设施入口 |
| WebFlux | Handler 提取请求 scope 并写入 Reactor Context，独立执行 HTTP 限额；容器 Gateway 执行同一策略链。HTTP Handler 无需增加第二套策略执行机制 |

永久回归包括 `QueryGatewaySubscriptionTest`、`DefaultEventStreamQueryGatewayTest`、`AbacQueryPolicyTest`、`QueryAutoConfigurationTest` 与 `QueryPolicyWebFluxTest`。EventStream 覆盖全部十个 Gateway 方法及二十次独立身份订阅。HTTP 用例使用真实 Spring 注册器和 Handler，分别对两模型的 list/count/aggregation 发起十二次成功请求与六次拒绝请求，检查最终谓词、403 响应和 Backend 零调用。临时移除 EventStream 策略注入后，两项 HTTP 用例分别捕获 owner 约束丢失和 403 变 200；原文件按字节恢复后回归通过。

五个相关模块 check 通过，测试分别为 query 355、Spring 19、starter 238、WebFlux 308、CoSec 6，共 **926**，无失败、错误或跳过。冻结 Java/Kotlin 调用方、七份 JVM 描述符、通用及模型 JSON 对照通过；文档构建及两项文档测试通过。独立代码复核无阻断；记录时该提交的 GitHub CI 全部通过。Codex 提出的三个实现层兼容桥建议按用户明确允许实现 SPI 破坏的范围不采纳，图表意见已修复，讨论已逐条回复并关闭；这不等同于自动审查从未提出意见。

HTTP 证据使用进程内 WebTestClient、记录型 Backend 和手工组装的路由合同，证明容器 Gateway 与实际 Handler 的接入；不证明完整应用启动、路由发现、真实存储过滤或生产性能。该策略增量没有新增存储实测或性能提升结论，完整查询重构的原生证据仍按前文各自提交和环境解读。QueryGateway API 与旧 Condition 兼容栈保持，后者按约定到 10.0.0 才移除。

本地证据分别位于 `build/query-architecture-migration/event-stream-policy/`、`webflux-query-policy/` 与 `review-b9e43876a/`。后续文档或 skill 改动不得改写这些已冻结的验证结论。
