---
title: Mongo Configuration
description: Configuration options for MongoDB event store and snapshot storage.
---

# Mongo Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.mongo.enabled` | Boolean | `true` | Enable MongoDB integration |
| `wow.mongo.auto-init-schema` | Boolean | `true` | Automatically initialize database schema on startup |
| `wow.mongo.event-stream-database` | String? | `null` | Separate database for event streams (defaults to main database) |
| `wow.mongo.snapshot-database` | String? | `null` | Separate database for snapshots (defaults to main database) |
| `wow.mongo.prepare-database` | String? | `null` | Separate database for PrepareKey storage (defaults to main database) |

## Example

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_events
    snapshot-database: wow_snapshots
```
