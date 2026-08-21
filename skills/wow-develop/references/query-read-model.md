# Query and Read-Model Decisions

Use this reference for Query DSL, filtering, projection, pagination, sorting, query rewriting, and read-model access.

## Stable decisions

- Keep query construction separate from backend conversion and execution.
- Apply tenant, owner, deletion, and authorization filters at the boundary that cannot be bypassed by callers.
- Treat pagination ordering as a correctness contract; define a deterministic tie-breaker when records can share the primary sort value.
- Project only fields supported by downstream mapping and serialization.
- Verify count and page semantics together when presenting totals.
- Preserve backend-specific null, collection, date/time, and nested-field semantics through focused converter tests.

## Discover the actual DSL

```bash
rg -n "singleQuery|listQuery|pagedQuery|condition|pagination|projection|sort" . -g '*.kt' -g '*.java'
# Run this from a separate checkout of the pinned Wow source:
rg -n "QueryDsl|QueryService|QueryRewriter|Condition" wow-query -g '*.kt'
```

Inspect the downstream usage plus DSL builders, condition types, snapshot/event query extensions, backend converters, service interfaces, and tests from a separate pinned Wow source checkout or resolved dependency sources. Never invent an operator or copy a complete method list into a Skill.

## Verification boundary

- Unit-test condition composition, enforced filters, projection, pagination, and sort construction.
- Test backend conversion when semantics differ by MongoDB, Elasticsearch, or another store.
- Use integration data for index usage, performance claims, collation, null handling, or backend-specific consistency.
- For production-performance conclusions, require reproducible queries and execution/profile evidence; green unit tests are insufficient.
