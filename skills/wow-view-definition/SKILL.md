---
name: "wow-view-definition"
description: "Write Wow View Engine view definitions with defineView over the service's committed query capability descriptor: fields and their words, narrowing, system record and analysis views, boards, event streams, self-checked with admit. Use for @ahoo-wang/wow-view-engine definitions and stories, including the Wow repository's Storybook and compensation console. Exclude host wiring and actions (wow-view-host), client code, data answers, and changing the engine."
---

# wow-view-definition

The deliverable is code a reviewer can merge, per dataset: a `defineView(descriptor, spec)` module, the descriptor snapshot it reads (committed beside it), the words for each of its keys in every language the host serves, its system views and boards, and a host test in which `admit` reports nothing the definition did not decide on.

The descriptor holds the **facts**: paths, kinds, values, sensitivity, deprecation, element structure, and what the store can filter, sort and aggregate. `defineView` takes them from the snapshot, and the runtime narrows to the live descriptor. The definition holds only **choices**: for whom, which fields, in what words, what is narrowed, what a reader opens on. This Skill is about those choices; the engine checks the rest. Registering the definition, its source, routes, store and commands is `wow-view-host`.

## Before writing

1. **Get the descriptor, in this order:** a descriptor already committed beside the definitions; then a development or staging service: `GET {base}/{aggregate}/snapshot/schema` for snapshots, `GET {base}/{aggregate}/event/schema` for event streams. Reading a production service needs the user's explicit consent in this conversation; a production URL in config, docs or history is not consent. Credentials come from the environment; never ask for a token in chat or print one.
2. **Commit what you read** as JSON beside the definition and name its `version` in a comment or the test, so a later descriptor shows its drift in review.
3. **Read the scenario for the audience:** who opens the view, what they work through (record views), what they ask (analysis views), what they glance at (a board), what one record is called for them (`recordNoun`), and which languages the host serves.
4. Load `references/choices.md` before the first field.

## The choices

1. **Plan in the audience's terms.** The queues people work, the questions they ask (a measure by a dimension over a period), the board they glance at. Drop what the scenario does not ask for: a definition is a selection, not a copy of the descriptor.
2. **List only what this audience reads, filters or groups by**, in the order they read it. A path you do not list does not appear. The record's identity, or the `record.rowKey` you choose, must be among the listed fields. A field the descriptor does not list does not exist: never invent one; say it is missing and where it would come from (a read-model field, a model declaration).
3. **Words are keys, said at the leaf.** Every title, label, option, field group, system view and metric display name is `text(key)`, with the words in a table per language. Words are the audience's, one word per meaning; never a path, a constant, a class name or a storage word. Word every category value the audience can see, or hide it (`false`): a value you leave out shows the descriptor's description, or the raw constant when there is none, and nothing reports it.
4. **Narrow for the audience, never to restate the store.** Leave capabilities open by default: the source grants them at run time, so one definition deploys to MongoDB and Elasticsearch alike. Narrow where the audience would be misled: `analysis: false` for identifiers and free text, `analysis: { groups: [] }` for an id you count but never group by, `sortable: false` and `operators: []` for text that is only read, `summary` only where a total means something, `dateUnits` the audience thinks in.
5. **Protected and deprecated fields.** The engine keeps a sensitive field out of analysis and a confidential one out of every comparison. Your choice is whether the audience sees it at all: list it only when they need the masked value, and never add a field or condition that reveals what the mask hides. A deprecated path is replaced by the one its message names; keep it only with `deprecated: { message }` saying why, and flag it in the report.
6. **Time.** `timeField` is the moment a board's one date filter means for this dataset (when it was paid, not when it was last touched); a system view overrides it, `null` to be read whole.
7. **Records.** Layouts the audience scans (`table`, `card` with the field that names a card), `rowKey` when the identity is not what people look a record up by, and `rowFields` for any field the host's declared actions read beyond the visible columns, including the aggregate id their commands address when `rowKey` is a business key.
8. **Event streams.** One record is one command's appended events: list `body` with `elementTitle: 'bodyType'`, the event types worded as business events, and payload fields from the descriptor's variants inside `elements`. A condition on an event's payload is one `ELEMENT_MATCH` on `body` holding both its `bodyType` and the payload condition.
9. **System views, analyses and boards** are the starting points, not every possible view: see `references/views-and-boards.md`. Those declared here are read-only code; stored system views that administrators publish on the screen belong to the store (`wow-view-host`).

## Self-check: admit

A definition is done when the engine admits it. The host's test calls `admit` from `@ahoo-wang/wow-view-engine/testing` over everything it registers, with the committed descriptors by `source` and the words of each language, and expects `[]`; a finding kept on purpose is asserted exactly, with a comment saying why. Run it and fix each finding at the choice it names (`references/admit.md`).

`admit` already checks every path, kind, value, capability, key, element, system view config and board reference against the descriptors; do not re-verify those by hand or with your own scripts. What it cannot judge is yours: the audience, the words, what is left out, the defaults. Review those against `references/admit.md` before reporting.

## References

The references hold names, shapes, rules and gotchas; each links the page of https://wow.ahoo.me/guide/typescript/view-engine.html that covers its topic in depth. Repository paths (`compensation/…`, `typescript/…`) are in the Wow repository. A page still on the deprecated `@ahoo-wang/fetcher-viewer` is rebuilt as a definition here and a host (`wow-view-host`), not renamed.

- `references/choices.md`: a `defineView` worked through choice by choice, words and keys, the analysis vocabulary, narrowing, protected, deprecated and event-stream fields. Load it before writing.
- `references/views-and-boards.md`: system record and analysis views, derived metrics, boards and their time filter, where definitions live, and revising after descriptor drift. Load it for the views.
- `references/admit.md`: the admission test, what each finding asks you to revisit, the review of what `admit` cannot judge, and the report. Load it before the self-check.

## Related Skills

- $wow-view-host: register the definition with its source and store, `ViewHost`, routes, and the commands on its records.
- $wow-client: TypeScript code that sends commands or queries outside the engine.
- $wow-data-query: answer a business question from a running service's data instead of writing a view.
- $wow-develop: a query the descriptor admits but the service rejects, or a result that contradicts the data.
