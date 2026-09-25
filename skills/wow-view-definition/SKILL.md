---
name: "wow-view-definition"
description: "Write or revise Wow View Engine view definitions (record, analysis, dashboard), their system views and their Storybook stories from a business scenario and the service's query capability descriptor. The definition only narrows what the descriptor grants, uses business wording for labels, keeps sensitive and deprecated fields out of analysis, and is proved by the engine's admission. Use for @ahoo-wang/wow-view-engine definitions in downstream apps and for the view definitions and stories inside the Wow repository. Exclude runtime client code (wow-client), answering data questions (wow-data-query), and changing the view engine itself."
---

# wow-view-definition

The deliverable is code a reviewer can merge: a `ViewDefinition` (fields, record and analysis capability, system views) or a dashboard, the descriptor it was written against, and the tests or stories that show the engine admits it. The descriptor says what the service can do; the definition chooses what this audience sees. **It only narrows: it never declares an operator, sort, group, metric, search, paging mode or limit the descriptor does not grant.**

## Before writing

1. **Get the descriptor, in this order:** a descriptor file already committed beside the definitions (a fixture), then a development or staging service: `GET {base}/{aggregate}/snapshot/schema` for snapshots, `GET {base}/{aggregate}/event/schema` for event streams. Reading a production service needs the user's explicit consent in this conversation; a production URL in config, docs or history is not consent. Credentials come from the environment; never ask for a token in chat or print one.
2. **Commit what you read.** Save the descriptor JSON next to the definitions (the repository's fixture convention) and name its `version` in the definition's comment or test, so a later descriptor shows drift in review.
3. **Read the scenario for the audience:** who opens the view, what they work through (record), what they ask (analysis), what they glance at (dashboard), and what one record is called for them (`recordNoun`).
4. Load `references/definition-anatomy.md` before the first field.

## Workflow

1. **Plan in business terms.** List the record views (queues people work), the analysis questions (measure by dimension over a period) and any dashboard. Drop what the scenario does not ask for; a definition is a choice, not a copy of the descriptor.
2. **Map every field to the descriptor.** Each `FieldDefinition.name` is a descriptor `path`; write the canonical path, not an alias. An element field is declared inside its array field's `elements`, named relative to the element, and exists in the descriptor with `scope` equal to that array (or in a `variants` entry). A field neither the descriptor nor its fixture lists does not exist: never invent or guess one. Say what is missing and where it would come from (a read-model field, a model declaration), instead of writing it.
3. **Declare only a subset.** Per field: `operators` within `filter.operators`; `sortable` only where `sort.paged` (paged) or `sort.cursor` (cursor) is true; analysis `groups` and `functions` within `aggregate`; `distinctCount`, `percentile`, `any` only when the field grants them and `analysis.metrics` lists the metric type; `dateUnits` within `analysis.dateUnits`. Per definition: a `paging` mode the descriptor's `record.paging` lists, a `rowKey` the descriptor sorts, a `search` field only when `record.search` exists with its `searchFields` and `searchMode` inside it, and `maxWindow` or `limits.maxLimit` only to go lower than the descriptor's limits. Never restate a server limit as a definition limit.
4. **Keep protected fields out of analysis.** A field with `sensitivity` is never an analysis group, a metric input, an expression operand or a metric filter, and never a `rowKey` or card title. A CONFIDENTIAL field (`comparable: false`) takes no operators, is not sortable and is not a search field. Show a masked value only when the audience needs it; otherwise leave the field out. Never add a derived field or condition that would reveal what the mask hides.
5. **Keep event conditions inside the event.** On an event stream, `body` is an array of events: declare it `kind: 'elementMatch'` with `elementTitle: 'bodyType'` and the event fields inside `elements`. A condition on an event's payload (a system view's filter, a metric's filter) is an `ELEMENT_MATCH` on `body` whose predicate holds both the `bodyType` condition and the payload condition, so both apply to the same event. Never write a root-level condition on a `body.*` path.
6. **Label for the audience.** Every `title`, `label`, `recordNoun`, option label, field group, system view title and analysis display name is in the audience's business words, in each language the host serves. Never a path, a class or enum constant, or a storage term. Analysis wording follows the engine's vocabulary; see the reference.
7. **Avoid deprecated fields.** A field with `deprecated` is replaced by the one its message names. When the scenario truly needs it, keep it, say why in a code comment, and flag it in the report.
8. **Match what the descriptor says about the value:** `temporal` against `semantic` (`TEMPORAL_EPOCH` with its `timeUnit`, `TEMPORAL_DATE`), enum `options` against `enum[].value`, and constraints such as `CURSOR_UNIQUE_SORT` (a cursor `rowKey` equals the appended identity), `COUNT_REQUIRES_FILTER`, `PARALLEL_ARRAY_SORT` and `NULL_OR_EMPTY_AS_MISSING`.
9. **Write system views and stories** from `references/recipes.md`, then run the self-check below.

## Self-check

A definition is done when the engine admits it against the committed descriptor. Run, and report each result:

- **Admission:** `validateDefinition(definition, kinds)` returns no `error` issue.
- **Narrowing:** an engine whose source `describe` answers the committed descriptor opens every system view (and every dashboard) with no `error` issue on `onIssue`, and with no `capability.*` warning the definition could have avoided: each such warning is a declared capability the descriptor lacks, so remove it rather than accept it.
- **Tests and stories:** the host's existing test for its definitions passes (or one is added in its style); stories type-check, lint and pass their interaction tests.

`references/checklist.md` lists every gate and the report.

## References

- `references/definition-anatomy.md`: the definition's shape, how each member maps to the descriptor, admission and narrowing codes, and the analysis vocabulary. Load it before writing.
- `references/recipes.md`: record queue, analysis breakdown and trend, event stream by event type, dashboard, a story, and revising after descriptor drift. Load the recipe for the scenario.
- `references/checklist.md`: the pre-review checklist, the gates and the final report. Load it before reporting.

## Related Skills

- $wow-client: TypeScript code that sends commands or queries, including wiring the descriptor client into a view source.
- $wow-data-query: answer a business question from a running service's data instead of writing a view.
- $wow-debug: a query the descriptor admits but the service rejects, or a result that contradicts the data.
