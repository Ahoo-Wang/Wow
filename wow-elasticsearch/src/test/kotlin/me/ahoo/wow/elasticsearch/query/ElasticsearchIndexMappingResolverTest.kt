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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Sort
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
        mapping.requireNested("state.items").assert().isEqualTo("state.items")

        val condition = mapping.resolve(
            Condition.and(
                Condition.eq("state.name", "Wow"),
                Condition.match("state.name", "Wow"),
                Condition.contains("state.name", "ow"),
                Condition.gt("state.age", 18),
                Condition.exists("state.name"),
            ),
        )
        condition.children.map { it.field }.assert().containsExactly(
            "state.name.keyword",
            "state.name",
            "state.name.keyword",
            "state.age",
            "state.name",
        )
        mapping.resolve(listOf(Sort("state.name", Sort.Direction.ASC))).single().field
            .assert().isEqualTo("state.name.keyword")
        val elementMatch = mapping.resolve(
            Condition.elemMatch("state.items", Condition.eq("state.items.name", "item")),
        )
        elementMatch.field.assert().isEqualTo("state.items")
        elementMatch.children.single().field.assert().isEqualTo("state.items.name")
        val raw = Condition.raw("""{"match_all":{}}""")
        mapping.resolve(raw).assert().isEqualTo(raw)
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
        mapping.resolve("state.runtime.code", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.runtime.code")
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
                    }
                }.runtime("state.runtimeCode") { it.type(RuntimeFieldType.Keyword) }
                .runtime("state.runtimeScore") { it.type(RuntimeFieldType.Double) }
                .runtime("state.runtimeAt") { it.type(RuntimeFieldType.Date) }
                .runtime("state.runtime") { runtime ->
                    runtime.type(RuntimeFieldType.Composite)
                        .fields("code") { it.type(RuntimeFieldType.Keyword) }
                }
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
