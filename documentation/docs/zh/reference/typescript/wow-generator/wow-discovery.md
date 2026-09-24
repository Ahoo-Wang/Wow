---
title: 'Wow 聚合识别'
description: 'Wow 聚合识别 — @ahoo-wang/wow-generator'
---

# Wow 聚合识别

识别属于生成器行为，不是公开的 AggregateResolver API。仅有带点号的 operationId 不够。下表反映当前实现，包括 requestBody 组件引用和 response 引用别名链。

## 识别矩阵

| 输入             | 命中条件                                                                                                                              | 缺失/形状错误                                     |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| 聚合 tag         | tag 精确形如 `context.aggregate`，两段均非空，可声明在根 `tags` 中，也可只出现在操作上                                                | 其他 tag 名不会建立聚合候选                       |
| 命令 operationId | 恰好三段点号分隔，末段为命令名                                                                                                        | 缺失/段数错误跳过；排除 `wow.command.send`        |
| 命令响应         | 成功响应（`200`，否则最小的其他 2xx）的 `$ref` 直接或经 response 组件别名链到 `#/components/responses/wow.CommandOk`                 | 内联等价响应不识别为命令；循环引用抛错            |
| 命令请求体       | 内联 RequestBody **或已解析的组件 requestBody**，其 application/json schema `$ref` 指向可解析模型                                     | 缺失/JSON schema 非引用：跳过该命令并给出警告     |
| 状态操作         | operationId 后缀 `.snapshot_state.single`，成功响应的 application/json schema 为引用                                                  | schema 非引用则失败（退出码 4）；没有状态操作则跳过该聚合并给出警告 |
| 字段操作         | operationId 后缀 `.snapshot.count`，请求体包含 `x-wow-query-fields: { $ref: ... }`，或 JSON condition schema 的 properties.field 引用 | 没有请求体、`x-wow-query-fields` 非引用或找不到 field 引用则失败（退出码 4）；没有字段操作则跳过该聚合并给出警告 |
| 操作 tags        | 必须匹配聚合 tag，命令/状态/字段才能附着                                                                                              | 未匹配 tag 忽略                                   |
| 路由段           | 状态或字段路由中 `/snapshot` 之前的一段（`/tenant/{tenantId}/sales-order/snapshot/count` → `sales-order`）                            | 默认取聚合名；作为查询客户端工厂的 `aggregateName` |
| 事件（可选）     | `.event.list_query`；响应数组 items 引用流 schema，其 properties.body.items.anyOf 变体含 name.const 和 body 引用                      | 缺失时事件联合为空；事件流 schema 格式错误则失败（退出码 4） |

解析器要求同时存在状态和字段，命令与事件可以为空。暴露了 Wow 路由但缺少其中之一的聚合会被跳过，警告会点名缺少的操作，其操作也不会退回到普通 API 客户端。Wow 元数据格式错误时以退出码 4 失败，消息会点名该操作，并说明生成器读取 Wow 8.10 及以后版本的文档。两段根 tag 确定上下文/聚合；operationId 文本和 tag 并不构成语义一致性校验。`info['x-wow-context-alias']` 另行影响普通客户端 basePath，并让文档在 `ignorePathParameters` 默认值上被视为 Wow 文档。

## 完整最小输入

保存为 `wow.json`，使用相同 CLI 命令并传 `-i ./wow.json`。示例明确使用内联命令 RequestBody、200 CommandOk 引用和两个必需查询操作。结果应在 shop/order 命名空间下包含 commandClient.ts 与 queryClient.ts。

```json
{
  "openapi": "3.0.3",
  "info": {
    "title": "Orders",
    "version": "1"
  },
  "tags": [
    {
      "name": "shop.order"
    }
  ],
  "paths": {
    "/orders": {
      "post": {
        "operationId": "shop.order.create",
        "tags": ["shop.order"],
        "requestBody": {
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/shop.CreateOrder"
              }
            }
          }
        },
        "responses": {
          "200": {
            "$ref": "#/components/responses/wow.CommandOk"
          }
        }
      }
    },
    "/orders/state": {
      "post": {
        "operationId": "shop.order.snapshot_state.single",
        "tags": ["shop.order"],
        "responses": {
          "200": {
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/shop.OrderState"
                }
              }
            }
          }
        }
      }
    },
    "/orders/count": {
      "post": {
        "operationId": "shop.order.snapshot.count",
        "tags": ["shop.order"],
        "requestBody": {
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/shop.Condition"
              }
            }
          },
          "x-wow-query-fields": {
            "$ref": "#/components/schemas/shop.OrderFields"
          }
        },
        "responses": {
          "200": {
            "description": "Count",
            "content": {
              "application/json": {
                "schema": {
                  "type": "integer"
                }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "shop.CreateOrder": {
        "type": "object",
        "properties": {
          "name": {
            "type": "string"
          }
        },
        "required": ["name"]
      },
      "shop.OrderState": {
        "type": "object",
        "properties": {
          "name": {
            "type": "string"
          }
        }
      },
      "shop.OrderFields": {
        "type": "string",
        "enum": ["name"]
      },
      "shop.Condition": {
        "type": "object",
        "properties": {
          "field": {
            "$ref": "#/components/schemas/shop.OrderFields"
          }
        }
      }
    },
    "responses": {
      "wow.CommandOk": {
        "description": "Accepted"
      }
    }
  }
}
```

## 实现源码

[typescript/wow-generator/src/aggregate/aggregateResolver.ts:51](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/aggregate/aggregateResolver.ts#L51)

[typescript/wow-generator/src/aggregate/utils.ts:27](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/aggregate/utils.ts#L27)

[typescript/wow-generator/src/utils/components.ts:25](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/components.ts#L25)
