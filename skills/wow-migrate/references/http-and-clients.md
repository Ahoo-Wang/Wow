# HTTP and TypeScript clients, 9.x to V10

The job: the applications that call a 9.x service keep working against its V10 service. V10 changes the routes, headers, errors and the client packages' API (decision D266): there is no compatibility layer, so every caller moves with the service. The order is fixed: the service's source migration produces its V10 Manifest first; the TypeScript clients are then regenerated from that Manifest, and the application code is fixed by what the type check reports. Verify every V10 name against the pinned packages (`node_modules/@ahoo-wang/wow-client/dist/*.d.ts`) and the service's Manifest; the 9.x names below are for recognition only.

## Recognize the 9.x side

`@ahoo-wang/fetcher-wow`, `@ahoo-wang/fetcher-generator` (or the script `fetcher-generator`), a 9.x `@ahoo-wang/wow-client` or `@ahoo-wang/wow-generator` (9.x versions), `@ahoo-wang/fetcher-react`'s Wow hooks, `@ahoo-wang/fetcher-viewer`; in code `CommandClient.send<C>(request)`, `commandHeaders`, `waitStrategy`, `CommandStage`, `QueryClientFactory`, `pagedState`, `singleState`, `getStateById`, `EventStreamQueryClient`, `toWowError`, `ErrorCodes`, `bindingErrors`, `@ahoo-wang/wow-client/legacy` (`Condition`), and routes with `/tenant/{tenantId}/`, `/owner/{ownerId}/` or `snapshot/paged`.

## Packages

| 9.x | V10 |
| --- | --- |
| `@ahoo-wang/fetcher-wow`, or a 9.x `@ahoo-wang/wow-client` | `@ahoo-wang/wow-client` of the V10 version (peers `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-eventstream`; no `fetcher-decorator`) |
| `@ahoo-wang/fetcher-generator`, a 9.x `@ahoo-wang/wow-generator` reading the service's OpenAPI (`/v3/api-docs`, the `x-wow-*` extensions) | `@ahoo-wang/wow-generator` of the V10 version reading the service's **Manifest** (`GET /wow/manifest` or its committed `manifest/baseline.json`). The OpenAPI-to-Wow path, the `fetcher-generator` alias and `fetcher-generator.config.json` are gone; plain OpenAPI-to-TS generation remains for non-Wow APIs |
| `@ahoo-wang/fetcher-react` Wow hooks, or a 9.x `@ahoo-wang/wow-react` | `@ahoo-wang/wow-react` of the V10 version: `use*Query({ query, execute })` over a client method, or `useFetcher*Query` over a route |
| `@ahoo-wang/fetcher-viewer` | `@ahoo-wang/wow-view-engine` with a definition and a host, rebuilt, not renamed ($wow-view-definition, $wow-view-host) |
| The 9.x view store (`/view-store/tenant/{tenantId}/owner/{ownerId}/…`) | `@ahoo-wang/wow-view-store`'s `WowViewStore` on the V10 view store service ($wow-view-host) |

Keep every Wow package on one V10 minor (`~` or exact).

## Routes and headers

| 9.x | V10 |
| --- | --- |
| `POST /{context}/[tenant/{tenantId}/][owner/{ownerId}/]{aggregate}/{id}/{command}` | `POST /{context}/{aggregate}/{id}/{command}` (kebab command); tenant and owner come from the caller, never the path |
| `…/snapshot/{single,list,paged,cursor,count,aggregation}` and their `/state` forms; `…/event/…` | `POST /{context}/{aggregate}/{snapshot\|events}/query` with `kind` in the body (`single`, `list`, `paged`, `cursor`, `count`, `aggregate`) |
| `GET …/snapshot/schema`, `…/event/schema` | `GET` on the same query route (`ETag` = version) |
| A state by id | `GET /{context}/{aggregate}/{id}/state` (`?version=`, `?at=`, `?minVersion=`, `…/state/trace`) |
| `/dataset/…` | `GET /wow/dataset`, `/wow/dataset/{context}/{dataset}/query` |
| `Command-Wait-Stage`, `-Context`, `-Processor`, `-Function`, `Command-Wait-Tail-*`, `Command-Wait-Timeout` | `Wow-Wait` (a plan: `accepted`, `committed`, `snapshot`, `handled([ctx/]Processor.function)`, chained with `>`), `Wow-Wait-Timeout` |
| `Command-Request-Id` | `Wow-Request-Id` |
| `Command-Aggregate-Version` | `If-Match` |
| `Command-Tenant-Id`, `Command-Owner-Id`, `Command-Aggregate-Id` | From the caller and the route; an allowed caller acts elsewhere with `Wow-Scope`, `Wow-Space` |
| `Command-Local-First`, `Command-Type`, `Command-Header-*` | Gone; request facts the service lists in `wow.http.request-facts` |

Gateways, CoSec policies and API docs keyed on the 9.x paths move too: list every rule that names a 9.x path and its V10 replacement.

## Client code

| 9.x | V10 |
| --- | --- |
| `new CommandClient(…).send<C>({ … headers: commandHeaders({ … }), … })` with `waitStrategy({ stage: CommandStage.SNAPSHOT })` | The generated `<Aggregate>CommandClient.<command>(body, { id, wait: { plan: [wait.snapshot()] } })`, or `CommandGateway.send(path, body, options)` |
| `sendAndWaitStream` | The generated `…Stream` method, or `CommandGateway.stream` |
| `QueryClientFactory`, `cartQueryClientFactory` | The generated `<Aggregate>SnapshotQueryClient`, `<Aggregate>EventQueryClient`, `<Aggregate>StateClient` |
| `pagedState`, `listState`, `singleState` (state alone) | `paged`, `list`, `single` answer whole records; read `record.state`, or project with a literal `include` |
| `getStateById(id)` | `<Aggregate>StateClient.get(id)` (`read(id, { minVersion })` after a command) |
| `EventStreamQueryClient`: records of `body[]` with `bodyType` (a class name) | `EventQueryClient`: records are commits with `events[]` of `{name, revision, body}`; `name` is `<context>.<aggregate>.<Event>` |
| `@ahoo-wang/wow-client/legacy` `Condition` | Gone: `filter.*` from `@ahoo-wang/wow-client/dsl` |
| `DatasetDirectoryClient`, `DatasetQueryClient` | `DatasetClient` (`list()`, `dataset(context, name)`) |
| `abort` argument, `urlParams.path.{ tenantId, ownerId }`, `contextAlias` | `RequestOptions.signal`; nothing (the caller); `WowClientOptions.basePath` for a gateway prefix |
| `toWowError(error)`, `ErrorCodes`, `errorCode`, `errorMsg`, `bindingErrors` | `error instanceof WowProblemError`: `code`, `status`, `detail`, `violations` (`path`, `code`, `message`), `retry`; `FrameworkErrorCodes` and the generated `<Context>ErrorCodes` |
| A failed command resolving with an error code | Every refusal and every command that did not succeed rejects (`resolveRejected: true` to resolve instead) |

## Behaviour that changes without a type error

- **Error codes:** every code changed: framework codes are `wow.*` (`wow.aggregate.not-found`, `wow.command.busy`, …), a domain rejection is `<context>.<aggregate>.<code>`. Grep the application for every 9.x code string and map each.
- **Exact numbers:** a `Long`, `BigInteger` or `BigDecimal` field is a `string` in the V10 types and read exactly; code that did arithmetic on a `number` now concatenates strings unless it was fixed. Find each such field in the regenerated types and every use.
- **Time zones:** a relative-time filter without `zoneId` reads calendar days in UTC in V10 (9.x used the server's zone): pass `zoneId` wherever the application meant a local day.
- **Event names:** `bodyType` class names become `name` values; saved filters, analytics and screens keyed on the old names change.
- **Snapshot fields:** 9.x `eventId` and `snapshotTime` are gone; `firstEventTime`, `lastEventTime`, `operator`, `firstOperator`, `tags` remain.
- **Waits:** 9.x stages map by meaning: `SENT` → `accepted`, `PROCESSED` → `committed`, `SNAPSHOT` → `snapshot` (the V10 default), `PROJECTED`, `EVENT_HANDLED`, `SAGA_HANDLED` → `handled('Processor.function')` naming the subscription, which the generated `<context>Wait` builders list.

## Steps and gates

1. The service's V10 Manifest exists and passes its own compatibility baseline (the source migration).
2. Replace the packages, regenerate from the Manifest into the same output directory (commit `.wow-generator.json`), delete the 9.x generator configuration and scripts.
3. Run `tsc`: fix each error by the tables above, never with a cast or `any`. Then lint and the application's tests.
4. Search for what the type check cannot see: 9.x error code strings, `bodyType`, hard-coded 9.x paths in fetches, gateway rules, CoSec policies, `zoneId`-less relative times, arithmetic on exact-number fields.
5. Run the application against the V10 service in development or staging: a command with its wait, a state read, a query, a refusal shown to the user. Report each gate with its command and result; what did not run is `MISSING EVIDENCE`.
