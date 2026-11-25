---
title: Generate a Client
description: Generate the typed clients of a Wow service from its Manifest with wow-generator, keep them in step with the service in CI, and read what each generated file holds.
---

# Generate a Client

`wow-generator` reads a service's Manifest and writes one typed client per aggregate: a method per command, the queries of its snapshots and events, its state reads, its error codes and the hops of its waits. The application calls the service only through these clients, so a route or a field the service changes shows up as a compile error, not as a failed request.

## Where the Manifest comes from

The service serves its Manifest at `GET /wow/manifest` (on its public port only with `wow.http.manifest.enabled: true`), and its build commits the same document as `manifest/baseline.json`, which `check` holds to what the service serves ([The Manifest](../guide/manifest)). Generate from the baseline: it needs no running service, it is the version that was reviewed, and a change to it is a change to the clients.

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
pnpm add -D @ahoo-wang/wow-generator typescript
pnpm exec wow-generator generate \
  --input ../ordering-server/manifest/baseline.json \
  --output src/generated/ordering \
  --strict
```

`--input` also takes an http(s) URL, with `-H 'Name: value'` for the headers it needs. `--strict` fails a run that logged a warning. One run reads one Manifest; a front end of several services runs the generator once per service, each into a directory of its own. The eShop's web UI does it for its five services:

<!-- sample: examples/eshop/web/scripts/generate.mjs -->

```js
// Generates the clients of the eShop's five services from their Manifest
// baselines (`examples/eshop/<context>/<context>-server/manifest/baseline.json`,
// which each server's `check` holds to what it serves) into
// src/generated/<context>/, one wow-generator run per service:
//
//   pnpm --filter @ahoo-wang/wow-generator... build
//   pnpm --filter wow-eshop-web generate
//
// The output is committed byte for byte. With `--check` (`node
// scripts/generate.mjs --check`), wow-generator writes nothing and fails,
// listing the files, when src/generated/<context>/ differs from what it would
// write; src/generated.test.ts runs it for each service.

import { execFileSync } from 'node:child_process';
import { rmSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const CONTEXTS = ['catalog', 'basket', 'ordering', 'inventory', 'payment'];

const WEB = fileURLToPath(new URL('..', import.meta.url));
export const ESHOP = join(WEB, '..');
export const CLI = join(WEB, '../../../typescript/wow-generator/dist/cli.js');

/** The Manifest baseline of the service of `context`. */
export function baselineOf(context) {
  return join(ESHOP, context, `${context}-server`, 'manifest', 'baseline.json');
}

/** Writes the clients of `context` into `output`, or with `--check` compares them. */
export function generate(context, output, ...options) {
  execFileSync(
    process.execPath,
    [CLI, 'generate', '-i', baselineOf(context), '-o', output, '--strict', '--quiet', ...options],
    { stdio: 'inherit' },
  );
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const check = process.argv.includes('--check');
  for (const context of CONTEXTS) {
    const output = join(WEB, 'src', 'generated', context);
    if (check) {
      generate(context, output, '--check');
    } else {
      rmSync(output, { recursive: true, force: true });
      generate(context, output);
    }
  }
}
```

The [CLI reference](../reference/typescript/wow-generator/cli) lists every option and exit code.

## What it writes

For each bounded context, a directory named after it:

| File | Holds |
| --- | --- |
| `contract.ts` | `<Context>Contract`: the context's name, its context-level error codes and its aggregates, each with its error codes; the `<Context>ErrorCode` union, and the hops of a wait in it |
| `manifest.ts` | `<Context>Manifest`: the routes of every command the context lists, by its names and former names, system commands and a job's included, an internal one left out, and of its service routes, and the property of the body that names the aggregate of a command whose body does (`idProperty`), as its Manifest lists them, with no schema. The command clients send by it, so they fetch no Manifest |
| `client.ts` | `<Context>Client`: the context's `CommandClient` (`commands`), which writes every command's exact numbers unquoted as the aggregate's command client does, and, per aggregate, its command, snapshot, event and state clients; its service routes (`routes`) when it has any |
| `routes.ts` | `<Context>Routes`: a method per service route of the context, on `ServiceRoutes` of `@ahoo-wang/wow-client/routes`, answering the route's answer, `undefined` beside it for a route the Manifest marks `responseOptional` (a `null` answered as `204 No Content`), and `void` for a route of no answer; written only when the Manifest lists service routes |
| `types.ts` | The commands, events and states of the Manifest's schemas, each named after its class's simple name. A name two classes share, or one the generated modules already bind (a TypeScript global they use such as `Promise` or `Record`, a name they import from `@ahoo-wang/wow-client` such as `CommandClient`, or one the generator declares such as `<Aggregate>Event`), is qualified by the class's package: `shop.Record` is `ShopRecord`. A parameterized type is written in place where it is used, but one that refers to itself (a `Node<String>` whose children are `Node<String>`) is declared once, named by its class, `Of` and its arguments' simple names, several joined by `And` and nested ones in their place: `a.Node(java.lang.String)` is `NodeOfString`, `java.util.Map(java.lang.String,java.util.List(a.Item))` is `MapOfStringAndListOfItem`; a clash is qualified by the class's package |
| `<aggregate>/aggregate.ts` | The aggregate's descriptor, the `state.*` fields a query names, and the union of its events |
| `<aggregate>/commandClient.ts` | `<Aggregate>CommandClient`: a method per command, a `…Stream` twin of each, and the lifecycle commands the aggregate lists. An internal command (`"internal": true`, [Keep a command internal](../guide/aggregates#keep-a-command-internal)) has none, nor an entry in `manifest.ts`, nor a type only it uses: the public port does not serve it |
| `<aggregate>/queryClient.ts` | `<Aggregate>SnapshotQueryClient`, `<Aggregate>EventQueryClient` and `<Aggregate>StateClient` |
| `index.ts` | Everything above, exported again |
| `.wow-generator.json` | The files the run wrote, each with its hash, so a later run deletes the ones it no longer writes, and a directory that leaves empty |

An error code's constant is named as the Kotlin contract names it: what follows its aggregate (`context.aggregate.code`) or its context, each dotted segment in upper snake case, joined by `__`, with `_` before a leading digit. `invalid-item` is `INVALID_ITEM`, `invalidURLValue` is `INVALID_URL_VALUE`, and `analytics.catalog.incomplete` of the context `analytics-catalog` is `ANALYTICS__CATALOG__INCOMPLETE`. Of two codes whose constants coincide, the first keeps it; both stay in `<Context>ErrorCode`. A name that meets a member the generator declares beside it gets one of its own: a command whose method or `…Stream` twin is taken (`StartStream` beside `Start`, `Commands`, `Then`, or a lifecycle command's name) takes `Command`, then `Command2` (so adding a command can rename a method that was there: `StartStream` alone is `startStream`, and once `Start` is added, `startStream` is `Start`'s twin and `StartStream`'s method is `startStreamCommand`; `tsc` names each call that moved); an aggregate `commands` or `routes` is `commandsAggregate` in `<Context>Client`, and `ErrorsAggregate` or `WaitAggregate` in the contract for an aggregate `errors` or `wait`, as the Kotlin contract names its object; a service route's method is named as a command's (`export-orders` is `exportOrders`), and a route `routes` is `routesRoute`. No generated class takes a member `then`, which would make it a thenable `await` reads as a promise.

The eShop ordering's contract. Its error codes are the domain's rejections only: a command that breaks its constraints answers the framework's `wow.command.invalid` (`FrameworkErrorCodes.COMMAND_INVALID`) with its `violations` ([Send Commands](./commands)).

<!-- typecheck-generated: examples/eshop/web/src/generated/ordering -->

<!-- typecheck: file=generated/eshop-ordering/contract.ts -->

<!-- sample: examples/eshop/web/src/generated/ordering/eshop-ordering/contract.ts -->

```ts
// Code generated by wow-generator. DO NOT EDIT.

import { Hop } from '@ahoo-wang/wow-client';
import { OrderAggregate } from './order/aggregate.js';

/**
 * The bounded context `eshop-ordering`: its name, its error codes, its wait hops and its aggregates, as the Kotlin `EshopOrderingContract` names them. A problem's `code` is one of its `Errors`, or of an aggregate's.
 * The eShop ordering: orders from placement to shipment
 */
export const EshopOrderingContract = {
  NAME: 'eshop-ordering',
  /** The hops of a wait chain (`Wow-Wait`) in `eshop-ordering`, as the Kotlin contract's `Wait` holds them. `WaitPlan.of(...)` chains them, those of other contexts too. */
  Wait: {
    /** Until a command of `eshop-ordering` passed admission. */
    accepted: (): Hop => Hop.accepted(EshopOrderingContract.NAME),
    /** Until a command's events are appended to the log of `eshop-ordering`. */
    committed: (): Hop => Hop.committed(EshopOrderingContract.NAME),
    /** Until the snapshot of `eshop-ordering` holds the commit. */
    snapshot: (): Hop => Hop.snapshot(EshopOrderingContract.NAME),
    /** Until the store the snapshot queries of `eshop-ordering` read holds the commit. */
    queryable: (): Hop => Hop.queryable(EshopOrderingContract.NAME),
    /** Until a subscription of `eshop-ordering` handled the commit. */
    handled: (subscription: EshopOrderingSubscription): Hop => Hop.handled(subscription, EshopOrderingContract.NAME),
  },
  Order: OrderAggregate,
} as const;

/** An error code of `eshop-ordering`, of the context or of one of its aggregates. */
export type EshopOrderingErrorCode = 'eshop-ordering.order.wrong-amount' | 'eshop-ordering.order.wrong-status';

/** The subscriptions of `eshop-ordering` a `handled` hop names. */
export type EshopOrderingSubscription = 'OrderProcessSaga.onOrderAwaitingValidation' | 'OrderProcessSaga.onOrderCancelled' | 'OrderProcessSaga.onOrderShipped' | 'OrderProcessSaga.onOrderSubmitted' | 'OrderProcessSaga.onReserveCommitted' | 'OrderSummaryProjection.onEvent' | 'ShipmentNotifier.onOrderShipped';
```

Every file starts with `// Code generated by wow-generator. DO NOT EDIT.`, and the next run overwrites it. Commit the output with `.wow-generator.json`. The generator writes `\n` line ends, and its hashes and `--check` read `\r\n` as `\n`, so a checkout that converts them (Git's `core.autocrlf`) holds the same files; to keep them as written, add `src/generated/** text eol=lf` to `.gitattributes`.

The output is plain TypeScript, without decorators, and its relative imports end in `.js`, so it compiles under `"moduleResolution": "bundler"` and `"nodenext"` alike. A bundler project keeps its own settings; a Node project that runs the compiled output, with `"type": "module"` in its `package.json`, can use this `tsconfig.json`:

<!-- typecheck: file=tsconfig.json -->

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "skipLibCheck": true,
    "rootDir": "src",
    "outDir": "dist"
  },
  "include": ["src/**/*.ts"]
}
```

CI compiles this page's code with exactly this `tsconfig.json`.

### Exact numbers

A field of a Kotlin `Long`, `BigInteger` or `BigDecimal` (schema format `int64`, `bigint` or `decimal`) is a `string` in the generated types, holding the digits exactly as the service wrote them; a JavaScript `number` would round them. The wire still carries JSON numbers: the aggregate's descriptor lists those fields, the clients read them from the response's text and write them back into a command body unquoted.

This holds at every depth and on every path whose values the Manifest describes: a state, an event, a command body, a query's records, and a service route's body and answer (the two values it does not describe are below). A recursive type (a tree whose `children` are trees) is marked once, in the context's `textNumbers.ts`, and read wherever a value reaches it. The alternatives of a union keep their own numbers: a value that is a `decimal` or an object of them is read as whichever it is, and objects told apart by a member with a constant value, as Jackson's `@JsonTypeInfo` writes its subtypes, each by their own. Alternatives that read one number differently, one exactly and one as a number, with no such member, fail the generation (exit code 4) with the schema, the path and both formats: give them a discriminator, or the same number format.

<!-- typecheck: skip — generated code, compiled with the rest of the eShop web UI -->

<!-- sample: examples/eshop/web/src/generated/ordering/eshop-ordering/order/aggregate.ts -->

```ts
// Code generated by wow-generator. DO NOT EDIT.

import type { CommittedEvent, NoAccess, SystemEvent } from '@ahoo-wang/wow-client';
import type { OrderAwaitingValidation, OrderCancelled, OrderPaid, OrderShipped, OrderStockConfirmed, OrderStockItemConfirmed, OrderSubmitted } from '../types.js';

/**
 * The aggregate `eshop-ordering.order`.
 * A buyer's order, from its submission to its shipment
 */
export const OrderAggregate = {
  context: 'eshop-ordering',
  name: 'order',
  ownership: 'owner',
  idSource: 'assigned',
  snapshot: true,
  textNumbers: {
    state: { items: { '*': { unitPrice: true } }, totalAmount: true },
    events: {
      'eshop-ordering.order.OrderPaid': { amount: true },
      'eshop-ordering.order.OrderSubmitted': { items: { '*': { unitPrice: true } } },
    },
    commands: {
      'eshop-ordering.order.PayOrder': { amount: true },
      'eshop-ordering.order.SubmitOrder': { items: { '*': { unitPrice: true } } },
    },
  },
  Errors: {
    /** 422: A payment pays the order's total amount. */
    WRONG_AMOUNT: 'eshop-ordering.order.wrong-amount',
    /** 409: The order's status does not allow the command. */
    WRONG_STATUS: 'eshop-ordering.order.wrong-status',
  },
} as const;

/** The `state.*` paths of `order`'s snapshots a query filters, sorts and projects on. */
export type OrderStateField = 'state.cancelReason' | 'state.confirmedProducts' | 'state.items' | 'state.items.productId' | 'state.items.quantity' | 'state.items.unitPrice' | 'state.paymentId' | 'state.status' | 'state.totalAmount';

/** The access attributes of `eshop-ordering.order`: it declares none. */
export type OrderAccess = NoAccess;

/** The events of `order`, named as the server sends them, `eshop-ordering.order.<Event>` at its current revision (the `events` query upgrades older records), and the system events every aggregate has. */
export type OrderEvent = CommittedEvent<'eshop-ordering.order.OrderAwaitingValidation', OrderAwaitingValidation> | CommittedEvent<'eshop-ordering.order.OrderCancelled', OrderCancelled> | CommittedEvent<'eshop-ordering.order.OrderPaid', OrderPaid> | CommittedEvent<'eshop-ordering.order.OrderShipped', OrderShipped> | CommittedEvent<'eshop-ordering.order.OrderStockConfirmed', OrderStockConfirmed> | CommittedEvent<'eshop-ordering.order.OrderStockItemConfirmed', OrderStockItemConfirmed> | CommittedEvent<'eshop-ordering.order.OrderSubmitted', OrderSubmitted> | SystemEvent;
```

`parseJson` and `stringifyJson` of `@ahoo-wang/wow-client` do the same for a request of your own, and a hand-written client takes the same marks (`TextNumbers`): `ServiceRoutes` by `textNumbers: { 'context.route': { request, response } }`, `DatasetClient.dataset(context, dataset, textNumbers)` for a dataset's rows. The marks take these forms:

| Form | Marks | Example |
| --- | --- | --- |
| `true` | this value is an exact number | `{ price: true }` |
| members, `'*'` | an object's members by name; `'*'` every item of an array and every value of a map | `{ lines: { '*': { amount: true } } }` |
| `() => marks` | marks read only where a value reaches them: a recursive type's | `const tree: TextNumbers = { amount: true, children: { '*': () => tree } }` |
| `textNumbersOr(marks)` | an exact number, or an object or array marked `marks`, whichever the value is | `textNumbersOr({ amount: true })` for `decimal \| { amount: decimal }` |
| `textNumbersBy(member, cases)` | an object's marks chosen by the string value of its `member` | `textNumbersBy('kind', { decimal: { amount: true } })` |

Two kinds of value are read without marks, so their large numbers are still rounded (an aggregation's `terms` key over a marked field is read as its text, as the field is, so two groups never read as one key):

- A command result's `results` are the service's own values, which the Manifest gives no schema: a `Long` or `BigDecimal` a command returns there arrives as a JavaScript `number`. Return such a value as a string, or read it from the aggregate's state.
- An aggregation's metric values: a sum or a maximum over an exact field that exceeds what a `number` holds is rounded.

A Kotlin `Double` or `Float` is a `number`. One that holds not-a-number or an infinity arrives as the string `"NaN"`, `"Infinity"` or `"-Infinity"`, as Wow's JSON writes it ([JSON Values](../reference/json-values)), though its type says `number`: a field that may hold one checks `typeof value === 'string'`. Widening every double would burden every caller for a value state seldom holds.

## Keep the clients in step

Generate again whenever the baseline changes, and let CI fail when the committed clients differ from what the generator writes now:

```bash
pnpm exec wow-generator generate --input ../ordering-server/manifest/baseline.json --output src/generated/ordering --strict
git diff --exit-code -- src/generated
```

Then compile: a command whose body changed, a removed field or a renamed aggregate fails `tsc` in the code that uses it. The eShop holds its clients to the baselines in a test, `src/generated.test.ts`, which runs `generate --check`: it writes nothing, and fails, listing the files, when the committed clients differ from what it would write.

Keep the generator on the same version as the `wow-client` the generated code compiles against, which is the version of the services it calls.

## From any OpenAPI document

The generator also writes TypeScript models and Fetcher decorator clients from an OpenAPI 3 document that does not come from Wow, a third-party API's, say; [Generate a Client from Any OpenAPI Document](./openapi-client) walks through it. A Wow service's own OpenAPI document ([OpenAPI](../guide/openapi)) is for other tools: generate its TypeScript clients from the Manifest.
