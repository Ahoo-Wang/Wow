---
title: Migrate Query Filter to Query Policy
description: Move legacy QueryFilter extensions to request, QueryPolicy, ResultPolicy, schema, and backend boundaries.
---

# Migrate Query Filter to Query Policy

All 8.x query entry points now use `QueryGateway`. The legacy `QueryFilter`, `QueryHandler`, and `QueryContext` types are removed. Choose the owner of each responsibility instead of recreating a generic condition-contributor hook. There is no `QueryConditionContributor` or replacement Filter runtime.

## Responsibility table

| Requirement | New owner | Why |
|---|---|---|
| One request only | request / Query DSL | The caller explicitly owns the condition |
| Caller-overridable domain default | domain Query Facade / request builder | It remains caller input |
| Mandatory on every entry point | `QueryPolicy` | Gateway merges it before backend I/O and callers cannot remove it |
| Result masking | `ResultPolicy`; retained masker APIs are compatibility adapters | Every item is masked once with common partial-result semantics |
| Logical-to-physical fields and capability binding | `QuerySchemaCustomizer` + backend compiler | Policies never know database field names |
| MongoDB / Elasticsearch execution | `QueryBackendFactory`, `QueryBackend`, Plan V1 consumer | Backends consume immutable schema and secured plans |

`RewriteRequestCondition` is deprecated 8.x HTTP compatibility only. It may add append-only `LEGACY_ENRICHMENT`; it is not authority. Route, path, headers, and body remain `CALLER_REQUEST`. Only an authenticated adapter can supply trusted authority.

## Before: legacy Filter (recognition only)

```kotlin
// Legacy code: these types are removed. Do not copy this into a new application.
class TenantSnapshotQueryFilter : SnapshotQueryFilter {
    override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
        context.asRewritableQuery().rewriteQuery { query ->
            query.appendCondition(Condition.tenantId(currentTenantId()))
        }
        return next.filter(context)
    }
}
```

## After: mandatory tenant policy

The core of this example is compiled and executed by `QueryPolicyDocumentationTest`:

```kotlin
object TenantPolicy : QueryPolicy {
    override fun evaluate(context: QueryPolicyContext): Mono<QueryPolicyResult> {
        val tenantId = context.invocationScope.trustedAuthority.tenantId
            ?: return Mono.error(QueryPolicyDeniedException("TENANT_REQUIRED"))
        return Mono.just(
            QueryPolicyResult(
                mandatoryExpression = PredicateExpression(
                    LogicalField("tenantId"),
                    PortableOperator.EQ,
                    listOf(QueryValue.StringValue(tenantId))
                ),
                constraints = QueryPolicyConstraints(
                    fieldAccess = QueryFieldAccess.Restricted(
                        setOf(LogicalField("tenantId"), LogicalField("state.status"))
                    ),
                    capabilityAccess = mapOf(QueryCapabilityId("full-text") to CapabilityDecision.GRANT),
                    maxBudget = QueryBudgetLimit(
                        timeout = Duration.ofSeconds(2),
                        maxResults = 100,
                        maxCost = 1_000
                    )
                )
            )
        )
    }
}
```

`mandatoryExpression` is appended to caller input. `fieldAccess` restricts queried, sorted, and returned fields. `capabilityAccess` decides FullText/Native capabilities. `maxBudget` is intersected with request, system, and backend limits.

### Spring ordering

Register ordinary Policy beans. Use `@Order` only when deterministic observation order is required; order cannot overwrite mandatory expressions or widen fields and budgets.

```kotlin
@Bean
@Order(100)
fun tenantQueryPolicy(): QueryPolicy = TenantPolicy
```

### Non-Spring

Create one explicit Gateway and place policies in the same configuration. Do not create a Gateway per repository:

```kotlin
val gateway = QueryGatewayFactory.create(
    QueryGatewayConfiguration(
        admission = admission,
        schemaResolver = schemaResolver,
        backendResolver = backendResolver,
        customPolicies = listOf(TenantPolicy),
        resultPolicies = listOf(maskingResultPolicy),
        clock = clock,
        zoneId = zoneId,
        structureLimits = structureLimits,
        systemBudgetLimit = systemBudgetLimit,
        enabledCapabilities = enabledCapabilities,
        meterRegistry = meterRegistry
    )
)
```

### Policy tests

Use the published `QueryPolicyTestKit`:

```kotlin
QueryPolicyTestKit(TenantPolicy, contextWithTrustedTenant)
    .expectMandatory(expectedTenantPredicate)

QueryPolicyTestKit(TenantPolicy, contextWithoutTenant)
    .expectDenied("TENANT_REQUIRED")
```

## Security checklist

- Reject tenant/owner/space mismatches before resolver, readiness, or backend execution.
- Missing mandatory authority fails closed; never fall back to headers, paths, or caller predicates.
- A requested capability needs at least one `GRANT` and no `DENY`; all-`ABSTAIN` is denied.
- Empty, failed, or invalid Policy output fails closed without backend I/O.
- Client-supplied `Wow-*`, `CoSec-*`, or ordinary headers are never trusted authority.
- Legacy QueryService, direct Gateway, and WebFlux must share one Policy/ResultPolicy chain.
- Mask only in `ResultPolicy`; do not retain a Filter or mask again after a facade.
- FullText and Native require schema, policy, and backend support; no fallback to string or RAW queries.

## Corrected behavior

- NoOp queries now fail with `BACKEND_NOT_READY / BACKEND_RESOLUTION / BACKEND_UNAVAILABLE` instead of empty results.
- Invalid page, limit, projection, sort, and unknown fields fail before backend I/O.
- Elasticsearch lists are no longer silently truncated at 10,000; pages return exact totals.
- A list failure after the first item is `INCOMPLETE_RESULT`; JSON omits the closing `]`, while SSE emits one safe terminal error event.
- Missing or incompatible MongoDB indexes and Elasticsearch mappings return NotReady; existing resources are not mutated automatically.

## Upgrade and rollback

Check MongoDB indexes, Elasticsearch templates/mappings, and presence metadata before cutover. For an incompatible existing Elasticsearch index, create and backfill a new index, then switch an alias atomically. Do not mutate an old mapping during application startup.

Rollback means deploying the previous artifact and restoring the rehearsed alias/route. There is no runtime `LEGACY`, `SHADOW`, or dual-engine switch. Document data and alias rollback if new writes are incompatible with the old artifact.

See also [Queries](../query.md), [Custom Query Backend](../extensions/query-backend.md), and [Data access](../data-access.md).
