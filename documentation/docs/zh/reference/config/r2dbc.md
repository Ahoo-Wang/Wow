---
title: R2DBC 配置
description: R2DBC 事件存储和快照存储的配置选项，包括分片支持。
---

# R2DBC 配置

## 基本属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.r2dbc.enabled` | Boolean | `true` | 启用 R2DBC 集成 |

## 数据源属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.r2dbc.datasource.type` | Type | `SIMPLE` | 数据源类型：`SIMPLE` 或 `SHARDING` |

## 分片属性

当 `wow.r2dbc.datasource.type` 为 `SHARDING` 时：

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.r2dbc.datasource.sharding.databases` | Map\<String, Database\> | `{}` | 分片数据库定义 |
| `wow.r2dbc.datasource.sharding.event-stream` | Map\<String, ShardingRule\> | `{}` | 事件流分片规则 |
| `wow.r2dbc.datasource.sharding.snapshot` | Map\<String, ShardingRule\> | `{}` | 快照分片规则 |
| `wow.r2dbc.datasource.sharding.algorithms` | Map\<String, ShardingAlgorithm\> | `{}` | 分片算法定义 |

## 示例

```yaml
wow:
  r2dbc:
    enabled: true
    datasource:
      type: simple
```

### 分片示例

```yaml
wow:
  r2dbc:
    enabled: true
    datasource:
      type: sharding
      sharding:
            databases:
              ds0:
                url: r2dbc:mysql://localhost:3306/wow_ds0
              ds1:
                url: r2dbc:mysql://localhost:3306/wow_ds1
            algorithms:
              order_mod:
                type: mod
                mod:
                  logic-name-prefix: order_event_stream
                  divisor: 2
            event-stream:
              order:
                database-algorithm: order_mod
                table-algorithm: order_mod
```
