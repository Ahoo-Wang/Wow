---
title: Troubleshooting the TypeScript Client
description: What the messages of the Wow TypeScript packages, the compiler and the service mean, and how to fix them.
---

# Troubleshooting the TypeScript Client

Search this page for the message you see.

## Installing

| Message | Cause | Fix |
| --- | --- | --- |
| `unmet peer @ahoo-wang/wow-client@~x.y.z: found …` | The Wow packages of the application are on different minor versions | Install one version of every Wow package ([Compatibility and Versions](./compatibility#versions)) |
| `Cannot find module 'react/compiler-runtime'` | React older than 19 | `wow-react` and the view engine need React 19 or later |
| `pnpm add` installs 9.x | `latest` names the newest stable release; a Wow 10 pre-release is on its own dist-tag | Install the version of your services, or the pre-release's dist-tag ([Release status](./compatibility#release-status)) |

## Compiling

| Message | Cause | Fix |
| --- | --- | --- |
| `TS2345` on a filter field, such as `'status'` or `'state.statsu'` | A query client types its fields: the system fields and the generated `<Aggregate>StateField`. A state field lives under `state.` | Name it `state.status`; regenerate if the service changed |
| `TS2322: Type 'number' is not assignable to type 'string'` on an amount or an id | A `Long`, `BigInteger` or `BigDecimal` field is a `string`, read and written exactly | Pass its digits as text: `unitPrice: '19.90'` ([Exact numbers](./generate#exact-numbers)) |
| `TS2339: Property '…' does not exist` on a query's record | The query's `projection.include` left that path out, and the result is narrowed to what it includes | Include the path, or drop the projection |
| `TS2307: Cannot find module './generated'` under `NodeNext` | Node resolution needs the file | Import `./generated/index.js` |
| `TS2882: Cannot find module or type declarations for side-effect import of '…/styles.css'` | TypeScript 6 checks side-effect imports | A Vite project declares `*.css` through `vite/client`; elsewhere add `declare module '*.css';` |

## Sending requests

| Message | Cause | Fix |
| --- | --- | --- |
| `Failed to parse URL from /eshop-ordering/…` | The client has no `fetcher`, and the default Fetcher has no base URL | Create the clients with `{ fetcher }` ([Send Commands](./commands#create-the-clients-once)) |
| `404` with an `about:blank` problem | The route does not exist where the request went: a gateway that does not route the context's prefix, or a `basePath` it does not expect | Send to the service or the gateway that serves `/<context>/…` |
| `TypeError: The path … needs id.` | A command on an existing aggregate was sent with an empty id | Pass the aggregate's id |
| `TypeError: Failed to fetch` in a browser, a CORS message in the console | The service does not allow the page's origin | Configure CORS on the service or the gateway, or serve the API from the page's origin |
| `401` `wow.auth.unauthenticated` on every request | No token, or the token is not sent by the Fetcher the clients use | Apply CoSec to that Fetcher and sign in ([Authentication](./authentication)) |

## Service answers

A command rejected by its aggregate or at admission (`wow.command.invalid` among them) rejects with a `CommandRejectedError`, with its `code`, its `result` and the `problem` the service answered; every other refusal, and a query's, rejects with a `WowProblemError`. The `code` is one of these, or a domain code of the generated `<Context>Contract.<Aggregate>.Errors`. [Error Codes](../reference/error-codes) lists them all.

| `code` | Status | Cause | Fix |
| --- | --- | --- | --- |
| A domain code, `<context>.<aggregate>.<name>` | 422 unless the service says otherwise | The aggregate rejected the command | Show `error.result.error?.message`; the request itself has to change |
| `wow.command.invalid` | 400 | The command failed validation | Show each of `error.result.error?.violations` beside its `path` |
| `wow.aggregate.not-found` | 404 | No aggregate with that id | Handle the absence |
| `wow.aggregate.version-mismatch` | 409, or 412 with `expectedVersion` | Another write came first | Read the state again and decide again |
| `wow.command.request-id-reused` | 409 | A request id was used by another command | Generate a new request id for a new command |
| `wow.wait.timeout` | 504 | The wait ran out of time; the command may have committed | Retry with the same request id, or read the state |
| `wow.wait.invalid` | 400 | The wait plan is not a chain the service can follow | Build the plan from the generated hops of each context |
| `wow.query.invalid` | 400 | The query broke a rule of the query model | Read each violation's `code` and `path` |
| `wow.query.min-version-not-reached` | 409 | A read's `minVersion` was not reached within the wait | Retry after `retryAfterMs`; the problem's `version` is the current one |
| `wow.command.busy`, `wow.query.busy` | 429 | The aggregate, the node or the store is busy | Retry after `retryAfterMs` |
| `wow.node.standby` | 503 | The service is on standby before a migration's cut-over and serves reads only | Retry later |

`error.retry` says whether a retry can succeed and whether to keep the request id.

## Streams

| Symptom | Cause | Fix |
| --- | --- | --- |
| A command stream ends before the last hop of its plan | A saga on the way sent nothing further: the last result has `chainEnded`, and `pending` lists the hops that will not come | Treat it as the chain's end, as the eShop's checkout does when a payment is declined |
| A command stream's last result is not `SUCCEEDED` | The command, or a command the chain led to, was rejected or failed after admission | Read the result's `status` and `error` |
| A query stream errors with a `WowProblemError` | The service failed midway with a problem the client cannot resume past | Handle it like a failed request |
| The connection stays open after the component is gone | The stream was never read to its end or cancelled | Pass a `signal` and abort it on cleanup, or cancel the reader |

## Generating

| Exit code or message | Cause | Fix |
| --- | --- | --- |
| Exit 2 | The input cannot be read or fetched, is neither a Manifest nor an OpenAPI 3 document, or an option is wrong | Read the message; an http(s) input that needs credentials takes `-H 'Authorization: Bearer …'` |
| Exit 3 | The configuration cannot be read, parsed or validated | Fix the file the message names ([configuration](../reference/typescript/wow-generator/configuration)) |
| Exit 4 | The document cannot be generated, or `--strict` and the run logged a warning | Read the warnings of the run |
| Exit 5 | The output cannot be written, a path escapes it, or a newer generator wrote its `.wow-generator.json` | Generate with the generator of the version that wrote it |
| The committed clients differ from a new run | The Manifest baseline changed, or the generator's version did | Commit the new output with the change that caused it |

Run with `--verbose` for every step and the stack trace of a failure.

## Still stuck

Collect the failing request's method and URL, the status, the problem's body and its `Wow-Request-Id`, the package versions (`pnpm ls @ahoo-wang/wow-client @ahoo-wang/fetcher`) and the service's Wow version, and open an issue at [Ahoo-Wang/Wow](https://github.com/Ahoo-Wang/Wow/issues).
