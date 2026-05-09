---
title: State Event Configuration
description: Configuration options for aggregate state event bus.
---

# State Event Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.state.bus.type` | BusType | `KAFKA` | State event bus type |
| `wow.eventsourcing.state.bus.local-first.enabled` | Boolean | `true` | Enable local-first delivery optimization |

## BusType Values

| Value | Description |
|-------|-------------|
| `KAFKA` | Apache Kafka (recommended for production) |
| `REDIS` | Redis Streams |
| `IN_MEMORY` | In-memory (testing only) |
| `NO_OP` | No operation (disabled) |

## Example

```yaml
wow:
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
```
