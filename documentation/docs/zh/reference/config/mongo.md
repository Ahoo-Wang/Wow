---
title: Mongo 配置
description: MongoDB 事件存储和快照存储的配置选项。
---

# Mongo 配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.mongo.enabled` | Boolean | `true` | 启用 MongoDB 集成 |
| `wow.mongo.auto-init-schema` | Boolean | `true` | 启动时自动初始化数据库 Schema |
| `wow.mongo.event-stream-database` | String? | `null` | 事件流的独立数据库（默认使用主数据库） |
| `wow.mongo.snapshot-database` | String? | `null` | 快照的独立数据库（默认使用主数据库） |
| `wow.mongo.prepare-database` | String? | `null` | PrepareKey 存储的独立数据库（默认使用主数据库） |

## 示例

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_events
    snapshot-database: wow_snapshots
```
