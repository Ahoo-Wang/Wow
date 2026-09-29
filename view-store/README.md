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

Add `wow-view-store-starter` to a Wow WebFlux service. The routes are served under
`/view-store/tenant/{tenantId}/owner/{ownerId}/…`:

- `POST /view` (create), `PUT /view/{id}/save` and `/rename`, and `DELETE /view/{id}` (Wow's own delete);
- `PUT /view/{id}/share` on the view's personal path moves it to `owner/(shared)`, and `PUT /view/{id}/claim` on the
  caller's own personal path moves a shared view to that owner. A change to the audience the view already has is
  answered at the current version without a write;
- `POST /view/snapshot/{list,single,paged,…}` and the same for `/view_preferences/`, which the query policy keeps
  in the request's application;
- `GET /system-views`, `GET` / `PUT /definitions/{definitionId}/preferences` and `GET /view/requests/{requestId}`
  (replay).

Every other route Wow generates for the two aggregates (state, tracing, event streams, snapshot maintenance,
compensation, recover and resource tags), and the command facade for their commands, answers 404. Commands carry no
id (Wow takes it from `{id}`); a command without fields (`share`, `delete`) is sent with the body `{}`. The application comes from `CoSec-App-Id`
only. Set `wow.view-store.enabled=false` to turn the starter off, and replace the default `SystemViewProvider` bean
to serve system views from somewhere other than `wow.view-store.system-views`.

The starter creates no MongoDB indexes of its own. Wow has no hook for a module to add snapshot indexes, so for
large stores add them yourself on `view_snapshot`: `state.config.kind` and the multikey
`state.config.panels.instanceId`, `state.config.panels.opens` and `state.config.panels.click.instanceId`.

**Aggregate names.** The aggregates are named `view` and `view_preferences`, and Wow's MongoDB collections
(`view_event_stream`, `view_snapshot`, …) carry no context. A host that has its own `view` or `view_preferences`
aggregate would share those collections: do not embed the starter in it, or rename the host's aggregate.

**Creating is not idempotent.** A retried `POST /view` creates another view with a new server-generated id. Saves,
renames, audience changes, deletes and preferences are idempotent by `Command-Request-Id`, and the replay route
answers what a request id's write left.

**Identity is the path.** The server does not authenticate: the `{ownerId}` of a path is the user (or `(shared)`).
The CoSec gateway must let a caller use `owner/{ownerId}` only when it is their own id (the token's `sub`), and decide
by role or permission who may use `owner/(shared)`. Claiming changes a shared view but is sent to the caller's own
path, so it needs both: `PUT …/tenant/{tenantId}/owner/{ownerId}/view/{id}/claim` requires `sub == {ownerId}` **and**
the role that may write `owner/(shared)`. A host gives `permissions.instance(id).changeAudience` by the same role.
The tenant and owner come from the path only: `Command-Tenant-Id` and `Command-Owner-Id` are dropped, and a path
whose decoded tenant or owner is empty or holds whitespace or control characters (`owner/%20`, `tenant/%E3%80%80`)
answers 400 `ViewScopeRequired`, because Wow reads a blank path value as missing and falls back to those headers.

**The shared-board check reads snapshots.** Claiming a view is refused while a shared dashboard references it; the
check queries the dashboards' snapshots, so a board saved a moment before may not be seen yet (eventual consistency).

**One deployment per Kafka topic namespace.** Wow names the Kafka topics by context and aggregate
(`wow.view-store.view.command`, …), so two deployments of the view store on one Kafka cluster (the standalone server
and a host embedding the starter, or two hosts) would consume each other's commands and events. Give each deployment
its own `wow.kafka.topic-prefix` (the standalone server uses `wow.view-store-server.`), or run one view store per
Kafka cluster. The prefix applies to every aggregate of the deployment.

## The standalone server

`wow-view-store-server` runs the starter on the same middleware as the compensation service, and can run as several
instances:

| Middleware | Used for | Setting |
| --- | --- | --- |
| MongoDB | event streams and snapshots (Wow's default storage) | `spring.mongodb.uri` |
| Kafka | the command, event and state-event buses (Wow's default buses) | `wow.kafka.bootstrap-servers`, `wow.kafka.topic-prefix` |
| Redis | CosId machine ids shared by the instances | `spring.data.redis.url`, `cosid.machine.distributor.type: redis` |

**Run it only behind the CoSec gateway**: the server trusts the tenant and owner of every path, so it must never be
reachable without the gateway's path rules in front of it. `src/dist/config/application.yaml` is the template for
`config/application.yaml`; the `Dockerfile` packages `installDist` like the compensation server's.

```bash
service_dir=view-store/wow-view-store-server
mkdir -p "$service_dir/logs" "$service_dir/data" "$service_dir/config"
test -e "$service_dir/config/application.yaml" || cp "$service_dir/src/dist/config/application.yaml" "$service_dir/config/application.yaml"
./gradlew :wow-view-store-server:run
```
