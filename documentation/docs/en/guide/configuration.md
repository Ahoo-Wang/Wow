---
title: Configuration
description: Comprehensive configuration options through Spring Boot configuration properties mechanism.
---

# Configuration

The Wow framework provides comprehensive configuration options through Spring Boot's configuration properties mechanism. This guide covers all available configuration options and how to configure them effectively.

## Configuration Structure

Wow configuration is organized under the `wow` prefix in your `application.yaml` or `application.yml` file:

```yaml
wow:
  enabled: true                    # Enable/disable Wow framework
  context-name: my-service         # Bounded context name
  shutdown-timeout: 60s           # Graceful shutdown timeout

  # Command Bus Configuration
  command:
    bus:
      type: kafka                  # kafka, redis, in_memory, no_op
      local-first:
        enabled: true              # Process local messages first

  # Event Bus Configuration
  event:
    bus:
      type: kafka
      local-first:
        enabled: true

  # State Event Bus Configuration
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
    store:
      storage: mongo               # Event store type: mongo, redis, elasticsearch, in_memory, delay
    snapshot:
      enabled: true
      strategy: all                # all, version_offset
      storage: mongo
      version-offset: 10

  # Infrastructure-specific configurations
  kafka:
    bootstrap-servers:
      - localhost:9092
    topic-prefix: 'wow.'

  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_event_db
    snapshot-database: wow_snapshot_db
    prepare-database: wow_prepare_db

  redis:
    enabled: true


  elasticsearch:
    enabled: true

  compensation:
    enabled: true
    webhook:
      weixin:
        url: <webhook-url>
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied

  openapi:
    enabled: true

  webflux:
    enabled: true
    global-error:
      enabled: true
```

## Core Configuration

### WowProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.enabled` | Boolean | `true` | Enable/disable the Wow framework |
| `wow.context-name` | String | `${spring.application.name}` | Bounded context name for the service |
| `wow.shutdown-timeout` | Duration | `60s` | Graceful shutdown timeout duration |

```yaml
wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s
```

## Dispatcher Tuning

`stripe-count` and `scheduler-pool-size` control different resource boundaries:

- `stripe-count` is the number of aggregate-ID hash ordering stripes. A stripe executes serially; reducing it increases hash collisions and head-of-line blocking.
- `scheduler-pool-size` is the Reactor Scheduler worker count **for each named aggregate type**, not a role-wide thread cap. A role's thread upper bound is approximately its active aggregate type count multiplied by this value.

| Property | Default | Description |
|----------|---------|-------------|
| `wow.command.dispatcher.stripe-count` | `64 × CPU` | Command ordering stripes |
| `wow.command.dispatcher.scheduler-pool-size` | `CPU` | Workers per command aggregate type |
| `wow.event.dispatcher.stripe-count` | `64 × CPU` | Domain event ordering stripes |
| `wow.event.dispatcher.scheduler-pool-size` | `CPU` | Workers per event aggregate type |
| `wow.projection.dispatcher.stripe-count` | `64 × CPU` | Projection ordering stripes |
| `wow.projection.dispatcher.scheduler-pool-size` | `CPU` | Workers per projection aggregate type |
| `wow.saga.stateless.dispatcher.stripe-count` | `64 × CPU` | Stateless saga ordering stripes |
| `wow.saga.stateless.dispatcher.scheduler-pool-size` | `CPU` | Workers per stateless saga aggregate type |

When a role property is absent, `stripe-count` remains compatible with the
`-Dwow.parallelism` JVM system property and `scheduler-pool-size` remains compatible with
`-Dreactor.schedulers.defaultPoolSize`. Role properties affect only the corresponding Wow
dispatcher and do not change other Reactor components. Every value must be greater than `0`.

The following values only demonstrate independent role overrides; they are not universal recommendations:

```yaml
wow:
  command:
    dispatcher:
      stripe-count: 896
      scheduler-pool-size: 4
  event:
    dispatcher:
      stripe-count: 896
      scheduler-pool-size: 8
  projection:
    dispatcher:
      stripe-count: 896
      scheduler-pool-size: 4
  saga:
    stateless:
      dispatcher:
        stripe-count: 896
        scheduler-pool-size: 4
```

For very short, in-memory, non-blocking commands, start a benchmark sweep around pool sizes
`2–4`; for CPU-heavy handlers, start near the available processor count. For homogeneous,
very short paths, also sweep stripes across `4/16/64/default`. Fewer stripes increase
collisions and head-of-line blocking, so accept a lower value only after p95/p99, hot-ID, and
heterogeneous-handler validation. Do not change a default from CPU count alone: verify
throughput, backlog, context switches, and real storage latency together. The snapshot
dispatcher does not yet expose role-level tuning and continues to use the compatible defaults.

## Command Bus Configuration

### CommandProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.command.bus.type` | BusType | `kafka` | Command bus implementation type |
| `wow.command.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
```

## Event Bus Configuration

### EventProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.event.bus.type` | BusType | `kafka` | Event bus implementation type |
| `wow.event.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |

```yaml
wow:
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
```

## State Event Bus Configuration

### StateProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.state.bus.type` | BusType | `kafka` | State event bus type |
| `wow.eventsourcing.state.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |

```yaml
wow:
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
```

## Event Sourcing Configuration

### Event Store Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.store.storage` | StorageType | `mongo` | Event store backend |

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo    # mongo, redis, elasticsearch, in_memory, delay
```

### Snapshot Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.snapshot.enabled` | Boolean | `true` | Enable snapshot functionality |
| `wow.eventsourcing.snapshot.strategy` | Strategy | `all` | Snapshot strategy |
| `wow.eventsourcing.snapshot.version-offset` | Int | `10` | Version offset for VERSION_OFFSET strategy |
| `wow.eventsourcing.snapshot.storage` | StorageType | `mongo` | Snapshot storage backend |

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: version_offset      # all, version_offset
      version-offset: 10
      storage: mongo
```

### Aggregate Storage Routing

`wow.eventsourcing.storage-routing` is optional. When an aggregate or channel is not configured, Wow keeps using the corresponding global default from `wow.eventsourcing.store.storage` or `wow.eventsourcing.snapshot.storage`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.storage-routing.aggregates.*.event.storage` | StorageType | | EventStore backend for one aggregate |
| `wow.eventsourcing.storage-routing.aggregates.*.event.binding` | String | | Named EventStore binding for one aggregate |
| `wow.eventsourcing.storage-routing.aggregates.*.snapshot.storage` | StorageType | | SnapshotStore backend for one aggregate |
| `wow.eventsourcing.storage-routing.aggregates.*.snapshot.binding` | String | | Named SnapshotStore binding for one aggregate |

```yaml
wow:
  context-name: order-service
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      storage: mongo
    storage-routing:
      aggregates:
        order:
          event:
            storage: redis
        cart:
          snapshot:
            storage: redis
        audit:
          event:
            binding: archive-event-store
          snapshot:
            binding: archive-snapshot-store
```

- `order` resolves to `order-service.order` by using the current `wow.context-name`.
- Full aggregate keys such as `order-service.order` are also accepted. Quote the key in YAML when needed.
- `event` routes only affect the aggregate `EventStore`; `snapshot` routes only affect the aggregate `SnapshotStore`.
- `event.binding` and `snapshot.binding` point to named custom bindings registered by application code or infrastructure auto-configuration.
- `storage` and `binding` are mutually exclusive inside the same `event` or `snapshot` channel.
- Changing a route to another backend does not migrate existing event streams or snapshots.
- The snapshot abstraction is now named `SnapshotStore`. Deprecated `SnapshotRepository` Kotlin compatibility aliases remain transitional and should not be used in new code.

## Infrastructure Configuration

### Kafka Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.kafka.enabled` | Boolean | `true` | Enable Kafka support |
| `wow.kafka.bootstrap-servers` | List\<String\> | | Kafka broker addresses |
| `wow.kafka.topic-prefix` | String | `wow.` | Topic name prefix |

```yaml
wow:
  kafka:
    enabled: true
    bootstrap-servers:
      - kafka-0:9092
      - kafka-1:9092
      - kafka-2:9092
    topic-prefix: 'wow.'
```

### MongoDB Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.mongo.enabled` | Boolean | `true` | Enable MongoDB support |
| `wow.mongo.auto-init-schema` | Boolean | `true` | Auto-create collections |
| `wow.mongo.event-stream-database` | String | Spring MongoDB database | Event stream database |
| `wow.mongo.snapshot-database` | String | Spring MongoDB database | Snapshot database |
| `wow.mongo.prepare-database` | String | Spring MongoDB database | Prepare key database |

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_event_db
    snapshot-database: wow_snapshot_db
    prepare-database: wow_prepare_db
```

### Redis Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.redis.enabled` | Boolean | `true` | Enable Redis support |

```yaml
wow:
  redis:
    enabled: true
```


### Elasticsearch Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.elasticsearch.enabled` | Boolean | `true` | Enable Elasticsearch support |

```yaml
wow:
  elasticsearch:
    enabled: true
```

## Feature Configuration

### Event Compensation Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.compensation.enabled` | Boolean | `true` | Enable event compensation |
| `wow.compensation.webhook.weixin.url` | String | | WeChat Work webhook URL |
| `wow.compensation.webhook.weixin.events` | List\<String\> | See description | Notification events |

```yaml
wow:
  compensation:
    enabled: true
    webhook:
      weixin:
        url: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied
```

### OpenAPI Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.openapi.enabled` | Boolean | `true` | Enable OpenAPI support |

```yaml
wow:
  openapi:
    enabled: true
```

### WebFlux Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.webflux.enabled` | Boolean | `true` | Enable WebFlux support |
| `wow.webflux.global-error.enabled` | Boolean | `true` | Enable global error handling |

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
```

### BI Script Configuration

These properties establish the server-side base for the ClickHouse SQL returned by `POST /wow/bi/script`:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.bi.script.enabled` | Boolean | `true` | Enabled by default; set to `false` to remove both the BI script HTTP route and its OpenAPI operation; application security must protect the endpoint |
| `wow.bi.script.database` | String | `bi_db` | Database for command/state `*_store` tables plus public, latest-state, and expansion views; maximum 128 characters |
| `wow.bi.script.consumer-database` | String | `bi_db_consumer` | Database for Kafka queue tables, consumer materialized views, and the deployment anchor; maximum 128 characters |
| `wow.bi.script.topology.mode` | Enum | `CLUSTER` | Physical DDL topology: `CLUSTER` or `STANDALONE` |
| `wow.bi.script.topology.cluster.name` | String | `{cluster}` | Cluster name used by `ON CLUSTER` and `Distributed` in `CLUSTER` mode; maximum 128 characters |
| `wow.bi.script.topology.cluster.installation` | String | `{installation}` | Installation segment in the replicated table path in `CLUSTER` mode; maximum 128 characters |
| `wow.bi.script.timezone` | String | `Asia/Shanghai` | ClickHouse timezone for generated date-time columns and conversions; maximum 64 characters |
| `wow.bi.script.kafka-bootstrap-servers` | String | Inherit `wow.kafka.bootstrap-servers`; otherwise `localhost:9093` | BI Kafka broker override; multiple inherited brokers are joined with commas; maximum 4096 characters |
| `wow.bi.script.topic-prefix` | String | Inherit `wow.kafka.topic-prefix`; otherwise `wow.` | BI topic prefix override; maximum 128 characters |
| `wow.bi.script.consumer-group-namespace` | String | none | Required for every `RESET`, including an empty aggregate scope, and for `DEPLOY` whenever Kafka consumers are generated; deployment-unique namespace embedded in every consumer group |
| `wow.bi.script.kafka-offset-storage` | Enum | `BROKER` | `BROKER` uses Kafka offsets; `KEEPER` enables ClickHouse Keeper-backed offsets |
| `wow.bi.script.kafka-keeper-path-prefix` | String | `/clickhouse/wow-bi` | Keeper path prefix used only with `KEEPER` |
| `wow.bi.script.max-expansion-depth` | Int | `5` | Maximum complex-property expansion depth; must be at least `1` |
| `wow.bi.script.unsupported-type-strategy` | Enum | `RAW_JSON` | `RAW_JSON` emits a scoped JSON convenience projection and a diagnostic; the exact lexical value is recovered from `__state` at the recovery `__path`; `FAIL` stops generation |
| `wow.bi.script.inspector.type` | Enum | `NO_OP` | Deployment-state inspector: `NO_OP` or `CLICKHOUSE`; the catalog is contacted only when `CLICKHOUSE` is explicitly selected |
| `wow.bi.script.inspector.timeout` | Duration | `30s` | Deadline for the complete inspection; cluster inspection performs two catalog operations, so this should exceed the per-operation execution timeout |
| `wow.bi.script.inspector.clickhouse.endpoints` | List&lt;URI&gt; | none | One or more distinct ClickHouse HTTP(S) endpoints; each requires an explicit port and may include a reverse-proxy base path |
| `wow.bi.script.inspector.clickhouse.username` | String | `default` | ClickHouse Basic Auth username |
| `wow.bi.script.inspector.clickhouse.password` | String | empty | ClickHouse Basic Auth password; redacted from property and client-option string representations |
| `wow.bi.script.inspector.clickhouse.connection-pool-enabled` | Boolean | `true` | Maps to `Client.Builder.enableConnectionPool` |
| `wow.bi.script.inspector.clickhouse.connection-timeout` | Duration | `3s` | Maps to `Client.Builder.setConnectTimeout`; must be at least `1ms` |
| `wow.bi.script.inspector.clickhouse.connection-request-timeout` | Duration | `10s` | Maximum wait for a pooled connection; maps to `setConnectionRequestTimeout` and must be at least `1ms` |
| `wow.bi.script.inspector.clickhouse.socket-timeout` | Duration | `10s` | Socket read/write timeout; maps to `setSocketTimeout`; must be at least `1ms` and no greater than `inspector.timeout` |
| `wow.bi.script.inspector.clickhouse.execution-timeout` | Duration | `10s` | Deadline for one driver operation; the inspector enables asynchronous requests, configures `setExecutionTimeout`, and bounds the returned future with this value; zero means no driver operation deadline, otherwise the minimum is `1ms` |
| `wow.bi.script.inspector.clickhouse.max-connections` | Int | `10` | Maximum open connections per endpoint; maps to `setMaxConnections` and must be positive |
| `wow.bi.script.inspector.clickhouse.max-retries` | Int | `0` | Driver retry count; maps to `setMaxRetries` and must not be negative |

`execution-timeout` bounds how long the inspector waits for the asynchronous result, but client-v2 does not turn that future timeout into an HTTP abort. Therefore `socket-timeout` is mandatory and cannot exceed the total `inspector.timeout`; cancelled-response cleanup runs off the timeout scheduler and remains bounded by that transport deadline.

The default `NO_OP` implementation does not contact ClickHouse. Select the `CLICKHOUSE` inspector for catalog reconciliation:

```yaml
wow:
  bi:
    script:
      enabled: true
      consumer-group-namespace: orders-production-blue
      topology:
        mode: STANDALONE
      inspector:
        type: CLICKHOUSE
        timeout: 30s
        clickhouse:
          endpoints:
            - http://clickhouse-1:8123
            - http://clickhouse-2:8123
          username: default
          password: ${CLICKHOUSE_PASSWORD:}
          connection-pool-enabled: true
          connection-timeout: 3s
          connection-request-timeout: 10s
          socket-timeout: 10s
          execution-timeout: 10s
          max-connections: 10
          max-retries: 0
```

The built-in inspector is implemented in `wow-bi` with the official ClickHouse Java `client-v2`. Its typed Spring Boot properties map one-to-one to the corresponding `Client.Builder` concepts instead of merging unrelated driver timeouts. The inspector owns and closes the client with the Spring context; it starts asynchronous client-v2 queries and waits for each returned future on Reactor's bounded-elastic scheduler without creating another driver executor. Catalog queries use typed RowBinary records and named parameters, and cluster mode verifies replica participation and owned-object definitions while ignoring unrelated replica-local catalog differences. Connection, timeout, invalid ownership-marker, and owned replica-divergence failures propagate without silently falling back to `NO_OP`. Selecting `CLICKHOUSE` without the client-v2 classes fails application startup. Use a custom `BiDeploymentInspector` bean for unsupported proxy, mTLS, or authentication requirements; a custom bean takes precedence over both built-in implementations.

Standalone topology:

```yaml
wow:
  bi:
    script:
      enabled: true
      consumer-group-namespace: orders-production-blue
      topology:
        mode: STANDALONE
```

Cluster topology:

```yaml
wow:
  bi:
    script:
      enabled: true
      consumer-group-namespace: orders-production-blue
      topology:
        mode: CLUSTER
        cluster:
          name: production
          installation: clickhouse
```

`STANDALONE` creates `*_store` physical tables with `ReplacingMergeTree`; `command`, `state`, and `state_last` remain read-only views that query their stores with `FINAL`. It rejects `topology.cluster`. `CLUSTER` creates replicated `*_store_local` physical tables, `*_store` `Distributed` write facades, and the same public read views; omitted cluster fields use the defaults shown above. Cluster DDL always uses ClickHouse's `{shard}` and `{replica}` server macros, including the Keeper consumer replica identity; they are intentionally not application-level overrides.

`DEPLOY` and `RESET` reconcile only the current physical ownership scope; they do not migrate `database`, `consumerDatabase`, `consumerGroupNamespace`, topology mode, cluster name, or installation. Visible topology-fingerprint drift is rejected. Because a scope change can make old objects undiscoverable, stop old consumers and explicitly clean the old scope before deploying the new scope.

The complete precedence, from lowest to highest, is:

1. `BiScriptOptions` domain defaults;
2. Kafka properties for bootstrap servers and topic prefix;
3. `wow.bi.script.*` application properties;
4. Non-null `POST` request fields.

When a real deployment inspector is configured, `database`, `consumerDatabase`, and `topology` are fixed to the server configuration and request overrides for those fields return `400`. This prevents a public request from using the server's ClickHouse credentials to inspect an arbitrary database or cluster. The default `NO_OP` inspector permits those overrides because it never contacts ClickHouse; such output is an offline preview, not a migration of an existing scope.

Thus, explicit `wow.bi.script.kafka-bootstrap-servers` / `wow.bi.script.topic-prefix` values override the corresponding `wow.kafka.bootstrap-servers` / `wow.kafka.topic-prefix` values, even when equal to their defaults. Multiple inherited Kafka brokers are joined with commas. Every other absent application binding falls back directly to its `BiScriptOptions` domain default. The length limits in the table apply equally to the server configuration and the corresponding non-null `POST` overrides (`database`, `consumerDatabase`, `timezone`, `kafkaBootstrapServers`, `topicPrefix`, `topology.cluster.name`, and `topology.cluster.installation`). A value exactly at its 64, 128, or 4096 character limit is accepted. When BI script generation is enabled, the Starter validates the server base while constructing the domain options: a value over its limit, blank required strings, control characters, `max-expansion-depth < 1`, and cluster fields supplied in `STANDALONE` mode all fail application startup. With `enabled=false`, the Starter neither constructs nor validates BI generation options or an inspector. For HTTP overrides, the server-configured `maxExpansionDepth` is the request ceiling.

The endpoint and its OpenAPI operation are registered by default; `enabled=false` removes both. Enablement does not provide authentication. Missing `consumer-group-namespace` does not fail startup, but generation returns `400` for every `RESET`, including an empty aggregate scope, and for `DEPLOY` whenever Kafka consumers are generated. An empty `DEPLOY` without it remains unanchored. The endpoint requires `Content-Type: application/json` and a JSON body. Use `{}` to generate SQL from the server base without request overrides:

```bash
curl -X POST 'http://localhost:8080/wow/bi/script' \
  -H 'content-type: application/json' \
  -H 'accept: application/sql' \
  --data '{}'
```

Non-null request fields override both ordinary options and Kafka-derived options for this generation. A Standalone request can also override the database:

```json
{
  "database": "analytics",
  "topology": {
    "mode": "STANDALONE"
  }
}
```

A Cluster request may provide only selected cluster fields. Omitted cluster fields inherit the current Cluster server base, or the `BiScriptOptions` Cluster defaults when the server base is Standalone:

```json
{
  "topology": {
    "mode": "CLUSTER",
    "cluster": {
      "name": "production"
    }
  },
  "kafkaBootstrapServers": "kafka:9092",
  "topicPrefix": "analytics."
}
```

When `topology` is present, `topology.mode` is mandatory. `STANDALONE` rejects a `cluster` object. Invalid JSON, an empty body, an over-limit non-null override, another invalid option value, or an invalid topology combination returns a `400` response. A missing or unsupported request `Content-Type` returns `415`; OpenAPI declares the common `wow.UnsupportedMediaType` response, and runtime uses `Wow-Error-Code: UnsupportedMediaType`. An unexpected generation failure returns `500`. With a real inspector, inconsistent catalog state returns `502`, an unavailable ClickHouse service returns `503`, and an inspection timeout returns `504`. `Accept` quality values are honored; JSON returns SQL, diagnostics, and the destructive flag, while SQL and wildcards return SQL. If no requested representation is supported or every supported representation is explicitly assigned `q=0`, the endpoint returns `406` with `Wow-Error-Code: NotAcceptable`. Every `200` response includes `Wow-BI-Diagnostic-Count`, including SQL responses whose body cannot carry diagnostics. Callers no longer submit manifests. The default NoOp inspector permits offline `DEPLOY` with an unreconciled diagnostic but rejects `RESET`. With an explicitly configured ClickHouse inspector, catalog ownership markers restore the identity and drive stale cleanup and confirmed Reset.

See [Business Intelligence](./bi) for structured result diagnostics, current expansion semantics, and lossless mappings.

## Bus Type

The framework supports multiple bus implementations:

| Type | Description |
|------|-------------|
| `kafka` | Apache Kafka message bus (recommended for production) |
| `redis` | Redis Streams message bus |
| `in_memory` | In-memory message bus (for testing) |
| `no_op` | No-op message bus (for special cases) |

## Storage Type

For event stores and snapshots:

| Type | Description |
|------|-------------|
| `mongo` | MongoDB (recommended for event store) |
| `redis` | Redis for high-performance scenarios |
| `elasticsearch` | Elasticsearch for full-text search |

## Complete Example

```yaml
spring:
  application:
    name: order-service

  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db
    redis:
      host: localhost
      port: 6379


  elasticsearch:
    uris:
      - http://localhost:9200

wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s

  command:
    bus:
      type: kafka
      local-first:
        enabled: true
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
  kafka:
    bootstrap-servers:
      - localhost:9092
    topic-prefix: 'wow.'
  mongo:
    enabled: true
    auto-init-schema: true
  elasticsearch:
    enabled: true
  compensation:
    enabled: true
  openapi:
    enabled: true
  webflux:
    enabled: true
    global-error:
      enabled: true

management:
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include:
          - health
          - wow
          - cosid

springdoc:
  show-actuator: true
```

## Configuration in Different Environments

### Development

```yaml
wow:
  command:
    bus:
      type: in_memory
  event:
    bus:
      type: in_memory
  eventsourcing:
    store:
      storage: in_memory
    snapshot:
      storage: in_memory
      strategy: all
```

### Production

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
  kafka:
    bootstrap-servers:
      - kafka-0:9092
      - kafka-1:9092
      - kafka-2:9092
  mongo:
    enabled: true
    auto-init-schema: true
```

## Configuration References

For detailed configuration of specific modules, see:
- [Kafka Extension](./extensions/kafka)
- [MongoDB Extension](./extensions/mongo)
- [Redis Extension](./extensions/redis)
- [Elasticsearch Extension](./extensions/elasticsearch)
- [Event Compensation](./event-compensation)
- [Command Configuration](../reference/config/core)
- [Event Configuration](../reference/config/core)
- [Event Sourcing Configuration](../reference/config/core)
