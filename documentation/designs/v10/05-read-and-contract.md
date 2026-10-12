# V10 读平面与契约

日期：2026-10-11。基线：`main` `b5813af56f`。状态：草案。上位文档：[00 目标架构](00-architecture.md)（A9、A11）。相关决定：D4、D16、D17、D18。

## 1. 读平面

[查询目标架构](../2026-09-24-query-target-architecture-design.md)与[数据集平台](../2026-10-11-dataset-platform-design.md)基本保留，它们已经是从第一性原理推出的。V10 只做四件事：

- 查询协议独立为 `wow-query-api`，只保留 `FilterExpression`，删除 Condition 全链路。
- 快照是默认读模型（[03](03-log-and-snapshot.md)），自定义投影只补快照满足不了的需求；查询后端（Mongo、ES、PostgreSQL、ClickHouse）只是它们的存储。
- 事件流可按聚合、按时间查询，用于审计与时间线。
- 数据集源表由 ClickHouse Sink 写入，与企业自己的表一视同仁。

## 2. Manifest

`wow-compiler` 在编译期生成 Manifest，服务端在运行时发布同一份（D17）。一个服务的领域往往分散在多个 Gradle 模块与 jar 里，KSP 只能看到当前模块（R6）：所以每个模块产出一个 Manifest **片段**与绑定索引，放在固定的资源路径下；运行时启动时汇总全部片段，同一上下文、聚合、命令或事件类型名重复声明即启动失败。内容：限界上下文、聚合、命令与事件的 schema 及 revision、每个命令可能产出的事件类型（由签名推出，编译期校验 decide 只产出声明过的事件）、身份规则、路由、错误码、查询能力、订阅与等待阶段、发布的事件流。

```text
注解 / DSL ─▶ wow-compiler ─▶ Manifest ─┬▶ HTTP 路由（运行时）
                                       ├▶ OpenAPI（投影）
                                       ├▶ wow-generator ─▶ TS 客户端
                                       ├▶ view-engine 的能力与默认视图
                                       └▶ Agent Skills
```

**读者**：运行时（命令按类型名解码、HTTP 路由、等待链校验、错误码、查询能力）、OpenAPI / TS 生成器 / view-engine、其他上下文（发布的 `StateEvent` 是公开语言，D23、D58）、迁移工具与 Skill（事件名、revision、别名）、兼容性门禁。

**结构**（草案，随 5a.1 定稿）：

```text
{
  "format": 1,
  "service": { "name", "version" },
  "contexts": [{
    "name", "description",
    "aggregates": [{
      "name", "description",
      "state":    { "schema": "#/$defs/…", "fingerprint" },
      "snapshot": { "enabled" },
      "commands": [{ "name", "aliases", "schema", "creation",
                     "produces": [事件名…], "errors": [错误码…],
                     "route": { "method", "path" } }],
      "events":   [{ "name", "aliases", "revision", "schema" }],
      "lifecycle":[启用的系统命令]
    }],
    "subscriptions": [{ "name", "kind": "PROJECTION | REACTOR | SAGA",
                        "source": { "context", "aggregates", "events" },
                        "function": FunctionInfo, "derives": [命令名…] }],
    "published": [{ "topic", "aggregates", "includeState" }],
    "queries":   [{ "model": "snapshot | 投影名 | events", "capabilities" }],
    "errors":    [{ "code", "status", "description" }]
  }],
  "$defs": { JSON Schema 2020-12 }
}
```

- **schema 按 Jackson 的实际行为生成**（D73）：KSP 产出结构与类型清单（片段）；构建期的 Gradle 任务加载编译后的类，用 Jackson 自己的序列化器内省出 JSON Schema。schema 因此就是线上的真实形状（命名策略、`@JsonProperty`、自定义序列化器、Kotlin 默认值都由 Jackson 决定，不在 KSP 里重新实现）；状态 schema 指纹（快照，[03](03-log-and-snapshot.md) §3）也由它计算。任务由 `wow-gradle-plugin` 提供（D83），生成器用 victools 加其 Jackson 模块，自定义序列化器由显式覆盖配置补上（D84）。内省只在构建期，运行时仍不反射（D37）。
- **组装与发布**（D74）：每个模块的片段放在 `META-INF/wow/manifest/<模块>.json`，运行时启动时合并，名字冲突拒绝启动；Gradle 任务同时合并出服务的 `manifest.json` 供离线工具使用。服务在公开端口发布 `GET /wow/manifest`，按普通 API 鉴权；跨上下文的下游从上游的构建产物或该端点取得，构建期生成类型化的消费代码。
- **v0 的实现要点**（Ahoo-Wang/Wow#4259）：
  - `wow-manifest` 是不带依赖的纯数据模型，合并函数一次报出全部冲突：同一上下文出现在两个片段、聚合重复、命令或事件全名重复（别名也算）、订阅名重复、同一 `$defs` 键的 schema 不同、片段格式不同。
  - KSP 在 `META-INF/wow/manifest/<模块>.json` 写出片段，schema 先以类的二进制名占位。上下文由 KSP 选项 `wow.context` 指定，缺省取模块里唯一的 `@BoundedContext`，都没有时只告警、不写片段。签名推不出产出哪些事件时（如 `Decision`、`Any`）省略 `produces`。
  - 插件 `me.ahoo.wow` 提供两个任务：`generateWowManifestFragment` 补全片段 schema，`mergeWowManifest` 合并出 `build/wow/manifest.json`（暂不挂到 `assemble`）。内省代码在 `wow-manifest-tools`，由用户项目里的 `wowManifestTools` 配置解析，只进隔离 Worker 的类路径，不进构建脚本的类路径（D83）。
  - Jackson 写出来不是对象的类型（自定义序列化器、`@JsonValue`），要在 `schemaOverrides` 显式给出 schema，否则任务失败，不猜（D84）。`$defs` 的键是类的二进制名，只引用一次的类型内联。
  - 状态 schema 指纹调用内核的 `EventCodec.stateSchemaFingerprint`，与运行时一致。限制：运行时若定制了 ObjectMapper，构建期的值会不同。
- **数据集不并入**（D75）：数据集由 dataset-registry 在运行时注册，作为查询侧的运行时目录单独提供；Manifest 只描述编译期的代码契约，view-engine 两者都读。
- **兼容性门禁**（D76）：CI 比较本次构建与上一个已发布版本的 Manifest，把差异分为加法（新增可选字段、新增命令）与破坏（删除命令或事件、新增必填字段、事件 schema 变化却没有升 revision 或没有升级函数）。主版本内出现破坏即失败（D48、D59、R5-1）；线上契约的兼容性由它守，不靠 ABI 转储。


## 3. REST（D16）

- **身份来自主体，不来自路径**：租户、所有者、空间取自认证主体的作用域；管理员跨作用域时用唯一的作用域请求头。9.x 按租户、所有者变体复制的路由不再复制。
- 命令：`POST /{context}/{aggregate}/{id}/{command}`；创建：`POST /{context}/{aggregate}/{command}`。
- 等待：`Wow-Wait` 与 `Wow-Wait-Timeout` 请求头，默认 `snapshot`（D69）；链的写法与流式等待（SSE）见 [04](04-subscriptions.md) §6.1。
- 错误：RFC 9457 `application/problem+json`，`type` 指向 Manifest 中的错误码。
- 查询：`POST /{context}/{aggregate}/{model}/query`，`model` 为 `snapshot`、投影名或 `events`。
- 历史状态：`GET /{context}/{aggregate}/{id}/state?version=` 或 `?at=`，以及逐事件追溯 `GET …/{id}/state/trace`（D40，[03](03-log-and-snapshot.md) §4）。

## 4. TypeScript

- `wow-client` 只保留 `FilterExpression` 与 Manifest 驱动的客户端，删除 `/legacy` 与 fetcher 旧名。
- `wow-generator` 读 Manifest，不读 OpenAPI。
- view-engine 从 Manifest 派生默认视图。

## 5. 待细化

- Manifest 字段的定稿（5a.1）与 JSON Schema 内省对 Kotlin 特性（值类、sealed 层级、默认值）的覆盖。
- 主体作用域模型与 CoSec 的对接。
- OpenAPI 投影的保真度（是否保留 `x-wow-*` 供第三方工具）。
- view-engine 的哪些能力描述改由 Manifest 承担。
