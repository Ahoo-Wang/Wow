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

package me.ahoo.wow.query

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryGatewayFixtures.AGGREGATE
import me.ahoo.wow.query.QueryGatewayFixtures.GatewayBackend
import me.ahoo.wow.query.QueryGatewayFixtures.SCHEMA
import me.ahoo.wow.query.QueryGatewayFixtures.errorObserver
import me.ahoo.wow.query.QueryGatewayFixtures.gateway
import me.ahoo.wow.query.QueryGatewayFixtures.snapshotNode
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.observe.QueryAudit
import me.ahoo.wow.query.observe.QueryLogObserver
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.observe.audits
import me.ahoo.wow.query.observe.fingerprintOf
import me.ahoo.wow.query.observe.started
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.TraceContext
import me.ahoo.wow.spi.observation.trace
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** What the gateway's observers hear of a query: its audit, its outcome, failures and cancellations, and the observation it runs in (D174). */
class QueryGatewayObservationTest {
    @Test
    fun `an auditing observer gets one audit per query without filter values`() {
        runTest {
            val audits = mutableListOf<QueryAudit>()
            val restricting = QueryPolicy { _, _ -> OwnerIdFilter("owner") }
            val permissive = QueryPolicy { _, _ -> MatchAllFilter }
            val gateway = gateway(
                GatewayBackend(rows = { flowOf(snapshotNode(), snapshotNode()) }),
                policies = listOf(permissive, restricting),
                observer = auditObserver { audits += it },
            )
            val query = ListQuery(EqualFilter(QueryField("aggregateId"), "secret-value"), limit = 10)
            val caller = QueryCaller(
                entry = QueryEntry.HTTP,
                scope = QueryScope(authenticated = TenantIdFilter("tenant"))
            )
            gateway.list(query, caller).toList().assert().hasSize(2)
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).assert()
                .isEqualTo(CountResult(0, exact = true))

            val (list, count) = audits
            list.aggregateType.assert().isEqualTo(AGGREGATE)
            list.queryType.assert().isEqualTo(QueryType.LIST)
            list.entry.assert().isEqualTo(QueryEntry.HTTP)
            list.model.assert().isEqualTo(QueryModel.SNAPSHOT)
            list.modelVersion.assert().isEqualTo(SCHEMA.version)
            list.scopeFields.assert().containsExactly("tenantId")
            list.policies.assert().containsExactly(restricting::class.java.name)
            list.rows.assert().isEqualTo(2)
            list.outcome.assert().isEqualTo(QueryAudit.Outcome.COMPLETE)
            list.errorCode.assert().isNull()
            list.caller.assert().isSameAs(caller)
            list.fingerprint.assert().isEqualTo(fingerprintOf(QueryType.LIST, query))
            (list.elapsed >= Duration.ZERO).assert().isTrue()
            list.toString().assert().doesNotContain("secret-value", "tenant\"")
            count.rows.assert().isEqualTo(1)
            count.entry.assert().isEqualTo(QueryEntry.UNSPECIFIED)

            // Scope fields come from each system filter's spec and each field predicate, not from its element predicate.
            val ids = OrFilter(listOf(IdsFilter(listOf("a")), EqualFilter(QueryField("state.region"), null)))
            val items = ElementMatchFilter(QueryField("state.items"), EqualFilter(QueryField("sku"), null))
            val scoped = QueryCaller(scope = QueryScope(declared = AndFilter(listOf(ids, items))))
            assertThrows<QuerySchemaValidationException> { gateway.count(CountQuery(MatchAllFilter), scoped) }
            audits.last().scopeFields.assert().containsExactly("aggregateId", "state.region", "state.items")
            audits.last().outcome.assert().isEqualTo(QueryAudit.Outcome.ERROR)
            audits.last().errorCode!!.assert().startsWith("${QueryErrors.INVALID}:")
        }
    }

    @Test
    fun `the route selection restricts the query but is not audited as scope`() {
        runTest {
            val audits = mutableListOf<QueryAudit>()
            val backend = GatewayBackend(row = ::snapshotNode)
            val caller = QueryCaller.IN_PROCESS.withSelection(AggregateIdFilter("aggregate"))
            gateway(backend, observer = auditObserver { audits += it }).single(SingleQuery(MatchAllFilter), caller)
                .assert().isNotNull()
            backend.filters.single().assert()
                .isEqualTo(AndFilter(listOf(AggregateIdFilter("aggregate"), DeletionFilter(DeletionState.ACTIVE))))
            audits.single().scopeFields.assert().isEmpty()
            audits.single().rows.assert().isEqualTo(1)
        }
    }

    @Test
    fun `the fingerprint groups equal shapes and a rejection is audited with its code`() {
        runTest {
            fun list(field: String, value: String) = ListQuery(EqualFilter(QueryField(field), value))
            fingerprintOf(QueryType.LIST, list("state.name", "a")).assert()
                .isEqualTo(fingerprintOf(QueryType.LIST, list("state.name", "b")))
                .isNotEqualTo(fingerprintOf(QueryType.LIST, list("state.other", "a")))
            val audits = mutableListOf<QueryAudit>()
            val gateway = gateway(
                GatewayBackend(),
                observer = auditObserver { audits += it },
                entryPolicy = QueryEntryPolicy(requireExplicitEntry = true),
            )
            assertThrows<QueryRequestException> { gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED) }
            audits.single().outcome.assert().isEqualTo(QueryAudit.Outcome.ERROR)
            audits.single().model.assert().isNull()
            audits.single().modelVersion.assert().isNull()
            audits.single().errorCode.assert()
                .isEqualTo("${QueryErrors.INVALID}:${QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED}")
        }
    }

    @Test
    fun `observer failure cannot replace original backend failure or mutate it`() {
        runTest {
            val original = IllegalStateException("backend")
            val observerFailure = AssertionError("observer")
            val gateway = gateway(
                GatewayBackend(row = { throw original }),
                observer = errorObserver { throw observerFailure },
            )
            assertThrows<QueryExecutionException> {
                gateway.single(
                    SingleQuery(MatchAllFilter),
                    QueryCaller.UNSPECIFIED
                )
            }
                .apply {
                    message.assert().isEqualTo("Query storage failed.")
                    generateSequence(cause) { it.cause }.last().assert().isSameAs(original)
                    suppressed.assert().isEmpty()
                }
            original.suppressed.assert().isEmpty()
        }
    }

    @Test
    fun `observer failure cannot replace successful completion`() {
        runTest {
            val observer = object : QueryObserver {
                override fun onComplete(aggregateType: AggregateType, queryType: QueryType) {
                    throw AssertionError("observer")
                }
            }
            gateway(GatewayBackend(row = ::snapshotNode), observer = observer)
                .single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).assert().isNotNull()
        }
    }

    @Test
    fun `empty backend results complete normally`() {
        runTest {
            val completed = mutableListOf<QueryType>()
            val observer = object : QueryObserver {
                override fun onComplete(aggregateType: AggregateType, queryType: QueryType) {
                    completed += queryType
                }
            }
            val gateway = gateway(GatewayBackend(), observer = observer)
            gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).assert().isNull()
            gateway.list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).toList().assert().isEmpty()
            completed.assert().isEqualTo(listOf(QueryType.SINGLE, QueryType.LIST))
        }
    }

    @Test
    fun `prepare rejection is observed and stops backend`() {
        runTest {
            val original = IllegalStateException("prepare")
            val errors = mutableListOf<Throwable>()
            val filter = object : QueryFilter {
                override suspend fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Q = throw original
            }
            val backend = GatewayBackend()
            assertThrows<IllegalStateException> {
                gateway(backend, filters = listOf(filter), observer = errorObserver { errors += it })
                    .single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            }.assert().isSameAs(original)
            backend.admitted.assert().isEmpty()
            errors.single().assert().isSameAs(original)
        }
    }

    @Test
    fun `a cancelled suspend query reports only its cancellation`() {
        runTest {
            val terminals = mutableListOf<String>()
            val audits = mutableListOf<QueryAudit>()
            val observer = terminalObserver(terminals) { audits += it }
            val gateway = gateway(GatewayBackend(row = { awaitCancellation() }), observer = observer)
            val call = launch { gateway.single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED) }
            runCurrent()
            call.cancel()
            call.join()
            terminals.assert().containsExactly("cancel")
            audits.single().outcome.assert().isEqualTo(QueryAudit.Outcome.CANCEL)
        }
    }

    @Test
    fun `stream observation follows terminal completion error and cancellation`() {
        runTest {
            val terminals = mutableListOf<String>()
            val audits = mutableListOf<QueryAudit>()
            val observer = terminalObserver(terminals) { audits += it }
            val original = IllegalStateException("late-error")
            fun rows(source: Flow<ObjectNode>) =
                gateway(GatewayBackend(rows = { source }), observer = observer)
                    .list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)

            rows(flowOf(snapshotNode())).collect { terminals.size.assert().isEqualTo(0) }
            assertThrows<QueryExecutionException> {
                rows(
                    flow {
                        emit(snapshotNode())
                        throw original
                    }
                ).collect { terminals.size.assert().isEqualTo(1) }
            }.let { generateSequence(it.cause) { cause -> cause.cause }.last().assert().isSameAs(original) }
            rows(
                flow {
                    emit(snapshotNode())
                    awaitCancellation()
                }
            ).take(1).collect { terminals.size.assert().isEqualTo(2) }
            terminals.assert().isEqualTo(listOf("complete", "error", "cancel"))
            audits.map { it.outcome }.assert().containsExactly(
                QueryAudit.Outcome.COMPLETE,
                QueryAudit.Outcome.ERROR,
                QueryAudit.Outcome.CANCEL,
            )
            audits.map { it.rows }.assert().containsExactly(1L, 1L, 1L)
            audits[1].errorCode.assert().isEqualTo(QueryErrors.FAILED)
        }
    }

    @Test
    fun `an aggregation is observed like a list`() {
        runTest {
            val terminals = mutableListOf<String>()
            val audits = mutableListOf<QueryAudit>()
            val gateway = gateway(
                GatewayBackend(groups = { flowOf(WowJson.mapper.createObjectNode().put("count", 3)) }),
                observer = terminalObserver(terminals) { audits += it },
            )
            val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
            gateway.aggregate(
                query,
                QueryCaller.UNSPECIFIED
            ).toList().single()["count"].intValue().assert().isEqualTo(3)
            terminals.assert().containsExactly("complete")
            audits.single().queryType.assert().isEqualTo(QueryType.AGGREGATION)
            audits.single().rows.assert().isEqualTo(1)
        }
    }

    @Test
    fun `cancellation while schema is loading is observed`() {
        runTest {
            val cancelled = AtomicInteger()
            val provider = object : QueryModelSchemaProvider {
                override suspend fun schema(): QueryModelSchema = awaitCancellation()
                override suspend fun refresh(): QueryModelSchema = schema()
            }
            val observer = object : QueryObserver {
                override fun onCancel(aggregateType: AggregateType, queryType: QueryType) {
                    cancelled.incrementAndGet()
                }
            }
            val call = launch {
                gateway(GatewayBackend(), provider, observer = observer)
                    .single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            }
            runCurrent()
            call.cancel()
            call.join()
            cancelled.get().assert().isOne()
        }
    }

    private fun auditObserver(record: (QueryAudit) -> Unit) = object : QueryObserver {
        override val audits: Boolean = true
        override fun onAudit(audit: QueryAudit) = record(audit)
    }

    private fun terminalObserver(terminals: MutableList<String>, audit: (QueryAudit) -> Unit) =
        object : QueryObserver {
            override val audits: Boolean = true
            override fun onComplete(aggregateType: AggregateType, queryType: QueryType) {
                terminals += "complete"
            }

            override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) {
                terminals += "error"
            }

            override fun onCancel(aggregateType: AggregateType, queryType: QueryType) {
                terminals += "cancel"
            }

            override fun onAudit(audit: QueryAudit) = audit(audit)
        }

    /** The trace of an observed query, named after its observation. */
    private class Trace(name: String) : TraceContext() {
        override val fields: Map<String, String> = mapOf("traceparent" to name)
    }

    /** Records in [told] what the observation [name] is told; it runs in the trace [name]. */
    private class Told(private val name: String, private val told: MutableList<String>) : Observation {
        override val context: CoroutineContext = Trace(name)

        override fun outcome(outcome: ObservedOutcome) {
            told += "$name $outcome"
        }

        override fun failed(error: Throwable) {
            told += "$name failed"
        }

        override fun end() {
            told += "$name end"
        }
    }

    private fun startingObserver(name: String, told: MutableList<String>) = object : QueryObserver {
        override fun start(aggregateType: AggregateType, queryType: QueryType, model: QueryModel): Observation =
            Told("$name ${queryType.name}", told)
    }

    @Test
    fun `a query should run in its observation, which ends with it and hears its failure (D174)`() {
        runTest {
            val told = CopyOnWriteArrayList<String>()
            val traces = CopyOnWriteArrayList<Map<String, String>>()
            val backend = GatewayBackend(row = {
                traces += currentCoroutineContext().trace
                null
            })
            val observer = startingObserver("q", told)
            gateway(backend, observer = observer).single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
            traces.single().assert().isEqualTo(mapOf("traceparent" to "q SINGLE"))
            gateway(backend, observer = observer).list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).toList()
            val failing = GatewayBackend(rows = { flow { error("The store is down.") } })
            assertThrows<QueryExecutionException> {
                gateway(failing, observer = observer).list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).toList()
            }
            told.assert().containsExactly("q SINGLE end", "q LIST end", "q LIST failed", "q LIST end")
        }
    }

    /** An observation that throws when it is told the outcome, the failure or the end. */
    private class Throwing : Observation {
        override fun outcome(outcome: ObservedOutcome) = error("The meter is closed.")

        override fun failed(error: Throwable) = error("The tracer is closed.")

        override fun end() = error("The span is closed.")
    }

    @Test
    fun `an observation that throws should fail no query and keep no other observation from ending`() {
        runTest {
            val told = CopyOnWriteArrayList<String>()
            val throwing = object : QueryObserver {
                override fun start(aggregateType: AggregateType, queryType: QueryType, model: QueryModel): Observation =
                    Throwing()
            }
            val alone = QueryObserver.of(listOf(throwing))
            val beside = QueryObserver.of(listOf(throwing, startingObserver("q", told)))
            val rows = GatewayBackend(rows = { flowOf(snapshotNode()) }, row = { snapshotNode() })
            val failing = GatewayBackend(rows = { flow { error("The store is down.") } })
            for (observer in listOf(alone, beside)) {
                gateway(rows, observer = observer).single(SingleQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
                    .assert().isNotNull()
                gateway(rows, observer = observer).list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED).toList()
                    .assert().hasSize(1)
                assertThrows<QueryExecutionException> {
                    gateway(failing, observer = observer).list(ListQuery(MatchAllFilter), QueryCaller.UNSPECIFIED)
                        .toList()
                }
            }
            told.assert().containsExactly("q SINGLE end", "q LIST end", "q LIST failed", "q LIST end")
            val observation = alone.started(AGGREGATE, QueryType.COUNT, QueryModel.SNAPSHOT)
            observation.outcome(ObservedOutcome.OK)
            observation.failed(IllegalStateException("boom"))
            observation.end()
            val traced = QueryObserver.of(listOf(startingObserver("t", told)))
            val trace = traced.started(AGGREGATE, QueryType.LIST, QueryModel.SNAPSHOT).trace
            trace.assert().isEqualTo(mapOf("traceparent" to "t LIST"))
        }
    }

    @Test
    fun `observers together should each observe, and one that fails to start observe nothing (D174)`() {
        val told = CopyOnWriteArrayList<String>()
        val failing = object : QueryObserver {
            override fun start(
                aggregateType: AggregateType,
                queryType: QueryType,
                model: QueryModel
            ): Observation = error(
                "No tracer."
            )
        }
        QueryObserver.of(listOf(failing, QueryLogObserver())).start(AGGREGATE, QueryType.LIST, QueryModel.SNAPSHOT)
            .assert().isSameAs(Observation.NONE)
        QueryObserver.of(
            listOf(startingObserver("a", told), QueryLogObserver())
        ).start(AGGREGATE, QueryType.LIST, QueryModel.SNAPSHOT)
            .context.trace.assert().isEqualTo(mapOf("traceparent" to "a LIST"))
        val both = QueryObserver.of(listOf(startingObserver("a", told), startingObserver("b", told)))
            .start(AGGREGATE, QueryType.COUNT, QueryModel.SNAPSHOT)
        both.context.trace.assert().isEqualTo(mapOf("traceparent" to "a COUNT"))
        both.outcome(ObservedOutcome.OK)
        both.failed(IllegalStateException("boom"))
        both.end()
        told.assert().containsExactly(
            "a COUNT ok",
            "b COUNT ok",
            "a COUNT failed",
            "b COUNT failed",
            "a COUNT end",
            "b COUNT end",
        )
        QueryObserver.NONE.start(AGGREGATE, QueryType.LIST, QueryModel.SNAPSHOT).assert().isSameAs(Observation.NONE)
    }
}
