---
title: R2DBC Configuration
description: Configuration options for R2DBC event store and snapshot storage, including sharding support.
---

# R2DBC Configuration

## Basic Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.r2dbc.enabled` | Boolean | `true` | Enable R2DBC integration |

## DataSource Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.r2dbc.datasource.type` | Type | `SIMPLE` | DataSource type: `SIMPLE` or `SHARDING` |

## Sharding Properties

When `wow.r2dbc.datasource.type` is `SHARDING`:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.r2dbc.datasource.sharding.databases` | Map\<String, Database\> | `{}` | Sharded database definitions |
| `wow.r2dbc.datasource.sharding.event-stream` | Map\<String, ShardingRule\> | `{}` | Event stream sharding rules |
| `wow.r2dbc.datasource.sharding.snapshot` | Map\<String, ShardingRule\> | `{}` | Snapshot sharding rules |
| `wow.r2dbc.datasource.sharding.algorithms` | Map\<String, ShardingAlgorithm\> | `{}` | Sharding algorithm definitions |

## Example

```yaml
wow:
  r2dbc:
    enabled: true
    datasource:
      type: simple
```

### Sharding Example

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
