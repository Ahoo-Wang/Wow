---
title: Share State through CoCache
description: Read another context's aggregates from a shared CoCache cache as their generated views, kept by the context that owns them, with nothing to declare.
outline: deep
---

# Share State through CoCache

A Saga, a name lookup or an internal computation often needs another context's aggregate, by its id, many times. `wow-cocache` keeps those states in a shared [CoCache](https://cocache.ahoo.me/) cache, a Redis L1 with a local L2 in each service:

- The context that **owns** the aggregate writes each state into the cache after its own commit, in version order.
- Every **reader** injects `StateViewCache<V>` of the generated view and reads it. There is no cache to declare, no subscription to write and no copy per reader.

A service that cannot reach the shared Redis reads the same views with the [typed peer read](../reading-state), `queries.snapshots<V>()`: the cache is an acceleration of that read, never a different answer.

## Add the modules

<!-- sample: fragment -->

```kotlin
dependencies {
    // The domain: the cache's interface, with no dependency of its own.
    implementation("me.ahoo.wow:wow-cocache-api")
}
```

<!-- sample: fragment -->

```kotlin
dependencies {
    // The server: the caches through CoCache, CoCache's starter and its Redis backend.
    implementation("me.ahoo.wow:wow-cocache")
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
    implementation("me.ahoo.cocache:cocache-redis")
}
```

`wow-cocache-api` holds `StateKey` and `StateViewCache` and nothing else, so a domain module that uses them pulls in neither CoCache nor Redis. `wow-cocache` turns itself on when the application runs CoCache, that is, when CoCache's starter has a backend (`cocache.backend: redis`, with Spring's `spring.data.redis.*`; `memory` for one JVM). It runs on CoCache 8 (`cocache-core`; its suspending views are in the core, so there is no `cocache-coroutines`).

## Read a state

The reader injects the cache of the view the owner's api module generates, `<State>View`, and reads it by the aggregate's tenant and id:

<!-- sample: fragment -->

```kotlin
@Saga
class PaymentGatewaySaga(
    private val gateway: PaymentGateway,
    private val orders: StateViewCache<OrderStateView>,
) {
    @OnEvent
    suspend fun onPaymentRequested(event: StateTypedEvent<PaymentState, PaymentRequested>): CommandRequest<*> {
        val order = orders.get(event.ref.tenantId, event.ref.id)
        // ...
    }
}
```

A state the cache does not hold, whose owner cannot be read now (unreachable, no route to it from this node, a timeout) or whose cache backend fails, is a `StateViewUnavailableException`, naming the keys. Nothing else is: a defect in the reading code, or an owner's state that does not decode as the reader's view, is thrown as it is. A state in the cache that does not decode as the reader's view is read from the owner instead, and the cache keeps the owner's state at its version.

Whether to fall back on it is the reader's call. A read for the reader's own use, a name or a description, may fall back. A read a decision rests on must not: it fails, and the handler retries and then parks as any failure does. The eShop's payment reads the order it charges to say what the charge is for, its own use, so a payment never waits on the ordering for it. It catches exactly the unavailable state, which the cache logs once per outage, and charges by the order's id:

<!-- sample: examples/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentGatewaySaga.kt#read-order -->

```kotlin
/**
 * The order [orderId] as the ordering's shared cache gives it; null when the cache cannot serve it now (the
 * ordering does not share its orders here, or a miss cannot reach the ordering service). The charge's description
 * is the saga's own use of the order, so a payment never waits on the ordering for it. Only an unavailable order
 * falls back, which the cache itself logs: an order that does not decode, or a defect, fails the handling as any
 * error does.
 */
private suspend fun orderOf(tenantId: String, orderId: String): OrderStateView? = try {
    orders.get(tenantId, orderId)
} catch (_: StateViewUnavailableException) {
    null
}
```

- `get(key)` and `get(tenantId, id)` answer the view, or `null` when the aggregate does not exist or is deleted.
- `getAll(keys)` answers the views of the aggregates that exist: one read of the cache, and one query of the owner per tenant for those it misses, their ids in one `IN`.

Everything about the cache follows from the view. Its name is `wow.<context>.<aggregate>`, the aggregate the view's `@StateView` names; its key is the aggregate's tenant and id; its value is the view's JSON. The owner and every reader derive the same, so nothing is configured to make them meet. Each view the contracts on the class path name gets a `StateViewCache<V>` bean. Outside Spring, `StateViewCaches.of<V>()` gives the same cache, created by CoCache's `CoCacheFactory`:

<!-- sample: fragment -->

```kotlin
val caches = StateViewCaches(CoCacheFactory(runtime)) { queryApi }
val orders: StateViewCache<OrderStateView> = caches.of()
```

In a Spring application the caches are created by the `CoCacheFactory` bean of CoCache's starter, so CoCache's settings apply to them; a factory on the runtime alone, as above, applies none.

A view the cache does not hold is read from the owner through the node's typed queries: in process when the node serves the aggregate, else over HTTP on the owner's internal port as this service ([Call Other Services](../calling-services)). An aggregate that was never created is cached as absent for a while (CoCache's negative cache).

## Write the states: the owner

The owning service lists the aggregate types whose states it shares:

```yaml
wow:
  cocache:
    publish:
      - eshop-ordering.order
```

Each listed type is written by a sink subscription of its own, `cocache-sink.<aggregate>`, on the owner's state stream. It checkpoints, retries and parks like any subscription ([Handle Failures](../failures)):

- A commit writes the aggregate's state after it, at the commit's version, and only over an older version. A commit delivered again, a replay, or an old owner beside the new one never takes the cache back.
- A deleting commit writes a tombstone at its version, which reads as absent and which no older state undoes.
- A reader's load of a missing state holds CoCache's lease on the key, and the owner's write takes it over: a load that read an older state never puts it back.

A state no one reads pays no write: an aggregate type that is not listed is not written. A listed type must be one of the service's own, with a view in its api's contract; otherwise the service does not start, and the error names `wow.cocache.publish`.

## Lifetimes and local copies

- **How long a state lives in Redis:** `wow.cocache.ttl` on the owner, for the states and tombstones it writes. When it is not set, the cache's own lifetime applies: CoCache's `cocache.caches.[wow.<context>.<aggregate>].ttl`, else `cocache.defaults.cache.ttl`, else CoCache's default of one hour.
- **What a reader fills:** a state a reader loads on a miss lives by the reader's `cocache.caches.[wow.<context>.<aggregate>].ttl`. Give both sides the same lifetime. Freshness comes from the owner's writes, not from the lifetime.
- **Local copies and negative caching** are each reader's own: `cocache.caches.[wow.<context>.<aggregate>].*` (`local-maximum-size`, `local-max-age`, `absent-ttl`), as CoCache documents them. CoCache's `cocache.defaults.cache.*` fills what neither the cache's own settings nor Wow's declaration set, and `cocache.overrides.cache.*` only tightens. Every duration has its unit (`30m`, `10s`).
- **No early refresh by default:** the caches declare `refresh-window: off`. The owner writes every version after its commit, so a refresh would only read the owner again, over HTTP on a reader; CoCache's global `cocache.defaults.cache.refresh-window` does not reach these caches. `cocache.caches.[wow.<context>.<aggregate>].refresh-window` turns refresh-ahead on for one cache, which then reads the owner before a copy expires.

```yaml
cocache:
  backend: redis
  caches:
    "[wow.eshop-ordering.order]":
      ttl: 30m
      local-max-age: 10s
```

## Drop a state from the cache

A state the cache must give up before it expires is dropped with CoCache's actuator endpoint on a node of any service that runs the cache, its key the aggregate's tenant and id. The endpoint is exposed only where `management.endpoints.web.exposure.include` lists `cocache`; keep it to operators.

```bash
curl -X DELETE "http://<node>/actuator/cocache/wow.eshop-ordering.order/<tenantId>/<id>"
```

- **The default is CoCache's `evict`, which keeps the version.** Every copy is dropped and the next read loads the state from the owner, but no state older than the dropped one is written back: a commit delivered again, or a replay, still cannot take the cache back.
- **`?purge=true` drops the version too**, so any version may be written again, an older one included. It needs `management.endpoint.cocache.allow-purge: true` and is meant for content that must go entirely, such as a key the owner no longer writes. Prefer `evict`.

The owner's next commit of the aggregate writes the state again either way. A state view is derived: the cache rebuilds from the owner, and nothing is lost by dropping it.

## A trusted read

The cache holds the full view as the service's identity sees it, for every tenant and owner. It is a read for trusted code: a Saga, a name in a message, an internal computation. Never return it to an end user as it is: a user reads the owner's routes, which apply the user's scope.

The cache is not where a decision is made. A command decides on its own aggregate's state; a check that must be consistent with another aggregate belongs to that aggregate, which rejects the command it cannot accept ([Rejections](../rejections)).

## What it costs

- The owner and its readers share one Redis. The cache's name, its keys and its value, the view's JSON, are a contract between services. The view's compatibility is the contract's: a field the owner adds breaks no reader, and the compatibility gate checks the rest ([The Manifest and Compatibility](../manifest)).
- Each shared state costs the owner one Redis write per commit.
- CoCache's own meters and health report the caches ([Observability](../../operate/observability)).
