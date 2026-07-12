---
title: OpenAPI
description: Wow OpenAPI 模块提供基于 OpenAPI 规范的 API 接口。
---

# OpenAPI

> Wow OpenAPI 模块提供了基于 [OpenAPI](https://swagger.io/specification/) 规范的 API 接口。

## 安装

::: code-group

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-openapi")
```

```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-openapi'
```

```xml [Maven]

<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-openapi</artifactId>
    <version>${wow.version}</version>
</dependency>
```

:::

## Swagger-UI

> Swagger-UI 是一个基于 OpenAPI 规范的 API 文档工具，可以通过 Swagger-UI 来查看和测试 API 接口。

![Swagger-UI](../../public/images/compensation/open-api.png)

## 聚合资源归属

一个聚合根资源可以归属于租户、空间和拥有者中的一种或多种。

## RESTful URL PATH Spec

`[tenant/{tenantId}]/[owner/{ownerId}]/resource/[{resourceId}]/action`

### 租户资源

当聚合根为租户资源时（未标记静态租户ID），自动生成的 RESTful API 会添加 `tenant/{tenantId}` 路径前缀。

### 空间资源

当聚合根为空间资源时，自动生成的 RESTful API 会添加 `Wow-Space-Id` 请求头参数。

### 拥有者资源

当聚合根被标记为拥有者资源时，自动生成的 RESTful API 会添加 `owner/{ownerId}` 路径前缀。

```kotlin
@AggregateRoot
@AggregateRoute(owner = AggregateRoute.Owner.ALWAYS)
class Order(private val state: OrderState)
```

当聚合根 ID 与拥有者 ID 相同时，自动生成的 RESTful API 会将 `{resourceId}` 路径参数移除。比如用户购物车ID即是用户ID时：

```kotlin
@StaticTenantId
@AggregateRoot
@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)
class Cart(private val state: CartState)
```

## 全局路由

### 获取 Wow 元数据

该路由提供了通过 RESTful API 获取 *Wow 编译时元数据*的能力，以便验证 Wow 元数据(`WowMetadata`)
定义的正确性。

::: code-group

```shell [OpenAPI]
curl -X 'GET' \
  'http://localhost:8080/wow/metadata' \
  -H 'accept: application/json'
```

```json [响应]
{
  "contexts": {
    "example-service": {
      "alias": "example",
      "scopes": [
        "me.ahoo.wow.example.server",
        "me.ahoo.wow.example.domain",
        "me.ahoo.wow.example.api"
      ],
      "aggregates": {
        "cart": {
          "scopes": [
            "me.ahoo.wow.example.api.cart"
          ],
          "type": "me.ahoo.wow.example.domain.cart.Cart",
          "tenantId": "(0)",
          "id": null,
          "commands": [
            "me.ahoo.wow.example.api.cart.ChangeQuantity",
            "me.ahoo.wow.example.api.cart.RemoveCartItem",
            "me.ahoo.wow.example.api.cart.AddCartItem"
          ],
          "events": [
            "me.ahoo.wow.example.api.cart.CartItemAdded",
            "me.ahoo.wow.example.api.cart.CartQuantityChanged",
            "me.ahoo.wow.example.api.cart.CartItemRemoved"
          ]
        },
        "order": {
          "scopes": [
            "me.ahoo.wow.example.api.order"
          ],
          "type": "me.ahoo.wow.example.domain.order.Order",
          "tenantId": null,
          "id": null,
          "commands": [
            "me.ahoo.wow.example.api.order.ChangeAddress",
            "me.ahoo.wow.example.api.order.ShipOrder",
            "me.ahoo.wow.example.api.order.PayOrder",
            "me.ahoo.wow.example.api.order.ReceiptOrder",
            "me.ahoo.wow.example.api.order.CreateOrder"
          ],
          "events": [
            "me.ahoo.wow.example.api.order.OrderShipped",
            "me.ahoo.wow.example.api.order.OrderCreated",
            "me.ahoo.wow.example.api.order.AddressChanged",
            "me.ahoo.wow.example.api.order.OrderReceived",
            "me.ahoo.wow.example.api.order.OrderPaid"
          ]
        }
      }
    },
    "compensation-service": {
      "alias": "compensation",
      "scopes": [
        "me.ahoo.wow.compensation"
      ],
      "aggregates": {
        "execution_failed": {
          "scopes": [
            "me.ahoo.wow.compensation.api"
          ],
          "type": null,
          "tenantId": "(0)",
          "id": null,
          "commands": [],
          "events": []
        }
      }
    }
  }
}
```

:::

### 生成 BI 同步脚本

`POST /wow/bi/script` 生成当前本地聚合的 ClickHouse 同步与展开 SQL，并要求提供 `application/json` 请求体。OpenAPI schema 列出全部请求字段：`database`、`consumerDatabase`、`topology`、`timezone`、`kafkaBootstrapServers`、`topicPrefix`、`maxExpansionDepth` 和 `unsupportedTypeStrategy`；嵌套拓扑字段为 `mode` 与 `cluster`，嵌套集群字段为 `name`、`installation`、`shard` 和 `replica`。schema 同时列出枚举值 `CLUSTER` / `STANDALONE` 与 `FAIL` / `RAW_JSON`。

| 状态 | `Content-Type` | 响应体 |
|------|----------------|--------|
| `200` | `application/sql` | 仅 SQL 文本 |
| `400` | 错误响应 | 空或无效 JSON 请求体、无效选项值或无效拓扑组合 |
| `415` | 错误响应 | 缺少或不支持的请求 `Content-Type` |

`{}` 保持服务端选项不变；非 `null` 请求字段只在本次生成中覆盖对应的服务端选项。提供 `topology` 时必须提供 `topology.mode`。在 `CLUSTER` 模式下，省略的集群字段继承当前集群服务端基础配置；如果服务端基础配置是独立模式，则继承领域集群默认值。`STANDALONE` 拒绝 `cluster` 对象。旧版 `GET` 方法在该路径上没有路由并返回 `404`。生成诊断逐条写入 WARN 日志，绝不会混入响应体。

::: code-group

```shell [空覆盖请求]
curl -X POST 'http://localhost:8080/wow/bi/script' \
  -H 'content-type: application/json' \
  -H 'accept: application/sql' \
  --data '{}'
```

```json [独立模式请求]
{
  "database": "analytics",
  "topology": {
    "mode": "STANDALONE"
  }
}
```

```json [部分集群请求]
{
  "topology": {
    "mode": "CLUSTER",
    "cluster": {
      "name": "production"
    }
  },
  "kafkaBootstrapServers": "kafka:9092",
  "topicPrefix": "analytics."
}
```

```http [代表性响应开头]
HTTP/1.1 200 OK
Content-Type: application/sql

-- global --
CREATE DATABASE IF NOT EXISTS "bi_db" ON CLUSTER '{cluster}';
CREATE DATABASE IF NOT EXISTS "bi_db_consumer" ON CLUSTER '{cluster}';
```

:::

当前展开 schema、结构化类型、原始值语义和无损标量映射参见[商业智能](./bi)；路由配置与 Kafka/topic 优先级参见[BI 脚本配置](./configuration#bi-脚本配置)。

### 生成全局 ID

该路由提供了通过 RESTful API 生成*全局ID*的能力。

::: code-group

```shell [OpenAPI]
curl -X 'GET' \
  'http://localhost:8080/wow/id/global' \
  -H 'accept: text/plain'
```

```text [响应]
0U2MNGBQ0001001
```

:::

## 聚合路由规范
