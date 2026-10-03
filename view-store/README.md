# View store

The Wow storage backend of the view engine's `ViewStore` port (`@ahoo-wang/wow-view-engine`): saved views and view
preferences as two Wow aggregates, reached from the front end with `WowViewStore` (`@ahoo-wang/wow-view-store`).

**The documentation is [View Store](https://wow.ahoo.me/guide/extensions/view-store)**
([简体中文](https://wow.ahoo.me/zh/guide/extensions/view-store)): embedding the starter or running the standalone
server and its Docker image, the properties, storage on MongoDB and Elasticsearch, system views, and the CoSec gateway
rules. The front end's side is [Where Views Live](https://wow.ahoo.me/guide/typescript/view-engine-storage). The design
is [view-store-backend.md](../typescript/wow-view-engine/docs/design/view-store-backend.md).

| Module | Contents |
| --- | --- |
| `wow-view-store-api` | Bounded context `view-store`, commands, events, `ViewKind` / `ViewAudience`, `SystemView`, error codes |
| `wow-view-store-domain` | Aggregates `View` (`view`) and `ViewPreferences` (`view_preferences`) and their rules |
| `wow-view-store-starter` | Spring Boot auto-configuration that embeds the view store in a Wow service |
| `wow-view-store-server` | A standalone service on the starter, with MongoDB for events and snapshots; the Docker image `wow-view-store-server` |

**Run it only behind the CoSec gateway.** The server does no authentication: it trusts the tenant and owner of every
path and the `CoSec-App-Id` header.

## System views

Configured and stored system views, and the one path stored ones are written on:
[System views](https://wow.ahoo.me/guide/extensions/view-store#system-views).

## CoSec gateway rules

The path rules every deployment needs, including the one that keeps the system views to platform administrators:
[Security model](https://wow.ahoo.me/guide/extensions/view-store#security-model).
