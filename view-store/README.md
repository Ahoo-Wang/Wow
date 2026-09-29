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

- `POST /view` (create), `PUT /view/{id}/save`, `/rename` and `/audience`, and `DELETE /view/{id}`;
- `POST /view/snapshot/{list,single,paged,…}` and the same for `/view_preferences/`, which the query policy keeps
  in the request's application;
- `GET /system-views`, `GET` / `PUT /definitions/{definitionId}/preferences` and `GET /view/requests/{requestId}`
  (replay).

Every other route Wow generates for the two aggregates (state, tracing, event streams, snapshot maintenance,
compensation), and the command facade for their commands, answers 404. The application comes from `CoSec-App-Id`
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

## The standalone server

`wow-view-store-server` runs the starter with MongoDB and in-memory buses, as a single instance (see
`src/dist/config/application.yaml`). **Run it only behind the CoSec gateway.** The gateway authenticates and
authorizes by path; the server only reads the user from the token the gateway forwards (`cosec.inject.enabled=true`)
and does not verify that token again. The user is the owner of a view made personal.

```bash
service_dir=view-store/wow-view-store-server
mkdir -p "$service_dir/logs" "$service_dir/data" "$service_dir/config"
test -e "$service_dir/config/application.yaml" || cp "$service_dir/src/dist/config/application.yaml" "$service_dir/config/application.yaml"
./gradlew :wow-view-store-server:run
```
