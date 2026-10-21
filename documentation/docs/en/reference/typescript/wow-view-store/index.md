---
title: 'wow-view-store reference'
description: 'WowViewStore, the @ahoo-wang/wow-view-store package: the view engine''s ViewStore on the Wow view store server.'
---

# wow-view-store reference

::: info On npm since Wow 9.2.0
`@ahoo-wang/wow-view-store` is on npm since Wow 9.2.0, released together with [wow-view-engine](../wow-view-engine/) from the same tag and with the same version as Wow. A patch release never breaks its public surface (the exports of the entry and the error codes); a minor release may, and its release notes list every break with the steps to follow ([version ranges](../../../guide/typescript/compatibility.md#version-ranges)). The [package README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) and the [design](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/view-store-backend.md) are the source of truth.
:::

`WowViewStore` implements the view engine's [`ViewStore` port](../wow-view-engine/#persistence) on the [view store](https://github.com/Ahoo-Wang/Wow/tree/v10/platform/view-store): saved views and preferences as two Wow aggregates, served by the standalone `wow-view-store-server` or by a Wow service that embeds `wow-view-store-starter`.

## Entry

| Export | Role |
|---|---|
| `WowViewStore` | The store: `new WowViewStore({ fetcher, permissions? })`, handed to `new ViewEngine({ store })` |
| `WowViewStoreOptions` | `fetcher`: the fetcher whose interceptors carry the tenant, the owner and the application; `permissions`: which buttons are enabled |
| `SHARED_OWNER_ID` | `(shared)`, the owner segment of shared views and shared preferences |
| `SYSTEM_OWNER_ID`, `SYSTEM_TENANT_ID` | `(system)` and `(platform)`: the one path stored system views are written on, whatever the caller's tenant |
| `WowViewStoreErrorCodes` | The view store's own error codes, beside Wow's |

```ts
export interface WowViewStoreOptions {
  fetcher: Fetcher;
  permissions?: (definitionId: string) => ViewPermissions;
}
```

### WowViewStore {#api-WowViewStore}

The view engine's [`ViewStore`](../wow-view-engine/store#api-ViewStore) over the Wow view store: every method of the port, the optional `changeAudience` included; `permissions` is there only when the host gave `options.permissions`. The port names a view by id alone, so the store remembers where it last saw each one (a list, a read, a write) and looks an unknown id up on the personal path, the shared path and the server's system views, in that order.

- `fetcher` goes to a base URL that serves `/view-store/…`: the CoSec gateway in front of the view store server, or the service that embeds the starter. Its interceptors carry who is asking; the store never does.
- `permissions` decides which buttons are enabled for one definition's views; everything is allowed when left out, as the port reads a store without `permissions`. The server does not authorize, the CoSec gateway does; a host answers this by the roles it holds there — `changeAudience` by the role that may write `owner/(shared)`, which claiming a view needs.

```ts
export declare class WowViewStore implements ViewStore {
  constructor(options: WowViewStoreOptions);
  changeAudience(id: string, audience: ViewAudience, revision: string, context: WriteContext): Promise<ViewInstance>;
  create(input: Omit<ViewInstance, 'id' | 'revision'>, context: WriteContext): Promise<ViewInstance>;
  delete(id: string, revision: string, context: WriteContext): Promise<void>;
  get(id: string, signal?: AbortSignal): Promise<ViewInstance>;
  getPreferences(definitionId: string, signal?: AbortSignal): Promise<ViewPreferences>;
  list(definitionId: string, signal?: AbortSignal): Promise<ViewInstanceSummary[]>;
  readonly permissions?: (definitionId: string) => ViewPermissions;
  rename(id: string, title: string, revision: string, context: WriteContext): Promise<ViewInstance>;
  save(id: string, config: ViewConfig, revision: string, context: WriteContext): Promise<ViewInstance>;
  setPreferences(definitionId: string, preferences: ViewPreferences, context: WriteContext): Promise<ViewPreferences>;
}
```

`WowViewStoreErrorCodes` are the server's `errorCode` strings, which a `ViewStoreError` carries as `detail.code`; they are named apart from the engine's `ViewStoreErrorCode` (the port's six codes):

```ts
export declare const WowViewStoreErrorCodes: Readonly<{
  readonly VIEW_INVALID: 'ViewInvalid';
  readonly VIEW_APP_REQUIRED: 'ViewAppRequired';
  readonly SYSTEM_VIEW_READ_ONLY: 'SystemViewReadOnly';
  readonly VIEW_SCOPE_REQUIRED: 'ViewScopeRequired';
  readonly VIEW_EVENT_STREAM_CLOSED: 'ViewEventStreamClosed';
}>;
```

## Contract

| Rule | Behavior |
|---|---|
| Who is asking | The store takes no tenant, user or application. The fetcher's interceptors fill the path's `{tenantId}` and, on a personal path, `{ownerId}` (fetcher-cosec's `ResourceAttributionRequestInterceptor`, from the token) and send `CoSec-App-Id`; a host nobody signs in to fills defaults with an interceptor of its own |
| Audience | The owner segment of the path: a personal view on the caller's path, a shared one on `owner/(shared)`. "Make shared" is `share` on the view's path, "Make personal" is `claim` on the caller's own path |
| Writes | `Command-Request-Id` is the port's `requestId`, `Command-Aggregate-Version` its `revision`; each waits for the snapshot and answers the view read back |
| Retries | A write refused as a stale version or a repeated request id is looked up by its request id first, and a retry answers what its first attempt wrote. The server does not deduplicate a create (it generates the id); a store asks the replay route before posting a retry of its own create again, so only a retry from another store makes a second view |
| Errors | By Wow's error code onto `CONFLICT`, `NOT_FOUND`, `FORBIDDEN`, `INVALID` and `UNAVAILABLE`; the HTTP status only when no known code came back; `UNSUPPORTED` from a server with no view store. The error keeps the server's code as `detail.code`, and an `UNAVAILABLE` the server answered says `reachable` |
| List order | System views, then shared, then personal, each audience oldest first; at most 1,000 of each audience |
| System views | Configured ones are read-only; stored ones (global, under `tenant/(platform)/owner/(system)`) carry `stored: true` and are created (published as a copy), saved, renamed and deleted there, never shared or claimed. Their `revision` is a content hash; the store sends the version it read beside it. The engine's `editSystem` is the host's to give, off when unsaid |
| Shared boards | A view a shared dashboard shows stays shared: the claim is `INVALID`, its `boards` the boards' titles as stored, which the view engine says in its own words |

## Requests {#requests}

How each method of the port becomes requests to the view store server.

Every route is under `/view-store/tenant/{tenantId}/owner/{ownerId}`, and **the
owner segment is the audience**: a personal view lives on the caller's own path,
a shared one on `owner/(shared)`.

| Port                               | Request                                                                                                                                                                                |
| ---------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `list`                             | The snapshot list on the caller's path and on `(shared)`, projected to the summary (`state.config.kind`, not the config), and `GET (shared)/system-views?definitionId=`, sent together |
| `get`                              | The snapshot of one id where the view was last seen; an unknown id is looked up on the caller's path, then `(shared)`, then the system views                                           |
| `create`                           | `POST …/view` on the path of its `scope` (`system`: `tenant/(platform)/owner/(system)`); the server generates the id                                                                   |
| `save`, `rename`, `delete`         | `PUT …/view/{id}/save`, `/rename`, `DELETE …/view/{id}` on the view's path                                                                                                             |
| `changeAudience('shared')`         | `PUT …/view/{id}/share` on the view's path                                                                                                                                             |
| `changeAudience('personal')`       | `PUT …/view/{id}/claim` on the caller's own path                                                                                                                                       |
| `getPreferences`, `setPreferences` | `GET`, `PUT …/definitions/{definitionId}/preferences` on the caller's path                                                                                                             |

A list answers in the port's order — the system views, then the shared views,
then the caller's, each audience oldest first (the server sorts it by
`firstEventTime`) — and reads at most 1,000 views of each audience, the server's
query budget: the oldest 1,000. A view past the cut is still read by its id, and
the workbench asks for one an address names before it sets it aside.

**Writes** send the port's `requestId` as `Command-Request-Id`, the `revision`
as `Command-Aggregate-Version` (`'0'` for preferences never written), and wait
for the snapshot. The answer is the view read back at the version the write
left. `share` and `delete` send `{}`, `claim` no body.

**A retry answers the first outcome.** A write the server refuses as a stale
version or a repeated request id is looked up by its request id first
(`GET …/view/requests/{requestId}`, on the path it was sent to and then the
other one): if the first attempt landed, its outcome is the answer. Only then is
a stale version `CONFLICT`, carrying the view as it is now — or `NOT_FOUND`,
when the view is gone. A lookup the server fails is no answer: on the path the
write went to, the outcome is unknown (`UNAVAILABLE`, and a retry is safe); on the
other path — `(shared)` refuses a caller without the shared role — the write is
taken as not replayed, and the refusal stands. A write that landed and was moved
on by another writer before the read back answers the view as the replay route
gives it, or, if that route cannot be asked, as it was read. Preferences have no such route: the store answers a retry
with what its own first attempt answered, and a retry whose first answer was
lost with what is stored, when that is what it wrote.

**Creating is not idempotent on the server**, which generates the id. The store
remembers the request ids of its own creates (the last 256), and a retry of one
first asks the replay route on the path of its `scope`: if the first attempt
landed, that view is the answer, and nothing is posted again. A retry from
another store instance — another tab, a reload — makes a second view.

**A view that moved.** When another tab shared a view this store remembers as
personal (or the other way round), the write is refused on the old path; the
store looks the view up again and sends the write once more to where it is.

**System views.** The server's system views (`GET (shared)/system-views`) are
configured ones, read-only, and stored ones (`source: 'stored'`), which
`WowViewStore` lists and reads with `stored: true` on the summary and the
instance. A stored one is written on `tenant/(platform)/owner/(system)`
(`SYSTEM_TENANT_ID`, `SYSTEM_OWNER_ID`) whatever the caller's tenant: they are
global. `create({ scope: 'system', … })` publishes one (a copy: the source
view stays), `save`, `rename` and `delete` edit and unpublish it;
`changeAudience` and any write to a configured or code system view are
`FORBIDDEN` before anything is sent.

A system view's `revision` is a hash of its content, as the engine needs for
its dirty baseline, while a write expects the aggregate version: the store
remembers the `version` each read of a stored view gave beside its revision,
sends that version, and when the engine holds a revision it has no version
for, reads the view again first — a revision other than the view's is
`CONFLICT` with the view as it is, and nothing is sent. The answer of a write
is read back from the system-views route at the version it left. A retry the
server refuses as a repeated request is found by the replay route on the
system path, but that route answers a snapshot, whose content hash only the
server computes: the store answers it with the system view **as it is now**,
which is the retried write's outcome unless another admin wrote in between.
A retry whose first attempt moved the view on (so the revision it holds is no
longer the view's) asks the replay route before it is refused as `CONFLICT`,
and a retried create of a system view answers the view from the system
views, flagged and at its hash.

## Error mapping {#errors}

Every rejection is the engine's `ViewStoreError`, read by Wow's error code — the
HTTP status only when an answer carries no code the store knows:

| Server                                                                                                                                                                                                                                                                     | Port          |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| `CommandExpectVersionConflict`, `EventVersionConflict`, `SourcingVersionConflict`; without a known code, 409 and 412                                                                                                                                                       | `CONFLICT`    |
| `NotFound` (also a view of another application), `IllegalAccessDeletedAggregate`; without a known code, 404 and 410                                                                                                                                                        | `NOT_FOUND`   |
| `IllegalAccessOwnerAggregate`, `IllegalAccessSpaceAggregate`, `IllegalAccessQueryScope`, `SystemViewReadOnly`, `ViewEventStreamClosed`; without a known code, 401 and 403                                                                                                  | `FORBIDDEN`   |
| `ViewInvalid`, `ViewAppRequired`, `ViewScopeRequired`, `BadRequest`, `CommandValidation`, `IllegalArgument`, `DuplicateAggregateId`, `QuerySchemaValidation`; without a known code, 400 and 422; a path variable the fetcher's interceptors never filled (nothing is sent) | `INVALID`     |
| `IllegalState`, `RequestTimeout`, `TooManyRequests`, `InternalServerError`, `QuerySchemaUnavailable`, `QuerySchemaConflict`; without a known code, any other status; no answer at all                                                                                      | `UNAVAILABLE` |
| `404` from a server with no view store at all (one released before it): a list, or a read whose every place is `404` while the server's system views are too                                                                                                               | `UNSUPPORTED` |

Every `ViewStoreError` keeps what it was read from: the request's failure as
`cause`, the server's `errorCode` as `detail.code` (so a host tells
`ViewAppRequired` from `ViewInvalid`, both `INVALID`), and on an `UNAVAILABLE`
the server answered — a 5xx, a timeout it reported, a page that is not JSON —
`reachable: true`, which the engine says as 「服务端暂时无法处理」 rather than
「无法连接服务端」.

A repeated request id (`DuplicateRequestId`) is not an error of its own: the
store looks the first attempt up ([Requests](#requests)). A path variable left unfilled is the
host's set-up — its interceptors do not fill `{tenantId}`, or `{ownerId}` on a
personal path — so it is `INVALID`, as the server's own `ViewScopeRequired` is:
the request is wrong as built, and a retry would send it the same.

A claim the server refuses because shared dashboards show the view carries those
boards' **titles** in the error's `boards`, as stored, and the view engine says
the refusal in its own words around them: a title written as a key stays a key,
said where the refusal is shown. The `message` keeps the server's words, for
logs.

## Hosts and the gateway

How a host wires `WowViewStore`, gives its `permissions` and serves a page nobody signs in to is in [Saved Views](../../../typescript/view-engine/storage.md#wowviewstore-views-on-a-wow-service). The server does not authenticate, so every deployment runs behind the CoSec gateway; its path rules, including the one that keeps the system views to platform administrators, are in the [View Store](https://github.com/Ahoo-Wang/Wow/blob/v10/deploy/v10/view-store/README.md) page.

## Source

[typescript/wow-view-store](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-store) · [README](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-store/README.md) · [view store server](https://github.com/Ahoo-Wang/Wow/tree/v10-9x-baseline/view-store)
