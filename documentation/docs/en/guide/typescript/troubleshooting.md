---
title: Troubleshooting the TypeScript Client
description: Find the cause of common wow-client, wow-generator and wow-react failures by the message they print.
---

# Troubleshooting the TypeScript Client

This page answers: **what does this error from the Wow TypeScript packages mean, and how do I fix it?** Search the page for the message you see. Failures on the server side — command timeouts, version conflicts, projections — are in the server's [Troubleshooting](../troubleshooting.md).

Most request failures reach your code as the fetcher's `ExchangeError`; its `message` and its `cause` name what went wrong, and `await toWowError(error)` reads Wow's error code when the server answered. See [Error Handling](./error-handling.md).

## Installing

| Message | Cause | Fix |
|---|---|---|
| `404 Not Found - GET https://registry.npmjs.org/@ahoo-wang%2fwow-client` (`E404`) | The packages are released with Wow 9.2.0 | Until then, use `@ahoo-wang/fetcher-wow` and `@ahoo-wang/fetcher-generator` 5.x; see [release status](./compatibility.md#release-status) |
| `WARN … unmet peer @ahoo-wang/wow-client@~9.2.0: found 9.3.0` | The Wow packages of the application are on different minor versions | Install one version of `wow-client`, `wow-generator` and `wow-react` |
| `Cannot find module 'react/compiler-runtime'` | React older than 19 | `wow-react` needs React 19.3 or later; React 18 is not supported |
| `Unsupported engine … wanted: {"node":">=22.12.0"}` | Node.js older than 22.12 | Upgrade Node.js |

## Compiling

| Message | Cause | Fix |
|---|---|---|
| `TS1206: Decorators are not valid here`, `TS1241: Unable to resolve signature of method decorator` in `src/generated` | Generated clients are legacy decorator classes | Set `"experimentalDecorators": true` in the `tsconfig.json` that compiles them |
| `TS2307: Cannot find module './generated'` under `NodeNext` | Node resolution needs the file | Import `./generated/index.js` |
| `TS2345` on a filter field, such as `'state.itmes'` | Query clients type their fields with the aggregate's field names | Fix the field name; the generated `…AggregatedFields` enum lists them |
| `TS2305: … has no exported member 'and'` (or `eq`, `Operator`, `Condition`) | The `Condition` API lives on `@ahoo-wang/wow-client/legacy` | Use `filter.*` from the root entry, or import from `/legacy` for a Wow 8.10 server |
| `TS2882: Cannot find module or type declarations for side-effect import of '…/styles.css'` | TypeScript 6 checks side-effect imports | Vite projects declare `*.css` through `vite/client`; elsewhere add `declare module '*.css';` |

## Sending requests

| Message | Cause | Fix |
|---|---|---|
| `Failed to parse URL from /example/owner/…` | The client has no `fetcher` and the default Fetcher has no base URL | Pass `{ fetcher }`, or register the service as the default; see [Authentication and Interceptors](./authentication.md#which-fetcher-a-client-uses) |
| `Request failed with status code 404 for http://…/example/owner/…` | The generated client adds the bounded-context prefix (`example`), which a gateway routes by, but the request went to the service itself | Pass `basePath: ''` to command clients and `contextAlias: ''` to query clients, or send through the gateway |
| `404` without the prefix, through a gateway | The prefix was cleared, or the gateway routes by another alias | Keep the generated default, or set `basePath` / `contextAlias` to the gateway's route |
| `Missing required path parameter: ownerId` (or `tenantId`), with a `[fetcher-decorator] Path template … has placeholder(s) {ownerId}` warning | An owner- or tenant-scoped route with nothing filling its variable | Apply CoSec to the Fetcher and sign in, or pass `urlParams: { path: { ownerId } }`; see [Authentication and Interceptors](./authentication.md) |
| `TypeError: Failed to fetch` in a browser, a CORS message in the console | The service does not allow the page's origin | Configure CORS on the service or gateway, or serve the API from the page's origin |
| `401` on every request | No token, or CoSec is not applied to the Fetcher the clients use | Check which Fetcher the client uses; see [Authentication and Interceptors](./authentication.md) |

## Server answers

`toWowError(error)` returns these codes:

| `errorCode` | Status | Cause | Fix |
|---|---|---|---|
| `CommandValidation` | 400 | The command body breaks its validation rules | Show `bindingErrors` next to their fields; see [Error Handling](./error-handling.md#show-validation-errors-on-a-form) |
| `IllegalArgument`, `IllegalState` | 400 | The command handler refused the command | Show `errorMsg`; the request itself has to change |
| `NotFound` | 404 | No aggregate or snapshot with that id | Handle the absence; after a command, wait for `CommandStage.SNAPSHOT` before reading |
| `RequestTimeout` | 408 | The wait stage did not arrive within `timeoutMs` | The command was received and may still complete; read the state instead of resending |
| `DuplicateRequestId` | 400 | A request id was sent twice | The first attempt reached the server; do not resend |
| `EventVersionConflict`, `CommandExpectVersionConflict` | 409 | Another write came first | Reload the state and decide again |
| `QuerySchemaValidation`: `Field [state.items.productId] requires its declared element scope` | 400 | A field inside an array was filtered directly | Match it with `filter.elementMatch('state.items', filter.eq('productId', …))` |
| `QuerySchemaValidation` on a field name | 400 | The field is not queryable on this aggregate | Use the generated field names; regenerate if the service changed |
| `QuerySchemaUnavailable`: `No query backend is configured for aggregate` | 503 | The service has no snapshot store for queries (for example an in-memory setup) | Configure the service's query backend |
| `400` from a Wow 8.11 or later server on a `/legacy` query with `raw()` | 400 | `raw()` reaches only Wow 8.10 | Rewrite the query with `filter.*` |

## Streams

| Symptom | Cause | Fix |
|---|---|---|
| A `for await` over a query stream throws `WowError` | The server failed midway and ended the stream with an error event | Handle it like a failed request; see [Error Handling](./error-handling.md#streams) |
| A generated `…StreamCommandClient` yields an event named `RequestTimeout` (or another error code) instead of a stage | Generated stream clients use Fetcher's plain event extractor | Check `event.event` against `CommandStage`, or use `CommandClient.sendAndWaitStream`, which throws a `WowError` |
| A streamed command ends at `PROCESSED` with an `errorCode` other than `Ok` | The command handler failed | Check `errorCode` on every result |
| The connection stays open after the component is gone | The stream was never read to its end or aborted | Pass an `AbortSignal` and abort it on cleanup, or `break` out of the loop |

## Generating

| Exit code or message | Cause | Fix |
|---|---|---|
| Exit 2: `Cannot read the OpenAPI document …: HTTP 401 Unauthorized` | The document needs credentials | Pass `-H "Authorization: Bearer …"` |
| Exit 2: `… no response within 30000 ms` | The service is unreachable or slow | Check the URL, or raise `--timeout` |
| Exit 2: `… is a Swagger 2.0 document` | `wow-generator` reads OpenAPI 3.x only | Convert it, for example with swagger2openapi |
| Exit 3 | The configuration file cannot be read, parsed or validated, or the file named with `-c` does not exist | Fix the file named in the message; see [configuration](../../reference/typescript/wow-generator/configuration.md) |
| Exit 4 | Two schemas or two methods generate the same name, a `$ref` points nowhere, Wow metadata is malformed, or `--strict` and a warning | Read the message; name methods with `methodNames` in the configuration |
| A method is missing, and a warning names its operation | The operation has no `operationId` or no tag | Give it both in the service; `--strict` turns the warning into exit 4 |
| Warning `… uses the deprecated name; rename it to wow-generator.config.json` | `fetcher-generator.config.json` was read | Rename it to `wow-generator.config.json` |
| Generated command clients or query factories are missing for an aggregate | The document does not describe it as a Wow aggregate | See [Wow aggregate discovery](../../reference/typescript/wow-generator/wow-discovery.md) |

Run with `--verbose` for every step and the stack trace of a failure.

## Still stuck

Collect the failing request's method and URL, the status, the `Wow-Error-Code` header and the body, the package versions (`pnpm ls @ahoo-wang/wow-client @ahoo-wang/fetcher`) and the server's Wow version, and open an issue at [Ahoo-Wang/Wow](https://github.com/Ahoo-Wang/Wow/issues).
