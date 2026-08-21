# PrepareKey Decisions

Use this reference for uniqueness, reservation, rollback, reprepare, and operations that must run only while a prepared value is owned.

## Stable decisions

- Model the prepared key and stored value as different concepts. Verify their generic types and serialization from the current API.
- Treat preparation as a distributed ownership operation with expiration, rollback, retry, and ambiguous-outcome behavior.
- Make the business aggregate or process that owns the reservation explicit; do not use a prepared key as hidden aggregate state.
- Keep the protected operation inside the current API's prepared execution boundary when atomic ownership is required.
- Define recovery for timeout, duplicate request, partial downstream failure, and an existing conflicting owner.

## Discover the target contract

```bash
rg -n "PrepareKey|PreparedValue|usingPrepare|reprepare|rollback" . -g '*.kt' -g '*.java'
```

In a separate checkout of the pinned Wow source, inspect `wow-core/src/main/kotlin/me/ahoo/wow/infra/prepare/`, the starter auto-registration/properties, backend implementations, and their tests. Resolve exact method signatures, TTL representation, storage selection, and error types from the target version.

Use unit tests for business branching and focused backend/integration tests for ownership, expiration, concurrency, rollback, and retry claims.
