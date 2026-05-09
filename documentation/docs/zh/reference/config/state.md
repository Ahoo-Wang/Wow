---
title: 状态事件配置
description: 聚合状态事件总线的配置选项。
---

# 状态事件配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.eventsourcing.state.bus.type` | BusType | `KAFKA` | 状态事件总线类型 |
| `wow.eventsourcing.state.bus.local-first.enabled` | Boolean | `true` | 启用本地优先投递优化 |

## BusType 可选值

| 值 | 说明 |
|----|------|
| `KAFKA` | Apache Kafka（推荐用于生产环境） |
| `REDIS` | Redis Streams |
| `IN_MEMORY` | 内存（仅用于测试） |
| `NO_OP` | 无操作（禁用） |

## 示例

```yaml
wow:
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
```
