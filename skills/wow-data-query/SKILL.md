---
name: "wow-data-query"
description: "Answer business data questions about a running Wow service by reading its query capability descriptor and running read-only snapshot or event-stream queries: counts, lists, breakdowns and trends, explained in business terms with the exact query and its caveats. Use when the deliverable is an answer from live data, not code. Exclude writing query code (wow-client), diagnosing a failing query or wrong result (wow-debug), view definitions, and developing the Wow repository itself."
---

# wow-data-query

The deliverable is an answer: numbers, rows or a breakdown, what they mean for the question, the query that produced them, and what could make them misleading. It is never application code.

## Before any query

1. **Pick the environment with the user.** Use a development or staging service by default. Querying production needs the user's explicit consent in this conversation; a production URL found in config, docs or history is not consent.
2. **Credentials come from the environment** (an exported token, a configured CLI or proxy). Never ask the user to paste a password or token into the chat, and never print one.
3. **Read the descriptor first**: `GET {base}/{aggregate}/snapshot/schema` (or `event/schema` for event history). Record its `version`. Load `references/descriptor.md` for how to read it.

## Workflow

1. Restate the question as a query plan in business terms: which aggregate, snapshot state or event history, which records, which measure, which breakdown and period.
2. Map every field in the plan to a descriptor `path` (or one of its `aliases`). A field the descriptor does not list does not exist for querying; never invent or guess one. Say what is missing instead.
3. Stay inside what the descriptor grants: only listed `filter.operators`, `sort.paged` or `sort.cursor`, `aggregate.groups` and metric kinds, and the `limits`. Never widen a capability. If the question needs something not granted, say so and offer the closest granted alternative.
4. Respect protection. A field with `sensitivity` is masked in results. A CONFIDENTIAL field (`comparable: false`) cannot be filtered, sorted or searched, and no protected field can be grouped or used as a metric. Do not try to recover masked values through filters, ranges or search.
5. On event streams, a condition on one event's payload goes inside an `ELEMENT_MATCH` on `body`, together with its `bodyType` (see `variants`), so that both apply to the same event.
6. Avoid `deprecated` fields; use the field its message points to. Mention it if the user named the deprecated one.
7. Build the smallest query that answers the question: `count` for "how many", `aggregation` for breakdowns and trends, `paged` or `cursor` with a projection for examples. Use the read-only query routes only (see the reference). Never call command routes or the snapshot `PUT` regeneration routes.
8. Run it. If it is rejected, read `bindingErrors[0].code` and `name`, fix the query within the descriptor, and retry once. If it still fails, or a result looks wrong, stop: that is a diagnosis for `wow-debug`.
9. Answer in business terms first, then the evidence.

## Answer checklist

- The direct answer, with units, the time zone of any date grouping and the period covered.
- Caveats that change the meaning:
  - metrics listed in `analysis.approximate` are estimates;
  - fields in a `NULL_OR_EMPTY_AS_MISSING` constraint treat null and empty as missing;
  - masked fields show masked values;
  - a limit or top-N may have cut the rows;
  - the default deletion scope hides deleted aggregates unless the query selects them.
- The evidence: environment, descriptor `version`, and the exact query JSON sent.

## References

- `references/descriptor.md`: reading the descriptor, the read-only query routes, query shapes, and rejection codes. Load it before building the first query.

## Related Skills

- $wow-client: write TypeScript code that queries a Wow service.
- $wow-debug: diagnose a rejected query that should be valid, a wrong result, or a failing query route.
