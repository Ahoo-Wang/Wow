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
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TodayFilter
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

        exception.errorCode.assert().isEqualTo(QuerySchemaValidationException.ERROR_CODE)
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
    fun `provider should return the rewritten aggregation request with its schema`() {
        val schema = schema()
        val provider = FixedProvider(Mono.just(schema))
        val filter = EqualFilter(LogicalField("state.name"), JsonNodeFactory.instance.stringNode("name"))
        val query = AggregationQuery(filter = filter, metrics = listOf(AggregationMetric.Count("count")))

        val resolved = provider.resolve(query, QuerySchemaValidationMode.STRICT).block()!!

        resolved.assert().isEqualTo(
            ResolvedAggregationQuery(
                query.copy(filter = filter.copy(field = LogicalField("document.name.keyword"))),
                schema,
            ),
        )
        query.filter.assert().isSameAs(filter)
    }

    @Test
    fun `compatible mode should fall back for non aggregation requests when schema is unavailable`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val filter = EqualFilter(LogicalField("state.name"), JsonNodeFactory.instance.stringNode("name"))
        val tagsProjection = Projection(include = listOf("tags.department"))
        val tagsSort = listOf(Sort("tags.department", Sort.Direction.ASC))
        val single = SingleQuery(filter, tagsProjection, tagsSort)
        val list = ListQuery(filter, tagsProjection, tagsSort, limit = 7)
        val paged = PagedQuery(filter, tagsProjection, tagsSort, Pagination(2, 11))

        provider.resolve(filter, QuerySchemaValidationMode.COMPATIBLE).block().assert().isSameAs(filter)
        provider.resolve(single, QuerySchemaValidationMode.COMPATIBLE).block().assert().isSameAs(single)
        provider.resolve(list, QuerySchemaValidationMode.COMPATIBLE).block().assert().isSameAs(list)
        provider.resolve(paged, QuerySchemaValidationMode.COMPATIBLE).block().assert().isSameAs(paged)
    }

    @Test
    fun `compatible mode should propagate schema unavailable for aggregation`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val aggregation = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))

        StepVerifier.create(provider.resolve(aggregation, QuerySchemaValidationMode.COMPATIBLE))
            .expectErrorSatisfies { error -> error.assert().isSameAs(unavailable) }
            .verify()
    }

    @Test
    fun `compatible mode should propagate schema unavailable for system tags filters`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val filter = EqualFilter(LogicalField("tags.department"), JsonNodeFactory.instance.stringNode("eng"))
        val requests = listOf<Mono<*>>(
            provider.resolve(filter, QuerySchemaValidationMode.COMPATIBLE),
            provider.resolve(SingleQuery(filter), QuerySchemaValidationMode.COMPATIBLE),
            provider.resolve(ListQuery(filter), QuerySchemaValidationMode.COMPATIBLE),
            provider.resolve(PagedQuery(filter), QuerySchemaValidationMode.COMPATIBLE),
            provider.resolve(
                AggregationQuery(filter = filter, metrics = listOf(AggregationMetric.Count("count"))),
                QuerySchemaValidationMode.COMPATIBLE,
            ),
        )

        requests.forEach { request ->
            StepVerifier.create(request)
                .expectErrorSatisfies { error -> error.assert().isSameAs(unavailable) }
                .verify()
        }
    }

    @Test
    fun `compatible mode should fall back for model search when schema is unavailable`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val filter = SearchFilter("hello")

        StepVerifier.create(provider.resolve(filter, QuerySchemaValidationMode.COMPATIBLE))
            .expectNext(filter)
            .verifyComplete()
    }

    @Test
    fun `compatible mode should fall back for non-tag relative time when schema is unavailable`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val filter = TodayFilter(LogicalField("state.createdAt"))

        StepVerifier.create(provider.resolve(filter, QuerySchemaValidationMode.COMPATIBLE))
            .expectNext(filter)
            .verifyComplete()
    }

    @Test
    fun `compatible mode should keep relative tags inside an element scope`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val filter = ElementMatchFilter(
            LogicalField("state.items"),
            EqualFilter(LogicalField("tags.department"), JsonNodeFactory.instance.stringNode("eng")),
        )

        StepVerifier.create(provider.resolve(filter, QuerySchemaValidationMode.COMPATIBLE))
            .expectNext(filter)
            .verifyComplete()
    }

    @Test
    fun `compatible mode should not treat tagsExtra as system tags`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val filter = EqualFilter(
            LogicalField("tagsExtra.department"),
            JsonNodeFactory.instance.stringNode("eng"),
        )

        StepVerifier.create(provider.resolve(filter, QuerySchemaValidationMode.COMPATIBLE))
            .expectNext(filter)
            .verifyComplete()
    }

    @Test
    fun `compatible mode should propagate unavailable for every root system tags reference`() {
        val unavailable = QuerySchemaUnavailableException("unavailable")
        val provider = FixedProvider(Mono.error(unavailable))
        val filters = listOf(
            SearchFilter("hello", setOf(LogicalField("tags.department"))),
            OrFilter(
                listOf(
                    MatchAllFilter,
                    EqualFilter(LogicalField("tags.department"), JsonNodeFactory.instance.stringNode("eng")),
                ),
            ),
        )

        filters.forEach { filter ->
            StepVerifier.create(provider.resolve(filter, QuerySchemaValidationMode.COMPATIBLE))
                .expectErrorSatisfies { error -> error.assert().isSameAs(unavailable) }
                .verify()
        }
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
