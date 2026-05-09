---
title: 快照配置
description: 聚合快照存储和策略的配置选项。
---

# 快照配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wow.eventsourcing.snapshot.enabled` | Boolean | `true` | 启用快照功能 |
| `wow.eventsourcing.snapshot.strategy` | Strategy | `ALL` | 快照策略：`ALL` 或 `VERSION_OFFSET` |
| `wow.eventsourcing.snapshot.version-offset` | Int | `10` | 版本偏移阈值（策略为 `VERSION_OFFSET` 时使用） |
| `wow.eventsourcing.snapshot.storage` | StorageType | `MONGO` | 快照存储后端 |

## StorageType 可选值

| 值 | 说明 |
|----|------|
| `MONGO` | MongoDB（推荐用于生产环境） |
| `REDIS` | Redis |
| `R2DBC` | 通过 R2DBC 连接关系型数据库 |
| `ELASTICSEARCH` | Elasticsearch |
| `IN_MEMORY` | 内存（仅用于测试） |
| `DELAY` | 延迟存储 |

## 示例

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
```
