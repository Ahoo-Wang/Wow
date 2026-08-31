---
title: API 客户端
description: 通过 CoApi 调用 Wow 命令与快照 HTTP 合同，并保持路由、等待阶段、查询 Schema 与服务端护栏边界。
---

# API 客户端

`wow-apiclient` 为 Wow 通用命令入口与快照查询路由提供手工维护的 CoApi 接口。它是传输适配器：

```text
CommandRequest / Query DTO
  -> CoApi HTTP exchange
  -> 生成的 WebFlux 路由
  -> 命令管线或受护栏约束的查询管线
```

它不会生成服务端路由、发现聚合专用字段、创建授权请求头，也不会把投影变成服务端 `QueryGateway`。运行服务的 OpenAPI 文档仍是路径和线协议的事实来源。

命令客户端的注册、`CommandRequest`、目标服务解析、响应式/同步调用、等待能力与错误映射统一见[命令 API Client](../command/api-client.md)。本页保留扩展安装与快照查询合同。

## 特性

- 使用 `Mono`、`Flux` 的响应式命令与查询 API。
- 为明确采用同步 I/O 的调用方提供阻塞式同步 API。
- 通过 `/wow/command/send` 发送通用命令并携带等待计划请求头。
- typed、仅状态和 dynamic 三种快照查询响应形状。
- single、list、paged、精确 count，以及显式选择的 aggregation 调用。
- 支持 CoApi load-balanced 命令网关。

`wow-apiclient` 不是 OpenAPI 代码生成器。Fetcher 或其他下游工具可以从 `/v3/api-docs` 生成另一套客户端；生成 diff 必须单独审阅。

## 安装

添加 Wow client 与 CoApi Spring Boot Starter：

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-apiclient")
implementation("me.ahoo.coapi:coapi-spring-boot-starter")
```

注册需要由 CoApi 物化的准确接口：

```kotlin
@EnableCoApi(
    clients = [
        CartQueryClient::class,
    ],
)
@SpringBootApplication
class ClientApplication
```

## 快速开始

### 1. 声明查询客户端

把查询接口绑定到聚合路由基址。以下示例指向无作用域的 `/cart/...` 路由：

```kotlin
@CoApi(baseUrl = "http://order-service:8080")
@HttpExchange("cart")
interface CartQueryClient : ReactiveSnapshotQueryApi<CartData>
```

`ReactiveSnapshotQueryApi<S>` 组合 single、list、paged、count 接口。其继承的 `@PostExchange` 路径都相对 `@HttpExchange`：`snapshot/single`、`snapshot/list`、`snapshot/paged`、`snapshot/count`，以及仅状态变体。

当 CoApi 或应用约定需要具体泛型元数据时，应像仓库示例客户端一样，以具体返回类型和 `@RequestBody` 重新声明方法。不要在每个方法重复路由路径。

`@HttpExchange("cart")` 调用基础无作用域快照查询变体。调用 tenant/owner 作用域变体时，应通过应用自有接口或路由层绑定生成路径并提供所需值。基础路由必须被显式保护；选择作用域客户端路径并不等于授权。不要猜测带 context 前缀的 URL，应检查服务端 OpenAPI。

### 2. 声明命令客户端

命令客户端的注册与调用统一见[命令 API Client](../command/api-client.md)。

### 3. 注入并使用

```kotlin
@Service
class CartApplicationService(
    private val carts: CartQueryClient,
) {
    fun getCart(id: String): Mono<CartData> = carts.getStateById(id)
}
```

`getStateById` 会把 HTTP 404 转换为空 `Mono`，其他查询错误继续传播。

### 服务发现

查询客户端不同：目标由 `@CoApi(baseUrl)` 与 `@HttpExchange` 基址决定。服务发现和路由作用域属于应用配置，不会从 Query DTO 推导。命令目标解析属于[命令 API Client](../command/api-client.md#目标服务解析)。

## 快照查询

快照数据查询、state-only/dynamic 结果、404 语义及独立聚合 API 见[查询 API 客户端](../query/query-api-client.md)。

## 错误处理

查询客户端只会把 single 查询的 404 规范化为空/null；校验、授权、限流、超时与后端错误仍作为传输错误交给应用处理。查询也只应重试被应用策略认定为瞬态的错误；查询 Schema 校验或 HTTP 护栏拒绝不会因重复请求而变为合法。命令错误映射与重试边界见[命令 API Client](../command/api-client.md#错误映射)和[失败与幂等](../command/reliability.md)。
