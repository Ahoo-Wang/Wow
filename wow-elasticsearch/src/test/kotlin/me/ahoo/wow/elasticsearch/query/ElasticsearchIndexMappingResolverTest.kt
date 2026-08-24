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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import co.elastic.clients.json.JsonData
import co.elastic.clients.util.DateTime
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.toFilterExpression
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test

class ElasticsearchIndexMappingResolverTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val indicesClient = mockk<ReactiveElasticsearchIndicesClient>()

    init {
        every { client.indices() } returns indicesClient
    }

    @Test
    fun `should cache successful mapping and actively refresh it`() {
        val initial = mappingResponse(textWithKeyword())
        val refreshed = mappingResponse(keywordOnly())
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany
            listOf(Mono.just(initial), Mono.just(refreshed))
        val resolver = ElasticsearchIndexMappingResolver(client)

        resolver.currentOrLoad(INDEX).block()!!.resolve("state.name", ElasticsearchFieldUsage.SEARCH)
            .assert().isEqualTo("state.name")
        resolver.currentOrLoad(INDEX).block()!!.resolve("state.name", ElasticsearchFieldUsage.SEARCH)
            .assert().isEqualTo("state.name")

        val refreshResult = resolver.refresh(INDEX).block()!!
        refreshResult.changed.assert().isTrue()
        refreshResult.mapping.resolve("state.name", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.name")
        resolver.currentOrLoad(INDEX).block()!!.resolve("state.name", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.name")
    }

    @Test
    fun `refreshing an unchanged mapping should report no change`() {
        val response = mappingResponse(textWithKeyword())
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(response)
        val resolver = ElasticsearchIndexMappingResolver(client)

        resolver.currentOrLoad(INDEX).block()!!.fieldCount.assert().isEqualTo(6)
        resolver.refresh(INDEX).block()!!.changed.assert().isFalse()
    }

    @Test
    fun `failed refresh should keep previous mapping and retry later`() {
        val initial = mappingResponse(textWithKeyword())
        val refreshed = mappingResponse(keywordOnly())
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(initial),
            Mono.error(IllegalStateException("unavailable")),
            Mono.just(refreshed),
        )
        val resolver = ElasticsearchIndexMappingResolver(client)
        resolver.currentOrLoad(INDEX).block()

        resolver.refresh(INDEX).test()
            .expectErrorMessage("unavailable")
            .verify()
        resolver.currentOrLoad(INDEX).block()!!.resolve("state.name", ElasticsearchFieldUsage.SEARCH)
            .assert().isEqualTo("state.name")

        resolver.refresh(INDEX).block()!!.changed.assert().isTrue()
    }

    @Test
    fun `concurrent loads should share one mapping request`() {
        val response = Sinks.one<GetMappingResponse>()
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns response.asMono()
        val resolver = ElasticsearchIndexMappingResolver(client)

        val verifier = Mono.zip(resolver.currentOrLoad(INDEX), resolver.currentOrLoad(INDEX)).test()
        response.tryEmitValue(mappingResponse(textWithKeyword()))

        verifier.expectNextCount(1).verifyComplete()
        verify(exactly = 1) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `multiple physical indices should fail closed`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            GetMappingResponse.of { response ->
                response
                    .mappings(
                        "$INDEX-000001",
                        IndexMappingRecord.of { record -> record.mappings(textWithKeyword()) },
                    ).mappings(
                        "$INDEX-000002",
                        IndexMappingRecord.of { record -> record.mappings(textWithKeyword()) },
                    )
            },
        )

        ElasticsearchIndexMappingResolver(client).currentOrLoad(INDEX).test()
            .expectErrorMatches {
                it.message!!.startsWith(
                    "Elasticsearch index [$INDEX] must resolve to exactly one physical index",
                )
            }.verify()
    }

    @Test
    fun `should resolve fields by operation capability`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, textWithKeyword())

        mapping.resolve("state.name", ElasticsearchFieldUsage.EXACT).assert().isEqualTo("state.name.keyword")
        mapping.resolve("state.name", ElasticsearchFieldUsage.LITERAL).assert().isEqualTo("state.name.keyword")
        mapping.resolve("state.name", ElasticsearchFieldUsage.SEARCH).assert().isEqualTo("state.name")
        mapping.resolve("state.name", ElasticsearchFieldUsage.SORT).assert().isEqualTo("state.name.keyword")
        mapping.resolve("state.name", ElasticsearchFieldUsage.TERMS).assert().isEqualTo("state.name.keyword")
        mapping.resolve("state.age", ElasticsearchFieldUsage.NUMERIC).assert().isEqualTo("state.age")
        mapping.requireNested("state.items").assert().isEqualTo("state.items")
        val documentIdFilter = mapping.resolve(
            EqualFilter(
                LogicalField("_id"),
                me.ahoo.wow.serialization.JsonSerializer.valueToTree("aggregate-1"),
            ),
        ) as EqualFilter
        documentIdFilter.field.value.assert().isEqualTo("_id")

        val filter = mapping.resolve(
            AndFilter(
                listOf(
                    EqualFilter(LogicalField("state.name"), json("Wow")),
                    SearchFilter("Wow", linkedSetOf(LogicalField("state.name"))),
                    ContainsFilter(LogicalField("state.name"), "ow"),
                    GreaterThanFilter(LogicalField("state.age"), json(18)),
                    ExistsFilter(LogicalField("state.name")),
                ),
            ),
        ) as AndFilter
        (filter.operands[0] as EqualFilter).field.value.assert().isEqualTo("state.name.keyword")
        (filter.operands[1] as SearchFilter).fields.single().value.assert().isEqualTo("state.name")
        (filter.operands[2] as ContainsFilter).field.value.assert().isEqualTo("state.name.keyword")
        (filter.operands[3] as GreaterThanFilter).field.value.assert().isEqualTo("state.age")
        (filter.operands[4] as ExistsFilter).field.value.assert().isEqualTo("state.name")
        mapping.resolve(listOf(Sort("state.name", Sort.Direction.ASC))).single().field
            .assert().isEqualTo("state.name.keyword")
        val elementMatch = mapping.resolve(
            ElementMatchFilter(
                LogicalField("state.items"),
                EqualFilter(LogicalField("state.items.name"), json("item")),
            ),
        ) as ElementMatchFilter
        elementMatch.field.value.assert().isEqualTo("state.items")
        (elementMatch.predicate as EqualFilter).field.value.assert()
            .isEqualTo("state.items.name")
    }

    private fun json(value: Any?) = me.ahoo.wow.serialization.JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(
        value
    )

    @Test
    fun `metadata filters should bypass logical field resolution`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, keywordOnly())
        val filters = listOf<FilterExpression>(
            IdFilter("id-1"),
            IdsFilter(listOf("id-1")),
            AggregateIdFilter("aggregate-1"),
            AggregateIdsFilter(listOf("aggregate-1")),
            TenantIdFilter("tenant-1"),
            OwnerIdFilter("owner-1"),
            SpaceIdFilter("space-1"),
        )

        filters.forEach { filter -> mapping.resolve(filter).assert().isSameAs(filter) }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `resolved legacy filter should use logical field mapping`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, textWithKeyword())
        val executable = Condition.eq("state.name", "Wow").toFilterExpression()

        val resolved = mapping.resolve(executable) as EqualFilter

        resolved.field.value.assert().isEqualTo("state.name.keyword")
    }

    @Test
    fun `should preserve presence fields without exact mappings`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, textWithKeyword())
        val nullValue = me.ahoo.wow.serialization.JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(null)

        (mapping.resolve(EqualFilter(LogicalField("state.name"), nullValue)) as EqualFilter).field.value.assert()
            .isEqualTo("state.name")
        (mapping.resolve(NotEqualFilter(LogicalField("state.name"), nullValue)) as NotEqualFilter).field.value.assert()
            .isEqualTo("state.name")
        (mapping.resolve(IsEmptyFilter(LogicalField("state.name"))) as IsEmptyFilter).field.value.assert()
            .isEqualTo("state.name")
        (mapping.resolve(IsNullFilter(LogicalField("state.name"))) as IsNullFilter).field.value.assert()
            .isEqualTo("state.name")
        (mapping.resolve(IsNotNullFilter(LogicalField("state.name"))) as IsNotNullFilter).field.value.assert()
            .isEqualTo("state.name")
        (mapping.resolve(ExistsFilter(LogicalField("state.name"))) as ExistsFilter).field.value.assert()
            .isEqualTo("state.name")
        (mapping.resolve(NotExistsFilter(LogicalField("state.name"))) as NotExistsFilter).field.value.assert()
            .isEqualTo("state.name")
        (mapping.resolve(NotExistsFilter(LogicalField("state.unmapped"))) as NotExistsFilter).field.value.assert()
            .isEqualTo("state.unmapped")
    }

    @Test
    fun `should fail closed for unsupported and ambiguous fields`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, ambiguousText())

        runCatching { mapping.resolve("state.name", ElasticsearchFieldUsage.EXACT) }
            .exceptionOrNull()!!.message.assert().contains("ambiguous")
        runCatching { mapping.resolve("state.code", ElasticsearchFieldUsage.SEARCH) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        runCatching { mapping.resolve("state.code", ElasticsearchFieldUsage.SORT) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        runCatching { mapping.requireNested("state.objectItems") }
            .exceptionOrNull()!!.message.assert().contains("nested")
    }

    @Test
    fun `should use a unique compatible multi-field and reject missing paths`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, multiFieldFallbacks())

        mapping.resolve("state.name", ElasticsearchFieldUsage.EXACT).assert().isEqualTo("state.name.raw")
        mapping.resolve("state.code", ElasticsearchFieldUsage.SEARCH).assert().isEqualTo("state.code.text")
        runCatching { mapping.resolve("state.missing", ElasticsearchFieldUsage.EXACT) }
            .exceptionOrNull()!!.message.assert().contains("not mapped")
        runCatching { mapping.requireNested("state.missing") }
            .exceptionOrNull()!!.message.assert().contains("not mapped")
    }

    @Test
    fun `should resolve concrete paths through a flattened parent`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, flattenedFields())

        mapping.resolve("state.labels.release", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.labels.release")
        mapping.resolve("state.labels.release", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("state.labels.release")
        mapping.resolve("state.labels.host.ip", ElasticsearchFieldUsage.RANGE)
            .assert().isEqualTo("state.labels.host.ip")
        runCatching { mapping.resolve("state.labels.release", ElasticsearchFieldUsage.LITERAL) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        runCatching { mapping.resolve("state.labels.release", ElasticsearchFieldUsage.RANGE) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        runCatching { mapping.resolve("state.unindexedLabels.release", ElasticsearchFieldUsage.EXACT) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        mapping.resolve("state.unindexedLabels.release", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("state.unindexedLabels.release")
    }

    @Test
    fun `should preserve field alias and runtime field query compatibility`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, aliasAndRuntimeFields())

        mapping.resolve("state.nameAlias", ElasticsearchFieldUsage.SEARCH)
            .assert().isEqualTo("state.nameAlias")
        mapping.resolve("state.codeAlias", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.codeAlias")
        mapping.resolve("state.runtimeCode", ElasticsearchFieldUsage.LITERAL)
            .assert().isEqualTo("state.runtimeCode")
        mapping.resolve("state.runtimeScore", ElasticsearchFieldUsage.RANGE)
            .assert().isEqualTo("state.runtimeScore")
        mapping.resolve("state.runtimeAt", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("state.runtimeAt")
        mapping.resolve("state.runtimeEnabled", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.runtimeEnabled")
        mapping.resolve("state.runtimeIp", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.runtimeIp")
        mapping.resolve("state.runtimeLong", ElasticsearchFieldUsage.RANGE)
            .assert().isEqualTo("state.runtimeLong")
        mapping.resolve("state.runtime.code", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.runtime.code")
        runCatching { mapping.resolve("state.runtimeGeo", ElasticsearchFieldUsage.EXACT) }
            .exceptionOrNull()!!.message.assert().contains("not mapped")
        runCatching { mapping.resolve("state.runtime.geo", ElasticsearchFieldUsage.EXACT) }
            .exceptionOrNull()!!.message.assert().contains("not mapped")
        runCatching { mapping.resolve("state.missingAlias", ElasticsearchFieldUsage.EXACT) }
            .exceptionOrNull()!!.message.assert().contains("not mapped")
    }

    @Test
    fun `should honor index and doc values capabilities across mapped field types`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, indexedFieldVariants())
        mapping.resolve("dateTrue", ElasticsearchFieldUsage.DATE).assert().isEqualTo("dateTrue")
        mapping.resolve("dateNanosTrue", ElasticsearchFieldUsage.DATE).assert().isEqualTo("dateNanosTrue")
        runCatching { mapping.resolve("integerTrue", ElasticsearchFieldUsage.DATE) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        val docValueUsages = mapOf(
            "boolean" to ElasticsearchFieldUsage.EXACT,
            "countedKeyword" to ElasticsearchFieldUsage.EXACT,
            "dateNanos" to ElasticsearchFieldUsage.RANGE,
            "date" to ElasticsearchFieldUsage.RANGE,
            "icu" to ElasticsearchFieldUsage.EXACT,
            "ip" to ElasticsearchFieldUsage.EXACT,
            "keyword" to ElasticsearchFieldUsage.EXACT,
            "integer" to ElasticsearchFieldUsage.RANGE,
            "tokenCount" to ElasticsearchFieldUsage.RANGE,
        )

        docValueUsages.forEach { (field, usage) ->
            mapping.resolve("${field}True", usage).assert().isEqualTo("${field}True")
            mapping.resolve("${field}False", usage).assert().isEqualTo("${field}False")
        }
        listOf("searchAsYouType", "text").forEach { field ->
            mapping.resolve("${field}True", ElasticsearchFieldUsage.SEARCH).assert().isEqualTo("${field}True")
            runCatching { mapping.resolve("${field}False", ElasticsearchFieldUsage.SEARCH) }
                .exceptionOrNull()!!.message.assert().contains("does not support")
        }
        runCatching { mapping.resolve("keywordFalse", ElasticsearchFieldUsage.LITERAL) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        mapping.resolve("keywordFalse", ElasticsearchFieldUsage.SORT).assert().isEqualTo("keywordFalse")
        mapping.resolve("constantKeyword", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("constantKeyword")
        mapping.resolve("countedKeywordTrue", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("countedKeywordTrue")
    }

    @Test
    fun `terms should require portable binary ordering`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, termOrderingFields())

        listOf("keyword", "constantKeyword", "countedKeyword", "wildcard", "integer", "boolean").forEach { field ->
            mapping.resolve(field, ElasticsearchFieldUsage.TERMS).assert().isEqualTo(field)
        }
        listOf(
            "ip",
            "version",
            "icu",
            "normalizedKeyword",
            "ignoredKeyword",
            "nullKeyword",
            "constantValue",
            "tokenCount",
            "nullableInteger",
            "nullableBoolean",
            "nullableDate",
            "halfFloat",
            "float",
            "copyTarget",
        ).forEach { field ->
            runCatching { mapping.resolve(field, ElasticsearchFieldUsage.TERMS) }
                .exceptionOrNull()!!.message.assert().contains("does not support")
        }
    }

    @Test
    fun `aggregation should reject lossy numeric and date nanos mappings`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, termOrderingFields())

        runCatching { mapping.resolve("scaledFloat", ElasticsearchFieldUsage.NUMERIC) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        (
            mapping.resolve(
                GreaterThanFilter(LogicalField("dateNanos"), json("2024-01-01T00:00:00.000000050Z")),
            ) is GreaterThanFilter
            ).assert().isTrue()
        runCatching {
            mapping.resolveAggregationFilter(
                GreaterThanFilter(LogicalField("dateNanos"), json("2024-01-01T00:00:00.000000050Z")),
            )
        }.exceptionOrNull()!!.message.assert().contains("date_nanos")
        runCatching {
            mapping.resolveAggregationFilter(
                EqualFilter(LogicalField("ignoredKeyword"), json("value")),
            )
        }.exceptionOrNull()!!.message.assert().contains("portable aggregation filters")

        val syntheticMapping = ElasticsearchIndexMapping.from(INDEX, aliasAndRuntimeFields())
        runCatching { syntheticMapping.resolve("state.codeAlias", ElasticsearchFieldUsage.TERMS) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        runCatching { syntheticMapping.resolve("state.runtimeScore", ElasticsearchFieldUsage.NUMERIC) }
            .exceptionOrNull()!!.message.assert().contains("does not support")

        runCatching {
            mapping.resolveAggregation("integer", ElasticsearchFieldUsage.NUMERIC, Double::class.javaObjectType)
        }.exceptionOrNull()!!.message.assert().contains("does not preserve")
        runCatching {
            mapping.resolveAggregation("double", ElasticsearchFieldUsage.NUMERIC, Long::class.javaObjectType)
        }.exceptionOrNull()!!.message.assert().contains("does not preserve")
    }

    @Test
    fun `should support range semantic text fielddata and metadata sorts`() {
        val mapping = ElasticsearchIndexMapping.from(INDEX, specialCapabilities())

        listOf("integerRange", "floatRange", "longRange", "doubleRange", "dateRange", "ipRange").forEach {
            mapping.resolve(it, ElasticsearchFieldUsage.RANGE).assert().isEqualTo(it)
        }
        mapping.resolve("semanticText", ElasticsearchFieldUsage.SEARCH).assert().isEqualTo("semanticText")
        mapping.resolve("fielddataText", ElasticsearchFieldUsage.SORT).assert().isEqualTo("fielddataText")
        listOf("integerRange", "plainText", "unindexedFielddataText").forEach {
            runCatching { mapping.resolve(it, ElasticsearchFieldUsage.SORT) }
                .exceptionOrNull()!!.message.assert().contains("does not support")
        }
        runCatching { mapping.resolve("unindexedRange", ElasticsearchFieldUsage.RANGE) }
            .exceptionOrNull()!!.message.assert().contains("does not support")
        mapping.resolve(
            listOf(
                Sort("_score", Sort.Direction.DESC),
                Sort("_doc", Sort.Direction.ASC),
                Sort("_shard_doc", Sort.Direction.ASC),
            ),
        ).map { it.field }.assert().containsExactly("_score", "_doc", "_shard_doc")
    }

    private fun mappingResponse(mapping: TypeMapping): GetMappingResponse =
        GetMappingResponse.of { response ->
            response.mappings(
                INDEX,
                IndexMappingRecord.of { record -> record.mappings(mapping) },
            )
        }

    private fun textWithKeyword(): TypeMapping =
        stateMapping(
            name = Property.of { property ->
                property.text { text ->
                    text.fields("keyword") { keyword -> keyword.keyword { it } }
                }
            },
            age = Property.of { property -> property.integer { it } },
            items = Property.of { property ->
                property.nested { nested ->
                    nested.properties("name") { it.keyword { keyword -> keyword } }
                }
            },
        )

    private fun keywordOnly(): TypeMapping =
        stateMapping(name = Property.of { property -> property.keyword { it } })

    private fun ambiguousText(): TypeMapping =
        stateMapping(
            name = Property.of { property ->
                property.text { text ->
                    text.fields("raw") { keyword -> keyword.keyword { it } }
                        .fields("normalized") { keyword -> keyword.keyword { it } }
                }
            },
            code = Property.of { property -> property.keyword { it.docValues(false) } },
            items = Property.of { property -> property.`object` { it } },
        )

    private fun multiFieldFallbacks(): TypeMapping =
        stateMapping(
            name = Property.of { property ->
                property.text { text ->
                    text.fields("keyword") { child -> child.text { it } }
                        .fields("raw") { keyword -> keyword.keyword { it } }
                }
            },
            code = Property.of { property ->
                property.keyword { keyword ->
                    keyword.fields("text") { text -> text.text { it } }
                }
            },
        )

    private fun flattenedFields(): TypeMapping =
        TypeMapping.of { mapping ->
            mapping.properties("state") { state ->
                state.`object` { objectField ->
                    objectField
                        .properties("labels") { labels ->
                            labels.flattened { flattened ->
                                flattened.properties("host.ip") { field -> field.ip { it } }
                            }
                        }.properties("unindexedLabels") { labels ->
                            labels.flattened { flattened -> flattened.index(false) }
                        }
                }
            }
        }

    private fun aliasAndRuntimeFields(): TypeMapping =
        TypeMapping.of { mapping ->
            mapping
                .properties("state") { state ->
                    state.`object` { objectField ->
                        objectField
                            .properties("name") { it.text { text -> text } }
                            .properties("code") { it.keyword { keyword -> keyword } }
                            .properties("nameAlias") { it.alias { alias -> alias.path("state.name") } }
                            .properties("codeAlias") { it.alias { alias -> alias.path("state.code") } }
                            .properties("missingAlias") { it.alias { alias -> alias.path("state.missing") } }
                    }
                }.runtime("state.runtimeCode") { it.type(RuntimeFieldType.Keyword) }
                .runtime("state.runtimeScore") { it.type(RuntimeFieldType.Double) }
                .runtime("state.runtimeAt") { it.type(RuntimeFieldType.Date) }
                .runtime("state.runtimeEnabled") { it.type(RuntimeFieldType.Boolean) }
                .runtime("state.runtimeIp") { it.type(RuntimeFieldType.Ip) }
                .runtime("state.runtimeLong") { it.type(RuntimeFieldType.Long) }
                .runtime("state.runtimeGeo") { it.type(RuntimeFieldType.GeoPoint) }
                .runtime("state.runtime") { runtime ->
                    runtime.type(RuntimeFieldType.Composite)
                        .fields("code") { it.type(RuntimeFieldType.Keyword) }
                        .fields("geo") { it.type(RuntimeFieldType.GeoPoint) }
                }
        }

    private fun indexedFieldVariants(): TypeMapping =
        TypeMapping.of { mapping ->
            mapping
                .properties("booleanTrue") { it.boolean_ { field -> field.index(true) } }
                .properties("booleanFalse") { it.boolean_ { field -> field.index(false) } }
                .properties("constantKeyword") { it.constantKeyword { field -> field } }
                .properties("countedKeywordTrue") { it.countedKeyword { field -> field.index(true) } }
                .properties("countedKeywordFalse") { it.countedKeyword { field -> field.index(false) } }
                .properties("dateNanosTrue") { it.dateNanos { field -> field.index(true) } }
                .properties("dateNanosFalse") { it.dateNanos { field -> field.index(false) } }
                .properties("dateTrue") { it.date { field -> field.index(true) } }
                .properties("dateFalse") { it.date { field -> field.index(false) } }
                .properties("icuTrue") { it.icuCollationKeyword { field -> field.index(true) } }
                .properties("icuFalse") { it.icuCollationKeyword { field -> field.index(false) } }
                .properties("ipTrue") { it.ip { field -> field.index(true) } }
                .properties("ipFalse") { it.ip { field -> field.index(false) } }
                .properties("keywordTrue") { it.keyword { field -> field.index(true) } }
                .properties("keywordFalse") { it.keyword { field -> field.index(false) } }
                .properties("integerTrue") { it.integer { field -> field.index(true) } }
                .properties("integerFalse") { it.integer { field -> field.index(false) } }
                .properties("searchAsYouTypeTrue") { it.searchAsYouType { field -> field.index(true) } }
                .properties("searchAsYouTypeFalse") { it.searchAsYouType { field -> field.index(false) } }
                .properties("textTrue") { it.text { field -> field.index(true) } }
                .properties("textFalse") { it.text { field -> field.index(false) } }
                .properties("tokenCountTrue") { it.tokenCount { field -> field.index(true) } }
                .properties("tokenCountFalse") { it.tokenCount { field -> field.index(false) } }
        }

    private fun specialCapabilities(): TypeMapping =
        TypeMapping.of { mapping ->
            mapping
                .properties("integerRange") { it.integerRange { field -> field } }
                .properties("floatRange") { it.floatRange { field -> field } }
                .properties("longRange") { it.longRange { field -> field } }
                .properties("doubleRange") { it.doubleRange { field -> field } }
                .properties("dateRange") { it.dateRange { field -> field } }
                .properties("ipRange") { it.ipRange { field -> field } }
                .properties("unindexedRange") { it.integerRange { field -> field.index(false) } }
                .properties("semanticText") { it.semanticText { field -> field } }
                .properties("plainText") { it.text { field -> field } }
                .properties("fielddataText") { it.text { field -> field.fielddata(true) } }
                .properties("unindexedFielddataText") { it.text { field -> field.index(false).fielddata(true) } }
        }

    private fun termOrderingFields(): TypeMapping =
        TypeMapping.of { mapping ->
            mapping
                .properties("keyword") { it.keyword { field -> field } }
                .properties("constantKeyword") { it.constantKeyword { field -> field } }
                .properties("countedKeyword") { it.countedKeyword { field -> field } }
                .properties("wildcard") { it.wildcard { field -> field } }
                .properties("integer") { it.integer { field -> field } }
                .properties("double") { it.double_ { field -> field } }
                .properties("boolean") { it.boolean_ { field -> field } }
                .properties("ip") { it.ip { field -> field } }
                .properties("version") { it.version { field -> field } }
                .properties("icu") { it.icuCollationKeyword { field -> field } }
                .properties("normalizedKeyword") { it.keyword { field -> field.normalizer("lowercase") } }
                .properties("ignoredKeyword") { it.keyword { field -> field.ignoreAbove(16) } }
                .properties("nullKeyword") { it.keyword { field -> field.nullValue("NULL") } }
                .properties("constantValue") { it.constantKeyword { field -> field.value(JsonData.of("constant")) } }
                .properties("tokenCount") { it.tokenCount { field -> field } }
                .properties("nullableInteger") { it.integer { field -> field.nullValue(0) } }
                .properties("nullableBoolean") { it.boolean_ { field -> field.nullValue(false) } }
                .properties("nullableDate") { it.date { field -> field.nullValue(DateTime.of("2024-01-01")) } }
                .properties("halfFloat") { it.halfFloat { field -> field } }
                .properties("float") { it.float_ { field -> field } }
                .properties("scaledFloat") { it.scaledFloat { field -> field.scalingFactor(100.0) } }
                .properties("dateNanos") { it.dateNanos { field -> field } }
                .properties("copySource") { it.keyword { field -> field.copyTo("copyTarget") } }
                .properties("copyTarget") { it.keyword { field -> field } }
        }

    private fun stateMapping(
        name: Property,
        code: Property? = null,
        age: Property? = null,
        items: Property? = null,
    ): TypeMapping =
        TypeMapping.of { mapping ->
            mapping.properties("state") { state ->
                state.`object` { objectField ->
                    objectField.properties("name", name)
                    code?.let { objectField.properties("code", it) }
                    age?.let { objectField.properties("age", it) }
                    items?.let {
                        objectField.properties(if (it._kind() == Property.Kind.Nested) "items" else "objectItems", it)
                    }
                    objectField
                }
            }
        }

    companion object {
        private const val INDEX = "wow.catalog.sku.snapshot"
    }
}
