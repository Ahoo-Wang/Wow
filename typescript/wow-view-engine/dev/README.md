# HTTP development experiment

This directory is excluded from the published package. Its routes, envelopes, status mapping and authentication fixture are provisional, not the public ViewHost contract. Use it to test service/runtime boundaries and recovery.

### HTTP adapter and protocol

```tsx
import { HttpViewHost } from './http/index.js';

const host = new HttpViewHost({
  baseUrl: 'https://example.test/view-service/',
  definitionId: orderDefinition.id,
  headers: () => applicationAuthHeaders(),
  resolveSource: id => businessSources[id],
  timeoutMs: 10000,
});
// ViewPage: host + definitionId + an access-scoped scopeKey; no local definition/instances props.
```

Development modules provide: `HttpViewDefinitionService`,
`HttpViewInstanceService`, and `HttpViewPreferenceService`. Use them directly
without a ViewHost or frontend runtime. `HttpViewTransport` shares authentication,
timeouts and errors; its options omit `resolveSource`. The shared
`transport.permission` client owns snapshot validation, versioning and subscriptions,
and exposes `load(definitionId, signal?)` as well as `refresh(signal?)`.

```ts
import { HttpViewTransport, HttpViewInstanceService } from './http/index.js';

const transport = new HttpViewTransport({
  baseUrl: 'https://example.test/view-service/',
  definitionId: 'orders',
  headers: () => applicationAuthHeaders(),
});
const instance = new HttpViewInstanceService(transport);
const views = await instance.list('orders');
```

| Method | Path relative to `/view-service/definitions/{definitionId}` | Body / condition                                            |
| ------ | ----------------------------------------------------------- | ----------------------------------------------------------- |
| GET    | `/`                                                         | ViewDefinition                                              |
| GET    | `/instances`                                                | ViewInstanceList                                            |
| GET    | `/instances/{id}`                                           | ViewInstance                                                |
| GET    | `/permissions`                                              | ViewPermissionSnapshot                                      |
| POST   | `/instances`                                                | Instance without id/revision; `Idempotency-Key` required    |
| PUT    | `/instances/{id}`                                           | Complete instance; quoted revision in `If-Match`            |
| PATCH  | `/instances/{id}/name`                                      | `{title}`; `If-Match`                                       |
| DELETE | `/instances/{id}`                                           | `If-Match`                                                  |
| PUT    | `/order`                                                    | `{instanceIds}`: complete unique visible order              |
| PUT    | `/default`                                                  | `{instanceId}`: visible ID, or `null` for no auto-selection |

Definition and instance IDs must be nonblank, valid Unicode strings; the entire
ID cannot be `.` or `..`. The same rule applies to local metadata and HTTP inputs.
Resource clients encode valid IDs once, preserving Unicode and reserved characters.
`permission.load()` returns a copy of the validated, currently accepted snapshot;
malformed, inconsistent or stale payloads reject with UNAVAILABLE. `refresh()`
continues to ignore older snapshots without exposing their raw payloads.

The GET root path is exactly the definition URL, without requiring a trailing slash. Success responses are `{data, permissions}`; completion-only writes use `data: null`. Instance reads and create/save/rename return complete authoritative instances with revisions. Errors are `{data: null, error: {code, message}, permissions?}`; authenticated failures refresh the permission snapshot when available. Responses and client fetches use `no-store`.

A permission snapshot is `{revision, reorder, instances: {[id]: {save, rename, delete, saveAsPersonal, saveAsShared}}}` with explicit booleans. Older authority revisions cannot restore revoked grants. An HTTP 401 clears cached grants and fences off older in-flight permission responses before parsing the body, including text or malformed JSON responses. It reports UNAUTHENTICATED regardless of the response-body format. Applications call `permission.refresh(signal?)` on an authority-change event; this is not a polling or push-transport implementation. Identity/access-scope changes still require a new ViewPage scopeKey.

| Code                  | HTTP | Meaning                                                                                                              |
| --------------------- | ---: | -------------------------------------------------------------------------------------------------------------------- |
| INVALID_ARGUMENT      |  400 | Invalid input                                                                                                        |
| UNAUTHENTICATED       |  401 | Missing/invalid server session                                                                                       |
| FORBIDDEN             |  403 | Authenticated but operation denied                                                                                   |
| NOT_FOUND             |  404 | Missing or invisible resource                                                                                        |
| CONFLICT              |  409 | Reused request ID with different content, or stale visible-order set                                                 |
| REVISION_CONFLICT     |  412 | Current revision does not match                                                                                      |
| PRECONDITION_REQUIRED |  428 | Missing write revision                                                                                               |
| CORRUPT_STATE         |  500 | Invalid stored service document                                                                                      |
| UNAVAILABLE           |  503 | Service/storage unavailable; also used locally for failed/timed-out reads                                            |
| UNKNOWN_OUTCOME       |  503 | Write receipt unavailable; locally raised after write timeout, cancellation after dispatch, or invalid/lost response |

`If-Match` revision failures use 412, following [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-match). Authentication is derived from a server-owned session, never a posted scopeKey/owner field. The included HTTP server uses explicit fake bearer sessions solely for contract verification; production authentication and persistence implementations must honor the same interface rather than deploy those fake sessions.

`ViewHost.instance.create(input, {requestId, signal?})` requires one ID per logical create. Same user/key and canonical body replay the stored receipt; changed content returns CONFLICT. The view and receipt are committed in the same transaction. ViewEngine retains the ID on unknown failures and blocks changing that pending request's content; retry or explicit reload reconciles the created instance. It never treats a transport failure as proof that a write did not happen. Direct clients must retain their request ID when retrying, including after reconstructing a client. Fixture receipts live until the administrative reset.

Personal ordering is a complete replacement: the last successful replacement for the same user wins. The current visible ID set must match, and no other user's order is modified. Instance writes use revision CAS. These are separate, explicit concurrency semantics.

The default preference is private to the authenticated user. Any visible view can be selected without edit permission; `null` disables automatic selection. The replacement is idempotent, so callers can retry the same value after an unknown write outcome or reload to confirm it.

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine build
pnpm storybook
# In another terminal:
node packages/view-engine/scripts/verify-http-view-host.mjs
# Manual Storybook service:
node packages/view-engine/scripts/verify-http-view-host.mjs --serve
```

The fixture allows only the origin in `VIEW_ENGINE_E2E_BASE_URL` (default `http://127.0.0.1:6006`). Set it when using another Storybook address, including `http://localhost:6006`. Requests from other browser origins are rejected before preflight or mutations.

The successful `DELETE /instances/{id}` envelope contains `ViewDeleteResult`: `{ defaultInstance: ViewInstance | null }`. The receipt comes from the deletion transaction; idempotent repeats also return the current user's authoritative default. Clients must retain this response body.

### Cross-definition dashboards

`HttpViewHost` is a definition-scoped client, not a global resource directory. `supportedFormats.dashboard` negotiates the stored format; it does not extend the routes to other definitions. For a cross-definition dashboard, configure one HTTP client per target definition and compose the existing public `ViewHost` at the application boundary. Import `ViewHost` and `ViewServiceError` from the core package. The example assumes `rootClient` and `ordersClient` are already configured.

```ts
// rootClient and ordersClient each use their own definitionId and transport.
const definitions = new Map([
  ['overview', rootClient],
  ['orders', ordersClient],
]);
const owners = new Map([
  ['dashboard', rootClient],
  ['saved-orders', ordersClient],
]);
function clientFor(registry: ReadonlyMap<string, HttpViewHost>, id: string) {
  const client = registry.get(id);
  if (!client)
    throw new ViewServiceError('NOT_FOUND', 'Resource not registered');
  return client;
}
const host: ViewHost = {
  definition: {
    load: (id, signal) =>
      clientFor(definitions, id).definition.load(id, signal),
  },
  instance: {
    list: rootClient.instance.list,
    load: (id, signal) => clientFor(owners, id).instance.load(id, signal),
    create: rootClient.instance.create,
    save: rootClient.instance.save,
    rename: rootClient.instance.rename,
    delete: rootClient.instance.delete,
  },
  preference: rootClient.preference,
  permission: rootClient.permission,
  resolveSource: rootClient.resolveSource,
};
```

Recreate all clients and the composed host when the user/tenant access scope changes. Instance IDs must be unique within the composed host. Resolve ownership from the application's authorized resource directory; do not guess it from ID text or probe every definition. Forward cancellation signals, keep list/write/preference operations on the root client, and keep its permission snapshot separate from reference clients. The [real HTTP regression](../test/dashboard/httpCompatibility.test.ts) uses a pure dashboard definition and a separate record service, verifies panel queries and root-only saves, and checks that child grants do not overwrite root grants.
