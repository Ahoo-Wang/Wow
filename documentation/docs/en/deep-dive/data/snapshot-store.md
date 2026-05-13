---
title: Snapshot Store
description: Snapshot mechanism in Wow Framework — optimizing aggregate loading performance
---

# Snapshot Store

The Snapshot Store optimizes aggregate loading by persisting aggregate state snapshots, avoiding the need to replay all historical events.

## Why Snapshots?

Without snapshots, loading an aggregate requires replaying ALL historical events. For long-lived aggregates with thousands of events, this becomes a performance bottleneck. Snapshots capture the aggregate state at a point in time, so only events after the snapshot need to be replayed.

```mermaid
sequenceDiagram
%%{init: {'theme': 'dark', 'themeVariables': {'primaryColor': '#2d333b', 'primaryTextColor': '#e6edf3', 'primaryBorderColor': '#6d5dfc', 'lineColor': '#8b949e', 'noteBkgColor': '#161b22', 'noteTextColor': '#e6edf3', 'actorBkg': '#2d333b', 'actorBorder': '#6d5dfc', 'actorTextColor': '#e6edf3', 'signalColor': '#8b949e', 'signalTextColor': '#e6edf3', 'labelBoxBkgColor': '#2d333b', 'labelBoxBorderColor': '#6d5dfc', 'labelTextColor': '#e6edf3', 'loopTextColor': '#e6edf3', 'activationBorderColor': '#6d5dfc', 'activationBkgColor': '#1a1f2e'}}}%%
    autonumber
    participant CB as Command Bus
    participant AG as Aggregate
    participant SS as Snapshot Store
    participant ES as Event Store

    CB->>AG: Load Aggregate(id)
    AG->>SS: Get Latest Snapshot(id)
    alt Snapshot Found
        SS-->>AG: Snapshot(v=50)
        AG->>ES: Get Events After(v=50)
        ES-->>AG: Events [51..55]
    else No Snapshot
        SS-->>AG: null
        AG->>ES: Get All Events(id)
        ES-->>AG: Events [1..55]
    end
    AG->>AG: Replay Events → State
    AG-->>CB: Aggregate Ready

```

<!-- Sources: wow-core/src/main/kotlin/me/ahoo/wow/event/snapshot/, wow-api/src/main/kotlin/me/ahoo/wow/api/event/snapshot/ -->

## Snapshot Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Create: Every N Events
    Create --> Store: Serialize State
    Store --> Active: Available for Loading
    Active --> Stale: New Events Added
    Stale --> Create: Interval Reached
    Active --> Delete: Aggregate Deleted
    Delete --> [*]

    style Create fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Store fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Active fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Stale fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Delete fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

<!-- Sources: wow-core/src/main/kotlin/me/ahoo/wow/event/snapshot/SnapshotHandler.kt -->

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `wow.snapshot.enabled` | `false` | Enable snapshot store |
| `wow.snapshot.interval` | `100` | Events before new snapshot |
| `wow.snapshot.store.type` | Event store backend | Snapshot storage backend |

## Supported Backends

| Backend | Module | Status |
|---------|--------|--------|
| MongoDB | `wow-mongo` | Production-ready |
| Redis | `wow-redis` | Production-ready |
| R2DBC | `wow-r2dbc` | Production-ready |

## Performance Impact

Snapshots dramatically reduce load time for long-lived aggregates. With a snapshot interval of 50, an aggregate with 1000 events replays at most 49 events instead of all 1000 — a ~95% reduction.

## Related Pages

- [Event Store](./event-store) — Event persistence layer
- [Aggregate Lifecycle](../architecture/aggregate-lifecycle) — Loading and state flow
- [Configuration](../../guide/configuration) — Snapshot configuration
