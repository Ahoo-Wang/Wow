# WebFlux Streaming Aggregate Tracing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the remaining full-history `collectList()` from WebFlux aggregate tracing by introducing a streaming replay unit.

**Architecture:** `TracingPolicy` will parse request options into a lightweight internal `TracingRequest`. `AggregateTracingReplay` will convert `Flux<DomainEventStream>` into `Flux<StateEvent<ObjectNode>>` by sourcing one state aggregate per subscription, emitting full and explicit ranges as events arrive, and buffering only the last N output states for tail-limit requests. `AggregateTracingHandlerFunction` remains a thin adapter from request context to event store Flux to response strategy.

**Tech Stack:** Kotlin 2.3, Spring WebFlux functional routing, Reactor `Flux`/`Mono`/`Sinks`, Reactor Test, JUnit Jupiter, FluentAssert.

---

## Scope Check

This plan touches one subsystem: WebFlux aggregate tracing replay. It does not change HTTP route metadata, storage contracts, route module wiring, response strategy, or benchmark report files.

## File Structure

- Create `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingReplay.kt`
  - Owns streaming replay from domain event streams to JSON snapshot state events.
  - Contains the ring-buffer tail-limit logic.
  - Contains the reusable `DomainEventStream.toStateEvent(...)` conversion currently private to `AggregateTracingHandlerFunction`.
- Create `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingReplayTest.kt`
  - Tests replay behavior directly, including streaming before upstream completion and bounded tail windows.
- Modify `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicy.kt`
  - Adds internal `TracingRequest`.
  - Adds `fun request(request: ServerRequest): TracingRequest`.
  - Keeps `fun range(request: ServerRequest, totalVersion: Int): TracingRange` compatible by delegating to parsed request options.
- Modify `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicyTest.kt`
  - Tests parsed request options and compatibility of the existing `range` behavior.
- Modify `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt`
  - Removes handler-level `collectList()`.
  - Delegates replay to `AggregateTracingReplay`.
  - Keeps existing list-based helper APIs for benchmark and test compatibility.
- Modify `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunctionTest.kt`
  - Keeps current handler behavior tests.
  - Adds a guard that aggregate tracing handler output still uses streaming response semantics.

---

### Task 1: Parse Tracing Request Options

**Files:**
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicyTest.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicy.kt`

- [ ] **Step 1: Write failing tests for parsed request options**

Add these tests to `TracingPolicyTest`:

```kotlin
@Test
fun `should parse tracing request options`() {
    val tracingRequest = TracingPolicy().request(
        request(
            "headVersion" to "10",
            "tailVersion" to "20",
            "limit" to "5",
        )
    )

    tracingRequest.headVersion.assert().isEqualTo(10)
    tracingRequest.emitHeadVersion.assert().isEqualTo(10)
    tracingRequest.tailVersion.assert().isEqualTo(20)
    tracingRequest.limit.assert().isEqualTo(5)
    tracingRequest.hasLimit.assert().isTrue()
}

@Test
fun `should parse default tracing request options`() {
    val tracingRequest = TracingPolicy().request(request())

    tracingRequest.headVersion.assert().isNull()
    tracingRequest.emitHeadVersion.assert().isOne()
    tracingRequest.tailVersion.assert().isNull()
    tracingRequest.limit.assert().isNull()
    tracingRequest.hasLimit.assert().isFalse()
}

@Test
fun `should parse zero limit as empty tail window`() {
    val tracingRequest = TracingPolicy().request(request("limit" to "0"))

    tracingRequest.limit.assert().isZero()
    tracingRequest.hasLimit.assert().isTrue()
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.policy.TracingPolicyTest"
```

Expected: FAIL with unresolved `request(...)`, `TracingRequest`, or `emitHeadVersion` members.

- [ ] **Step 3: Implement `TracingRequest` and delegate `range`**

Update `TracingPolicy.kt` to this shape:

```kotlin
internal data class TracingRequest(
    val headVersion: Int?,
    val tailVersion: Int?,
    val limit: Int?
) {
    val emitHeadVersion: Int
        get() = headVersion ?: TracingPolicy.DEFAULT_HEAD_VERSION

    val hasLimit: Boolean
        get() = limit != null

    fun toRange(totalVersion: Int): TracingRange {
        val effectiveTailVersion = if (totalVersion <= TracingPolicy.EMPTY_TAIL_VERSION) {
            TracingPolicy.EMPTY_TAIL_VERSION
        } else {
            tailVersion?.coerceAtMost(totalVersion) ?: totalVersion
        }
        val effectiveEmitHeadVersion = limit?.let {
            val limitedHeadVersion = effectiveTailVersion - it + 1
            maxOf(emitHeadVersion, limitedHeadVersion)
        } ?: emitHeadVersion

        return TracingRange(
            replayHeadVersion = TracingPolicy.DEFAULT_HEAD_VERSION,
            emitHeadVersion = effectiveEmitHeadVersion,
            tailVersion = effectiveTailVersion,
        )
    }
}

class TracingPolicy {

    fun range(request: ServerRequest, totalVersion: Int): TracingRange {
        return request(request).toRange(totalVersion)
    }

    fun request(request: ServerRequest): TracingRequest {
        val headVersion = request.queryInt(HEAD_VERSION)
        val tailVersion = request.queryInt(TAIL_VERSION)
        val limit = request.queryInt(LIMIT)

        headVersion?.let {
            require(it > 0) {
                "$HEAD_VERSION must be greater than 0."
            }
        }
        limit?.let {
            require(it >= 0) {
                "$LIMIT must be greater than or equal to 0."
            }
        }

        val emitHeadVersion = headVersion ?: DEFAULT_HEAD_VERSION
        tailVersion?.let {
            require(it >= emitHeadVersion) {
                "$TAIL_VERSION must be greater than or equal to $HEAD_VERSION."
            }
        }

        return TracingRequest(
            headVersion = headVersion,
            tailVersion = tailVersion,
            limit = limit,
        )
    }

    private fun ServerRequest.queryInt(name: String): Int? {
        return queryParam(name)
            .map {
                require(it.isNotBlank()) {
                    "$name must not be blank."
                }
                it.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer.")
            }
            .orElse(null)
    }

    companion object {
        const val HEAD_VERSION: String = "headVersion"
        const val TAIL_VERSION: String = "tailVersion"
        const val LIMIT: String = "limit"
        const val DEFAULT_HEAD_VERSION: Int = 1
        const val EMPTY_TAIL_VERSION: Int = 0
    }
}
```

- [ ] **Step 4: Run tests and verify GREEN**

Run:

```bash
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.policy.TracingPolicyTest"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicy.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicyTest.kt
git commit -m "refactor(webflux): parse tracing request options"
```

---

### Task 2: Add Streaming Aggregate Tracing Replay

**Files:**
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingReplayTest.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingReplay.kt`

- [ ] **Step 1: Write failing replay tests**

Create `AggregateTracingReplayTest.kt` with these tests and helpers:

```kotlin
package me.ahoo.wow.webflux.route.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import me.ahoo.wow.webflux.route.policy.TracingRequest
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

class AggregateTracingReplayTest {

    @Test
    fun `full history replay should emit before source completes`() {
        val sink = Sinks.many().unicast().onBackpressureBuffer<DomainEventStream>()

        AggregateTracingReplay.trace(
            stateAggregateMetadata = CART_AGGREGATE_METADATA.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = sink.asFlux(),
            tracingRequest = TracingRequest(headVersion = null, tailVersion = null, limit = null),
        ).test()
            .then {
                sink.tryEmitNext(cartEventStreams(eventCount = 1).single())
            }
            .consumeNextWith {
                it.state.assertJsonState().itemProductIds().assert().isEqualTo(listOf("product-1"))
            }
            .thenCancel()
            .verify()
    }

    @Test
    fun `explicit range replay should emit requested window with sourced prefix`() {
        AggregateTracingReplay.trace(
            stateAggregateMetadata = CART_AGGREGATE_METADATA.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = Flux.fromIterable(cartEventStreams(eventCount = 4)),
            tracingRequest = TracingRequest(headVersion = 2, tailVersion = 3, limit = null),
        ).collectList()
            .test()
            .consumeNextWith { tracedStates ->
                tracedStates.assert().hasSize(2)
                tracedStates[0].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2"))
                tracedStates[1].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2", "product-3"))
            }
            .verifyComplete()
    }

    @Test
    fun `tail limit replay should emit only bounded tail states`() {
        AggregateTracingReplay.trace(
            stateAggregateMetadata = CART_AGGREGATE_METADATA.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = Flux.fromIterable(cartEventStreams(eventCount = 4)),
            tracingRequest = TracingRequest(headVersion = null, tailVersion = null, limit = 2),
        ).collectList()
            .test()
            .consumeNextWith { tracedStates ->
                tracedStates.assert().hasSize(2)
                tracedStates[0].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2", "product-3"))
                tracedStates[1].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2", "product-3", "product-4"))
            }
            .verifyComplete()
    }

    @Test
    fun `zero tail limit replay should emit empty history`() {
        AggregateTracingReplay.trace(
            stateAggregateMetadata = CART_AGGREGATE_METADATA.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = Flux.fromIterable(cartEventStreams(eventCount = 2)),
            tracingRequest = TracingRequest(headVersion = null, tailVersion = null, limit = 0),
        ).test()
            .verifyComplete()
    }

    private companion object {
        val CART_AGGREGATE_METADATA = aggregateMetadata<Cart, CartState>()

        fun cartEventStreams(eventCount: Int): List<DomainEventStream> {
            val aggregateId = CART_AGGREGATE_METADATA.aggregateId("streaming-trace-cart")
            val upstream = GivenInitializationCommand(aggregateId)
            return (1..eventCount).map { version ->
                CartItemAdded(CartItem(productId = "product-$version", quantity = version))
                    .toDomainEventStream(
                        upstream = upstream,
                        aggregateVersion = version - 1,
                    )
            }
        }

        fun Any.assertJsonState(): ObjectNode {
            this.assert().isInstanceOf(ObjectNode::class.java)
            return this as ObjectNode
        }

        fun ObjectNode.itemProductIds(): List<String> {
            val items = this["items"]
            return (0 until items.size()).map { index ->
                items[index]["productId"].asString()
            }
        }
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.state.AggregateTracingReplayTest"
```

Expected: FAIL with unresolved `AggregateTracingReplay`.

- [ ] **Step 3: Implement streaming replay**

Create `AggregateTracingReplay.kt`:

```kotlin
package me.ahoo.wow.webflux.route.state

import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.webflux.route.policy.TracingRequest
import reactor.core.publisher.Flux
import tools.jackson.databind.node.ObjectNode
import java.util.ArrayDeque

internal object AggregateTracingReplay {

    fun <S : Any> trace(
        stateAggregateMetadata: StateAggregateMetadata<S>,
        stateAggregateFactory: StateAggregateFactory,
        eventStreams: Flux<DomainEventStream>,
        tracingRequest: TracingRequest
    ): Flux<StateEvent<ObjectNode>> {
        if (tracingRequest.hasLimit) {
            return traceTailLimit(
                stateAggregateMetadata = stateAggregateMetadata,
                stateAggregateFactory = stateAggregateFactory,
                eventStreams = eventStreams,
                tracingRequest = tracingRequest,
            )
        }
        return traceStreamingRange(
            stateAggregateMetadata = stateAggregateMetadata,
            stateAggregateFactory = stateAggregateFactory,
            eventStreams = eventStreams,
            tracingRequest = tracingRequest,
        )
    }

    private fun <S : Any> traceStreamingRange(
        stateAggregateMetadata: StateAggregateMetadata<S>,
        stateAggregateFactory: StateAggregateFactory,
        eventStreams: Flux<DomainEventStream>,
        tracingRequest: TracingRequest
    ): Flux<StateEvent<ObjectNode>> {
        return Flux.defer {
            val replayState = ReplayState(stateAggregateMetadata, stateAggregateFactory)
            eventStreams
                .takeUntilTail(tracingRequest.tailVersion)
                .handle<StateEvent<ObjectNode>> { eventStream, sink ->
                    val stateEvent = replayState.source(eventStream)
                    if (eventStream.version >= tracingRequest.emitHeadVersion) {
                        sink.next(stateEvent)
                    }
                }
        }
    }

    private fun <S : Any> traceTailLimit(
        stateAggregateMetadata: StateAggregateMetadata<S>,
        stateAggregateFactory: StateAggregateFactory,
        eventStreams: Flux<DomainEventStream>,
        tracingRequest: TracingRequest
    ): Flux<StateEvent<ObjectNode>> {
        val limit = tracingRequest.limit ?: return Flux.empty()
        if (limit == 0) {
            return eventStreams.takeUntilTail(tracingRequest.tailVersion).thenMany(Flux.empty())
        }
        return Flux.defer {
            val replayState = ReplayState(stateAggregateMetadata, stateAggregateFactory)
            val tailBuffer = ArrayDeque<StateEvent<ObjectNode>>(limit)
            eventStreams
                .takeUntilTail(tracingRequest.tailVersion)
                .doOnNext { eventStream ->
                    val stateEvent = replayState.source(eventStream)
                    if (eventStream.version >= tracingRequest.emitHeadVersion) {
                        if (tailBuffer.size == limit) {
                            tailBuffer.removeFirst()
                        }
                        tailBuffer.addLast(stateEvent)
                    }
                }
                .thenMany(Flux.defer { Flux.fromIterable(tailBuffer) })
        }
    }

    private fun Flux<DomainEventStream>.takeUntilTail(tailVersion: Int?): Flux<DomainEventStream> {
        return tailVersion?.let { tail ->
            takeWhile { it.version <= tail }
        } ?: this
    }

    private class ReplayState<S : Any>(
        private val stateAggregateMetadata: StateAggregateMetadata<S>,
        private val stateAggregateFactory: StateAggregateFactory
    ) {
        private var stateAggregate: StateAggregate<S>? = null

        fun source(eventStream: DomainEventStream): StateEvent<ObjectNode> {
            val aggregate = stateAggregate ?: stateAggregateFactory
                .create(stateAggregateMetadata, eventStream.aggregateId)
                .also {
                    stateAggregate = it
                }
            aggregate.onSourcing(eventStream)
            return eventStream.toStateEvent(aggregate)
        }
    }

    internal fun <S : Any> DomainEventStream.toStateEvent(
        stateAggregate: StateAggregate<S>
    ): StateEvent<ObjectNode> {
        return toStateEvent(
            state = stateAggregate.state.toJsonNode<ObjectNode>(),
            firstOperator = stateAggregate.firstOperator,
            firstEventTime = stateAggregate.firstEventTime,
            tags = stateAggregate.tags,
            deleted = stateAggregate.deleted,
        )
    }
}
```

- [ ] **Step 4: Run tests and verify GREEN**

Run:

```bash
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.state.AggregateTracingReplayTest"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingReplay.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingReplayTest.kt
git commit -m "feat(webflux): stream aggregate tracing replay"
```

---

### Task 3: Migrate Aggregate Tracing Handler

**Files:**
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunctionTest.kt`

- [ ] **Step 1: Add handler response semantics guard**

Add this test to `AggregateTracingHandlerFunctionTest`:

```kotlin
@Test
fun `handler tracing response should remain streaming server response`() {
    val eventStore = InMemoryEventStore()
    val aggregateId = generateGlobalId()
    aggregateVerifier<MockCommandAggregate, MockStateAggregate>(eventStore = eventStore)
        .whenCommand(MockCreateAggregate(id = aggregateId, data = "test-data"))
        .expectNoError()
        .expectEventType(MockAggregateCreated::class.java)
        .verify()
    val handlerFunction = AggregateTracingHandlerFunctionFactory(
        ConstructorStateAggregateFactory,
        eventStore,
        DefaultRequestExceptionHandler
    ).create(
        AggregateTracingRouteSpec(
            MOCK_AGGREGATE_METADATA,
            aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
            componentContext = OpenAPIComponentContext.default()
        )
    )

    val request = MockServerRequest.builder()
        .pathVariable(MessageRecords.ID, aggregateId)
        .pathVariable(MessageRecords.TENANT_ID, TenantId.DEFAULT_TENANT_ID)
        .build()

    handlerFunction.handle(request)
        .test()
        .consumeNextWith {
            it::class.java.name.assert().contains("StreamingJsonArrayResponse")
        }
        .verifyComplete()
}
```

- [ ] **Step 2: Run handler tests before implementation**

Run:

```bash
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunctionTest"
```

Expected: PASS. This is a guard for response semantics before removing `collectList()`; the behavior already exists.

- [ ] **Step 3: Migrate handler to replay unit**

Replace `AggregateTracingHandlerFunction.handle` with:

```kotlin
override fun handle(request: ServerRequest): Mono<ServerResponse> {
    val context = WowWebRequestContext.of(request, aggregateMetadata)
    val tracingRequest = tracingPolicy.request(request)
    return eventStore
        .load(
            aggregateId = context.aggregateId,
            tailVersion = tracingRequest.tailVersion ?: DEFAULT_TAIL_VERSION,
        )
        .let { eventStreams ->
            AggregateTracingReplay.trace(
                stateAggregateMetadata = aggregateMetadata.state,
                stateAggregateFactory = stateAggregateFactory,
                eventStreams = eventStreams,
                tracingRequest = tracingRequest,
            )
        }.toServerResponse(request, exceptionHandler)
}
```

Add the missing import:

```kotlin
import me.ahoo.wow.eventsourcing.EventStore.Companion.DEFAULT_TAIL_VERSION
```

Use the imported constant in the handler:

```kotlin
tailVersion = tracingRequest.tailVersion ?: DEFAULT_TAIL_VERSION
```

- [ ] **Step 4: Move the windowed list helper onto replay implementation without `collectList()`**

Update the windowed `AggregateTracingHandlerFunction.Companion.trace(...)` helper so it delegates to `AggregateTracingReplay`. Keep the non-windowed list helper imperative. Neither helper may call `collectList()` in production source.

Use this code for the windowed helper:

```kotlin
fun <S : Any> StateAggregateMetadata<S>.trace(
    stateAggregateFactory: StateAggregateFactory,
    eventStreams: List<DomainEventStream>,
    emitHeadVersion: Int,
    tailVersion: Int
): Flux<StateEvent<ObjectNode>> {
    require(emitHeadVersion > 0) {
        "emitHeadVersion must be greater than 0."
    }
    require(tailVersion >= 0) {
        "tailVersion must be greater than or equal to 0."
    }
    if (eventStreams.isEmpty() || tailVersion < emitHeadVersion) {
        return Flux.empty()
    }
    return AggregateTracingReplay.trace(
        stateAggregateMetadata = this,
        stateAggregateFactory = stateAggregateFactory,
        eventStreams = Flux.fromIterable(eventStreams),
        tracingRequest = TracingRequest(
            headVersion = emitHeadVersion,
            tailVersion = tailVersion,
            limit = null,
        ),
    )
}
```

Add import:

```kotlin
import me.ahoo.wow.webflux.route.policy.TracingRequest
```

- [ ] **Step 5: Run aggregate tracing tests**

Run:

```bash
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.state.*AggregateTracing*"
```

Expected: PASS.

- [ ] **Step 6: Verify `collectList()` no longer appears in production WebFlux source**

Run:

```bash
rg -n "collectList\\(" wow-webflux/src/main/kotlin
```

Expected: no output.

- [ ] **Step 7: Commit Task 3**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunctionTest.kt
git commit -m "refactor(webflux): use streaming aggregate tracing replay"
```

---

### Task 4: Final Verification And PR Update

**Files:**
- Verify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingReplay.kt`
- Verify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt`
- Verify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicy.kt`

- [ ] **Step 1: Run focused tests**

```bash
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.state.*AggregateTracing*"
./gradlew --console=plain :wow-webflux:test --tests "me.ahoo.wow.webflux.route.policy.TracingPolicyTest"
```

Expected: both commands PASS.

- [ ] **Step 2: Run WebFlux module tests**

```bash
./gradlew --console=plain :wow-webflux:test
```

Expected: PASS.

- [ ] **Step 3: Run benchmark source compilation**

```bash
./gradlew --console=plain :wow-benchmarks:compileJmhKotlin
```

Expected: PASS.

- [ ] **Step 4: Run source scans**

```bash
rg -n "collectList\\(" wow-webflux/src/main/kotlin
rg -n "Schedulers\\.boundedElastic\\(" wow-webflux/src/main/kotlin
```

Expected:
- `collectList` scan has no output.
- `boundedElastic` scan has no output.

- [ ] **Step 5: Push branch and update PR**

```bash
git status --short --branch
git push
```

Expected:
- working tree clean before push;
- branch pushed to `origin/codex/webflux-architecture-optimization`;
- PR #2700 updates with the new commits.
