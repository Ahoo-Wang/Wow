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

## 3. REST（D16）

- **身份来自主体，不来自路径**：租户、所有者、空间取自认证主体的作用域；管理员跨作用域时用唯一的作用域请求头。9.x 按租户、所有者变体复制的路由不再复制。
- 命令：`POST /{context}/{aggregate}/{id}/{command}`；创建：`POST /{context}/{aggregate}/{command}`。
- 等待：`Prefer: wait=committed`（默认）、`wait=snapshot`、`wait=handled;subscription=…`；流式等待用 SSE。
- 错误：RFC 9457 `application/problem+json`，`type` 指向 Manifest 中的错误码。
- 查询：`POST /{context}/{aggregate}/{model}/query`，`model` 为 `snapshot`、投影名或 `events`。
- 历史状态：`GET /{context}/{aggregate}/{id}/state?version=` 或 `?at=`，以及逐事件追溯 `GET …/{id}/state/trace`（D40，[03](03-log-and-snapshot.md) §4）。

## 4. TypeScript

- `wow-client` 只保留 `FilterExpression` 与 Manifest 驱动的客户端，删除 `/legacy` 与 fetcher 旧名。
- `wow-generator` 读 Manifest，不读 OpenAPI。
- view-engine 从 Manifest 派生默认视图。

## 5. 待细化

- Manifest 的 schema、版本与组装方式（编译期产物与运行时数据集注册如何合并）。
- 主体作用域模型与 CoSec 的对接。
- OpenAPI 投影的保真度（是否保留 `x-wow-*` 供第三方工具）。
- view-engine 的哪些能力描述改由 Manifest 承担。
