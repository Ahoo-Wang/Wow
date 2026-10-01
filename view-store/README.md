# View store

The Wow storage backend of the view engine's `ViewStore` port (`@ahoo-wang/wow-view-engine`): saved views and view
preferences as two Wow aggregates. Design: [view-store-backend.md](../typescript/wow-view-engine/docs/design/view-store-backend.md).

| Module | Contents |
| --- | --- |
| `wow-view-store-api` | Bounded context `view-store`, commands, events, `ViewKind` / `ViewAudience`, `SystemView`, error codes |
| `wow-view-store-domain` | Aggregates `View` (`view`) and `ViewPreferences` (`view_preferences`) and their rules |
| `wow-view-store-starter` | Spring Boot auto-configuration that embeds the view store in a Wow service |
| `wow-view-store-server` | A standalone service on the starter, with MongoDB for events and snapshots |

## Embedding the starter

Add `wow-view-store-starter` to a Wow WebFlux service. The example server (`example/example-server`) and the
compensation service (`compensation/wow-compensation-server`) embed it; the compensation console keeps its views
there. The routes are served under `/view-store/tenant/{tenantId}/owner/{ownerId}/…`:

- `POST /view` (create), `PUT /view/{id}/save` and `/rename`, and `DELETE /view/{id}` (the view store's own
  `DeleteView`, so that the host's `DefaultDeleteAggregate` keeps its schema name in the host's OpenAPI);
- `PUT /view/{id}/share` on the view's personal path moves it to `owner/(shared)`, and `PUT /view/{id}/claim` on the
  caller's own personal path moves a shared view to that owner. A change to the audience the view already has is
  answered at the current version without a write;
- `POST /view/snapshot/{list,single,paged,…}` and the same for `/view_preferences/`, which the query policy keeps
  in the request's application;
- `GET /system-views` (the configured and the stored system views, under `(shared)`; see
  [System views](#system-views)), `GET` / `PUT /definitions/{definitionId}/preferences` and
  `GET /view/requests/{requestId}` (replay).

Every other route Wow generates for the two aggregates (state, tracing, event streams, snapshot maintenance,
compensation, recover and resource tags), and the command facade for their commands, answers 404. Commands carry no
id (Wow takes it from `{id}`); a command without fields (`share`, `delete`) is sent with the body `{}`. The application comes from `CoSec-App-Id`
only, and every `Command-Header-*` a caller sends to a view store path is dropped (Wow would copy it into the command's
header as it is, `command_operator` included). The starter matches paths case-insensitively for all of these rules, so
a host that sets `PathMatchConfigurer.setUseCaseSensitiveMatch(false)` is covered, except one: an open route wins over
a closed one only in its own case (on a case-sensitive host `…/view/REQUESTS/state` is Wow's closed state route for a
view with the id `REQUESTS`, not the replay route), so on a case-insensitive host such a path answers 404; a host that replaces Spring's
`RouterFunctionMapping` with a parser of other options must not embed the starter. Set `wow.view-store.enabled=false` to turn the starter off, and replace the default `SystemViewProvider` bean
to serve configured system views from somewhere other than `wow.view-store.system-views` (stored ones are
[System views](#system-views)).

## System views

A system view is a view every user of an application reads and none of them owns. They come from three places:

| Source | Where | Written by |
| --- | --- | --- |
| Code | the host's view definitions, ids `system:…` | nobody: read-only, never sent to the server |
| Configured | `wow.view-store.system-views` (or a host's `SystemViewProvider` bean), per tenant and application (blank = all) | nobody: read-only; a change needs a restart |
| Stored | View aggregates under the reserved tenant `(platform)` and owner `(system)`, per application and definition | admins, through the normal view routes, without a restart |

`GET …/tenant/{tenantId}/owner/(shared)/system-views[?definitionId=]` answers, for **any** request tenant, the
configured views that match its tenant and application followed by the stored views of its application, and
`GET …/system-views/{id}` the stored one before the configured one. Each `SystemView` carries `source`
(`configured` or `stored`) and, for a stored one, `version`; `revision` is a hash of its content for both. A stored
view with the id of a configured one wins, on reads and on writes, and the server logs a warning once. The server
generates every view id, so over HTTP such a clash does not happen by accident; a host that wants to replace a
configured view in place creates the stored one in process with that id (`CreateView` to the aggregate
`(platform)`/`(system)`/`<id>`).

Stored system views are **global**: they live under the tenant `(platform)` (`ViewStoreService.SYSTEM_TENANT_ID`, the value of
CoSec's platform tenant; not Wow's default tenant `(0)`, which a deployment without tenants uses)
only, and every tenant reads them. A create under the owner `(system)` in any other tenant is refused (`ViewInvalid`).
They are written through the routes every view has, on one path:

```
POST   /view-store/tenant/(platform)/owner/(system)/view                 create ("publish": a copy of a view's title and config)
PUT    /view-store/tenant/(platform)/owner/(system)/view/{id}/save
PUT    /view-store/tenant/(platform)/owner/(system)/view/{id}/rename
DELETE /view-store/tenant/(platform)/owner/(system)/view/{id}            delete ("unpublish")
GET    /view-store/tenant/(platform)/owner/(system)/view/requests/{requestId}   replay of a retried write
```

(without the `/view-store` prefix in a host whose own context is `view-store`). A system view never moves audience:
`share` and `claim` are refused (`SystemViewReadOnly`). Deleting one that a shared dashboard shows is allowed, as for
a shared view; the panel breaks. A request whose path decodes to `(platform)` / `(system)` but spells it otherwise
(percent-encoded, with a `;` parameter, in another letter case) is refused (`ViewScopeRequired`), so a gateway rule
on that literal path sees every write. Nothing else writes a view: Wow's command facade and every batch and
maintenance route of the view store's aggregates are closed, and a command sent to another owner or tenant path
fails Wow's owner check or misses the aggregate.

**Who may write them is the gateway's decision** (see [CoSec gateway rules](#cosec-gateway-rules)): the server has no
setting for it. Without a gateway rule that keeps `…/tenant/(platform)/owner/(system)/**` to administrators, every caller
who reaches the view store can create, change and delete the system views of every tenant.

The stored views are read from the view snapshots through the host's snapshot query backend (MongoDB or
Elasticsearch), at most 1000 per application (a warning names an application past that). A host without one (in-memory
snapshots) serves the configured views alone and logs one warning at startup: stored system views are off there. A
`StoredSystemViewSource` bean of the host's replaces either.

### Storage

**MongoDB.** The starter creates no MongoDB indexes of its own. Wow has no hook for a module to add snapshot indexes, so
for large stores add them yourself on `view_snapshot`: `state.config.kind` and the multikey
`state.config.panels.instanceId`, `state.config.panels.opens` and `state.config.panels.click.instanceId`.

**Aggregate names.** The aggregates are named `view` and `view_preferences`, and Wow's MongoDB collections
(`view_event_stream`, `view_snapshot`, …) carry no context. A host that has its own `view` or `view_preferences`
aggregate would share those collections: do not embed the starter in it, or rename the host's aggregate. Two
deployments of the view store keep their views apart on MongoDB only by database: two hosts on one database share
the collections (the queries keep tenants, owners and applications apart, the storage does not), so give each its
own database, as the compensation service and the standalone server do.

**Elasticsearch.** The starter ships the index definitions of its two snapshot indices
(`META-INF/wow/elasticsearch/wow.view-store.view.snapshot.json` and `wow.view-store.view_preferences.snapshot.json`)
and of the view's event stream (`wow.view-store.view.es.json`), which Wow creates at startup when the index does not
exist yet, beside its own templates:

- the queried paths are `keyword` without `ignore_above` (`state.definitionId`, `state.appId`, `state.config.kind`
  and the panel references), so that the query schema admits the filters the view store sends whatever Wow's
  template infers: on the template alone, the `*Id` strings are queryable (their `ignore_above` is 8191), and
  `state.config.kind` is `text` with a `keyword` sub-field, capped at 8191 on an index created by 9.2 but at 256 (and
  refused) on one a 9.1 template created;
- `state.config` is `dynamic: false`: it holds whatever the engine's config holds, values of several types under
  one key included, and only `kind` and `panels` (a `nested` array, for the shared-board check, with `instanceId`,
  `opens` and `click.instanceId`) are fields. The server refuses a config whose `panels` is not an array of at most
  1000 objects (each panel is a nested document, and Elasticsearch caps them at 10000 per document) or whose
  references are not strings of at most 256 characters, and a `definitionId` longer than 256 characters; the
  preferences' `definitionId`, `order` and `defaultInstanceId` hold ids of at most 256 characters as well, since a
  store refuses a document it cannot index;
- `state.lastTabs` of the preferences is not indexed (`enabled: false`): its keys are the host's;
- in the view's events, `body.body` (an event's payload, which Wow's event-stream template does not index) is
  `dynamic: false` with `audience` and `toOwnerId` as keywords: the replay route (`GET …/view/requests/{requestId}`)
  finds a claim by them. A config in a `ViewCreated` or `ViewSaved` event adds no field.

An index Wow already created for these aggregates from its templates alone (a host that wrote views before it ran a
starter with these definitions) keeps its mapping, and the host logs a warning at startup naming the paths it maps
otherwise: on such a snapshot index the view lists are refused, on such an event-stream index the replay answers 400.
Delete it while it is empty, or reindex it into an index created from the definition, before the host starts: Wow does
not change an existing index's mapping. For the event stream, that is (with the host stopped):

1. `PUT wow.view-store.view.es-new` with the body `{"mappings":{"enabled":false}}`, and `POST _reindex` from
   `wow.view-store.view.es` into it. The temporary index matches no Wow template (`wow.*.es`), so nothing but its body
   maps it: a body that maps only part of the documents (such as the definition alone) lets Elasticsearch map the
   rest dynamically, and the reindex fails on an event whose payload has another shape. With the mapping disabled it
   only keeps `_source`, which is all the way back needs;
2. delete `wow.view-store.view.es`, `PUT` it again with the body of `wow.view-store.view.es.json` (Wow's template
   supplies the rest), and `POST _reindex` back from `wow.view-store.view.es-new`;
3. delete `wow.view-store.view.es-new` and start the host: the warning is gone.

The view store's indices have no prefix of their own: two hosts that embed the starter on one Elasticsearch cluster
share `wow.view-store.view.snapshot` (the queries keep their applications apart, the storage does not), unless each
host sets `wow.elasticsearch.index-prefix`, which moves all of its indices and templates, the view store's included
(`<prefix>wow.view-store.view.snapshot`, …); the definitions above keep their file names.

### Writes, identity and topics

**Creating is not idempotent.** A retried `POST /view` creates another view with a new server-generated id. Saves,
renames, audience changes, deletes and preferences are idempotent by `Command-Request-Id`, and the replay route
answers what a request id's write left.

**Identity is the path.** The server does not authenticate: the `{ownerId}` of a path is the user (or `(shared)`),
and the tenant and the application are the path's `{tenantId}` and the `CoSec-App-Id` header. Who may use which path
is the CoSec gateway's to decide; see [CoSec gateway rules](#cosec-gateway-rules). The tenant and owner come from the
path only: `Command-Tenant-Id` and `Command-Owner-Id` are dropped, and a path
whose decoded tenant or owner is empty or holds a character that shows as nothing or a blank (whitespace, control and
format characters such as U+200B, surrogates, private-use and unassigned code points (by the JDK's Unicode version),
and invisible characters of other categories: the combining grapheme joiner, variation selectors, Hangul fillers,
the braille blank: `owner/%20`,
`tenant/%E3%80%80`, `owner/alice%E2%80%8B`) answers 400 `ViewScopeRequired`: Wow reads a blank path value as missing
and falls back to those headers, and an invisible character makes an owner that reads as another. A claimed owner
follows the same rule.

**The shared-board check reads snapshots.** Claiming a view is refused while a shared dashboard references it; the
check queries the dashboards' snapshots, so a board saved a moment before may not be seen yet (eventual consistency).

**One deployment per Kafka topic namespace.** Wow names the Kafka topics by context and aggregate
(`wow.view-store.view.command`, …), so two deployments of the view store on one Kafka cluster (the standalone server
and a host embedding the starter, or two hosts) would consume each other's commands and events. Give each deployment
a prefix of its own:

- a host that embeds the starter sets `wow.view-store.kafka.topic-prefix`, which applies to the view store's two
  aggregates only (`<prefix>view-store.view.command`, …); the host's own aggregates keep exactly the topics
  `wow.kafka.topic-prefix` gives them. The compensation service sets `wow.compensation-service.`. Unset or blank, the
  view store's topics follow `wow.kafka.topic-prefix`, as before. With it set, each of the host's topic converter
  beans must be one kind (command, event stream or state event), as Wow's own are: a bean that is several at once
  cannot say which kind it is asked for, so the host refuses to start;
- the standalone server sets `wow.kafka.topic-prefix` itself (`wow.view-store-server.`): it has no other aggregates.

Changing either prefix of a running deployment moves the view store to new, empty topics; drain the old ones first.
A host whose view store topics are Wow's default ones (neither prefix set, and a bus on Kafka) logs a warning at
startup naming `wow.view-store.kafka.topic-prefix`: nothing tells it whether another deployment shares the cluster.

On a Kafka cluster that does not create topics on first use (`auto.create.topics.enable=false`), create the view
store's six topics before the host starts: `<prefix>view-store.view.{command,event,state}` and
`<prefix>view-store.view_preferences.{command,event,state}`. For the compensation service that is
`wow.compensation-service.view-store.view.command` and the five beside it.

A host with its own view store prefix leaves the view store out of the BI script it generates (`wow.bi.script`): the
script reads every aggregate's topics under BI's one `topic-prefix`, which does not name the view store's. A host
without one keeps the view store in it, on the same prefix as its own aggregates.

## CoSec gateway rules

Every deployment of the view store, embedded or standalone, runs behind the CoSec gateway, and the gateway's rules
are what keep one user out of another's views. The server takes the tenant, the owner and the application as the
request names them, so the rules must tie each to the token:

| Path (under the service's base path) | Methods | Allow when |
| --- | --- | --- |
| `/view-store/tenant/{tenantId}/owner/{ownerId}/**`, except `…/view/{id}/claim` and `…/view/{id}/share` | all | `{tenantId}` is the token's tenant **and** `{ownerId}` is the token's `sub` |
| `/view-store/tenant/{tenantId}/owner/(shared)/**` | `GET`, and `POST …/snapshot/**` (reads) | `{tenantId}` is the token's tenant |
| `/view-store/tenant/{tenantId}/owner/(shared)/**` | `POST /view`, `PUT`, `DELETE` (writes) | `{tenantId}` is the token's tenant **and** the caller has the role that may write shared views |
| `/view-store/tenant/{tenantId}/owner/{ownerId}/view/{id}/claim`, `…/view/{id}/share` | `PUT` | `{tenantId}` is the token's tenant, `{ownerId}` is the token's `sub`, **and** the caller has the role that may write shared views |
| `/view-store/tenant/{tenantId}/owner/(shared)/definitions/{definitionId}/preferences` | `PUT` | as a shared write (a host nobody signs in to keeps its preferences there) |
| `/view-store/tenant/(platform)/owner/(system)/**` | all | the caller is an administrator of the system views (any tenant: the views are global) |

- **Claim and share need both.** Claiming moves a shared view to the caller, so it takes it out of everyone's list;
  sharing moves a personal view to `(shared)`, so it publishes it into everyone's list. Both are sent to a personal
  path (`sub == {ownerId}`: claim to the caller's own, share to the view's, which is the caller's) and need the
  shared-write role as well. The personal-path rule must not admit either on its own: leave `…/view/{id}/claim` and
  `…/view/{id}/share` out of it, so only the claim-and-share rule decides; otherwise anyone could publish a shared
  view by creating a personal one and sharing it.
- **The application comes from the token too.** CoSec authenticates `CoSec-App-Id`; reject a request whose header is
  missing or names an application the token is not for. The server keeps applications apart by that header.
- **`(shared)` and `(system)` are never users.** Do not issue a token whose `sub` is `(shared)` or `(system)`, or
  holds parentheses; the server refuses such an owner for a claim, and the gateway's personal rule would otherwise
  admit it to the shared or the system path.
- **System views are an administrator's.** A rule on `…/tenant/(platform)/owner/(system)/**` decides who may publish,
  change and unpublish the system views every tenant reads; the personal rule must not admit that path (a tenant `(platform)`
  user's `sub` is never `(system)`). Reading them needs no rule of its own: clients read them through
  `…/owner/(shared)/system-views` of their own tenant. A CoSec policy that denies the path, matched without regard to
  case, to everyone but a user of the platform tenant with the role `admin` (who is admitted by the deployment's own
  allow rules; the policy only refuses everyone else). It has no policy-level `condition`, since CoSec 5.2 refuses an
  empty one:

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

  The server refuses any other spelling of the path, so the rule sees every system-view write. Without such a rule,
  anyone who reaches the view store writes the system views of every tenant. Two limits of any path rule: the
  gateway must see the path as the server routes it, so merge repeated slashes before the gateway
  (`/view-store//tenant/…` merged only after it would escape a literal rule); and a producer that writes to the
  command bus (Kafka) directly bypasses the gateway, which is the same trust boundary as for every other view.
- **A host's buttons follow the same role.** `WowViewStore`'s `permissions` (`createShared`, `instance(id).save`,
  `rename`, `delete`, `changeAudience`, and the engine's `editSystem` for stored system views) are the host's to give,
  by the same role the gateway checks: `createShared` and `changeAudience` (claim and share) both need the
  shared-write role, `editSystem` the system-view administrator's; the server does not tell the client what it may
  do.
- A host nobody signs in to (the compensation console) has no token: it fills the tenant `(platform)`, the owner `(shared)`
  and its application itself, and its gateway admits it by network or by a service token, as it admits the rest of
  that console.

## The standalone server

`wow-view-store-server` runs the starter on the same middleware as the compensation service, and can run as several
instances:

| Middleware | Used for | Setting |
| --- | --- | --- |
| MongoDB | event streams and snapshots (Wow's default storage) | `spring.mongodb.uri` |
| Kafka | the command, event and state-event buses (Wow's default buses) | `wow.kafka.bootstrap-servers`, `wow.kafka.topic-prefix` |
| Redis | CosId machine ids shared by the instances | `spring.data.redis.url`, `cosid.machine.distributor.type: redis` |

**MongoDB only in 9.2.0.** The standalone server is built without Wow's Elasticsearch support, so it stores events
and snapshots on MongoDB alone. To keep views on Elasticsearch, embed the starter in a host that runs on Elasticsearch:
the index definitions above ship with the starter, not with the server.

**Run it only behind the CoSec gateway**: the server trusts the tenant and owner of every path, so it must never be
reachable without the gateway's path rules in front of it. `src/dist/config/application.yaml` is the template for
`config/application.yaml`; the `Dockerfile` packages `installDist` like the compensation server's.

```bash
service_dir=view-store/wow-view-store-server
mkdir -p "$service_dir/logs" "$service_dir/data" "$service_dir/config"
test -e "$service_dir/config/application.yaml" || cp "$service_dir/src/dist/config/application.yaml" "$service_dir/config/application.yaml"
./gradlew :wow-view-store-server:run
```

### Docker image

`.github/workflows/view-store-deploy.yml` builds the image from `installDist` for `linux/amd64` and `linux/arm64`
and pushes it as:

- `ahoowang/wow-view-store-server`
- `ghcr.io/ahoo-wang/wow-view-store-server`
- `registry.cn-shanghai.aliyuncs.com/ahoo/wow-view-store-server`

A `v<version>` tag publishes `<version>` and `<major>.<minor>`; a push to `main` (and the daily run) publishes
`main`. A patch to an older line (a `v9.2.3` tag after `v9.3.0`) does not move `latest`, which stays on the highest
stable release.

**The image does no authentication.** It trusts the tenant and owner of every path and the `CoSec-App-Id` header
(see [Identity is the path](#writes-identity-and-topics)): deploy it only behind the CoSec gateway with the
[gateway rules](#cosec-gateway-rules), on a network nothing else reaches it from. Its port must never be published to
users directly.

The image runs `/opt/wow-view-store-server/bin/wow-view-store-server` as a non-root user on port 8080. Its
`HEALTHCHECK` reads `/actuator/health/liveness` (the process runs); a backend that is down shows in `/actuator/health`,
which the template answers with the status alone (`show-details: when-authorized`), so that the port does not name
the backends. It reads its configuration from `/opt/wow-view-store-server/config/`, which
holds the `src/dist/config/application.yaml` template (everything on `localhost`): mount your own `application.yaml`
there, or override the settings with environment variables. Set the three backend addresses (MongoDB, Kafka, Redis): a
container that misses one starts, points at `localhost` and fails only when it first uses that backend.

| Variable | Setting | Template value |
| --- | --- | --- |
| `SPRING_MONGODB_URI` | `spring.mongodb.uri` | `mongodb://root:root@localhost:27017/wow_view_store_db?authSource=admin&maxIdleTimeMS=60000` |
| `WOW_KAFKA_BOOTSTRAPSERVERS` | `wow.kafka.bootstrap-servers` | `PLAINTEXT://localhost:9092` |
| `WOW_KAFKA_TOPICPREFIX` | `wow.kafka.topic-prefix` | `wow.view-store-server.` |
| `SPRING_DATA_REDIS_URL` | `spring.data.redis.url` (CosId machine ids) | `redis://localhost:6379` |

The JVM options (`-Xms512M -Xmx512M`, ZGC, GC log under `logs/`, heap dumps under `data/`) come from the
`installDist` start script; set `JAVA_OPTS` to add to them. As with every deployment of the view store, the
container must be reachable only through the CoSec gateway.

```bash
docker run -d -p 8080:8080 \
  -e SPRING_MONGODB_URI='mongodb://root:root@mongo:27017/wow_view_store_db?authSource=admin' \
  -e WOW_KAFKA_BOOTSTRAPSERVERS='PLAINTEXT://kafka:9092' \
  -e SPRING_DATA_REDIS_URL='redis://redis:6379' \
  ahoowang/wow-view-store-server:<version>
```
