---
title: Snapshot Configuration
description: Configuration options for aggregate snapshot storage and strategy.
---

# Snapshot Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.snapshot.enabled` | Boolean | `true` | Enable snapshot functionality |
| `wow.eventsourcing.snapshot.strategy` | Strategy | `ALL` | Snapshot strategy: `ALL` or `VERSION_OFFSET` |
| `wow.eventsourcing.snapshot.version-offset` | Int | `10` | Version offset threshold (used when strategy is `VERSION_OFFSET`) |
| `wow.eventsourcing.snapshot.storage` | StorageType | `MONGO` | Snapshot storage backend |

## StorageType Values

| Value | Description |
|-------|-------------|
| `MONGO` | MongoDB (recommended for production) |
| `REDIS` | Redis |
| `R2DBC` | Relational database via R2DBC |
| `ELASTICSEARCH` | Elasticsearch |
| `IN_MEMORY` | In-memory (testing only) |
| `DELAY` | Delayed storage |

## Example

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
```
