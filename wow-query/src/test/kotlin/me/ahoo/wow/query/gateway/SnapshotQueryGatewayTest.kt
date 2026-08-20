/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.query.gateway

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.MatchAll
import me.ahoo.wow.api.query.MatchNone
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryPage
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.api.query.QueryScope
import me.ahoo.wow.api.query.RelativeTimeExpression
import me.ahoo.wow.api.query.RelativeTimeOperator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryRouter
import me.ahoo.wow.query.backend.SecuredQuery
import me.ahoo.wow.query.policy.QueryAuthority
import me.ahoo.wow.query.policy.QueryAuthorization
import me.ahoo.wow.query.policy.QueryContexts
import me.ahoo.wow.query.policy.QueryDecision
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.result.QueryResultPolicy
import me.ahoo.wow.query.result.canonicalSnapshot
import me.ahoo.wow.query.schema.JacksonQuerySchemaProvider
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QuerySchemaProvider
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import reactor.test.StepVerifier
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SnapshotQueryGatewayTest {
    private val metadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()

    @Test
    fun `should keep publisher cold and isolate ObjectNode ownership`() {
        val subscriptions = AtomicInteger()
        val backendRecord = record()
        val backend = backend(
            stream = Flux.defer {
                subscriptions.incrementAndGet()
                Flux.just(backendRecord)
            }
        )
        val gateway = gateway(backend)
        val publisher = gateway.firstRecord()

        val first = publisher.block()!!
        (first["state"] as ObjectNode).put("data", "changed")
        val second = publisher.block()!!

        subscriptions.get().assert().isEqualTo(2)
        (backendRecord["state"] as ObjectNode)["data"].asString().assert().isEqualTo("data")
        (second["state"] as ObjectNode)["data"].asString().assert().isEqualTo("data")
    }

    @Test
    fun `should isolate the subscribed projection from caller mutation`() {
        val source = Sinks.one<ObjectNode>()
        val fields = linkedSetOf(LogicalField("aggregateId"))
        val query = Query(projection = QueryProjection.Include(fields))
        val publisher = gateway(backend(stream = source.asMono().flux())).firstRecord(query)

        StepVerifier.create(publisher)
            .then {
                fields.clear()
                fields += LogicalField("state.data")
                source.tryEmitValue(record())
            }
            .assertNext { result ->
                result.propertyNames().asSequence().toList().assert().isEqualTo(listOf("aggregateId"))
            }
            .verifyComplete()
    }

    @Test
    fun `should apply policy before router and backend`() {
        val routed = AtomicBoolean()
        val policy = QueryPolicy { Mono.just(QueryAuthorization(decision = QueryDecision.DENY)) }
        val factory = SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter {
                routed.set(true)
                backend()
            },
            objectMapper = JsonSerializer,
            policies = listOf(policy)
        )

        StepVerifier.create(factory.create(metadata).firstRecord())
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.POLICY_DENIED
            }
            .verify()
        routed.get().assert().isFalse()
    }

    @Test
    fun `should revalidate a decorated schema`() {
        val jackson = JacksonQuerySchemaProvider(JsonSerializer)
        val invalid = QuerySchemaProvider { target ->
            val schema = jackson.getSchema(target)
            schema.copy(fields = schema.fields - LogicalField("aggregateId"))
        }
        val factory = SnapshotQueryGatewayFactory.create(
            schemaProvider = invalid,
            router = QueryRouter { backend() },
            objectMapper = JsonSerializer
        )

        assertThrows<IllegalArgumentException> { factory.create(metadata) }
    }

    @Test
    fun `should only allow scope to narrow trusted authority`() {
        val gateway = gateway(backend(stream = Flux.just(record())))
        val query = Query(scope = QueryScope(tenantId = "tenant-1"))

        StepVerifier.create(gateway.firstRecord(query))
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.POLICY_DENIED
            }
            .verify()

        StepVerifier.create(
            gateway.firstRecord(query)
                .contextWrite(QueryContexts.withAuthority(QueryAuthority(tenantId = "tenant-1")))
        )
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `should treat an empty space allowlist as no access`() {
        val captured = AtomicReference<SecuredQuery>()
        val backend = object : QueryBackend {
            override val id: String = "test"

            override fun validate(query: SecuredQuery) {
                captured.set(query)
            }

            override fun stream(query: SecuredQuery): Flux<ObjectNode> = Flux.empty()

            override fun page(query: SecuredQuery): Mono<QueryPage<ObjectNode>> = Mono.just(QueryPage(emptyList(), 0))

            override fun count(query: SecuredQuery): Mono<Long> = Mono.just(0)
        }

        gateway(backend).firstRecord()
            .contextWrite(QueryContexts.withAuthority(QueryAuthority(subjectId = "user", spaceIds = emptySet())))
            .test()
            .verifyComplete()

        val filter = captured.get().filter as LogicalExpression
        filter.operands.assert().contains(MatchNone)
    }

    @Test
    fun `should authorize count using only caller requested fields`() {
        val aggregateId = LogicalField("aggregateId")
        val policy = QueryPolicy {
            Mono.just(
                QueryAuthorization(
                    decision = QueryDecision.GRANT,
                    fieldAccess = QueryFieldAccess.Restricted(setOf(aggregateId))
                )
            )
        }
        val factory = SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter { backend(count = Mono.just(1)) },
            objectMapper = JsonSerializer,
            policies = listOf(policy)
        )
        val filter = PredicateExpression(
            aggregateId,
            PredicateOperator.EQ,
            listOf(JsonNodeFactory.instance.textNode("aggregate-1"))
        )

        StepVerifier.create(factory.create(metadata).count(filter))
            .expectNext(1)
            .verifyComplete()
    }

    @Test
    fun `should not let a parent projection bypass child field access`() {
        val policy = QueryPolicy {
            Mono.just(
                QueryAuthorization(
                    decision = QueryDecision.GRANT,
                    fieldAccess = QueryFieldAccess.Restricted(setOf(LogicalField("state")))
                )
            )
        }
        val factory = SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter { backend() },
            objectMapper = JsonSerializer,
            policies = listOf(policy)
        )

        StepVerifier.create(
            factory.create(metadata).firstRecord(
                Query(projection = QueryProjection.Include(setOf(LogicalField("state"))))
            )
        )
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.POLICY_DENIED
            }
            .verify()
    }

    @Test
    fun `should reject literal type mismatch before routing`() {
        val routed = AtomicBoolean()
        val gateway = SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter {
                routed.set(true)
                backend()
            },
            objectMapper = JsonSerializer
        ).create(metadata)
        val query = Query(
            filter = PredicateExpression(
                LogicalField("state.data"),
                PredicateOperator.EQ,
                listOf(JsonNodeFactory.instance.numberNode(1))
            )
        )

        StepVerifier.create(gateway.firstRecord(query))
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.INVALID_QUERY
            }
            .verify()
        routed.get().assert().isFalse()
    }

    @Test
    fun `should reject excessive expression depth before routing`() {
        val routed = AtomicBoolean()
        val gateway = SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter {
                routed.set(true)
                backend()
            },
            objectMapper = JsonSerializer
        ).create(metadata)
        var expression: QueryExpression = MatchAll
        repeat(129) {
            expression = LogicalExpression(LogicalOperator.AND, listOf(expression))
        }

        StepVerifier.create(gateway.firstRecord(Query(filter = expression)))
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.INVALID_QUERY
            }
            .verify()
        routed.get().assert().isFalse()
    }

    @Test
    fun `should include preparation time in the absolute deadline`() {
        val subscribedAt = Instant.parse("2026-08-20T00:00:00Z")
        val clock = mockk<Clock>()
        every { clock.instant() } returnsMany listOf(subscribedAt, subscribedAt.plusSeconds(2))
        val routed = AtomicBoolean()
        val gateway = SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter {
                routed.set(true)
                backend()
            },
            objectMapper = JsonSerializer,
            clock = clock
        ).create(metadata)

        gateway.firstRecord(Query(budget = QueryBudget(timeout = Duration.ofSeconds(1))))
            .test()
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.DEADLINE_EXCEEDED
            }
            .verify()

        routed.get().assert().isFalse()
    }

    @Test
    fun `should lower every relative time operator before routing`() {
        val captured = mutableListOf<SecuredQuery>()
        val fixedClock = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC)
        val gateway = SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter { backend(count = Mono.just(0), onValidate = captured::add) },
            objectMapper = JsonSerializer,
            clock = fixedClock,
            zoneId = ZoneOffset.UTC
        ).create(metadata)
        val field = LogicalField("eventTime")
        val seconds = JsonNodeFactory.instance.numberNode(8 * 60 * 60)
        val days = JsonNodeFactory.instance.numberNode(7)
        val expressions = listOf(
            RelativeTimeExpression(field, RelativeTimeOperator.TODAY),
            RelativeTimeExpression(field, RelativeTimeOperator.BEFORE_TODAY, listOf(seconds)),
            RelativeTimeExpression(field, RelativeTimeOperator.TOMORROW, zoneId = "Asia/Shanghai"),
            RelativeTimeExpression(field, RelativeTimeOperator.THIS_WEEK),
            RelativeTimeExpression(field, RelativeTimeOperator.NEXT_WEEK),
            RelativeTimeExpression(field, RelativeTimeOperator.LAST_WEEK),
            RelativeTimeExpression(field, RelativeTimeOperator.THIS_MONTH),
            RelativeTimeExpression(field, RelativeTimeOperator.LAST_MONTH),
            RelativeTimeExpression(field, RelativeTimeOperator.RECENT_DAYS, listOf(days)),
            RelativeTimeExpression(field, RelativeTimeOperator.EARLIER_DAYS, listOf(days))
        )

        expressions.forEach { expression ->
            gateway.count(expression).test().expectNext(0).verifyComplete()
        }

        captured.assert().hasSize(expressions.size)
        captured.any { it.filter.containsRelativeTime() }.assert().isFalse()
    }

    @Test
    fun `should reject invalid relative time operands`() {
        val gateway = gateway(backend())
        val field = LogicalField("eventTime")
        val expressions = listOf(
            RelativeTimeExpression(
                field,
                RelativeTimeOperator.RECENT_DAYS,
                listOf(JsonNodeFactory.instance.numberNode(0))
            ),
            RelativeTimeExpression(
                field,
                RelativeTimeOperator.BEFORE_TODAY,
                listOf(JsonNodeFactory.instance.numberNode(24 * 60 * 60))
            )
        )

        expressions.forEach { expression ->
            gateway.count(expression).test()
                .expectErrorMatches { error ->
                    error is QueryException && error.code == QueryErrorCode.INVALID_QUERY
                }
                .verify()
        }
    }

    @Test
    fun `should treat local temporal values as encoded strings`() {
        val temporalState = mockk<StateAggregateMetadata<TemporalState>> {
            every { aggregateType } returns TemporalState::class.java
        }
        val temporalMetadata = mockk<AggregateMetadata<Any, TemporalState>> {
            every { state } returns temporalState
        }
        val schema = JacksonQuerySchemaProvider(JsonSerializer).getSchema(
            temporalMetadata
        )

        val date = schema[LogicalField("state.date")]!!
        date.valueKind.assert().isEqualTo(QueryValueKind.STRING)
        date.fullText.assert().isFalse()
    }

    @Test
    fun `should keep recursive and map collection state opaque`() {
        val recursiveState = mockk<StateAggregateMetadata<RecursiveState>> {
            every { aggregateType } returns RecursiveState::class.java
        }
        val recursiveMetadata = mockk<AggregateMetadata<Any, RecursiveState>> {
            every { state } returns recursiveState
        }
        val schema = JacksonQuerySchemaProvider(JsonSerializer).getSchema(recursiveMetadata)

        schema[LogicalField("state.child")]!!.valueKind.assert().isEqualTo(QueryValueKind.MAP)
        schema[LogicalField("state.children")]!!.also { field ->
            field.valueKind.assert().isEqualTo(QueryValueKind.MAP)
            field.collectionKind.assert().isEqualTo(QueryCollectionKind.OBJECT)
            field.queryable.assert().isFalse()
        }
        val source = record()
        (source["state"] as ObjectNode).apply {
            remove("data")
            set("child", JsonNodeFactory.instance.objectNode().put("id", "child"))
            putArray("children").addObject().put("id", "nested")
            putArray("attributes").addObject().put("key", "value")
        }
        canonicalSnapshot(source, schema)
    }

    @Test
    fun `should materialize typed snapshot through the shared materializer`() {
        val gateway = gateway(backend(stream = Flux.just(record())))

        StepVerifier.create(gateway.first())
            .assertNext { snapshot ->
                snapshot.aggregateId.assert().isEqualTo("aggregate-1")
                snapshot.state.id.assert().isEqualTo("aggregate-1")
                snapshot.state.data.assert().isEqualTo("data")
                snapshot.eventTime.assert().isEqualTo(Instant.parse("2026-08-19T00:00:00Z").toEpochMilli())
            }
            .verifyComplete()
    }

    @Test
    fun `should apply result policies in order and protect canonical identity`() {
        val first = QueryResultPolicy { _, record ->
            (record["state"] as ObjectNode).put("data", "first")
            record
        }
        val second = QueryResultPolicy { _, record ->
            check((record["state"] as ObjectNode)["data"].asString() == "first")
            (record["state"] as ObjectNode).put("data", "second")
            record
        }
        val ordered = gateway(backend(stream = Flux.just(record())), listOf(first, second))

        StepVerifier.create(ordered.firstRecord())
            .assertNext { result ->
                (result["state"] as ObjectNode)["data"].asString().assert().isEqualTo("second")
            }
            .verifyComplete()

        val corrupting = QueryResultPolicy { _, result -> result.put("aggregateId", "other") }
        val protected = gateway(backend(stream = Flux.just(record())), listOf(corrupting))
        StepVerifier.create(protected.firstRecord())
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.RESULT_INVALID
            }
            .verify()
    }

    @Test
    fun `should reject records outside the logical schema`() {
        val unknown = record().also { (it["state"] as ObjectNode).put("secret", "value") }

        StepVerifier.create(gateway(backend(stream = Flux.just(unknown))).firstRecord())
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.RESULT_INVALID
            }
            .verify()
    }

    @Test
    fun `should not expose backend error details`() {
        val gateway = gateway(backend(stream = Flux.error(IllegalStateException("secret-query-value"))))

        StepVerifier.create(gateway.firstRecord())
            .expectErrorMatches { error ->
                error is QueryException &&
                    error.code == QueryErrorCode.BACKEND_FAILURE &&
                    !error.message.orEmpty().contains("secret-query-value") &&
                    error.cause == null
            }
            .verify()
    }

    @Test
    fun `should propagate downstream cancellation`() {
        val subscribed = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val gateway = gateway(
            backend(
                stream = Flux.never<ObjectNode>()
                    .doOnSubscribe { subscribed.set(true) }
                    .doOnCancel { cancelled.set(true) }
            )
        )

        StepVerifier.create(gateway.streamRecords())
            .thenAwait(Duration.ofMillis(10))
            .thenCancel()
            .verify()

        subscribed.get().assert().isTrue()
        cancelled.get().assert().isTrue()
    }

    @Test
    fun `should cancel and report incomplete result after budget is exceeded`() {
        val cancelled = AtomicBoolean()
        val gateway = gateway(
            backend(
                stream = Flux.range(0, 3)
                    .map { record().put("aggregateId", "aggregate-$it") }
                    .doOnCancel { cancelled.set(true) }
            )
        )

        StepVerifier.create(gateway.streamRecords(Query(budget = QueryBudget(maxRecords = 1))))
            .expectNextCount(1)
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.INCOMPLETE_RESULT &&
                    (error.cause as? QueryException)?.code == QueryErrorCode.BUDGET_EXCEEDED
            }
            .verify()
        cancelled.get().assert().isTrue()
    }

    private fun gateway(
        backend: QueryBackend,
        resultPolicies: List<QueryResultPolicy> = emptyList()
    ): SnapshotQueryGateway<MockStateAggregate> =
        SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter { backend },
            objectMapper = JsonSerializer,
            resultPolicies = resultPolicies
        ).create(metadata)

    private fun backend(
        stream: Flux<ObjectNode> = Flux.empty(),
        page: Mono<QueryPage<ObjectNode>> = Mono.just(QueryPage(emptyList(), 0)),
        count: Mono<Long> = Mono.just(0),
        onValidate: (SecuredQuery) -> Unit = {}
    ): QueryBackend = object : QueryBackend {
        override val id: String = "test"

        override fun validate(query: SecuredQuery) = onValidate(query)

        override fun stream(query: SecuredQuery): Flux<ObjectNode> = stream

        override fun page(query: SecuredQuery): Mono<QueryPage<ObjectNode>> = page

        override fun count(query: SecuredQuery): Mono<Long> = count
    }

    private fun QueryExpression.containsRelativeTime(): Boolean = when (this) {
        is RelativeTimeExpression -> true
        is LogicalExpression -> operands.any { it.containsRelativeTime() }
        is ElementMatchExpression -> predicate.containsRelativeTime()
        else -> false
    }

    private fun record(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        put("contextName", metadata.contextName)
        put("aggregateName", metadata.aggregateName)
        put("tenantId", "tenant-1")
        put("ownerId", "owner-1")
        put("spaceId", "space-1")
        put("version", 1)
        put("aggregateId", "aggregate-1")
        put("eventId", "event-1")
        put("firstOperator", "operator-1")
        put("operator", "operator-1")
        put("firstEventTime", "2026-08-19T00:00:00Z")
        put("eventTime", "2026-08-19T00:00:00Z")
        put("snapshotTime", "2026-08-19T00:00:00Z")
        set("tags", JsonNodeFactory.instance.objectNode())
        put("deleted", false)
        set(
            "state",
            JsonNodeFactory.instance.objectNode().apply {
                put("id", "aggregate-1")
                put("data", "data")
            }
        )
    }
}

private data class TemporalState(val id: String) {
    val date: LocalDate = LocalDate.of(2026, 8, 20)
}

private data class RecursiveState(
    val id: String,
    val child: RecursiveState? = null,
    val children: List<RecursiveState> = emptyList(),
    val attributes: List<Map<String, String>> = emptyList()
)
