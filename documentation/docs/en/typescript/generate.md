---
title: Generate a Client
description: Generate the typed clients of a Wow service from its Manifest with wow-generator, keep them in step with the service in CI, and read what each generated file holds.
---

# Generate a Client

`wow-generator` reads a service's Manifest and writes one typed client per aggregate: a method per command, the queries of its snapshots and events, its state reads, its error codes and the hops of its waits. The application calls the service only through these clients, so a route or a field the service changes shows up as a compile error, not as a failed request.

## Where the Manifest comes from

The service serves its Manifest at `GET /wow/manifest`, and its build commits the same document as `manifest/baseline.json`, which `check` holds to what the service serves ([The Manifest](../guide/manifest)). Generate from the baseline: it needs no running service, it is the version that was reviewed, and a change to it is a change to the clients.

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
// The output is committed byte for byte; src/generated.test.ts fails when it
// differs from what this writes.

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

/** Writes the clients of `context` into `output`. */
export function generate(context, output) {
  execFileSync(
    process.execPath,
    [CLI, 'generate', '-i', baselineOf(context), '-o', output, '--strict', '--quiet'],
    { stdio: 'inherit' },
  );
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  for (const context of CONTEXTS) {
    const output = join(WEB, 'src', 'generated', context);
    rmSync(output, { recursive: true, force: true });
    generate(context, output);
  }
}
```

The [CLI reference](../reference/typescript/wow-generator/cli) lists every option and exit code.

## What it writes

For each bounded context, a directory named after it:

| File | Holds |
| --- | --- |
| `contract.ts` | `<Context>Contract`: the context's name, its context-level error codes and its aggregates, each with its error codes; the `<Context>ErrorCode` union, and the hops of a wait in it |
| `manifest.ts` | `<Context>Manifest`: the routes of the context's commands, as its Manifest lists them, with no schema. The command clients send by it, so they fetch no Manifest |
| `client.ts` | `<Context>Client`: the context's `CommandClient` (`commands`) and, per aggregate, its command, snapshot, event and state clients |
| `types.ts` | The commands, events and states of the Manifest's schemas |
| `<aggregate>/aggregate.ts` | The aggregate's descriptor, the `state.*` fields a query names, and the union of its events |
| `<aggregate>/commandClient.ts` | `<Aggregate>CommandClient`: a method per command, a `…Stream` twin of each, and the lifecycle commands the aggregate lists |
| `<aggregate>/queryClient.ts` | `<Aggregate>SnapshotQueryClient`, `<Aggregate>EventQueryClient` and `<Aggregate>StateClient` |
| `index.ts` | Everything above, exported again |
| `.wow-generator.json` | The files the run wrote, so a later run deletes the ones it no longer writes |

The eShop ordering's contract. Its error codes are the domain's rejections only: a command that breaks its constraints answers the framework's `wow.command.invalid` (`FrameworkErrorCodes.COMMAND_INVALID`) with its `violations` ([Send Commands](./commands)).

<!-- typecheck-generated: examples/eshop/web/src/generated/ordering -->

<!-- typecheck: file=generated/eshop-ordering/contract.ts -->

<!-- sample: examples/eshop/web/src/generated/ordering/eshop-ordering/contract.ts -->

```ts
// Code generated by wow-generator. DO NOT EDIT.

import { type Hop, wait } from '@ahoo-wang/wow-client';
import { OrderAggregate } from './order/aggregate.js';

/**
 * The bounded context `eshop-ordering`: its name, its error codes and its aggregates, as the Kotlin `EshopOrderingContract` names them. A problem's `code` is one of its `Errors`, or of an aggregate's.
 * The eShop ordering: orders from placement to shipment
 */
export const EshopOrderingContract = {
  NAME: 'eshop-ordering',
  Order: OrderAggregate,
} as const;

/** An error code of `eshop-ordering`, of the context or of one of its aggregates. */
export type EshopOrderingErrorCode = 'eshop-ordering.order.wrong-amount' | 'eshop-ordering.order.wrong-status';

/** The subscriptions of `eshop-ordering` a `handled` hop names. */
export type EshopOrderingSubscription = 'OrderProcessSaga.onOrderAwaitingValidation' | 'OrderProcessSaga.onOrderCancelled' | 'OrderProcessSaga.onOrderShipped' | 'OrderProcessSaga.onOrderSubmitted' | 'OrderProcessSaga.onReleaseRejected' | 'OrderProcessSaga.onReserveCommitted' | 'OrderProcessSaga.onReserveRejected' | 'OrderSummaryProjection.onEvent' | 'ShipmentNotifier.onOrderShipped';

/** The hops of a wait chain (`Wow-Wait`) in `eshop-ordering`. */
export const eshopOrderingWait = {
  /** Until a command of `eshop-ordering` passed admission. */
  accepted: (): Hop => wait.accepted(EshopOrderingContract.NAME),
  /** Until a command's events are appended to the log of `eshop-ordering`. */
  committed: (): Hop => wait.committed(EshopOrderingContract.NAME),
  /** Until the snapshot of `eshop-ordering` holds the commit. */
  snapshot: (): Hop => wait.snapshot(EshopOrderingContract.NAME),
  /** Until a subscription of `eshop-ordering` handled the commit. */
  handled: (subscription: EshopOrderingSubscription): Hop => wait.handled(subscription, EshopOrderingContract.NAME),
} as const;
```

Every file starts with `// Code generated by wow-generator. DO NOT EDIT.`, and the next run overwrites it. Commit the output with `.wow-generator.json`.

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

<!-- typecheck: skip — generated code, compiled with the rest of the eShop web UI -->

<!-- sample: examples/eshop/web/src/generated/ordering/eshop-ordering/order/aggregate.ts -->

```ts
// Code generated by wow-generator. DO NOT EDIT.

import type { CommittedEvent, SystemEvent } from '@ahoo-wang/wow-client';
import type { OrderAwaitingValidation, OrderCancelled, OrderPaid, OrderShipped, OrderStockConfirmed, OrderStockItemConfirmed, OrderSubmitted } from '../types.js';

/**
 * The aggregate `eshop-ordering.order`.
 * A buyer's order, from its submission to its shipment
 */
export const OrderAggregate = {
  context: 'eshop-ordering',
  name: 'order',
  path: '/eshop-ordering/order',
  ownership: 'owner',
  snapshot: true,
  textNumbers: {
    state: { items: { '*': { unitPrice: true } }, totalAmount: true },
    events: {
      'eshop-ordering.order.OrderPaid': { amount: true },
      'eshop-ordering.order.OrderSubmitted': { items: { '*': { unitPrice: true } } },
    },
    commands: {
      PayOrder: { amount: true },
      SubmitOrder: { items: { '*': { unitPrice: true } } },
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
export type OrderStateField = 'state.cancelReason' | 'state.confirmedProducts' | 'state.items' | 'state.paymentId' | 'state.status' | 'state.totalAmount';

/** The events of `order`, named as the server sends them, `eshop-ordering.order.<Event>` at its current revision (the `events` query upgrades older records), and the system events every aggregate has. */
export type OrderEvent = CommittedEvent<'eshop-ordering.order.OrderAwaitingValidation', OrderAwaitingValidation> | CommittedEvent<'eshop-ordering.order.OrderCancelled', OrderCancelled> | CommittedEvent<'eshop-ordering.order.OrderPaid', OrderPaid> | CommittedEvent<'eshop-ordering.order.OrderShipped', OrderShipped> | CommittedEvent<'eshop-ordering.order.OrderStockConfirmed', OrderStockConfirmed> | CommittedEvent<'eshop-ordering.order.OrderStockItemConfirmed', OrderStockItemConfirmed> | CommittedEvent<'eshop-ordering.order.OrderSubmitted', OrderSubmitted> | SystemEvent;
```

`parseJson` and `stringifyJson` of `@ahoo-wang/wow-client` do the same for a request of your own.

## Keep the clients in step

Generate again whenever the baseline changes, and let CI fail when the committed clients differ from what the generator writes now:

```bash
pnpm exec wow-generator generate --input ../ordering-server/manifest/baseline.json --output src/generated/ordering --strict
git diff --exit-code -- src/generated
```

Then compile: a command whose body changed, a removed field or a renamed aggregate fails `tsc` in the code that uses it. The eShop holds its clients to the baselines in a test, `src/generated.test.ts`, which generates into a temporary directory and compares every file.

Keep the generator on the same version as the `wow-client` the generated code compiles against, which is the version of the services it calls.

## From any OpenAPI document

The generator also writes TypeScript models and Fetcher decorator clients from an OpenAPI 3 document that does not come from Wow, a third-party API's, say; [Generate a Client from Any OpenAPI Document](./openapi-client) walks through it. A Wow service's own OpenAPI document ([OpenAPI](../guide/openapi)) is for other tools: generate its TypeScript clients from the Manifest.
