---
title: View Store
description: Where the view engine's saved views and preferences live on a Wow server — embedding wow-view-store-starter or running the standalone server and its Docker image, the properties, storage on MongoDB and Elasticsearch, system views, and a security model the CoSec gateway owns.
---

# View Store

The view store is the server side of the view engine's `ViewStore` port on Wow: the views and preferences readers save are two Wow aggregates, `view` and `view_preferences`, in the bounded context `view-store`. The front end reaches it with `WowViewStore` from `@ahoo-wang/wow-view-store` ([Where Views Live](../typescript/view-engine-storage.md)).

**It does no authentication.** It takes the tenant, the owner and the application as the request names them: the path's `{tenantId}` and `{ownerId}`, and the `CoSec-App-Id` header. Who may use which path is decided entirely by the CoSec gateway in front of it. So every deployment, embedded or standalone, runs only behind the CoSec gateway ([security model](#security-model)).

## Modules

| Module | Contents |
|---|---|
| `wow-view-store-api` | Bounded context `view-store`, commands, events, `ViewKind` / `ViewAudience`, `SystemView`, error codes |
| `wow-view-store-domain` | Aggregates `View` (`view`) and `ViewPreferences` (`view_preferences`) and their rules |
| `wow-view-store-starter` | Spring Boot auto-configuration that embeds the view store in a Wow service |
| `wow-view-store-server` | A standalone service on the starter, with MongoDB for events and snapshots; published as a Docker image |

The first three are on Maven Central since 9.2.0, constrained by `wow-bom`, with the same version as the view engine's npm packages.

## Embed the starter, or run the standalone server

| | Embed `wow-view-store-starter` | Standalone `wow-view-store-server` |
|---|---|---|
| For | A Wow WebFlux service you already run, whose business the views belong to | No Wow service of your own, or views of several front ends kept in one place |
| Storage | The host's: MongoDB or Elasticsearch | MongoDB only (9.2.0) |
| Bus | The host's; the view store's Kafka topics can take a prefix of their own | Kafka topics under a prefix of its own |
| Examples | The example server (`example/example-server`), the compensation service (the compensation console keeps its views there) | The Docker image `wow-view-store-server` |

### Embed the starter

Add the dependency to a Wow WebFlux service:

```kotlin
implementation("me.ahoo.wow:wow-view-store-starter")
```

The two aggregates come with the domain module on the classpath, and Wow routes them like the host's own, under `/view-store/tenant/{tenantId}/owner/{ownerId}/…`. The starter adds what they need around them; every bean is named for the view store and touches only the view store's aggregates and paths, so the host's own aggregates, routes and queries are left as they were.

- **Open routes**: `POST /view` (create), `PUT /view/{id}/save` and `/rename`, `DELETE /view/{id}`; `PUT /view/{id}/share` (on the view's personal path, moves it to `owner/(shared)`) and `PUT /view/{id}/claim` (on the caller's own personal path, moves a shared view to the caller); the snapshot queries under `/view/snapshot/…` and `/view_preferences/snapshot/…`; `GET /system-views`, `GET` / `PUT /definitions/{definitionId}/preferences`, and `GET /view/requests/{requestId}` (replay).
- **Every other route answers 404**: the state, tracing, event-stream, snapshot-maintenance, compensation, recover and resource-tag routes Wow generates for the two aggregates, and the command facade for their commands, are closed. One route fewer is one path fewer for the gateway to govern.
- Commands carry no id (Wow takes it from `{id}`); a command without fields (`share`, `delete`) is sent with the body `{}`.
- Set `wow.view-store.enabled=false` to turn the starter off.

**Aggregate names can collide.** The aggregates are named `view` and `view_preferences`, and Wow's MongoDB collections (`view_event_stream`, `view_snapshot`, …) carry no context. A host with its own `view` or `view_preferences` aggregate would share those collections: do not embed the starter in it, or rename the host's aggregate.

**Path case.** The starter matches paths case-insensitively for all of its rules, so a host that sets `PathMatchConfigurer.setUseCaseSensitiveMatch(false)` is covered, with one exception: an open route wins over a closed one only in its own case, so on a case-insensitive host a path such as `…/view/REQUESTS/state` answers 404. A host that replaces Spring's `RouterFunctionMapping` with a parser of other options must not embed the starter.

### The standalone server

`wow-view-store-server` runs the starter on the same middleware as the compensation service, and can run as several instances:

| Middleware | Used for | Setting |
|---|---|---|
| MongoDB | Event streams and snapshots (Wow's default storage) | `spring.mongodb.uri` |
| Kafka | The command, event and state-event buses (Wow's default buses) | `wow.kafka.bootstrap-servers`, `wow.kafka.topic-prefix` |
| Redis | CosId machine ids shared by the instances | `spring.data.redis.url`, `cosid.machine.distributor.type: redis` |

**MongoDB only in 9.2.0.** The standalone server is built without Wow's Elasticsearch support. To keep views on Elasticsearch, embed the starter in a host that runs on Elasticsearch: the index definitions ship with the starter, not with the server.

Run it from source (at the repository root):

```bash
service_dir=view-store/wow-view-store-server
mkdir -p "$service_dir/logs" "$service_dir/data" "$service_dir/config"
test -e "$service_dir/config/application.yaml" || cp "$service_dir/src/dist/config/application.yaml" "$service_dir/config/application.yaml"
./gradlew :wow-view-store-server:run
```

`src/dist/config/application.yaml` is the template for `config/application.yaml`, with every backend on `localhost`.

### Docker image

The image is built from `installDist` for `linux/amd64` and `linux/arm64` and pushed as:

- `ahoowang/wow-view-store-server`
- `ghcr.io/ahoo-wang/wow-view-store-server`
- `registry.cn-shanghai.aliyuncs.com/ahoo/wow-view-store-server`

A `v<version>` tag publishes `<version>` and `<major>.<minor>`; a push to `main` (and the daily run) publishes `main`. A pre-release tag (`v9.2.0-rc.0`) publishes no image. A patch to an older line (a `v9.2.3` tag after `v9.3.0`) does not move `latest`, which stays on the highest stable release.

The image runs `/opt/wow-view-store-server/bin/wow-view-store-server` as a non-root user on port 8080 and reads its configuration from `/opt/wow-view-store-server/config/`: mount your own `application.yaml` there, or override the settings with environment variables. Set all three backend addresses: a container that misses one starts, points at `localhost`, and fails only when it first uses that backend.

| Variable | Setting | Template value |
|---|---|---|
| `SPRING_MONGODB_URI` | `spring.mongodb.uri` | `mongodb://root:root@localhost:27017/wow_view_store_db?authSource=admin&maxIdleTimeMS=60000` |
| `WOW_KAFKA_BOOTSTRAPSERVERS` | `wow.kafka.bootstrap-servers` | `PLAINTEXT://localhost:9092` |
| `WOW_KAFKA_TOPICPREFIX` | `wow.kafka.topic-prefix` | `wow.view-store-server.` |
| `SPRING_DATA_REDIS_URL` | `spring.data.redis.url` (CosId machine ids) | `redis://localhost:6379` |

```bash
docker run -d --network internal \
  -e SPRING_MONGODB_URI='mongodb://root:root@mongo:27017/wow_view_store_db?authSource=admin' \
  -e WOW_KAFKA_BOOTSTRAPSERVERS='PLAINTEXT://kafka:9092' \
  -e SPRING_DATA_REDIS_URL='redis://redis:6379' \
  ahoowang/wow-view-store-server:9.2.0
```

- There is no `-p 8080:8080` here: **the image does no authentication**, and its port is for the CoSec gateway alone, never published to users. `internal` is the network the gateway and the container share.
- Its `HEALTHCHECK` reads `/actuator/health/liveness` (the process runs). A backend that is down shows in `/actuator/health`, which the template answers with the status alone (`show-details: when-authorized`), so that the port does not name the backends and their addresses.
- The JVM options (`-Xms512M -Xmx512M`, ZGC, a GC log under `logs/`, heap dumps under `data/`) come from the `installDist` start script; set `JAVA_OPTS` to add to them.

## Properties

The starter's settings are under `wow.view-store` (`ViewStoreProperties`):

| Property | Default | Effect |
|---|---|---|
| `wow.view-store.enabled` | `true` | Whether the view store is added to the host |
| `wow.view-store.system-views` | empty | Configured system views; see [System views](#system-views) |
| `wow.view-store.kafka.topic-prefix` | unset | A Kafka topic prefix for the view store's two aggregates only; see below |

Related Wow settings:

| Property | What it means for the view store |
|---|---|
| `wow.kafka.topic-prefix` | Without `wow.view-store.kafka.topic-prefix`, the view store's topics follow it; the standalone server uses it (`wow.view-store-server.`) |
| `wow.elasticsearch.index-prefix` | The view store's indices have no prefix of their own and move with it ([Several deployments on one cluster](./elasticsearch.md#index-prefix)) |

### One deployment per Kafka topic namespace

Wow names the Kafka topics by context and aggregate (`wow.view-store.view.command`, …), so two deployments of the view store on one Kafka cluster (the standalone server and a host embedding the starter, or two hosts) would consume each other's commands and events. Give each deployment a prefix of its own:

- **A host that embeds the starter** sets `wow.view-store.kafka.topic-prefix`, which applies to the view store's two aggregates only (`<prefix>view-store.view.command`, …); the host's own aggregates keep the topics `wow.kafka.topic-prefix` gives them. The compensation service sets `wow.compensation-service.`. Unset or blank, the view store's topics follow `wow.kafka.topic-prefix`. With it set, each of the host's topic converter beans must be one kind (command, event stream or state event), as Wow's own are: a bean that is several at once cannot say which kind it is asked for, so the host refuses to start.
- **The standalone server** sets `wow.kafka.topic-prefix` itself (`wow.view-store-server.`): it has no other aggregates.

```yaml
wow:
  view-store:
    kafka:
      topic-prefix: wow.compensation-service.
```

- Changing either prefix of a running deployment moves the view store to new, empty topics: drain the old ones first.
- A host whose view store topics are Wow's default ones (neither prefix set, and a bus on Kafka) logs a warning at startup naming `wow.view-store.kafka.topic-prefix`: nothing tells it whether another deployment shares the cluster.
- On a Kafka cluster that does not create topics on first use (`auto.create.topics.enable=false`), create the view store's six topics before the host starts: `<prefix>view-store.view.{command,event,state}` and `<prefix>view-store.view_preferences.{command,event,state}`. For the compensation service that is `wow.compensation-service.view-store.view.command` and the five beside it.
- A host with its own view store prefix leaves the view store out of the BI script it generates (`wow.bi.script`): the script reads every aggregate's topics under BI's one `topic-prefix`, which is not the view store's.

## Storage

### MongoDB

- The starter creates no MongoDB indexes of its own: Wow has no hook for a module to add snapshot indexes. For large stores add them yourself on `view_snapshot`: `state.config.kind`, and the multikey `state.config.panels.instanceId`, `state.config.panels.opens` and `state.config.panels.click.instanceId`.
- On MongoDB, two deployments of the view store keep their views apart only by database: two hosts on one database share the collections (the queries keep tenants, owners and applications apart, the storage does not). Give each its own database, as the compensation service and the standalone server do.

### Elasticsearch

The starter ships the index definitions of its two snapshot indices (`META-INF/wow/elasticsearch/wow.view-store.view.snapshot.json` and `wow.view-store.view_preferences.snapshot.json`) and of the view's event stream (`wow.view-store.view.es.json`), which Wow creates at startup when the index does not exist yet, beside its own templates:

- The queried paths (`state.definitionId`, `state.appId`, `state.config.kind` and the panel references) are `keyword` without `ignore_above`, so the query schema admits the filters the view store sends whatever Wow's template infers.
- `state.config` is `dynamic: false`: it holds whatever the engine's config holds, values of several types under one key included, and only `kind` and `panels` (a `nested` array, for the shared-board check, with `instanceId`, `opens` and `click.instanceId`) are fields. So the server refuses a config whose `panels` is not an array of at most 1000 objects or whose references are not strings of at most 256 characters, and a `definitionId` longer than 256 characters: a store refuses a document it cannot index.
- The preferences' `state.lastTabs` is not indexed (`enabled: false`): its keys are the host's.
- In the view's events, `body.body` (an event's payload) is `dynamic: false` with `audience` and `toOwnerId` as keywords: the replay route finds a claim by them.

**An existing index is not changed.** When a host wrote views before it ran a starter with these definitions, Wow created the index from its templates alone; the index keeps that mapping, and the host logs a warning at startup naming the paths it maps otherwise: on such a snapshot index the view lists are refused, on such an event-stream index the replay answers 400. Delete it while it is empty, or reindex it into an index created from the definition before the host starts (the general steps are in [Reindex an existing index](./elasticsearch.md#reindex-existing-index)). For the event stream, with the host stopped:

1. `PUT wow.view-store.view.es-new` with the body `{"mappings":{"enabled":false}}`, and `POST _reindex` from `wow.view-store.view.es` into it. The temporary index matches no Wow template (`wow.*.es`), so nothing but its body maps it: a body that maps only part of the documents lets Elasticsearch map the rest dynamically, and the reindex fails on an event whose payload has another shape. With the mapping disabled it only keeps `_source`, which is all the way back needs.
2. Delete `wow.view-store.view.es`, `PUT` it again with the body of `wow.view-store.view.es.json` (Wow's template supplies the rest), and `POST _reindex` back from `wow.view-store.view.es-new`.
3. Delete `wow.view-store.view.es-new` and start the host: the warning is gone.

**Index prefix.** The view store's indices have no prefix of their own: two hosts that embed the starter on one Elasticsearch cluster share `wow.view-store.view.snapshot` (the queries keep their applications apart, the storage does not), unless each host sets `wow.elasticsearch.index-prefix`. It moves all of the host's indices and templates, the view store's included (`<prefix>wow.view-store.view.snapshot`, …); the definitions above keep their file names.

## System views

A system view is a view every user of an application reads and none of them owns. They come from three places:

| Source | Where | Written by |
|---|---|---|
| Code | The host's view definitions, ids `system:…` | Nobody: read-only, never sent to the server |
| Configured | `wow.view-store.system-views` (or a host's `SystemViewProvider` bean), per tenant and application (blank = all) | Nobody: read-only; a change needs a restart |
| Stored | View aggregates under the reserved tenant `(platform)` and owner `(system)`, per application and definition | Administrators, through the normal view routes, without a restart |

`GET …/tenant/{tenantId}/owner/(shared)/system-views[?definitionId=]` answers, for **any** request tenant, the configured views that match its tenant and application followed by the stored views of its application, and `GET …/system-views/{id}` the stored one before the configured one. Each `SystemView` carries `source` (`configured` or `stored`) and, for a stored one, `version`; `revision` is a hash of its content for both. A stored view with the id of a configured one wins, on reads and on writes, and the server logs a warning once.

### Configured system views

```yaml
wow:
  view-store:
    system-views:
      - tenant-id: ''        # blank: every tenant
        app-id: my-app       # blank: every application
        definition-id: orders
        id: orders-open
        title: Open orders
        config: |
          {"kind": "record", "columns": []}
```

`config` is the engine's `ViewConfig` as JSON text. Each entry is checked at startup: `config` must be a JSON object whose `kind` is `record`, `analysis` or `dashboard`, the id must not be blank nor start with `system:` (kept for the views declared in code), and an id must not repeat within one tenant and application; otherwise startup fails.

To read them from somewhere else (a database, a configuration service), replace the default `SystemViewProvider` bean and build each view with `SystemViews.of`, which runs the same checks and gives it its content-hash revision:

```kotlin
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.viewstore.starter.system.SystemViews
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Flux
import tools.jackson.databind.node.ObjectNode

@Configuration
class SystemViewConfiguration {
    @Bean
    fun systemViewProvider(): SystemViewProvider = SystemViewProvider { tenantId, appId ->
        // Per the request's tenant and application; here, the same for all.
        Flux.just(
            SystemViews.of(
                id = "orders-open",
                definitionId = "orders",
                title = "Open orders",
                config = """{"kind": "record", "columns": []}""".toObject<ObjectNode>(),
            ),
        )
    }
}
```

### Stored system views

Stored system views are **global**: they live under the tenant `(platform)` only (`ViewStoreService.SYSTEM_TENANT_ID`, the value of CoSec's platform tenant; not Wow's default tenant `(0)`, which a deployment without tenants uses), and every tenant reads them. A create under the owner `(system)` in any other tenant is refused (`ViewInvalid`). They are written through the routes every view has, on one path:

```text
POST   /view-store/tenant/(platform)/owner/(system)/view                 create ("publish": a copy of a view's title and config)
PUT    /view-store/tenant/(platform)/owner/(system)/view/{id}/save
PUT    /view-store/tenant/(platform)/owner/(system)/view/{id}/rename
DELETE /view-store/tenant/(platform)/owner/(system)/view/{id}            delete ("unpublish")
GET    /view-store/tenant/(platform)/owner/(system)/view/requests/{requestId}   replay of a retried write
```

(Without the `/view-store` prefix in a host whose own context is `view-store`.)

- A system view never moves audience: `share` and `claim` are refused (`SystemViewReadOnly`). Deleting one that a shared dashboard shows is allowed, as for a shared view; the panel breaks.
- The server generates every view id, so over HTTP a clash with a configured view does not happen by accident; a host that wants to replace a configured view in place creates the stored one in process with that id (`CreateView` to the aggregate `(platform)`/`(system)`/`<id>`).
- Stored views are read from the view snapshots through the host's snapshot query backend (MongoDB or Elasticsearch), at most 1000 per application (a warning names an application past that). A host without one (in-memory snapshots) serves the configured views alone and logs one warning at startup: stored system views are off there. A `StoredSystemViewSource` bean of the host's replaces either.
- On the front end, whoever the host grants `editSystem` publishes and edits them ([permissions](../typescript/view-engine-storage.md#permissions-buttons-only-the-gateway-decides)).

## Security model

### The gateway owns permissions

The server does not authenticate; **identity is the path**. The path's `{ownerId}` is the user (or `(shared)`), and the tenant and the application are the path's `{tenantId}` and the `CoSec-App-Id` header. The server has no setting for who may write: every admission is a path rule of the CoSec gateway ([CoSec](./cosec.md)). What the server guarantees is that the path is the one it reads:

- The application comes from `CoSec-App-Id` only; every `Command-Header-*` a caller sends to a view store path is dropped (Wow rejects the keys it reserves, `command_operator` and `app_id` among them, and would copy any other key into the command's header), and so are `Command-Tenant-Id` and `Command-Owner-Id` (Wow rejects one that contradicts the path, and a claim is dispatched under `(shared)`, which the caller's own owner header would contradict).
- A snapshot query is kept in its tenant, owner and application: the starter adds `state.appId = <CoSec-App-Id>` to the query's scope as a scope contributor ([Query Gateway](../query/query-gateway.md)), beside the tenant and owner the host's scope reads from the path. A query without `CoSec-App-Id` answers 400 `ViewAppRequired`, and so does one whose scope lacks the application (a host that builds the query routes with its own scope skips contributors, and the starter's query policy fails closed); an HTTP query of the view store's event streams answers `ViewEventStreamClosed`.
- A path whose decoded tenant or owner is empty, or holds a character that shows as nothing or a blank (whitespace, control and format characters such as U+200B, surrogates, private-use and unassigned code points, and invisible characters of other categories), answers 400 `ViewScopeRequired` before Wow sees it: Wow itself rejects a blank declared path value (400 `IllegalArgument`) but not an invisible character, which makes an owner that reads as another. A claimed owner follows the same rule.

### Gateway rules

The rules tie the tenant, the owner and the application to the token (paths under the service's base path):

| Path | Methods | Allow when |
|---|---|---|
| `/view-store/tenant/{tenantId}/owner/{ownerId}/**`, except `…/view/{id}/claim` and `…/view/{id}/share` | all | `{tenantId}` is the token's tenant **and** `{ownerId}` is the token's `sub` |
| `/view-store/tenant/{tenantId}/owner/(shared)/**` | `GET`, and `POST …/snapshot/**` (reads) | `{tenantId}` is the token's tenant |
| `/view-store/tenant/{tenantId}/owner/(shared)/**` | `POST /view`, `PUT`, `DELETE` (writes) | `{tenantId}` is the token's tenant **and** the caller has the role that may write shared views |
| `/view-store/tenant/{tenantId}/owner/{ownerId}/view/{id}/claim`, `…/view/{id}/share` | `PUT` | `{tenantId}` is the token's tenant, `{ownerId}` is the token's `sub`, **and** the caller has the role that may write shared views |
| `/view-store/tenant/{tenantId}/owner/(shared)/definitions/{definitionId}/preferences` | `PUT` | As a shared write (a host nobody signs in to keeps its preferences there) |
| `/view-store/tenant/(platform)/owner/(system)/**` | all | The caller is an administrator of the system views (any tenant: the views are global) |

- **Claim and share need both.** Claiming moves a shared view to the caller, so it takes it out of everyone's list; sharing moves a personal view to `(shared)`, so it publishes it into everyone's list. Both are sent to a personal path (`sub == {ownerId}`) and need the shared-write role as well. The personal rule must not admit either on its own: leave `…/view/{id}/claim` and `…/view/{id}/share` out of it, so only this rule decides; otherwise anyone could publish a shared view by creating a personal one and sharing it.
- **The application comes from the token too.** CoSec authenticates `CoSec-App-Id`; reject a request whose header is missing or names an application the token is not for. The server keeps applications apart by that header.
- **`(shared)` and `(system)` are never users.** Do not issue a token whose `sub` is `(shared)` or `(system)`, or holds parentheses: the server refuses such an owner for a claim, and the gateway's personal rule would otherwise admit it to the shared or the system path.
- **Reading system views needs no rule of its own**: clients read them through `…/owner/(shared)/system-views` of their own tenant.

### System views are written by platform administrators only

A rule on `…/tenant/(platform)/owner/(system)/**` decides who may publish, change and unpublish the system views every tenant reads; the personal rule must not admit that path (a tenant `(platform)` user's `sub` is never `(system)`). The CoSec policy below matches the path without regard to case and denies it to everyone but a user of the platform tenant with the role `admin` (who is admitted by the deployment's own allow rules; the policy only refuses everyone else). It has no policy-level `condition`, since CoSec 5.2 refuses an empty one:

```json
{
  "id": "view-store-system-views",
  "name": "View store system views",
  "category": "view-store",
  "description": "Only platform administrators write the global system views.",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "SystemViewsPlatformAdminOnly",
      "effect": "deny",
      "action": {
        "path": {
          "pattern": "/view-store/tenant/(platform)/owner/(system)/**",
          "options": { "caseSensitive": false }
        }
      },
      "condition": {
        "bool": {
          "or": [
            { "inTenant": { "value": "platform", "negate": true } },
            { "inRole": { "value": "admin", "negate": true } }
          ]
        }
      }
    }
  ]
}
```

**The server refuses any other spelling of the path.** A request whose path decodes to `(platform)` / `(system)` but spells it otherwise (percent-encoded, with a `;` parameter, in another letter case) is refused (`ViewScopeRequired`), so a gateway rule on the literal path sees every system-view write. Nothing else writes a view: Wow's command facade and every batch and maintenance route of the view store's aggregates are closed, and a command sent to another owner or tenant path fails Wow's owner check or misses the aggregate.

Any path rule has two limits: the gateway must see the path as the server routes it, so merge repeated slashes before the gateway (`/view-store//tenant/…` merged only after it would escape a literal rule); and a producer that writes to the command bus (Kafka) directly bypasses the gateway, which is the same trust boundary as for every other view.

::: warning
Without a gateway rule on `…/tenant/(platform)/owner/(system)/**`, anyone who reaches the view store creates, changes and deletes the system views of every tenant.
:::

### The front end's buttons follow the same roles

`WowViewStore`'s `permissions` (`createShared`, `instance(id)`'s `save` / `rename` / `delete` / `changeAudience`, and the engine's `editSystem`) are the host's to give, by the same roles the gateway checks: `createShared` and `changeAudience` need the shared-write role, `editSystem` the system-view administrator's. The server does not tell the client what it may do.

A host nobody signs in to (such as the compensation console) has no token: it fills the tenant `(0)`, the owner `(shared)` and its application itself, and its gateway admits it by network or by a service token, as it admits the rest of that console ([A host nobody signs in to](../typescript/view-engine-storage.md#a-host-nobody-signs-in-to)).

## Facts about writes

- **Creating is not idempotent.** A retried `POST /view` creates another view with a new server-generated id. Saves, renames, audience changes, deletes and preferences are idempotent by `Command-Request-Id`, and the replay route answers what a request id's write left.
- **The shared-board check reads snapshots.** A view a shared dashboard references cannot be made personal; the check queries the dashboards' snapshots, so a board saved a moment before may not be seen yet (eventual consistency).

## Source

[`view-store/`](https://github.com/Ahoo-Wang/Wow/tree/main/view-store) · [`ViewStoreProperties.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/wow-view-store-starter/src/main/kotlin/me/ahoo/wow/viewstore/starter/ViewStoreProperties.kt) · [`ViewStoreAutoConfiguration.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/wow-view-store-starter/src/main/kotlin/me/ahoo/wow/viewstore/starter/ViewStoreAutoConfiguration.kt) · [server configuration template](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/wow-view-store-server/src/dist/config/application.yaml) · [`Dockerfile`](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/wow-view-store-server/Dockerfile)
