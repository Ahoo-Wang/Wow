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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.databind.node.JsonNodeFactory

class QuerySchemaValidationModeTest {
    @Test
    fun `validation modes should accept only their documented levels`() {
        QuerySchemaValidationMode.COMPATIBLE.accepts(QueryCompatibilityLevel.EXACT).assert().isTrue()
        QuerySchemaValidationMode.COMPATIBLE.accepts(QueryCompatibilityLevel.COMPATIBLE).assert().isTrue()
        QuerySchemaValidationMode.COMPATIBLE.accepts(QueryCompatibilityLevel.INCOMPATIBLE).assert().isFalse()
        QuerySchemaValidationMode.STRICT.accepts(QueryCompatibilityLevel.EXACT).assert().isTrue()
        QuerySchemaValidationMode.STRICT.accepts(QueryCompatibilityLevel.COMPATIBLE).assert().isFalse()
        QuerySchemaValidationMode.STRICT.accepts(QueryCompatibilityLevel.INCOMPATIBLE).assert().isFalse()
    }

    @Test
    fun `rejected compatibility should use the query schema validation exception contract`() {
        val exception = assertThrows<QuerySchemaValidationException> {
            QuerySchemaResolution(Unit, QueryCompatibilityLevel.COMPATIBLE)
                .requireAccepted(QuerySchemaValidationMode.STRICT)
        }

        exception.errorCode.assert().isEqualTo("QuerySchemaValidation")
    }

    @Test
    fun `provider should resolve single list paged and filter requests without losing request fields`() {
        val provider = FixedProvider(Mono.just(schema()))
        val field = LogicalField("state.name")
        val filter = EqualFilter(field, JsonNodeFactory.instance.stringNode("name"))
        val projection = Projection(include = listOf(field.value))
        val sort = listOf(Sort(field.value, Sort.Direction.DESC))

        provider.resolve(SingleQuery(filter, projection, sort), QuerySchemaValidationMode.STRICT).block()
            .assert().isEqualTo(
                SingleQuery(
                    EqualFilter(LogicalField("document.name.keyword"), filter.value),
                    Projection(include = listOf("document.name")),
                    listOf(Sort("document.name.sort", Sort.Direction.DESC)),
                ),
            )
        provider.resolve(ListQuery(filter, projection, sort, limit = 17), QuerySchemaValidationMode.STRICT).block()
            .assert().isEqualTo(
                ListQuery(
                    EqualFilter(LogicalField("document.name.keyword"), filter.value),
                    Projection(include = listOf("document.name")),
                    listOf(Sort("document.name.sort", Sort.Direction.DESC)),
                    limit = 17,
                ),
            )
        provider.resolve(
            PagedQuery(filter, projection, sort, Pagination(3, 19)),
            QuerySchemaValidationMode.STRICT,
        ).block().assert().isEqualTo(
            PagedQuery(
                EqualFilter(LogicalField("document.name.keyword"), filter.value),
                Projection(include = listOf("document.name")),
                listOf(Sort("document.name.sort", Sort.Direction.DESC)),
                Pagination(3, 19),
            ),
        )
        provider.resolve(filter, QuerySchemaValidationMode.STRICT).block().assert().isEqualTo(
            EqualFilter(LogicalField("document.name.keyword"), filter.value),
        )
    }

    @Test
    fun `provider should return its schema after accepting an aggregation request`() {
        val schema = schema()
        val provider = FixedProvider(Mono.just(schema))
        val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))

        val resolved = provider.resolve(query, QuerySchemaValidationMode.STRICT).block()!!

        resolved.isPresent.assert().isTrue()
        resolved.get().assert().isSameAs(schema)
    }

    @Test
    fun `compatible mode should fall back only when schema is unavailable`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val filter = EqualFilter(LogicalField("state.name"), JsonNodeFactory.instance.stringNode("name"))
        val single = SingleQuery(filter)
        val list = ListQuery(filter, limit = 7)
        val paged = PagedQuery(filter, pagination = Pagination(2, 11))
        val aggregation = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))

        provider.resolve(filter, QuerySchemaValidationMode.COMPATIBLE).block().assert().isSameAs(filter)
        provider.resolve(single, QuerySchemaValidationMode.COMPATIBLE).block().assert().isSameAs(single)
        provider.resolve(list, QuerySchemaValidationMode.COMPATIBLE).block().assert().isSameAs(list)
        provider.resolve(paged, QuerySchemaValidationMode.COMPATIBLE).block().assert().isSameAs(paged)
        provider.resolve(aggregation, QuerySchemaValidationMode.COMPATIBLE).block()!!.isEmpty.assert().isTrue()
    }

    @Test
    fun `strict mode should propagate schema unavailable for every request shape`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val filter = EqualFilter(LogicalField("state.name"), JsonNodeFactory.instance.stringNode("name"))
        val requests = listOf<Mono<*>>(
            provider.resolve(filter, QuerySchemaValidationMode.STRICT),
            provider.resolve(SingleQuery(filter), QuerySchemaValidationMode.STRICT),
            provider.resolve(ListQuery(filter), QuerySchemaValidationMode.STRICT),
            provider.resolve(PagedQuery(filter), QuerySchemaValidationMode.STRICT),
            provider.resolve(
                AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
                QuerySchemaValidationMode.STRICT,
            ),
        )

        requests.forEach { request ->
            StepVerifier.create(request)
                .expectErrorSatisfies { error -> error.assert().isSameAs(unavailable) }
                .verify()
        }
    }

    @Test
    fun `schema conflict should propagate in every validation mode`() {
        val conflict = QuerySchemaConflictException("conflict")
        val provider = FixedProvider(Mono.error(conflict))
        val filter = EqualFilter(LogicalField("state.name"), JsonNodeFactory.instance.stringNode("name"))

        QuerySchemaValidationMode.entries.forEach { mode ->
            StepVerifier.create(provider.resolve(filter, mode))
                .expectErrorSatisfies { error -> error.assert().isSameAs(conflict) }
                .verify()
            StepVerifier.create(
                provider.resolve(
                    AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
                    mode,
                ),
            )
                .expectErrorSatisfies { error -> error.assert().isSameAs(conflict) }
                .verify()
        }
    }

    private class FixedProvider(private val schema: Mono<QueryModelSchema>) : QueryModelSchemaProvider {
        override fun schema(): Mono<QueryModelSchema> = schema

        override fun refresh(): Mono<QueryModelSchema> = schema
    }

    private fun schema(): QueryModelSchema = QueryModelSchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        mapOf(
            LogicalField("state.name") to QueryFieldSchema(
                title = null,
                description = null,
                enumValues = null,
                valueTypes = setOf(QueryValueType.STRING),
                nullable = false,
                required = true,
                cardinality = QueryCardinality.SINGLE,
                semanticType = null,
                dynamicChildren = false,
                bindings = mapOf(
                    QueryCapability.EXACT_MATCH to QueryFieldBinding("document.name.keyword", null),
                    QueryCapability.PRESENCE to QueryFieldBinding("document.name", null),
                    QueryCapability.SORT to QueryFieldBinding("document.name.sort", null),
                ),
            ),
        ),
    )
}
