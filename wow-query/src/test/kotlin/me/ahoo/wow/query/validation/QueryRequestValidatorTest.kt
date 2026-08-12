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

package me.ahoo.wow.query.validation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPageSpec
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class QueryRequestValidatorTest {
    private val target = queryTarget("order")
    private val schema = QuerySchema(
        target,
        listOf(
            QueryFieldSchema.string(LogicalField("name"), nullable = false).copy(
                sortable = true,
                projectable = true
            ),
            QueryFieldSchema.string(LogicalField("secret"), nullable = false).copy(projectable = false),
            QueryFieldSchema.string(LogicalField("unsortable"), nullable = false),
            QueryFieldSchema(
                path = LogicalField("age"),
                valueKind = QueryFieldValueKind.INTEGER,
                nullable = false,
                sortable = true
            )
        )
    )
    private val validator = QueryRequestValidator(
        QueryStructureLimits(
            maxDepth = 4,
            maxNodes = 8,
            maxMembershipItems = 4,
            maxNativeParameterBytes = 32
        )
    )

    @Test
    fun `is cold and completes structural validation before evaluating schema resolver supplier`() {
        val resolverInvocations = AtomicInteger()
        val invalidRequest = request(
            PredicateExpression(LogicalField("name"), PortableOperator.EQ, emptyList())
        )
        val validation = validator.validate(invalidRequest) {
            resolverInvocations.incrementAndGet()
            Mono.just(schema)
        }

        resolverInvocations.get().assert().isZero()
        StepVerifier.create(validation)
            .expectErrorSatisfies { error ->
                (error as QueryException).stage.assert().isEqualTo(QueryStage.VALIDATION)
            }
            .verify()
        resolverInvocations.get().assert().isZero()

        val validRequest = request(MatchAll)
        val validValidation = validator.validate(validRequest) {
            resolverInvocations.incrementAndGet()
            Mono.just(schema)
        }
        resolverInvocations.get().assert().isZero()
        StepVerifier.create(validValidation)
            .expectNext(validRequest)
            .verifyComplete()
        resolverInvocations.get().assert().isOne()
    }

    @Test
    fun `requires schema target to equal request target`() {
        val otherSchema = QuerySchema(queryTarget("other"), schema.fields.values)
        assertInvalid {
            validator.validateSchema(request(MatchAll), otherSchema)
        }
    }

    @Test
    fun `validates projection and sort fields and their schema capabilities`() {
        listOf(
            QueryProjection.Include(setOf(LogicalField("name"))),
            QueryProjection.Exclude(setOf(LogicalField("name"))),
            QueryProjection.All
        ).forEach { projection ->
            validator.validateSchema(request(MatchAll, projection = projection), schema)
        }
        validator.validateSchema(
            request(MatchAll, sort = listOf(QuerySort(LogicalField("name"), QuerySortDirection.ASC))),
            schema
        )

        listOf(
            request(MatchAll, projection = QueryProjection.Include(setOf(LogicalField("missing")))),
            request(MatchAll, projection = QueryProjection.Exclude(setOf(LogicalField("secret")))),
            request(MatchAll, sort = listOf(QuerySort(LogicalField("missing"), QuerySortDirection.ASC))),
            request(MatchAll, sort = listOf(QuerySort(LogicalField("unsortable"), QuerySortDirection.DESC)))
        ).forEach { request -> assertInvalid { validator.validateSchema(request, schema) } }
    }

    @Test
    fun `validates dynamic result shape explicitly while retaining sort validation`() {
        val valid: SingleQueryRequest<DynamicDocument> = SingleQueryRequest(
            target = target,
            resultShape = QueryResultShape.Dynamic,
            sort = listOf(QuerySort(LogicalField("name"), QuerySortDirection.ASC))
        )
        val invalid: SingleQueryRequest<DynamicDocument> = SingleQueryRequest(
            target = target,
            resultShape = QueryResultShape.Dynamic,
            sort = listOf(QuerySort(LogicalField("unsortable"), QuerySortDirection.ASC))
        )

        validator.validateSchema(valid, schema).assert().isSameAs(valid)
        assertInvalid { validator.validateSchema(invalid, schema) }
        valid.resultShape.assert().isInstanceOf(QueryResultShape.Dynamic::class.java)
    }

    @Test
    fun `accepts list limit zero while retaining a finite merged result budget`() {
        val request = ListQueryRequest(
            target = target,
            resultShape = QueryResultShape.Typed(String::class.java),
            limit = 0,
            budget = QueryBudgetHint(maxResults = 25)
        )

        validator.validateSchema(request, schema).assert().isSameAs(request)
        QueryBudgetLimit.min(
            request.budget,
            QueryBudgetLimit(maxResults = 100),
            QueryBudgetLimit.UNBOUNDED,
            QueryBudgetLimit(maxResults = 50)
        ).maxResults.assert().isEqualTo(25)
    }

    @Test
    fun `merges timeout result and cost dimensions independently across all sources`() {
        val request = QueryBudgetHint(
            timeout = Duration.ofSeconds(40),
            maxResults = null,
            maxCost = 10
        )
        val system = QueryBudgetLimit(
            timeout = Duration.ofSeconds(30),
            maxResults = 100,
            maxCost = null
        )
        val policy = QueryBudgetLimit(
            timeout = null,
            maxResults = 0,
            maxCost = 8
        )
        val backend = QueryBudgetLimit(
            timeout = Duration.ofSeconds(20),
            maxResults = 50,
            maxCost = 9
        )

        val merged = QueryBudgetLimit.min(request, system, policy, backend)

        merged.assert().isEqualTo(
            QueryBudgetLimit(
                timeout = Duration.ofSeconds(20),
                maxResults = 0,
                maxCost = 8
            )
        )
        request.assert().isEqualTo(QueryBudgetHint(Duration.ofSeconds(40), null, 10))
        system.assert().isEqualTo(QueryBudgetLimit(Duration.ofSeconds(30), 100, null))
        policy.assert().isEqualTo(QueryBudgetLimit(null, 0, 8))
        backend.assert().isEqualTo(QueryBudgetLimit(Duration.ofSeconds(20), 50, 9))
    }

    @Test
    fun `treats null and explicit unbounded sources as no additional limit`() {
        QueryBudgetLimit.min(
            QueryBudgetHint(),
            QueryBudgetLimit.UNBOUNDED,
            QueryBudgetLimit.UNBOUNDED,
            QueryBudgetLimit.UNBOUNDED
        ).assert().isEqualTo(QueryBudgetLimit.UNBOUNDED)

        listOf(0, 1, 2, 3).forEach { finiteSource ->
            val timeouts = MutableList<Duration?>(4) { null }.apply {
                this[finiteSource] = Duration.ofSeconds(1)
            }
            val results = MutableList<Long?>(4) { null }.apply { this[finiteSource] = 2 }
            val costs = MutableList<Long?>(4) { null }.apply { this[finiteSource] = 3 }
            QueryBudgetLimit.min(
                QueryBudgetHint(timeouts[0], results[0], costs[0]),
                QueryBudgetLimit(timeouts[1], results[1], costs[1]),
                QueryBudgetLimit(timeouts[2], results[2], costs[2]),
                QueryBudgetLimit(timeouts[3], results[3], costs[3])
            ).assert().isEqualTo(QueryBudgetLimit(Duration.ofSeconds(1), 2, 3))
        }
    }

    @Test
    fun `merges every independent timeout results and cost source combination`() {
        repeat(4) { timeoutSource ->
            repeat(4) { resultsSource ->
                repeat(4) { costSource ->
                    val timeouts = MutableList<Duration?>(4) { null }.apply {
                        this[timeoutSource] = Duration.ofSeconds(7)
                    }
                    val results = MutableList<Long?>(4) { null }.apply { this[resultsSource] = 11 }
                    val costs = MutableList<Long?>(4) { null }.apply { this[costSource] = 13 }

                    QueryBudgetLimit.min(
                        QueryBudgetHint(timeouts[0], results[0], costs[0]),
                        QueryBudgetLimit(timeouts[1], results[1], costs[1]),
                        QueryBudgetLimit(timeouts[2], results[2], costs[2]),
                        QueryBudgetLimit(timeouts[3], results[3], costs[3])
                    ).assert().isEqualTo(
                        QueryBudgetLimit(Duration.ofSeconds(7), 11, 13)
                    )
                }
            }
        }
    }

    @Test
    fun `preserves finite zero as the strictest budget in every dimension`() {
        QueryBudgetLimit.min(
            QueryBudgetHint(Duration.ZERO, 0, 0),
            QueryBudgetLimit(Duration.ofSeconds(1), 1, 1),
            QueryBudgetLimit.UNBOUNDED,
            QueryBudgetLimit.UNBOUNDED
        ).assert().isEqualTo(QueryBudgetLimit(Duration.ZERO, 0, 0))
    }

    @Test
    fun `value models reject negative page size limit and budget without unsafe construction`() {
        assertAll(
            { assertThrows<IllegalArgumentException> { QueryPageSpec(index = 0, size = 1) } },
            { assertThrows<IllegalArgumentException> { QueryPageSpec(index = 1, size = 0) } },
            {
                assertThrows<IllegalArgumentException> {
                    ListQueryRequest(
                        target = target,
                        resultShape = QueryResultShape.Typed(String::class.java),
                        limit = -1
                    )
                }
            },
            { assertThrows<IllegalArgumentException> { QueryBudgetHint(timeout = Duration.ofNanos(-1)) } },
            { assertThrows<IllegalArgumentException> { QueryBudgetHint(maxResults = -1) } },
            { assertThrows<IllegalArgumentException> { QueryBudgetHint(maxCost = -1) } },
            { assertThrows<IllegalArgumentException> { QueryBudgetLimit(timeout = Duration.ofNanos(-1)) } },
            { assertThrows<IllegalArgumentException> { QueryBudgetLimit(maxResults = -1) } },
            { assertThrows<IllegalArgumentException> { QueryBudgetLimit(maxCost = -1) } }
        )

        PageQueryRequest(
            target = target,
            resultShape = QueryResultShape.Typed(String::class.java),
            page = QueryPageSpec(index = 1, size = 1)
        ).page.assert().isEqualTo(QueryPageSpec(1, 1))
    }

    @Test
    fun `validation errors never expose request field value native or schema details`() {
        val sensitiveField = "sensitiveField"
        val sensitiveValue = "sensitiveValue"
        val error = assertThrows<QueryException> {
            validator.validateSchema(
                request(
                    PredicateExpression(
                        LogicalField(sensitiveField),
                        PortableOperator.EQ,
                        listOf(QueryValue.StringValue(sensitiveValue))
                    )
                ),
                schema
            )
        }

        error.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
        error.stage.assert().isEqualTo(QueryStage.VALIDATION)
        error.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
        error.message.orEmpty().assert().doesNotContain(sensitiveField, sensitiveValue, "native", "schema")
    }

    private fun request(
        expression: me.ahoo.wow.api.query.expression.QueryExpression,
        projection: QueryProjection = QueryProjection.All,
        sort: List<QuerySort> = emptyList()
    ): SingleQueryRequest<String> = SingleQueryRequest(
        target = target,
        expression = expression,
        resultShape = QueryResultShape.Typed(String::class.java, projection),
        sort = sort
    )

    private fun queryTarget(aggregateName: String): QueryTarget = QueryTarget(
        object : NamedAggregate {
            override val contextName: String = "sales"
            override val aggregateName: String = aggregateName
        },
        QueryDocumentKind.SNAPSHOT
    )

    private fun assertInvalid(action: () -> Unit) {
        val error = assertThrows<QueryException>(action)
        error.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
        error.stage.assert().isEqualTo(QueryStage.VALIDATION)
        error.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
    }
}
