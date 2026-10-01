# Admission

`admit(definitions, descriptors, { text })` from `@ahoo-wang/wow-view-engine/testing` admits a host's declarations exactly as the engine does when it registers them: each definition's keys said in `text`, its own rules and every system view config, its boards against every other definition, and each data definition narrowed to the committed descriptor of its `source`. It returns every finding with the definition it is about, `[]` when all of it holds. The engine's README has the reference: [Testing a host](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#testing-a-host-an-in-memory-source).

## The test

One test admits everything the host registers, in every language it serves. It takes definitions or the host's resources as they are (`{ definition, source }`), so pass the same list the engine gets.

<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import type { DashboardDefinition, DataViewDefinition } from '@ahoo-wang/wow-view-engine';
declare const orders: DataViewDefinition;
declare const overview: DashboardDefinition;
declare const orderDescriptor: QueryModelDescriptor;
declare const ORDER_WORDS: Record<'en' | 'zh-CN', Record<string, string>>;
declare const describe: (name: string, body: () => void) => void;
declare const it: { each<T>(cases: readonly T[]): (name: string, body: (value: T) => void) => void };
declare function expect(value: unknown): { toEqual(expected: unknown): void };
-->

```ts
import { admit } from '@ahoo-wang/wow-view-engine/testing';

describe('the order views', () => {
  it.each(['zh-CN', 'en'] as const)('are admitted in %s', locale => {
    const words: Readonly<Record<string, string>> = ORDER_WORDS[locale];
    expect(
      admit(
        [orders, overview],
        // By each data definition's `source`: the committed snapshots.
        { order: orderDescriptor },
        { text: key => words[key] },
      ),
    ).toEqual([]);
  });
});
```

Run it with the host's test runner, in a Node environment: `admit` and the definitions need no browser. Where the package's Vitest config is a browser project (Storybook's, a jsdom app's with setup files), give the definitions a config of their own:

<!-- typecheck: skip — a Vitest config; vitest is not among the packages the samples compile against -->

```ts
// src/views/vitest.config.ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: { name: 'views', environment: 'node', include: ['**/*.test.ts'] },
});
```

Run it as `vitest run --root src/views`: Vitest takes the `vitest.config.ts` of the root it is pointed at, and `include` is relative to that root. With the config elsewhere, name both: `vitest run --config vitest.views.config.ts`. Type-check with the host's `tsconfig.json` (`wow-view-host` shows a minimal one for the views).

Read every finding. Fix it at the choice it names; do not silence it by widening `text`, dropping the descriptor or filtering the result. A finding you keep on purpose is asserted exactly, with the reason beside it, so a new finding still fails the test:

<!-- typecheck-context
import { admit } from '@ahoo-wang/wow-view-engine/testing';
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import type { DataViewDefinition } from '@ahoo-wang/wow-view-engine';
declare const failures: DataViewDefinition;
declare const descriptors: Record<string, QueryModelDescriptor>;
declare const text: (key: string) => string | undefined;
declare const expect: ((value: unknown) => { toEqual(expected: unknown): void }) & { objectContaining(value: object): unknown };
-->

```ts
// MongoDB has no text index here, so the error search exists only on
// Elasticsearch; the definition keeps it for that deployment.
expect(admit([failures], descriptors, { text })).toEqual([
  expect.objectContaining({
    code: 'capability.search.unavailable',
    params: { field: 'keyword' },
    severity: 'warning',
  }),
]);
```

`admit` checks what can be checked mechanically: every path, kind, category value, element, capability, key, system view config and board reference against the descriptors and the other definitions. Do not check those again by hand or with a script of your own.

## What a finding asks you to revisit

Each finding has a `code`, `params`, a `path` into the definition and the `definition` it is about. The message catalogue (`defaultMessages`, `zhCN` in `@ahoo-wang/wow-view-engine/ui`) words every code.

| Finding | Revisit |
| --- | --- |
| `definition.text.unknown` | A key without words in that language: add them to that language's table, or fix the key's spelling. |
| `definition.field.undescribed`, `definition.option.undescribed`, `capability.field.unknown` | A path or value the descriptor does not list. Never "fix" it by guessing another path; drop it and say what is missing. |
| `definition.field.unlabelled` | Give the field the audience's word. |
| `definition.field.deprecated`, `capability.field.deprecated*` | Move to the replacement path, or keep it with `deprecated: { message }` and flag it. |
| `capability.field.protected` | A sensitive or confidential field listed: keep it only if the audience must see it masked (then assert the finding with the reason), else remove it. |
| `definition.field.operator-wider`, `sort-wider`, `summary-wider`, `analysis-wider`, `definition.analysis.wider`, `definition.record.paging-wider` | You narrowed to something the store does not have. Remove the narrowing, or keep it only for a second deployment that has it, and assert it with the reason. |
| `capability.field.operators-narrowed`, `unfilterable`, `unsortable`, `summary-narrowed` | Only where you **wrote** the capability (`operators`, `sortable`, `summary`) and this store lacks part of it; a field you left open follows the store and reports nothing. Drop what the store lacks from your list, or keep it only for a second deployment that has it, and assert it with the reason. |
| `capability.search.*` | A search box the store cannot answer; keep it only for a deployment that can, and assert it. |
| `definition.record.row-key-unknown`, `row-key-unsortable`, `capability.record.*` | List the identity (or the `rowKey` you chose) and choose a key the store sorts uniquely; on a cursor, the identity the store appends. |
| `capability.field.temporal-mismatch` | The descriptor keeps the time differently; follow the descriptor, never override `temporal`. |
| `definition.descriptor.missing` | `admit` was not given the descriptor for that `source`; pass the committed snapshot. |
| `definition.view.*`, `filter.*`, `analysis.*`, `dashboard.*`, `record.*` | A system view or board config: the field, operator, alias or panel reference it names. |

## What admit cannot judge

Before reporting, read the definition as its audience:

- Is every listed field one they read, filter or group by, in their reading order, and is everything they need there?
- Are the words theirs, one word per meaning, in every language served, with every visible category value worded and the analysis vocabulary followed?
- Does each narrowing protect the audience rather than restate the store?
- Do the system views and boards open on what they do first, sorted by what decides the next one, with a total only where it means something?
- Is a sensitive field listed only where the audience needs it masked, and is no derived field or condition revealing it?

## Report

- Scenario and audience; the definitions, system views and boards written or revised.
- Descriptor source (fixture or environment) and `version`; whether production was read, with the consent.
- What was left out and narrowed, and why; fields the scenario wanted that the descriptor lacks, and where they would come from.
- Protected and deprecated fields, and how each was handled.
- The admission test's command and result, every finding kept on purpose with its reason, and any other gate run (type check, lint, stories); anything not run, stated as missing evidence.
