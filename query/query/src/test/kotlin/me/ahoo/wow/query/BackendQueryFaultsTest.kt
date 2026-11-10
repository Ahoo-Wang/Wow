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

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.backend.storageFault
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Duration

/** How every shape fails: rows that are not standard JSON, counts and storages that fail, timeouts and storage failures. */
internal class BackendQueryFaultsTest : BackendQueriesFixtures() {
    @Test
    fun `every shape rejects a backend row that is not standard JSON`() {
        runTest {
            val nan = WowJson.mapper.createObjectNode().put("name", "a").also {
                it.putObject("state").putArray("values").add(Double.NaN)
            }
            val infinite = WowJson.mapper.createObjectNode().put("name", "a").put("amount", Float.POSITIVE_INFINITY)
            val nonStandard = listOf(
                JsonNodeFactory.instance.pojoNode(Any()),
                JsonNodeFactory.instance.missingNode(),
                JsonNodeFactory.instance.binaryNode(byteArrayOf(1)),
            ).map { node ->
                WowJson.mapper.createObjectNode().also { it.putArray("nested").addObject().set("value", node) }
            }

            (listOf(nan, infinite) + nonStandard).forEach { row ->
                val backend = RecordingBackend(
                    pages = { BackendPage(listOf(row), 1, listOf(CursorPosition(listOf(1L)))) },
                    records = { flowOf(row) },
                )
                assertThrows<QueryExecutionException> {
                    backend.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema))
                }
                assertThrows<QueryExecutionException> {
                    backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
                }
                assertThrows<QueryExecutionException> {
                    backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema))
                }
                assertThrows<QueryExecutionException> {
                    backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), schema))
                }
            }
            assertThrows<QueryExecutionException> {
                RecordingBackend(records = { flowOf(nan) })
                    .list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
            }.message.assert().isEqualTo("Query result [state.values] must be finite.")
            assertThrows<QueryExecutionException> {
                RecordingBackend(pages = { BackendPage(listOf(nonStandard.first())) })
                    .single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema))
            }.message.assert().isEqualTo("Query result [nested.value] must be a standard JSON value.")
            // An empty property name renders as it always did: a name after an empty prefix starts the path again.
            val emptyKeys = WowJson.mapper.createObjectNode().also {
                it.putObject("").putObject("state").putObject("").put("value", Double.NaN)
            }
            assertThrows<QueryExecutionException> {
                RecordingBackend(records = { flowOf(emptyKeys) })
                    .list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
            }.message.assert().isEqualTo("Query result [state..value] must be finite.")
        }
    }

    @Test
    fun `count is a server fault when the storage fails or answers a negative number`() {
        runTest {
            suspend fun count(backend: RecordingBackend) =
                backend.countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema))

            count(RecordingBackend(counts = { 3 })).assert().isEqualTo(CountResult(3, exact = true))
            assertThrows<QueryExecutionException> { count(RecordingBackend(counts = { -1 })) }
                .message.assert().isEqualTo("Backend count must be greater than or equal to 0.")
            val driver = IllegalStateException("driver text with values")
            assertThrows<QueryExecutionException> { count(RecordingBackend(counts = { throw driver })) }.apply {
                message.assert().isEqualTo("Query storage failed.")
                cause.assert().isSameAs(driver)
                errorCode.assert().isEqualTo(QueryErrors.FAILED)
            }
        }
    }

    @Test
    fun `a storage that is unavailable is the retryable storage error, not a server fault`() {
        runTest {
            val unavailable = StorageUnavailableException("PostgreSQL is unavailable; retry later.")
            assertThrows<QueryStorageUnavailableException> {
                RecordingBackend(counts = { throw unavailable })
                    .countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema))
            }.apply {
                errorCode.assert().isEqualTo(QueryErrors.STORAGE_UNAVAILABLE)
                errorMsg.assert().isEqualTo("Query storage is unavailable; retry later.")
                generateSequence(cause) { it.cause }.last().assert().isSameAs(unavailable)
            }
            assertThrows<QueryStorageUnavailableException> {
                RecordingBackend(records = { throw unavailable })
                    .list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
            }.errorCode.assert().isEqualTo(QueryErrors.STORAGE_UNAVAILABLE)
        }
    }

    @Test
    fun `aggregation rows with a non-finite metric fail the query`() {
        runTest {
            val backend = RecordingBackend(groups = {
                flowOf(WowJson.mapper.createObjectNode().put("name", "a").put("total", Double.POSITIVE_INFINITY))
            })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("amount")),
                        "total"
                    )
                ),
            )
            assertThrows<QueryExecutionException> {
                backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
            }.message.assert().isEqualTo("Aggregation metric [total] must be finite.")
            val badGroup = RecordingBackend(groups = {
                flowOf(WowJson.mapper.createObjectNode().put("name", Double.NaN).put("total", 1))
            })
            assertThrows<QueryExecutionException> {
                badGroup.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
            }.message.assert().isEqualTo("Aggregation group [name] must be finite.")
        }
    }

    // D150: timeouts.

    @Test
    fun `every shape fails with the query timeout when the backend outlasts it`() {
        runTest {
            val timeout = Duration.ofMillis(100)
            val slow = RecordingBackend(
                pages = {
                    delay(SLOW)
                    BackendPage(listOf(row("a")), 1, listOf(CursorPosition(listOf(1L))))
                },
                records = { flow { delay(SLOW) } },
                groups = { flow { delay(SLOW) } },
                counts = {
                    delay(SLOW)
                    1
                },
            )
            val shapes = listOf<suspend () -> Any?>(
                {
                    slow.single(
                        QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema).withTimeout(timeout)
                    )
                },
                { slow.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema).withTimeout(timeout)) },
                {
                    slow.cursor(
                        QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), schema).withTimeout(timeout)
                    )
                },
                {
                    slow.countBounded(
                        QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema).withTimeout(timeout)
                    )
                },
                {
                    slow.list(
                        QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema).withTimeout(timeout)
                    ).toList()
                },
                {
                    val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
                    slow.aggregate(QueryAdmission.Trusted.aggregate(query, schema).withTimeout(timeout)).toList()
                },
            )
            shapes.forEach { shape ->
                assertThrows<QueryTimeoutException> { shape() }.apply {
                    this.timeout.assert().isEqualTo(timeout)
                    errorCode.assert().isEqualTo(QueryErrors.TIMEOUT)
                    errorMsg.assert().isEqualTo("Query exceeded its execution timeout of 100 ms.")
                }
            }
        }
    }

    @Test
    fun `a backend within the timeout answers as without one`() {
        runTest {
            val timeout = Duration.ofSeconds(1)
            val backend = RecordingBackend(
                pages = {
                    delay(10)
                    BackendPage(emptyList(), 0)
                },
                records = { flowOf(row("a")) },
                counts = { 2 },
            )
            backend.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema).withTimeout(timeout))
                .assert().isNull()
            backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema).withTimeout(timeout))
                .total.assert().isEqualTo(0)
            backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema).withTimeout(timeout)).toList()
                .assert().hasSize(1)
            backend.countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema).withTimeout(timeout))
                .assert().isEqualTo(CountResult(2, exact = true))
            val admitted = QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema)
            admitted.withTimeout(null).assert().isSameAs(admitted)
            admitted.withTimeout(timeout).timeout.assert().isEqualTo(timeout)
        }
    }

    @Test
    fun `an enclosing timeout of the caller stays the caller's cancellation`() {
        runTest {
            val timeout = Duration.ofSeconds(1)
            val slow = RecordingBackend(
                pages = {
                    delay(SLOW)
                    BackendPage(emptyList(), 0)
                },
                records = { flow { delay(SLOW) } },
            )
            assertThrows<TimeoutCancellationException> {
                withTimeout(50) {
                    slow.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema).withTimeout(timeout))
                }
            }
            assertThrows<TimeoutCancellationException> {
                withTimeout(50) {
                    slow.list(
                        QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema).withTimeout(timeout)
                    ).toList()
                }
            }
            assertThrows<TimeoutCancellationException> {
                withTimeout(50) { slow.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema)) }
            }
        }
    }

    // Storage failures.

    @Test
    fun `a storage failure of every shape is reported as the storage failure, its cause kept server-side`() {
        runTest {
            val driver = IllegalStateException("driver text with values")
            val failing = RecordingBackend(
                pages = { fail(driver) },
                records = { flow { fail(driver) } },
                groups = { flow { fail(driver) } },
                counts = { fail(driver) },
            )
            val timeout = Duration.ofSeconds(1)
            val shapes = listOf<suspend (Duration?) -> Any?>(
                { failing.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema).withTimeout(it)) },
                { failing.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema).withTimeout(it)) },
                { failing.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), schema).withTimeout(it)) },
                {
                    failing.countBounded(
                        QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema).withTimeout(it)
                    )
                },
                {
                    failing.list(
                        QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema).withTimeout(it)
                    ).toList()
                },
                {
                    val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
                    failing.aggregate(QueryAdmission.Trusted.aggregate(query, schema).withTimeout(it)).toList()
                },
            )
            listOf(null, timeout).forEach { limit ->
                shapes.forEach { shape ->
                    assertThrows<QueryExecutionException> { shape(limit) }.apply {
                        message.assert().isEqualTo("Query storage failed.")
                        // Stack trace recovery (assertions on) may copy the error across a timeout scope.
                        generateSequence(cause) { it.cause }.last().assert().isSameAs(driver)
                    }
                }
            }
        }
    }

    @Test
    fun `a query error a backend throws reaches the caller as it is`() {
        runTest {
            val rejection = QueryViolation.InvalidCursor.rejection()
            val failing = RecordingBackend(pages = { throw rejection }, records = { flow { throw rejection } })
            assertThrows<RuntimeException> {
                failing.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema))
            }.assert().isSameAs(rejection)
            assertThrows<RuntimeException> {
                failing.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
            }.assert().isSameAs(rejection)
            storageFault(rejection).assert().isSameAs(rejection)
            storageFault(IllegalStateException()).assert().isInstanceOf(QueryExecutionException::class.java)
        }
    }
}
