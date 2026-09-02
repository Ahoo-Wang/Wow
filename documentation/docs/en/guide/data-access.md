---
title: "Data Access Control"
description: "Scope Wow routes and queries by tenant, owner, space, and application-supplied ABAC filters without confusing routing data with authorization."
outline: deep
---

# Data Access Control

Wow carries four kinds of data-access context through the write and read paths:

1. **Tenant** — aggregate storage and route scope;
2. **Owner** — optional ownership metadata and route scope;
3. **Space** — optional namespace metadata supplied by a request header;
4. **ABAC tags** — resource tags plus an application-supplied principal filter.

On WebFlux query routes, `RewriteRequestFilter` appends tenant, owner, and space metadata filters before invoking `QueryGateway`. A configured `AbacQueryFilter` then appends resource-tag conditions inside the Gateway filter chain.

::: danger Scope is not authentication
A tenant or owner path, `Wow-Space-Id` header, or ABAC tag is data used by routing and filtering. It does not prove who sent the request or whether that principal may choose the value. Authenticate first, bind allowed scopes on the server, authorize command and query routes, and keep raw query factories out of untrusted request paths.
:::

## RESTful URL Pattern

Generated aggregate routes follow this shape where each optional segment is enabled by aggregate metadata:

```text
[tenant/{tenantId}/][owner/{ownerId}/]{resourceName}[/{resourceId}]/{action}
```

| Scope | Wire value | Route condition |
|---|---|---|
| Tenant | `tenant/{tenantId}` | Aggregate has no static tenant ID |
| Owner | `owner/{ownerId}` | Effective `AggregateRoute.Owner` is not `NEVER` |
| Space | `Wow-Space-Id` header | Effective route is spaced |

`AGGREGATE_ID` ownership removes the separate resource-ID segment when owner ID identifies the aggregate. Command-level `@CommandRoute` settings may override aggregate route defaults. Snapshot query contributors always publish a base aggregate route and add tenant/owner variants when applicable; an unscoped query route therefore needs an explicit security policy. Use the running OpenAPI document for the exact route; a bounded-context alias is not automatically a URL prefix.

Query-schema routes (`/{aggregate}/snapshot/schema`, `/{aggregate}/event/schema`, and their `/refresh` routes) intentionally have no tenant, owner, or aggregate-ID path variants: they describe the aggregate's query models, not data for one caller. The common aggregate contract may still declare `Wow-Space-Id` for a spaced aggregate.

## Tenant

Tenant is part of `AggregateId` and persisted message/snapshot metadata. The aggregate ID remains globally unique within a named aggregate; tenant does not make the same aggregate ID reusable.

### Annotation-based Tenant ID

Mark the command property that carries a dynamic tenant identifier:

```kotlin
data class CreateOrder(
    @AggregateId val orderId: String,
    @TenantId val tenantId: String,
    val items: List<OrderItem>,
)
```

KSP metadata records how the tenant is resolved. Runtime WebFlux routes extract the tenant path value and carry it into the command or query context. The annotation itself does not verify membership in that tenant.

### Static Tenant ID

Use `@StaticTenantId` when every instance of an aggregate belongs to one fixed tenant:

```kotlin
@AggregateRoot
@StaticTenantId("system")
class SystemConfiguration(private val state: SystemConfigurationState)
```

The generated route omits the dynamic tenant prefix. This is a routing/storage choice, not a declaration that the resource is public.

### Default Tenant

`TenantId.DEFAULT_TENANT_ID` is `(0)`. Static single-tenant aggregates commonly use this value. Do not use the default as an authorization fallback when a dynamic tenant is expected.

## Owner

Owner is snapshot and message metadata within an aggregate. It can constrain generated routes and snapshot queries, but the caller identity must still be bound by application security.

### Annotation-based Owner ID

Mark the command field that supplies ownership metadata:

```kotlin
data class CreateCart(
    @AggregateId val cartId: String,
    @OwnerId val userId: String,
)
```

Do not trust a client-supplied `userId` merely because it carries `@OwnerId`; compare or replace it using the authenticated principal at the application boundary.

### Ownership Routing Policy

```kotlin
@AggregateRoot
@AggregateRoute(resourceName = "orders", owner = AggregateRoute.Owner.ALWAYS)
class Order(private val state: OrderState)
```

| Policy | Owner path | Resource ID | Meaning |
|---|---|---|---|
| `NEVER` | absent | normal | no owner routing metadata |
| `ALWAYS` | required | normal | owner and aggregate ID are distinct |
| `AGGREGATE_ID` | required | omitted | owner ID also identifies the aggregate |

The generated route and `OwnerAggregatePrecondition` can enforce that a loaded aggregate's owner metadata matches the route value. They do not establish that the authenticated caller owns that route value.

### Ownership Transfer

An event implementing `OwnerTransferred` changes the state aggregate's owner metadata while sourcing:

```kotlin
data class OrderAssigned(
    override val toOwnerId: String,
) : OwnerTransferred
```

The domain decides when transfer is allowed. Authorize the transfer command before emitting the event; the marker interface only applies the new metadata.

## Space

Space is a string namespace stored with messages and snapshots. It is independent from tenant and owner and defaults to the empty string.

### Enabling Space

```kotlin
@AggregateRoot
@AggregateRoute(resourceName = "sales-order", spaced = true)
class Order(private val state: OrderState)
```

WebFlux reads `Wow-Space-Id` and appends a `SPACE_ID` filter to query routes. The header does not become a URL segment and does not authenticate access to the space.

### Space Transfer

An event implementing `SpaceTransferred` changes the state aggregate's space metadata:

```kotlin
data class OrderArchived(
    override val toSpaceId: SpaceId,
) : SpaceTransferred
```

As with ownership transfer, the event applies a state transition; the command-side policy decides whether the transition is permitted.

## ABAC (Attribute-Based Access Control)

Wow stores resource tags and provides `AbacQueryFilter` as an extension point. The application supplies principal tags from authenticated context and decides whether missing context is public or denied.

### Core Concepts

`AbacTags` is `Map<String, List<String>>`:

```kotlin
val principalTags = mapOf(
    "department" to listOf("engineering", "product"),
    "role" to listOf("reader"),
)

val resourceTags = mapOf(
    "department" to listOf("engineering"),
)
```

For each principal tag key, regular values match a resource when that key is absent/empty or intersects the principal values. `listOf("*")` maps to an `EXISTS` condition for that key. Conditions for multiple principal keys are combined with `AND`.

An untagged resource is therefore public to the built-in tag-matching expression. If that is not the business rule, override the policy rather than treating tags as a complete authorization system.

### Applying Resource Tags

`DefaultApplyResourceTags` is a built-in command with `PUT`, action `tags`, and an ID path. When an aggregate does not register its own `ApplyResourceTags` handler, Wow can use the default command function and source `DefaultResourceTagsApplied` into state-aggregate metadata.

```kotlin
val command = DefaultApplyResourceTags(
    tags = mapOf("department" to listOf("engineering")),
)
```

Protect this command like any other authorization-changing operation. Publishing a generated route does not grant only administrators access.

### Tag Merging

`merge` combines values for the same key:

```kotlin
val effective = mapOf("department" to listOf("engineering"))
    .merge(mapOf("department" to listOf("product"), "role" to listOf("reader")))
```

Choose replacement versus merge deliberately. `DefaultResourceTagsApplied` replaces the state aggregate's stored resource tags; it does not silently merge them.

### Dynamic Tag Extraction with StateAggregateTagsExtractor

Implement `StateAggregateTagsExtractor<S>` on state when tags must be derived from the materialized aggregate:

```kotlin
class OrderState(
    val department: String,
) : StateAggregateTagsExtractor<OrderState> {
    override fun extract(source: ReadOnlyStateAggregate<OrderState>): AbacTags =
        mapOf("department" to listOf(department)).merge(source.tags)
}
```

The extractor computes resource metadata during state materialization. It does not resolve principal identity.

### ABAC Query Filter

Subclass `AbacQueryFilter` and fail closed for protected queries:

```kotlin
@Component
class MemberAbacQueryFilter(
    private val memberships: MembershipRepository,
) : AbacQueryFilter() {
    override fun getPrincipalTags(
        contextView: ContextView,
        context: QueryContext<*, *>,
    ): Mono<AbacTags> = contextView.getOrEmpty<Principal>(Principal::class.java)
        .map { principal -> memberships.tags(principal.name, context) }
        .orElseGet { Mono.error(AccessDeniedException("Missing principal")) }
}
```

This example represents an application policy; adapt it to the actual security context. The framework default for empty tags or `Mono.empty()` is `MatchAllFilter`, so protected applications must reject missing identity/tags explicitly.

### Query Entry Points and Policy Enforcement

Spring-registered aggregate `SnapshotQueryGateway` and `EventStreamQueryGateway` Beans are explicitly paired with an independent `QueryModelSchemaProvider` and execute configured ABAC and generic query filters. As an assembly detail, the current Spring Registrars obtain that Provider from the routed Backend with `requiredQueryModelSchemaProvider()`, then pass the Backend and Provider separately to the Gateway. In-process calls do not execute WebFlux `RewriteRequestFilter`; callers must provide tenant, owner, and space scope explicitly in the query or through a trusted context supported by their filters. Managed Gateways automatically mask query and aggregate-state load results from Query Model Schema. See [Field Masking](./query/masking.md) for the complete boundary.

`SnapshotQueryBackendFactory` and `EventStreamQueryBackendFactory` are raw backend entries. Direct `QueryBackend` invocation bypasses the `QueryGateway` policy chain whether or not that Backend implements `QueryModelSchemaProvider`, and is trusted infrastructure access that must be protected. A Backend without a Provider cannot be assembled by the current Registrar; it is not a managed Backend that merely skips Mask or returns a managed raw result.

Aggregation uses the snapshot filter chain for its root filter. Schema allows ordinary filter/search/sort operations on masked fields but rejects groups, field metrics, or expressions that would return their raw values; count is unchanged. Do not expose sensitive aggregation merely because ordinary snapshot queries pass through ABAC.

## Required Security Closure

1. Authenticate the request before it enters generated routes.
2. Derive allowed tenant, owner, and space scopes from trusted server-side membership data.
3. Authorize commands, especially owner/space transfer and resource-tag changes.
4. Register a fail-closed query policy for protected data.
5. Keep raw factories and unwrapped custom services outside untrusted request paths.
6. Test anonymous, forged-scope, cross-tenant, missing-tag, aggregation, and raw-entry negative cases.

## Layered Isolation Summary

| Layer | Stored/query metadata | HTTP representation | What Wow supplies | What the application must supply |
|---|---|---|---|---|
| Tenant | `tenantId` | path prefix when dynamic | propagation and query scoping | authenticated scope binding |
| Owner | `ownerId` | optional path prefix | route shape, precondition, query scoping | caller-to-owner authorization |
| Space | `spaceId` | `Wow-Space-Id` header | propagation and query scoping | caller-to-space authorization |
| ABAC | `tags` | internal query filter | tag storage and extension point | principal resolution and fail-closed policy |

These mechanisms form a security boundary only when the application completes identity binding and authorization. Route shape, metadata propagation, query-schema validation, and HTTP cost guards are not substitutes for that policy.
