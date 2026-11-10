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

package me.ahoo.wow.query.tck

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.querySchema
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration

/**
 * The query backend contract (the 9.x `SnapshotQueryBackendSpec` of wow-tck, ported to the 10.x snapshot
 * model): every snapshot query backend passes it. A backend's test extends it, stores the snapshots each test gives in
 * [store], and supplies its binding of an aggregate type in [binding].
 *
 * Each test runs on its own aggregate type (a fresh context), so a backend that keeps a table or a collection per
 * context isolates the tests. The schema is compiled as the Catalog compiles it: the snapshot system fields, the
 * order state [DECLARATION] and the backend's storage facts. Queries are admitted through [QueryAdmission.Trusted]
 * (no caller scope, no default deletion scope) and run through the core's query shapes (`BackendQueries.kt`), except
 * the gateway tests, which run the whole admission.
 *
 * Features a storage may decline (keyset pages, unbounded streams, distinct counts, percentiles, FIRST / LAST, root
 * groups, temporal aggregation) are assumed only where its facts grant them; a declined feature skips its test.
 *
 * The cases come in families, each a parent class of this one ([SnapshotQueryFixtures] lists them); this class holds
 * the timeouts and the gateway cases.
 */
abstract class QueryBackendSpec : SnapshotDateBucketCases() {
    // Timeouts

    @Test
    fun `an expired timeout fails every primitive with the timeout error`() = blocking {
        val fixture = given(orders())
        val expired = Duration.ofNanos(1)
        suspend fun fails(block: suspend () -> Any?) {
            val error = runCatching { block() }.exceptionOrNull()
            (error is QueryTimeoutException).assert().describedAs("$error").isTrue()
            (error as QueryTimeoutException).errorCode.assert().isEqualTo(QueryErrors.TIMEOUT)
        }
        fails {
            fixture.backend.list(
                QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), fixture.schema).withTimeout(expired)
            ).toList()
        }
        fails {
            fixture.backend.paged(
                QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), fixture.schema).withTimeout(expired)
            )
        }
        fails {
            fixture.backend.countBounded(
                QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), fixture.schema).withTimeout(expired)
            )
        }
        fails {
            val aggregation = AggregationQuery(MatchAllFilter, metrics = listOf(AggregationMetric.Count("count")))
            fixture.backend.aggregate(
                QueryAdmission.Trusted.aggregate(aggregation, fixture.schema).withTimeout(expired)
            ).toList()
        }
    }

    @Test
    fun `a query within its timeout runs as without one`() = blocking {
        val fixture = given(orders())
        val admitted: AdmittedQuery<me.ahoo.wow.api.query.CountQuery> =
            QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), fixture.schema).withTimeout(GENEROUS)
        admitted.timeout.assert().isEqualTo(GENEROUS)
        fixture.backend.countBounded(admitted).assert().isEqualTo(CountResult(5, exact = true))
        Unit
    }

    // The gateway over the backend

    @Test
    fun `the gateway admits snapshot queries with the active default scope`() = blocking {
        val fixture = given(orders())
        val gateway = DefaultQueryGateway(type, fixture.backend, FixedQueryModelSchemaProvider(fixture.schema))
        gateway.list(ListQuery(MatchAllFilter, sort = listOf(Sort(AMOUNT, Sort.Direction.ASC))), QueryCaller.IN_PROCESS)
            .toList().map { it.id() }.assert().containsExactly("o1", "o2", "o3", "o5")
        gateway.count(
            CountQuery(MatchAllFilter),
            QueryCaller.IN_PROCESS
        ).assert().isEqualTo(CountResult(4, exact = true))
        gateway.single(SingleQuery(AggregateIdFilter("o4")), QueryCaller.IN_PROCESS).assert().isNull()
        Unit
    }

    @Test
    fun `the gateway bounds counts and totals by the entry's budget`() = blocking {
        val fixture = given(orders())
        val policy = QueryEntryPolicy(
            http = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 2, timeout = GENEROUS.toKotlinDuration())
        )
        val gateway = DefaultQueryGateway(
            type,
            fixture.backend,
            FixedQueryModelSchemaProvider(fixture.schema),
            entryPolicy = policy
        )
        val http = QueryCaller(entry = QueryEntry.HTTP)
        gateway.count(CountQuery(MatchAllFilter), http).assert().isEqualTo(CountResult(2, exact = false))
        gateway.paged(PagedQuery(MatchAllFilter, pagination = Pagination(1, 1)), http).let { page ->
            page.list.assert().hasSize(1)
            page.total.assert().isEqualTo(2L)
            page.exact.assert().isFalse()
        }
        val exact = runCatching {
            gateway.paged(
                PagedQuery(MatchAllFilter, total = TotalMode.EXACT),
                http
            )
        }.exceptionOrNull()
        (exact as QueryRequestException).code.assert().isEqualTo(
            me.ahoo.wow.api.query.QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED
        )
        Unit
    }

    companion object {
        internal const val CONTEXT_SUFFIX = 12
        internal const val MAX_LIST = 100
        const val TENANT: String = "tenant"
        const val OTHER_TENANT: String = "other-tenant"

        /** 2026-01-05T00:00:00Z, a Monday. */
        val T0: Long = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
        const val DAY: Long = 86_400_000L
        internal val AMOUNT = QueryField("state.amount")
        internal val KEYWORDS = QueryField("state.keywords")
        private val GENEROUS: Duration = Duration.ofSeconds(30)

        /** The state field each semantic shape's probes are stored in. */
        val PROBE_FIELDS: Map<SemanticShape, String> = mapOf(
            SemanticShape.STRING to "state.probeText",
            SemanticShape.NUMBER to "state.probeNumber",
            SemanticShape.STRING_ARRAY to "state.probeTags",
        )

        fun probeId(probe: SemanticProbe): String = "semantic-${probe.shape.name.lowercase()}-${probe.name}"

        /** The fields of the order state the TCK queries, as the Manifest would declare them. */
        val DECLARATION = querySchema {
            field("state.name") { types(QueryValueType.STRING) }
            field("state.status") { types(QueryValueType.STRING) }
            field("state.amount") { types(QueryValueType.DECIMAL) }
            field("state.quantity") { types(QueryValueType.INTEGER) }
            field("state.createdAt") {
                types(QueryValueType.INTEGER)
                temporalEpoch()
            }
            field("state.keywords") {
                items {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            }
            field("state.lines") {
                items {
                    kind(QueryValueKind.OBJECT)
                    property("sku") { types(QueryValueType.STRING) }
                    property("price") { types(QueryValueType.DECIMAL) }
                    property("quantity") { types(QueryValueType.INTEGER) }
                }
            }
            field("state.grid") {
                items {
                    items {
                        kind(QueryValueKind.OBJECT)
                        property("city") { types(QueryValueType.STRING) }
                    }
                }
            }
            field("state.probeText") { types(QueryValueType.STRING) }
            field("state.probeNumber") { types(QueryValueType.INTEGER) }
            field("state.probeTags") { items { types(QueryValueType.STRING) } }
            // The access attributes the Manifest of an aggregate types: one single, one multiple.
            field("access.region") { types(QueryValueType.STRING) }
            field("access.teams") { items { types(QueryValueType.STRING) } }
        }

        internal fun ObjectNode.id(): String = path("aggregateId").stringValue()
    }
}
