# Definition anatomy

Confirm every member against the installed `@ahoo-wang/wow-view-engine` typings (or, in the Wow repository, `typescript/wow-view-engine/src/model` and `docs/design/model.md`) before relying on it: the package is pre-release and names may move.

## Shape

```ts
import type { DataViewDefinition, DashboardDefinition } from '@ahoo-wang/wow-view-engine';

const orders: DataViewDefinition = {
  id: 'orders',             // no ':'; system views become `system:orders:<view id>`
  title: '订单',             // the audience's name for the dataset
  recordNoun: '订单',        // what one record is called; the analysis counts in it
  kind: 'data',
  source: 'orders',          // the host's resolveSource key
  fields: [/* FieldDefinition[] */],
  fieldGroups: [/* { id, label, fields } for the field picker */],
  record: { rowKey: 'state.orderNo', paging: 'paged', layouts: ['table', 'card'] },
  analysis: { count: true, fields: [/* AggregationFieldCapability[] */] },
  views: [/* SystemView[]: { id, title, config } */],
};
```

A dashboard definition is `{ id, title, kind: 'dashboard', views }`; its views are dashboard configs whose panels reference saved views or own an analysis over a data definition.

### FieldDefinition

| Member | Meaning | Written against |
|---|---|---|
| `name` | Descriptor `path`; element fields relative to their array | `fields[].path`, `scope`, `variants` |
| `label` | The audience's word for it | the scenario, never the path |
| `kind` | `string`, `number`, `boolean`, `date`, `datetime`, `enum`, `reference`, `array`, `elementMatch`, `search`, and the metadata kinds `documentId`, `aggregateId`, `tenantId`, `ownerId`, `spaceId`, `deletion` | `types`, `kind`, `role`; metadata kinds against `record.rootOperators` |
| `operators` | Subset of the kind's defaults | `filter.operators` |
| `sortable` | Offered as a sort | `sort.paged` or `sort.cursor`, by paging mode |
| `options` | Enum values with labels and a `tone` (`neutral`, `success`, `warning`, `danger`) | `enum[].value` |
| `temporal` | `{ type: 'epoch', timeUnit? }` or `{ type: 'date' }` on `date`/`datetime` | `semantic` |
| `summary` | Record-view summary functions (a time only MIN, MAX, COUNT) | `aggregate.functions`, `analysis.metrics` |
| `numberFormat`, `cell` | How the value reads (`status`, `tags`, `link`, `text`, `copyable`) | presentation only |
| `searchFields`, `searchMode` | For `kind: 'search'` | `record.search.fields`, `record.search.modes` |
| `elements`, `elementTitle` | Element fields of an array, and which one names each element | `elements[]`, fields with `scope` |

### RecordCapability

`rowKey` (declared and `sortable: true`), `paging` (`'paged'` or `'cursor'`), `layouts`, optional `rowFields` (fields host code reads from a row), `maxWindow` (paged only, lower than the descriptor's `maxPageWindow`), `defaults`.

### AnalysisCapability

`count`, `fields` (`{ field, groups, functions, dateUnits?, distinctCount?, percentile?, any?, missingKey?, inMetricFilter?, expressionInput? }`), `elements` (a chain of array paths, outer to inner, each with its own `aggregations` named relative to the element), `expressions`, `having`, and the narrowing-only members `havingMetrics`, `metricSort`, `dense`, `approximate`, `limits`.

## Descriptor to definition

| Descriptor | Definition may declare | Never |
|---|---|---|
| `fields[].path`, `aliases` | a field named by the canonical `path` | a name the descriptor lacks |
| `filter.operators` | `operators` within it | an operator outside it |
| `sort.paged` / `sort.cursor` | `sortable: true` where the paging mode sorts it | a sortable field, or `rowKey`, the descriptor cannot sort |
| `aggregate.groups` | `groups` within it (`TERMS`, `HISTOGRAM`, `DATE_HISTOGRAM`) | a group on a field without `aggregate` |
| `aggregate.functions` | `functions` within it | `SUM`/`AVG` the field lacks |
| `aggregate.distinctCount`, `percentile`, `any` plus `analysis.metrics` | the matching flag | a flag where either is false |
| `aggregate.missingKey`, `inMetricFilter`, `expressionInput` | leave unset or `false` | `true` where the descriptor says false |
| `analysis.dateUnits` | `dateUnits` within it | a unit outside it |
| `analysis.expressions`, `having`, `sort.metrics`, `dense` | `expressions`, `having`, and their narrowing members | enabling what the descriptor lacks |
| `analysis.approximate` | nothing; the engine reads it | claiming a metric is exact |
| `elements[]` (`filter`, `aggregate`) | `elementMatch` and `analysis.elements` on that path | element matching or expansion where it is false |
| `record.paging` | `paging` listed there | a mode not listed |
| `record.search` | a `search` field with fields and mode inside it | a search field where it is absent (MongoDB without a text index) |
| `record.rootOperators` | the metadata kinds whose operator is listed | a metadata kind whose operator is absent |
| `limits` | `maxWindow`, `analysis.limits` only when lower | restating the server's limit |
| `sensitivity` | display, masked, when the audience needs it | any analysis use, sort, `rowKey`, card title; for `comparable: false` also operators and search |
| `deprecated` | the replacement its message names | the deprecated path, unflagged |
| `semantic` | the matching `temporal` | a different time encoding (error) |
| `constraints` | a design that respects them | a cursor `rowKey` other than `CURSOR_UNIQUE_SORT.appended`; a sort over two arrays of one `PARALLEL_ARRAY_SORT` group |
| `variants` (event streams) | payload fields inside `body`'s `elements`, conditions inside `ELEMENT_MATCH` with `bodyType` | a root-level condition on a payload path |

Declaring less is always admissible; the runtime also intersects the definition with the live descriptor, so a capability declared beyond it is removed at run time and reported as a `capability.*` warning. That warning is a defect of the definition, not a feature of the deployment, unless the definition is intentionally shared by two deployments (for example a phrase search that exists only on Elasticsearch), which the code comment then says.

## Event streams

An event-stream record is one command's appended events: root fields such as `aggregateId`, `id`, `version`, `createTime`, `commandId`, `ownerId`, and `body`, an array of events. Each event has `bodyType`, `name`, `revision` and its payload `body`. The descriptor's `variants` (`element: 'body'`, `discriminator: 'bodyType'`) lists each event type's payload fields relative to the event.

```ts
{
  name: 'body',
  label: '事件',
  kind: 'elementMatch',
  operators: ['ELEMENT_MATCH'],
  elementTitle: 'bodyType',
  elements: [
    { name: 'bodyType', label: '事件类型', kind: 'enum', options: EVENT_TYPE_OPTIONS },
    { name: 'body.paidAmount', label: '实付金额', kind: 'number' }, // only on OrderPaid
  ],
}
```

In configs, element fields are written as full paths from the root (`body.bodyType`, `body.body.paidAmount`); the engine strips the prefix when it sends them.

## Admission and narrowing codes

- `validateDefinition(definition, kinds)` (kinds: `builtinFieldKinds`, or the host's registry) reports `definition.*`: invalid or duplicate field names, `row-key-unknown` / `row-key-unsortable`, `row-field-unknown`, `element-title-unknown` / `not-a-value`, `temporal-misplaced` / `invalid`, `cell-invalid`, `tone-invalid`, `analysis.field-unknown`, `element-undeclared`, `no-metric`, `view.kind-mismatch`, `view.id-duplicate`, and each system view's own config admission. Any `error` makes the engine refuse every view of the definition with `view.definition.invalid`.
- Narrowing against the descriptor reports `capability.*`. Errors (the definition contradicts the deployment): `capability.field.temporal-mismatch`, `capability.record.paging`, `capability.record.row-key-unsortable`, `capability.record.cursor-appended`. Warnings (the definition declared more than is granted): `capability.field.unknown`, `operators-narrowed`, `unfilterable`, `unsortable`, `summary-narrowed`, `not-projectable`, `protected`, `deprecated` / `deprecated-because`, `options-undescribed`, `capability.analysis.*`, `capability.search.*`. Notes: `capability.field.alias` (an alias was renamed to its path; write the path) and `capability.descriptor.unavailable`.

## Wording

Labels are the audience's words, one word per meaning, the same meaning in each language. Never a path (`state.amounts.paidAmount`), a constant (`PAID`, `OrderPaid`), a class name, or a storage word (document, collection, index, snapshot, aggregate, payload).

Analysis uses the engine's own vocabulary; do not coin synonyms in display names or view titles:

| 中文 | English |
|---|---|
| 维度 | Dimension |
| 指标 | Metric |
| 记录数 | Record count |
| 显示名 | Display name |
| 前 N 组 | Top N groups |
| 展开 / 明细项 | Expand / line items |
| 合计行 | Totals row |
| 只保留 | Keep only |
| 总和（SUM；「合计」只指合计行） | Sum |

A metric's display name says what it measures for the business (「实付」「客单价」「退款率」), not the field and function (`SUM(paidAmount)`). The engine's `label` is one string: where the host serves several languages, follow its convention (for example a definition function that takes the locale and reads a text table), and write both languages.
