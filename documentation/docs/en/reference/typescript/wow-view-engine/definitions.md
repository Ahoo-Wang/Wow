---
title: 'Definitions and Field Kinds'
description: 'defineView and its spec, text keys, FieldKind and the field kind registry — @ahoo-wang/wow-view-engine'
---

# Definitions and Field Kinds

A definition is code: it ships with the application and says how one business object can be observed — which fields, of what kind, how they filter, sort and summarize, and the system views that ship with it. Every view a user saves is checked against it. `defineView` builds a data definition from its source's descriptor: the descriptor gives the facts, the spec picks, names and narrows within them.

Guides: [Writing a Definition](../../../guide/typescript/view-engine-definitions.md) (choice by choice from the descriptor, the words, system views and boards); [View Engine Core Concepts](../../../guide/typescript/view-engine-concepts.md) (where a definition sits in the model); [Getting Started with the View Engine](../../../guide/typescript/view-engine-getting-started.md) (step 5's whole definition).

## defineView {#api-defineView}

`descriptor` is the snapshot of the query descriptor committed beside the definition (what `GET /<aggregate>/snapshot/schema` answers): the definition is built when the module loads, and a test builds the same one. The descriptor the source answers at run time narrows it again, as it does any definition.

- **Facts are the descriptor's**: paths, kinds, values, sensitivity, an array's entries. A field not listed does not appear. A path or a value the descriptor lacks is an error admission reports (`DataViewDefinition.described`), never a throw: a definition wrong in one place still loads, and says where.
- **Capabilities are not baked in**: where the host narrows nothing, the definition takes whatever its source grants; where it narrows, its subset of that. A capability asked beyond the snapshot is a warning (`definition.field.sort-wider` and its kin) — what a path sorts and aggregates by is the store's, and another store may grant it.
- The result is an ordinary definition: nothing past this function knows how it was written, and one written in full by hand is still one.

```ts
export declare function defineView(descriptor: QueryModelDescriptor, spec: DefineViewSpec, options?: DefineViewOptions): DataViewDefinition;
```

<!-- typecheck-context
declare const ordersDescriptor: import('@ahoo-wang/wow-client').QueryModelDescriptor
-->

```ts
import { defineView, text } from '@ahoo-wang/wow-view-engine';

export const ordersDefinition = defineView(ordersDescriptor, {
  id: 'orders',
  // The key the host resolves this definition's source by (ViewEngine's resources).
  source: 'order',
  title: text('orders.title'),
  timeField: 'firstEventTime',
  // Only what is listed appears, in this order; the descriptor says what
  // each one is and what it sorts, filters and aggregates by.
  fields: {
    aggregateId: { label: text('orders.id'), cell: 'copyable' },
    'state.status': {
      label: text('orders.status'),
      cell: 'status',
      options: {
        PAID: { label: text('orders.paid'), tone: 'warning' },
        SHIPPED: { label: text('orders.shipped'), tone: 'success' },
      },
    },
    'state.amount': { label: text('orders.amount'), summary: ['SUM', 'AVG'] },
    firstEventTime: text('orders.placedAt'),
  },
  // Fields an action reads are fetched even where the view hides them; see rowFields below.
  record: { layouts: ['table', 'card'], rowFields: ['state.status'] },
});
```

### DefineViewSpec {#api-DefineViewSpec}

| Member | Role |
|---|---|
| `id`, `title`, `recordNoun` | The definition's identity and title; a title may be `text(key)` |
| `source` | The key the host resolves the definition's source of data by |
| `fields` | The fields a reader sees, by root path, in the order listed. A field the descriptor has and this does not list does not appear. A string value is the label alone |
| `fieldGroups` | The groups a field picker lists fields under, in order |
| `record` | Records: the row key and paging default to the descriptor's (its identity; paged where it pages, else by cursor), shown as a table. `false` offers no records |
| `analysis` | Analyses, as the descriptor offers them; `false` offers none |
| `timeField` | What a board's time filter reaches its panels through; a date field listed in `fields` |
| `views` | System views shipped with the definition: everyone sees them, nobody overwrites them, anyone may save a copy |

```ts
export interface DefineViewSpec {
  analysis?: AnalysisSpec | false;
  fieldGroups?: FieldGroupDefinition[];
  fields: Readonly<Record<string, FieldSpec | string>>;
  id: string;
  record?: Partial<RecordCapability> | false;
  recordNoun?: string;
  source: string;
  timeField?: string;
  title: string;
  views?: SystemView[];
}
```

### FieldSpec {#api-FieldSpec}

One field: what the host says of it, over what its path is.

| Member | Role |
|---|---|
| `label` | The word a reader knows it by. Left out, the descriptor's description, else the path — and admission says so (`definition.field.unlabelled`, a note) |
| `kind` | The kind, where the descriptor leaves a choice: copyable reference ids, a string read as a category. Left out, the descriptor's value type decides |
| `cell` | How a cell reads it: `string`, `number`, `boolean`, `date`, `datetime`, `enum`, `status`, `tags`, `link`, `text`, `copyable` |
| `operators` | The comparisons offered, a subset of the path's; asking more is the warning `definition.field.operator-wider` |
| `sortable` | `false` offers no sort on a path that sorts; `true` on a path that does not is the warning `definition.field.sort-wider` |
| `summary` | The footer summaries offered, among the functions the path feeds; asking more is the warning `definition.field.summary-wider` |
| `options` | The category's values in the order listed, each with its words and tone, `false` to hide one. On a path the descriptor gives no values for, this is the host's own closed list. A category of numbers is written as a list of `[value, words]`, because JavaScript puts an object's integer keys first |
| `elements`, `elementTitle` | An array's entries: their fields by path within the entry, each path an element the descriptor lists |
| `search` | A search box rather than a path: the key is a handle, `fields` the paths it searches |
| `analysis` | How it analyses, narrowed; `false` keeps it out of analyses |
| `deprecated` | Why it is kept although the descriptor deprecates it |
| `timePrecision` | How finely a table cell writes its time of day: `'second'` where the seconds are the point (an event stream's times); the minute when left out |
| `more` | Anything else a field may say of itself, as written by hand |

```ts
export interface FieldSpec {
  analysis?: FieldAnalysisSpec | false;
  cell?: FieldCellId;
  deprecated?: { message?: string };
  elements?: Readonly<Record<string, FieldSpec | string>>;
  elementTitle?: string;
  kind?: FieldKindId;
  label?: string;
  more?: Partial<Pick<FieldDefinition, 'numberFormat' | 'numeric' | 'temporal' | 'remote' | 'stringComparison'>>;
  operators?: FilterOperatorName[];
  options?: Readonly<Record<string, OptionSpec>> | readonly (readonly [FieldOption['value'], OptionSpec])[];
  search?: { fields: string[]; mode?: SearchModeName };
  sortable?: boolean;
  summary?: SummaryFunction[];
  timePrecision?: TimePrecision;
}
```

### Which fields a row carries: rowFields {#api-RecordCapability}

A page of records asks its source for the fields the view shows (the row key, the visible columns, the card fields, the sort) and no more — not the document. A field the host's own code reads that the view may not show — an action's `available` rule, a bulk action, a custom cell — goes in `record.rowFields`; each must be a declared field a row holds, which admission checks.

This is the commonest surprise when wiring a host: an action that reads `state.status` to decide whether an order can ship stays disabled in every view that does not show the status column, until the definition says `rowFields: ['state.status']`. There is no "fetch everything when there are actions": the whole document is what once made a page of failed executions 808 KB.

```ts
export interface RecordCapability {
  defaults?: Partial<RecordViewConfig>;
  layouts: RecordLayout[];
  maxSortFields?: number;
  maxWindow?: number;
  paging: PagingMode;
  parallelArrays?: string[][];
  requiresFilter?: boolean;
  rowFields?: string[];
  rowKey: string;
}
```

### DefineViewOptions {#api-DefineViewOptions}

The field kinds the host registers (as `ViewEngineOptions.kinds`). A definition is built before any engine is, so the snapshot's comparisons are written on a field of a host's own kind only when the kind is known here. Left out, the built-in kinds.

```ts
export interface DefineViewOptions {
  kinds?: FieldKindRegistry;
}
```

### SystemView {#api-SystemView}

A baseline view shipped with the definition. `id` is unique within the definition and free of `:`. `timeField` is the moment this view reads its records as happening at, where it is not the definition's; `null` for a view read whole, which a board's time filter does not reach.

```ts
export interface SystemView {
  config: ViewConfig;
  id: string;
  timeField?: string | null;
  title: string;
}
```

## text and keys {#api-text}

A definition says `text('orders.title')` where one language would say "Orders", so one definition serves every language and a host's catalogue says it.

- A key travels as a string: every label slot of a definition, a system view and a board is a `string`, and a key sits between two characters no wording uses (U+E000 and U+E001, private use). So a key is read inside a longer string code has built, and said there too. A literal string is still a label, for a host of one language.
- **A key is said at the leaf**: the definitions, the saved configs and every runtime's state and snapshot keep their keys; only where text is shown or leaves the engine — a rendered label, a chart's option, an export, an accessible name, a title — is it said, in the words of the Provider in force, else the ones the engine was built with (`ViewEngineOptions.text`). So one engine serves every language, and a change of language only redraws.
- A key with no words reads as itself and is reported (`definition.text.unknown`, `definition.text.fallback`; see [issue codes](./issues)). In a test, [`admit`](./testing#api-admit) checks them with the words.

```ts
export declare function text(key: string): Text;
```

```ts
export type Text = string & {
  readonly __text: unique symbol;
};
```

## Field kinds {#api-FieldKind}

`FieldKind` is the engine's main extension point: everything it knows about one field type — the operators it supports, the shape and validation of its values, compiling to Wow's `FilterExpression`, and an editor descriptor. A kind owns the shape of its values, so an application adds a type without the kernel learning anything about it.

- `editor()` answers **data**, never a component name: `EditorDescriptor.input` is a closed union whose members `EDITOR_INPUTS` lists, and `/ui`'s value editor switches over exactly them. There is no renderer registry, so a custom kind picks one of the existing inputs; asking for one the engine has no control for is refused by `validateFilter` as `filter.kind.unknown-editor`, an unregistered kind as `filter.kind.unregistered`, and Apply is blocked.
- `emptyValue()` is the value a leaf starts from, normally "nothing yet": a field picked without a value is a normal editing state, neither validated nor compiled. Seeding a number with `0` would silently apply `amount = 0` the moment the row appeared.
- `compile()` maps one admitted leaf onto the Wow protocol; `compiledOperators()` says which Wow operators an operator is sent as, and the descriptor must admit every one of them for the operator to be offered.
- `readLeaf()` reads a saved leaf written before the field was of this kind, in a shape the kind offers; every pass over a tree reads each leaf through it first. The built-in `enum` reads an `EQ` saved while the field was a string as the `IN` of its one value, so the condition keeps admitting and compiling.
- `scalar`, `singleString`, `fieldless` and `nested` tell the engine what shape a value of the kind has on the Wow side, so that a condition the engine calls usable is not refused by the server.

Built-in kinds: `string`, `number`, `boolean`, `date`, `datetime`, `enum`, `reference`, `array`, `elementMatch`, `search`, and the kinds backed by Wow's metadata filters: `documentId`, `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `deletion`.

```ts
export interface FieldKind {
  compile(context: FieldKindCompileContext): FilterExpression;
  compiledOperators?(operator: FilterOperatorName): readonly FilterOperatorName[];
  defaultOperator: FilterOperatorName;
  describe(context: FieldKindDescribeContext): FieldKindDescription;
  editor(operator: FilterOperatorName, field: FieldDefinition, value?: unknown): EditorDescriptor;
  emptyValue(operator: FilterOperatorName, field: FieldDefinition): unknown;
  fieldless?: true;
  id: FieldKindId;
  isBlank?(context: FieldKindBlankContext): boolean;
  nested?(value: unknown, field: FieldDefinition, operator: FilterOperatorName): NestedTree | null;
  operators: FilterOperatorName[];
  readLeaf?(leaf: FilterLeaf, field: FieldDefinition): FilterLeaf;
  relations?: Partial<Record<FilterOperatorName, FilterSummaryRelation>>;
  scalar?: boolean;
  singleString?: boolean;
  validate(context: FieldKindValidateContext): Issue[];
}
```

### The registry {#api-FieldKindRegistry}

A registry is a read-only map by id. `builtinFieldKinds` is a ready one; `withFieldKinds` adds or replaces kinds over it without changing it, and the result goes to `ViewEngineOptions.kinds` and to `defineView`'s `options.kinds`.

<!-- typecheck-context
declare const moneyKind: import('@ahoo-wang/wow-view-engine').FieldKind
-->

```ts
import { builtinFieldKinds, withFieldKinds } from '@ahoo-wang/wow-view-engine';

export const kinds = withFieldKinds(builtinFieldKinds, [moneyKind]);
```

```ts
export type FieldKindRegistry = ReadonlyMap<FieldKindId, FieldKind>;
export declare const builtinFieldKinds: FieldKindRegistry;
export declare const BUILTIN_FIELD_KINDS: readonly FieldKind[];
export declare function withFieldKinds(registry: FieldKindRegistry, kinds: readonly FieldKind[]): FieldKindRegistry;
export declare function createFieldKindRegistry(kinds: readonly FieldKind[]): FieldKindRegistry;
```

### Editor descriptor {#api-EditorDescriptor}

```ts
export interface EditorDescriptor {
  input: EditorInput;
  multiple?: boolean;
  options?: FieldOption[];
  range?: boolean;
  remote?: string;
  withTime?: boolean;
}
export declare const EDITOR_INPUTS: readonly ['none', 'text', 'number', 'boolean', 'deletion', 'select', 'remote', 'date', 'dateRange', 'relativeDate', 'predicate', 'duration'];
```

## The full working version

- Storybook's [integration walk-through](/storybook/?path=/docs/view-engine-接入导览--docs) declares the orders definition from a committed descriptor.
- Source files: [`ordersDefinition.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDefinition.ts) and [`ordersDescriptor.json`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDescriptor.json); `defineView` is [`src/runtime/define/defineView.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/runtime/define/defineView.ts), `FieldKind` [`src/filter/fieldKind.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/filter/fieldKind.ts).
