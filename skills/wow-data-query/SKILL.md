---
name: "wow-data-query"
description: "Answer business data questions from a running Wow V10 service: read a query model's capability descriptor, run read-only snapshot, events or dataset queries, and explain counts, lists, breakdowns or trends with the exact query and caveats. Use when the deliverable is numbers from live data, not code or a view. Exclude query code (wow-client), failing-query diagnosis (wow-develop), and non-Wow databases."
---

# wow-data-query

The job: someone asks a business question ("how many orders shipped late last week, by warehouse?") and the answer is in a running Wow V10 service. The deliverable is an answer: numbers, rows or a breakdown, what they mean for the question, the query that produced them, and what could make them misleading. It is never application code.

## Three query models

| Model | One record is | Route | Use for |
| --- | --- | --- | --- |
| `snapshot` | One aggregate's current state | `/{context}/{aggregate}/snapshot/query` | "How many orders are paid now", "which carts are open" |
| `events` | One commit: the events one command appended | `/{context}/{aggregate}/events/query` | "When did this order change", history and timelines |
| Dataset | A row of a wide or warehouse table the service publishes | `/wow/dataset/{context}/{dataset}/query`, listed by `GET /wow/dataset` | Questions across aggregates or contexts (orders by customer segment) |

`POST` on a route queries (the body's `kind` names the operation); **`GET` on the same route is its capability descriptor**, with its version as the `ETag`. One aggregate's state at a version or a time is `GET /{context}/{aggregate}/{id}/state?version=` or `?at=`, only when the user asked about that aggregate.

## Before any query

1. **Pick the environment with the user.** A development or staging service by default. Querying production needs the user's explicit consent in this conversation; a production URL in config, docs or history is not consent.
2. **Credentials come from the environment** (an exported token, a configured CLI or proxy). Never ask for a password or token in chat, never print one. The service reads the tenant and the caller from them; a query reads the caller's tenant only.
3. **Read the descriptor first** and record its `version`. Load `references/descriptor.md` for how to read it and the request shapes.

## Workflow

1. Restate the question as a query plan in business terms: which model (and aggregate or dataset), which records, which measure, which breakdown, which period and time zone. A question across aggregates is a dataset's if one serves it; never join two queries' results by hand.
2. Map every field to a descriptor `path` (or one of its `aliases`). A field the descriptor does not list does not exist for querying; never invent one, say what is missing.
3. Stay inside what the descriptor grants: the field's `filter.operators`, `sort.paged`/`sort.cursor`, `aggregate` groups and functions, `analysis`, `limits` and `constraints`. The same question can be fine on one backend and refused on another; the descriptor of this service is the authority.
4. Respect protection: a field with `sensitivity` is masked in results; a `comparable: false` one cannot be filtered or sorted; no protected field is grouped or aggregated. Never try to recover masked values through filters, ranges or search.
5. **Events:** a condition on an event's payload is one `ELEMENT_MATCH` on `events` holding the event's `name` and the payload condition (paths relative to the event: `name`, `body.<field>`). Only payload fields of events still at revision 1 can be filtered or grouped (the descriptor says so). A record is a commit: say whether a number counts commits or events.
6. **Time:** relative-time operators (`TODAY`, `RECENT_DAYS`, `LAST_MONTH`, …) and `DATE_HISTOGRAM` read their days in UTC unless the query gives `zoneId`/`timeZone`. Ask or infer the business's zone, set it, and state it in the answer.
7. Build the smallest query: `count` for "how many", `aggregate` for breakdowns and trends, `list`, `paged` or `cursor` with a projection for examples. Only the read-only routes above; never a command route, the snapshot rebuild route, the ops routes, or anything that writes.
8. Run it. On a refusal, read the problem's `code` and its `violations` (`code`, `path`, `message`), fix the query within the descriptor and retry once. A 429 `wow.query.busy` waits for `Retry-After`; a 409 `wow.query.dataset-unavailable` means the dataset is being revised (try later or another source); a 504 `wow.query.timeout` means narrow the query. A second refusal, a 5xx, or a result that contradicts the data is a diagnosis for `wow-develop`.
9. Answer in business terms first, then the evidence.

## Answer checklist

- The direct answer, with units, the time zone of any date grouping and the period covered.
- Caveats that change the meaning:
  - a count or a paged total with `exact: false` is a lower bound ("at least 10000");
  - metrics listed in `analysis.approximate` are estimates;
  - masked fields show masked values;
  - a `limit` or top N may have cut the rows;
  - the default deletion scope hides deleted aggregates unless the query selects them;
  - snapshots lag commits by moments; a dataset lags its sources by its refresh.
- The evidence: environment, descriptor `version`, and the exact request body sent.

## References

- `references/descriptor.md`: reading the descriptor, the request shapes of each `kind`, filters, aggregation and windows, events, datasets, and the problem codes. Load it before the first query.

## Related Skills

- $wow-client: TypeScript code that queries a Wow service.
- $wow-view-definition: a view people open again and again instead of a one-off answer.
- $wow-develop: a refused query that should be valid, a wrong result, or a failing query route.
